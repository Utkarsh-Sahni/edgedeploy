package com.edgedeploy.controller;

import com.edgedeploy.auth.GitHubOAuth2UserService;
import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.config.RequestIdFilter;
import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.deployment.DeploymentEventBroadcaster;
import com.edgedeploy.deployment.DeploymentLogStreamBroadcaster;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.dto.TriggerDeploymentRequest;
import com.edgedeploy.entity.Framework;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.exception.ResourceNotFoundException;
import com.edgedeploy.github.GitHubApiException;
import com.edgedeploy.github.GitHubModels;
import com.edgedeploy.github.GitHubService;
import com.edgedeploy.security.SecurityConfig;
import com.edgedeploy.security.SecurityContextCurrentUserProvider;
import com.edgedeploy.service.DeploymentService;
import com.edgedeploy.service.EnvironmentVariableService;
import com.edgedeploy.service.ProjectService;
import com.edgedeploy.service.UserService;
import com.edgedeploy.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer contract with the real security configuration: authentication, CSRF, CORS, validation,
 * ProblemDetail shape. Services are mocked; persistence and Kafka are covered by ApiIntegrationTest.
 */
@WebMvcTest(controllers = {ProjectController.class, DeploymentController.class, GitHubController.class, AuthController.class,
        EnvironmentVariableController.class})
@Import({SecurityConfig.class, SecurityContextCurrentUserProvider.class})
@EnableConfigurationProperties(EdgeDeployProperties.class)
@ActiveProfiles("test")
class ApiWebLayerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String PROJECT_JSON = """
            {"name":"Portfolio","repository":"octocat/portfolio","branch":"main"}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProjectService projectService;
    @MockitoBean
    DeploymentService deploymentService;
    @MockitoBean
    GitHubService gitHubService;
    @MockitoBean
    UserService userService;
    @MockitoBean
    EnvironmentVariableService environmentVariables;
    @MockitoBean
    DeploymentEventBroadcaster broadcaster;
    @MockitoBean
    DeploymentLogStreamBroadcaster logStreams;
    @MockitoBean
    GitHubOAuth2UserService gitHubOAuth2UserService;

    private static RequestPostProcessor signedIn() {
        return authentication(TestFixtures.authenticationFor(USER_ID));
    }

    // ---- authentication -------------------------------------------------------------------------

    @Test
    void apiRequiresAuthenticationAndAnswers401ProblemInsteadOfRedirecting() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.requestId", notNullValue()));
        verifyNoInteractions(projectService);
    }

    @Test
    void signInStartsTheGitHubAuthorizationRedirect() throws Exception {
        mvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://github.com/login/oauth/authorize?")))
                .andExpect(header().string("Location", containsString("client_id=test-client-id")))
                .andExpect(header().string("Location", containsString("scope=read:user%20user:email%20repo")))
                .andExpect(header().string("Location", containsString("state=")));
    }

    @Test
    void currentUserIsTakenFromTheSessionPrincipalNeverFromTheRequest() throws Exception {
        when(projectService.list(USER_ID)).thenReturn(List.of());

        mvc.perform(get("/api/projects").param("userId", UUID.randomUUID().toString()).with(signedIn()))
                .andExpect(status().isOk());

        verify(projectService).list(USER_ID);
    }

    @Test
    void mutationsWithoutCsrfTokenAreForbidden() throws Exception {
        mvc.perform(post("/api/projects").with(signedIn()).contentType(MediaType.APPLICATION_JSON).content(PROJECT_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf"));
        verifyNoInteractions(projectService);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        mvc.perform(post("/api/auth/logout").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---- projects -------------------------------------------------------------------------------

    @Test
    void createProjectReturns201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(projectService.create(eq(USER_ID), any())).thenReturn(project(id));

        mvc.perform(post("/api/projects").with(signedIn()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PROJECT_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/projects/" + id)))
                .andExpect(jsonPath("$.owner").value("octocat"));
    }

    @Test
    void invalidProjectReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/projects").with(signedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","repository":"not a repo","branch":"../etc","buildCommand":"npm run build\\nrm -rf /"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.repository").exists())
                .andExpect(jsonPath("$.errors.branch").exists())
                .andExpect(jsonPath("$.errors.buildCommand").exists());
        verify(projectService, never()).create(any(), any());
    }

    @Test
    void otherUsersProjectIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(projectService.get(USER_ID, id)).thenThrow(new ResourceNotFoundException("Project", id));

        mvc.perform(get("/api/projects/{id}", id).with(signedIn()))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchAndDeleteProject() throws Exception {
        UUID id = UUID.randomUUID();
        when(projectService.update(eq(USER_ID), eq(id), any())).thenReturn(project(id));

        mvc.perform(patch("/api/projects/{id}", id).with(signedIn()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"branch\":\"develop\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/projects/{id}", id).with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent());
        verify(projectService).delete(USER_ID, id);
    }

    @Test
    void conflictMapsTo409() throws Exception {
        when(projectService.create(eq(USER_ID), any())).thenThrow(new ConflictException("already exists"));

        mvc.perform(post("/api/projects").with(signedIn()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PROJECT_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("already exists"));
    }

    // ---- GitHub ---------------------------------------------------------------------------------

    @Test
    void listsRepositoriesWithoutLeakingTokens() throws Exception {
        when(gitHubService.getUserRepositories(USER_ID, 1, 50)).thenReturn(new GitHubModels.Page<>(
                List.of(TestFixtures.repository("octocat/portfolio", true)), 1, 50, true));

        mvc.perform(get("/api/github/repositories").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fullName").value("octocat/portfolio"))
                .andExpect(jsonPath("$.items[0].owner").value("octocat"))
                .andExpect(jsonPath("$.items[0].private").value(false))
                .andExpect(jsonPath("$.items[0].defaultBranch").value("main"))
                .andExpect(jsonPath("$.items[0].canDeploy").value(true))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$..token").doesNotExist());
    }

    @Test
    void revokedGitHubTokenAsksTheUserToSignInAgain() throws Exception {
        when(gitHubService.getBranches(USER_ID, "octocat", "portfolio", 1, 100))
                .thenThrow(new GitHubApiException(GitHubApiException.Kind.UNAUTHORIZED, 401, "bad credentials"));

        mvc.perform(get("/api/github/repositories/octocat/portfolio/branches").with(signedIn()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("github_reauth_required"))
                .andExpect(jsonPath("$.detail", not(containsString("bad credentials"))));
    }

    @Test
    void rejectsMalformedRepositoryPath() throws Exception {
        mvc.perform(get("/api/github/repositories/{owner}/{repo}/branches", "-bad-", "x").with(signedIn()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gitHubService);
    }

    // ---- deployments ----------------------------------------------------------------------------

    @Test
    void triggerDeploymentReturns202Queued() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.trigger(USER_ID, projectId, TriggerDeploymentRequest.branchTip()))
                .thenReturn(new DeploymentResponse(deploymentId, 1, projectId, "Portfolio", TestFixtures.COMMIT,
                        DeploymentStatus.QUEUED, null, null, null, null, null, Instant.now(), Instant.now()));

        mvc.perform(post("/api/projects/{id}/deployments", projectId).with(signedIn()).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/api/deployments/" + deploymentId)))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.commitSha").value(TestFixtures.COMMIT));
    }

    @Test
    void triggerRejectsMalformedCommitSha() throws Exception {
        mvc.perform(post("/api/projects/{id}/deployments", UUID.randomUUID()).with(signedIn()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commitSha\":\"abc12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.commitSha").exists());
    }

    @Test
    void logStreamRequiresAuthenticationAndOwnership() throws Exception {
        UUID id = UUID.randomUUID();
        when(deploymentService.get(USER_ID, id)).thenThrow(new ResourceNotFoundException("Deployment", id));

        mvc.perform(get("/api/deployments/{id}/logs/stream", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/deployments/{id}/logs/stream", id).with(signedIn())).andExpect(status().isNotFound());
        verifyNoInteractions(logStreams);
    }

    @Test
    void logStreamResumesFromLastEventId() throws Exception {
        UUID id = UUID.randomUUID();
        when(deploymentService.get(USER_ID, id)).thenReturn(new DeploymentResponse(id, 3, UUID.randomUUID(), "Portfolio",
                TestFixtures.COMMIT, DeploymentStatus.BUILDING, null, null, null, null, null, Instant.now(), Instant.now()));
        when(logStreams.subscribe(id, 41L, false)).thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());

        mvc.perform(get("/api/deployments/{id}/logs/stream", id).with(signedIn()).header("Last-Event-ID", "41"))
                .andExpect(status().isOk());
        verify(logStreams).subscribe(id, 41L, false);
    }

    @Test
    void illegalCancelIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(deploymentService.cancel(USER_ID, id)).thenThrow(new ConflictException("Only queued or in-progress"));

        mvc.perform(post("/api/deployments/{id}/cancel", id).with(signedIn()).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedIdAndOutOfRangeLimitAre400() throws Exception {
        mvc.perform(get("/api/deployments/not-a-uuid").with(signedIn())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/deployments").param("limit", "1000").with(signedIn())).andExpect(status().isBadRequest());
    }

    // ---- environment variables ------------------------------------------------------------------

    @Test
    void environmentVariableValuesAreWriteOnly() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(environmentVariables.set(USER_ID, projectId, "API_URL", "https://api.example.com"))
                .thenReturn(new com.edgedeploy.dto.EnvironmentVariableResponse("API_URL", Instant.now(), Instant.now()));
        when(environmentVariables.list(USER_ID, projectId))
                .thenReturn(List.of(new com.edgedeploy.dto.EnvironmentVariableResponse("API_URL", Instant.now(), Instant.now())));

        mvc.perform(put("/api/projects/{id}/env/{key}", projectId, "API_URL").with(signedIn()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"https://api.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("API_URL"))
                .andExpect(jsonPath("$.value").doesNotExist());
        mvc.perform(get("/api/projects/{id}/env", projectId).with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("API_URL"))
                .andExpect(jsonPath("$[0].value").doesNotExist());
        mvc.perform(delete("/api/projects/{id}/env/{key}", projectId, "API_URL").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void environmentVariableValueIsRequiredAndBounded() throws Exception {
        mvc.perform(put("/api/projects/{id}/env/{key}", UUID.randomUUID(), "API_URL").with(signedIn()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + "x".repeat(4097) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.value").exists());
        verifyNoInteractions(environmentVariables);
    }

    // ---- cross-cutting --------------------------------------------------------------------------

    @Test
    void echoesSafeRequestIdAndReplacesUnsafeOne() throws Exception {
        mvc.perform(get("/api/auth/csrf").header(RequestIdFilter.HEADER, "client-trace-1234"))
                .andExpect(header().string(RequestIdFilter.HEADER, "client-trace-1234"));
        mvc.perform(get("/api/auth/csrf").header(RequestIdFilter.HEADER, "bad id\nInjected: yes"))
                .andExpect(header().string(RequestIdFilter.HEADER, not(containsString("Injected"))));
    }

    @Test
    void corsAllowsTheDashboardOriginWithCredentialsOnly() throws Exception {
        mvc.perform(options("/api/projects")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mvc.perform(options("/api/projects")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void browserNavigationToNonApiPathsRedirectsToTheDashboardLogin() throws Exception {
        mvc.perform(get("/actuator/metrics").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("http://localhost:3000/login")));
    }

    private static ProjectResponse project(UUID id) {
        return new ProjectResponse(id, "Portfolio", "portfolio", "octocat/portfolio", "octocat", 4242L, "main",
                Framework.UNKNOWN, null, null, Instant.now(), Instant.now());
    }
}
