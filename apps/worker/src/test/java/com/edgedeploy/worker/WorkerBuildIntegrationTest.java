package com.edgedeploy.worker;

import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.worker.docker.DockerBuildService;
import com.edgedeploy.worker.support.GitFixture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The build engine end to end against real Postgres + Kafka and real git (repositories served over
 * file://). Only the Docker build is stubbed, so the test needs no image downloads or network.
 * Skipped automatically when Docker (for Testcontainers) is unavailable.
 */
@SpringBootTest(properties = {
        WorkerIntegrationTestSupport.MIGRATIONS,
        "edgedeploy.mode=build",
        "edgedeploy.git.allow-file-protocol=true",
        "edgedeploy.git.deploy-token=",
        "edgedeploy.kafka.retry.max-retries=1",
        "edgedeploy.kafka.retry.initial-interval=PT0.1S",
})
@Import(WorkerIntegrationTestSupport.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class WorkerBuildIntegrationTest extends WorkerIntegrationTestSupport {

    private static final Path REMOTES;
    private static final Path WORKSPACES;

    static {
        try {
            REMOTES = Files.createTempDirectory("edgedeploy-it-remotes");
            WORKSPACES = Files.createTempDirectory("edgedeploy-it-workspaces");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void gitAndWorkspace(DynamicPropertyRegistry registry) {
        registry.add("edgedeploy.git.base-url", () -> new GitFixture(REMOTES).baseUrl());
        registry.add("edgedeploy.workspace.base-dir", WORKSPACES::toString);
    }

    @MockitoBean
    DockerBuildService docker;

    private final GitFixture fixture = new GitFixture(REMOTES);

    @Test
    void queuedDeploymentIsClonedVerifiedDetectedAndBuiltExactlyOnce() throws Exception {
        String repository = "octocat/vite-" + shortId();
        Path repo = fixture.createRepository("octocat", repository.substring("octocat/".length()), true);
        String sha = fixture.commit(repo, Map.of(
                "package.json", "{\"scripts\":{\"build\":\"vite build\"},\"devDependencies\":{\"vite\":\"6.0.0\"}}",
                "index.html", "<h1>hello</h1>"), "initial");
        when(docker.build(any())).thenAnswer(invocation -> {
            DockerBuildService.Request request = invocation.getArgument(0);
            assertThat(request.context().resolve("package.json")).exists(); // real checkout is the context
            request.output().accept("#1 building...");
            return new DockerBuildService.BuiltImage(request.image(), "sha256:feedface", "amd64");
        });
        UUID deploymentId = seedQueuedDeployment(repository, sha);
        DeploymentRequestedEvent event = requested(deploymentId);

        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, deploymentId.toString(), event);
        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, deploymentId.toString(), event); // duplicate delivery

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(status(deploymentId)).isEqualTo("IMAGE_BUILT"));
        UUID projectId = event.projectId();
        Map<String, Object> row = jdbc.sql("SELECT image_uri, completed_at FROM deployments WHERE id = ?")
                .param(deploymentId).query().singleRow();
        assertThat(row.get("image_uri")).isEqualTo("edgedeploy/" + projectId + ":" + sha);
        assertThat(row.get("completed_at")).isNotNull();

        // Let the duplicate be consumed, then prove the build ran once and the timeline is complete.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(docker, times(1)).build(any()));
        for (String step : new String[]{"CLONE", "COMMIT", "FRAMEWORK", "IMAGE"}) {
            assertThat(countStep(deploymentId, step, "INFO")).as(step).isEqualTo(1);
        }
        assertThat(countLogs(deploymentId, "Detected framework: VITE%")).isEqualTo(1);
        assertThat(countLogs(deploymentId, "#1 building...")).isEqualTo(1);
        assertThat(WORKSPACES.resolve(deploymentId.toString())).doesNotExist(); // workspace cleaned up
    }

    @Test
    void failedDeploymentIsRecordedAndTheWorkerKeepsProcessing() throws Exception {
        String brokenRepo = "octocat/broken-" + shortId();
        Path broken = fixture.createRepository("octocat", brokenRepo.substring("octocat/".length()), true);
        String brokenSha = fixture.commit(broken, Map.of("README.md", "no package.json here"), "docs only");
        String goodRepo = "octocat/node-" + shortId();
        Path good = fixture.createRepository("octocat", goodRepo.substring("octocat/".length()), true);
        String goodSha = fixture.commit(good, Map.of("package.json", "{\"scripts\":{\"start\":\"node server.js\"}}"), "app");
        when(docker.build(any())).thenAnswer(invocation ->
                new DockerBuildService.BuiltImage(((DockerBuildService.Request) invocation.getArgument(0)).image(), null, null));

        UUID failing = seedQueuedDeployment(brokenRepo, brokenSha);
        UUID healthy = seedQueuedDeployment(goodRepo, goodSha);
        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, failing.toString(), requested(failing));
        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, healthy.toString(), requested(healthy));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(status(failing)).isEqualTo("FAILED");
            assertThat(status(healthy)).isEqualTo("IMAGE_BUILT");
        });
        String error = jdbc.sql("SELECT error_message FROM deployments WHERE id = ?").param(failing).query(String.class).single();
        assertThat(error).startsWith("Framework detection failed: No package.json");
        assertThat(countStep(failing, "FRAMEWORK", "ERROR")).isEqualTo(1);
    }

    @Test
    void unknownCommitFailsTheCommitStep() throws Exception {
        String repository = "octocat/sha-" + shortId();
        Path repo = fixture.createRepository("octocat", repository.substring("octocat/".length()), true);
        fixture.commit(repo, Map.of("package.json", "{}"), "only");
        UUID deploymentId = seedQueuedDeployment(repository, "0123456789abcdef0123456789abcdef01234567");

        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, deploymentId.toString(), requested(deploymentId));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(status(deploymentId)).isEqualTo("FAILED"));
        assertThat(countStep(deploymentId, "COMMIT", "ERROR")).isEqualTo(1);
    }

    @Test
    void malformedEventIsDeadLetteredWithoutRetries() {
        try (Consumer<String, byte[]> dlt = consumer(KafkaTopics.DEPLOYMENT_REQUESTED_DLT)) {
            UUID marker = UUID.randomUUID();
            kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, marker.toString(),
                    new DeploymentRequestedEvent(marker, null, null, "octocat/x", "main", null, Instant.now()));

            ConsumerRecord<String, byte[]> dead = await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, byte[]> record : KafkaTestUtils.getRecords(dlt, Duration.ofMillis(500))) {
                    if (marker.toString().equals(record.key())) {
                        return record;
                    }
                }
                return null;
            }, Objects::nonNull);

            String exceptionClasses = header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN) + " "
                    + header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
            assertThat(exceptionClasses).contains("InvalidDeploymentEventException");
        }
    }

    private long countStep(UUID deploymentId, String step, String level) {
        return jdbc.sql("SELECT count(*) FROM deployment_logs WHERE deployment_id = ? AND step = ? AND level = ?")
                .params(deploymentId, step, level).query(Long.class).single();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
