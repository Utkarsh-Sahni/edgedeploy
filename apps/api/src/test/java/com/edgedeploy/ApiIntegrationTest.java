package com.edgedeploy;

import com.edgedeploy.auth.GitHubProfile;
import com.edgedeploy.auth.UserProvisioningService;
import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.entity.OutboxEvent;
import com.edgedeploy.entity.User;
import com.edgedeploy.github.GitHubService;
import com.edgedeploy.github.GitHubTokenStore;
import com.edgedeploy.repository.OutboxEventRepository;
import com.edgedeploy.repository.UserRepository;
import com.edgedeploy.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The api against real Postgres (Flyway schema), Redis (sessions) and Kafka, with GitHub mocked.
 * Uses the genuine CSRF cookie/header round trip rather than the csrf() test shortcut.
 * Skipped automatically when Docker is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    KafkaProperties kafkaProperties;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    UserRepository users;
    @Autowired
    UserProvisioningService provisioning;
    @Autowired
    GitHubTokenStore tokenStore;
    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    GitHubService gitHub;

    private User alice;
    private User bob;
    private Consumer<String, String> requestedConsumer;

    @BeforeEach
    void setUp() {
        alice = signUp("alice");
        bob = signUp("bob");
        when(gitHub.getRepository(any(), anyString(), anyString())).thenAnswer(invocation ->
                TestFixtures.repository(invocation.getArgument(1) + "/" + invocation.getArgument(2), true));
        when(gitHub.getBranch(any(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                TestFixtures.branch(invocation.getArgument(3)));

        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        requestedConsumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        requestedConsumer.subscribe(List.of(KafkaTopics.DEPLOYMENT_REQUESTED));
    }

    @AfterEach
    void closeConsumer() {
        requestedConsumer.close();
    }

    // ---- authentication / users -------------------------------------------------------------------

    @Test
    void signInCreatesThenUpdatesTheUserAndStoresTheTokenEncrypted() {
        String githubId = "gh-" + UUID.randomUUID();
        User created = provisioning.provision(new GitHubProfile(githubId, "carol", "Carol", null, null),
                "gho_first_token", List.of("repo"));
        User again = provisioning.provision(new GitHubProfile(githubId, "carol-renamed", "Carol C", null, "https://a/1"),
                "gho_second_token", List.of("repo"));

        assertThat(again.getId()).isEqualTo(created.getId());
        assertThat(users.findById(created.getId())).get().extracting(User::getGithubLogin).isEqualTo("carol-renamed");

        String stored = jdbc.sql("SELECT encrypted_access_token FROM github_credentials WHERE user_id = ?")
                .param(created.getId()).query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain("gho_second_token");
        assertThat(tokenStore.findAccessToken(created.getId())).contains("gho_second_token");
    }

    @Test
    void meReturnsTheSignedInUserWithoutSecrets() throws Exception {
        mvc.perform(get("/api/auth/me").with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.getId().toString()))
                .andExpect(jsonPath("$.login").value("alice"))
                .andExpect(jsonPath("$..token").doesNotExist());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void mutationsNeedTheRealCsrfCookieAndHeader() throws Exception {
        mvc.perform(post("/api/projects").with(as(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("No CSRF", "alice/no-csrf")))
                .andExpect(status().isForbidden());

        mvc.perform(withCsrf(post("/api/projects")).with(as(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("With CSRF", "alice/with-csrf-" + shortId())))
                .andExpect(status().isCreated());
    }

    // ---- projects ---------------------------------------------------------------------------------

    @Test
    void projectsAreStrictlyScopedToTheirOwner() throws Exception {
        UUID projectId = createProject(alice, "Alice Site", "alice/site-" + shortId());

        mvc.perform(get("/api/projects/{id}", projectId).with(as(alice))).andExpect(status().isOk());
        assertThat(listProjectIds(alice)).contains(projectId);

        // Bob can neither see nor touch Alice's project, and learns nothing about its existence.
        mvc.perform(get("/api/projects/{id}", projectId).with(as(bob)))
                .andExpect(status().isNotFound());
        assertThat(listProjectIds(bob)).doesNotContain(projectId);
        mvc.perform(withCsrf(patch("/api/projects/{id}", projectId)).with(as(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"pwned\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(withCsrf(delete("/api/projects/{id}", projectId)).with(as(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(withCsrf(post("/api/projects/{id}/deployments", projectId)).with(as(bob)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/projects/{id}", projectId).with(as(alice)))
                .andExpect(jsonPath("$.name").value("Alice Site"));
    }

    @Test
    void ownerCanUpdateAndDeleteTheirProject() throws Exception {
        UUID projectId = createProject(alice, "Editable", "alice/editable-" + shortId());

        mvc.perform(withCsrf(patch("/api/projects/{id}", projectId)).with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Edited\",\"branch\":\"develop\",\"framework\":\"VITE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Edited"))
                .andExpect(jsonPath("$.branch").value("develop"))
                .andExpect(jsonPath("$.framework").value("VITE"));

        mvc.perform(withCsrf(delete("/api/projects/{id}", projectId)).with(as(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/projects/{id}", projectId).with(as(alice))).andExpect(status().isNotFound());
    }

    @Test
    void duplicateRepositoryForTheSameUserIsRejectedButOtherUsersMayUseIt() throws Exception {
        String repository = "shared/repo-" + shortId();
        createProject(alice, "First", repository);

        mvc.perform(withCsrf(post("/api/projects")).with(as(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("Second", repository)))
                .andExpect(status().isConflict());
        createProject(bob, "Bob's copy", repository);
    }

    // ---- deployments ------------------------------------------------------------------------------

    @Test
    void deployQueuesAPinnedCommitAndPublishesDeploymentRequested() throws Exception {
        String repository = "alice/app-" + shortId();
        UUID projectId = createProject(alice, "Deployable", repository);

        MvcResult result = mvc.perform(withCsrf(post("/api/projects/{id}/deployments", projectId)).with(as(alice)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.commitSha").value(TestFixtures.COMMIT))
                .andExpect(jsonPath("$.number").value(1))
                .andReturn();
        UUID deploymentId = idOf(result);

        // The timeline starts with the api's "queued" milestone; numbering is per project.
        mvc.perform(get("/api/deployments/{id}/logs", deploymentId).with(as(alice)))
                .andExpect(jsonPath("$[0].step").value("QUEUED"))
                .andExpect(jsonPath("$[0].seq").isNumber())
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.startsWith("Deployment #1 queued")));
        mvc.perform(withCsrf(post("/api/projects/{id}/deployments", projectId)).with(as(alice)))
                .andExpect(jsonPath("$.number").value(2));

        JsonNode event = await().atMost(Duration.ofSeconds(20)).until(() -> {
            for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(requestedConsumer, Duration.ofMillis(500))) {
                if (deploymentId.toString().equals(record.key())) {
                    return objectMapper.readTree(record.value());
                }
            }
            return null;
        }, Objects::nonNull);

        assertThat(event.get("eventId").asText()).isNotBlank();
        assertThat(event.get("deploymentId").asText()).isEqualTo(deploymentId.toString());
        assertThat(event.get("projectId").asText()).isEqualTo(projectId.toString());
        assertThat(event.get("repository").asText()).isEqualTo(repository);
        assertThat(event.get("branch").asText()).isEqualTo("main");
        assertThat(event.get("commitSha").asText()).isEqualTo(TestFixtures.COMMIT);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateId().equals(deploymentId))
                        .singleElement().extracting(OutboxEvent::getPublishedAt).isNotNull());

        mvc.perform(get("/api/deployments").with(as(alice)))
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(deploymentId.toString())));
        mvc.perform(get("/api/deployments/{id}", deploymentId).with(as(bob))).andExpect(status().isNotFound());
    }

    @Test
    void cancellingFollowsTheStateMachine() throws Exception {
        UUID projectId = createProject(alice, "Cancellable", "alice/cancel-" + shortId());
        UUID deploymentId = idOf(mvc.perform(withCsrf(post("/api/projects/{id}/deployments", projectId)).with(as(alice)))
                .andExpect(status().isAccepted()).andReturn());

        mvc.perform(withCsrf(post("/api/deployments/{id}/cancel", deploymentId)).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        // STOPPED is terminal: a second cancel is rejected, not silently re-applied.
        mvc.perform(withCsrf(post("/api/deployments/{id}/cancel", deploymentId)).with(as(alice)))
                .andExpect(status().isConflict());
    }

    // ---- operations -------------------------------------------------------------------------------

    @Test
    void healthAndApiDocsAreAvailable() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/projects'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/projects'].get.responses['401']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.session").exists());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private User signUp(String login) {
        return provisioning.provision(new GitHubProfile("gh-" + UUID.randomUUID(), login, login, null, null),
                "gho_" + login, List.of("repo"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(User user) {
        return authentication(TestFixtures.authenticationFor(user.getId()));
    }

    /** The browser flow: read the XSRF-TOKEN cookie, send it back as both cookie and header. */
    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private UUID createProject(User owner, String name, String repository) throws Exception {
        return idOf(mvc.perform(withCsrf(post("/api/projects")).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(name, repository)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private List<UUID> listProjectIds(User user) throws Exception {
        JsonNode body = objectMapper.readTree(mvc.perform(get("/api/projects").with(as(user)))
                .andReturn().getResponse().getContentAsString());
        return java.util.stream.StreamSupport.stream(body.spliterator(), false)
                .map(node -> UUID.fromString(node.get("id").asText())).toList();
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String projectJson(String name, String repository) {
        return """
                {"name":"%s","repository":"%s","branch":"main"}
                """.formatted(name, repository);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
