package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.ContainerRegistryService;
import com.edgedeploy.worker.delivery.DeliveryException;
import com.edgedeploy.worker.docker.DockerBuildException;
import com.edgedeploy.worker.docker.DockerBuildService;
import com.edgedeploy.worker.docker.DockerImageClient;
import com.edgedeploy.worker.docker.ImageName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.CreateRepositoryRequest;
import software.amazon.awssdk.services.ecr.model.CreateRepositoryResponse;
import software.amazon.awssdk.services.ecr.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ecr.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesRequest;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesResponse;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse;
import software.amazon.awssdk.services.ecr.model.ImageDetail;
import software.amazon.awssdk.services.ecr.model.PutLifecyclePolicyRequest;
import software.amazon.awssdk.services.ecr.model.PutLifecyclePolicyResponse;
import software.amazon.awssdk.services.ecr.model.Repository;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * SDK client mocked with CALLS_REAL_METHODS: the convenience overloads used by the service really build the
 * request objects, so the tests assert the exact requests sent to AWS.
 */
class AwsEcrServiceTest {

    private static final UUID PROJECT = UUID.fromString("8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6");
    private static final String SHA = "a83f12c0a83f12c0a83f12c0a83f12c0a83f12c0";
    private static final String REGISTRY = "123456789012.dkr.ecr.ap-south-1.amazonaws.com";
    private static final String REPOSITORY_URI = REGISTRY + "/edgedeploy/" + PROJECT;
    private static final String DIGEST = "sha256:4f1c8b5e0d2a7f6c9e3b1a5d8c7f2e6b4a9d3c1e5f7b2a8c6d4e9f1b3a5c7d2e";

    @TempDir
    Path scratch;

    private final EcrClient ecr = mock(EcrClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final DockerImageClient docker = mock(DockerImageClient.class);
    private final List<String> progress = new ArrayList<>();
    private AwsEcrService service;

    @BeforeEach
    void setUp() {
        AwsProperties properties = AwsTestProperties.create();
        service = new AwsEcrService(ecr, docker, new AwsResourceNames(properties), properties, duration -> { });
        String token = Base64.getEncoder().encodeToString("AWS:registry-password".getBytes(StandardCharsets.UTF_8));
        doReturn(GetAuthorizationTokenResponse.builder().authorizationData(AuthorizationData.builder()
                .authorizationToken(token).proxyEndpoint("https://" + REGISTRY).build()).build())
                .when(ecr).getAuthorizationToken(any(GetAuthorizationTokenRequest.class));
        doReturn(DescribeImagesResponse.builder().imageDetails(ImageDetail.builder().imageDigest(DIGEST).build()).build())
                .when(ecr).describeImages(any(DescribeImagesRequest.class));
    }

    @Test
    void createsTheProjectRepositoryOnFirstPushAndReturnsTagAndDigestReferences() throws Exception {
        doThrow(RepositoryNotFoundException.builder().message("not found").build())
                .when(ecr).describeRepositories(any(DescribeRepositoriesRequest.class));
        doReturn(CreateRepositoryResponse.builder().repository(repository()).build())
                .when(ecr).createRepository(any(CreateRepositoryRequest.class));
        doReturn(PutLifecyclePolicyResponse.builder().build()).when(ecr).putLifecyclePolicy(any(PutLifecyclePolicyRequest.class));

        ContainerRegistryService.PublishedImage published = service.publish(request());

        ArgumentCaptor<CreateRepositoryRequest> created = ArgumentCaptor.forClass(CreateRepositoryRequest.class);
        verify(ecr).createRepository(created.capture());
        assertThat(created.getValue().repositoryName()).isEqualTo("edgedeploy/" + PROJECT);
        assertThat(created.getValue().imageScanningConfiguration().scanOnPush()).isTrue();
        assertThat(created.getValue().tags()).anyMatch(t -> t.key().equals(AwsTags.PROJECT) && t.value().equals(PROJECT.toString()));
        ArgumentCaptor<PutLifecyclePolicyRequest> lifecycle = ArgumentCaptor.forClass(PutLifecyclePolicyRequest.class);
        verify(ecr).putLifecyclePolicy(lifecycle.capture());
        assertThat(lifecycle.getValue().lifecyclePolicyText()).contains("\"countNumber\":10").contains("imageCountMoreThan");

        assertThat(published.imageUri()).isEqualTo(REPOSITORY_URI + ":" + SHA);
        assertThat(published.deployReference()).isEqualTo(REPOSITORY_URI + "@" + DIGEST);
        assertThat(published.digest()).isEqualTo(DIGEST);
        assertThat(published.pushed()).isTrue();

        verify(docker).tag("edgedeploy/" + PROJECT + ":" + SHA, REPOSITORY_URI + ":" + SHA);
        ArgumentCaptor<DockerImageClient.RegistryCredentials> credentials = ArgumentCaptor.forClass(DockerImageClient.RegistryCredentials.class);
        verify(docker).push(eq(REPOSITORY_URI + ":" + SHA), credentials.capture(), eq(scratch.resolve("docker-config")), any(), any(), any());
        assertThat(credentials.getValue()).isEqualTo(new DockerImageClient.RegistryCredentials(REGISTRY, "AWS", "registry-password"));
        assertThat(credentials.getValue().toString()).doesNotContain("registry-password");
        assertThat(progress).contains("Authenticating with ECR").anyMatch(p -> p.startsWith("Image pushed successfully"));
    }

    @Test
    void reusesAnExistingRepository() throws Exception {
        doReturn(DescribeRepositoriesResponse.builder().repositories(repository()).build())
                .when(ecr).describeRepositories(any(DescribeRepositoriesRequest.class));

        service.publish(request());

        verify(ecr, never()).createRepository(any(CreateRepositoryRequest.class));
    }

    @Test
    void retriesTransientPushFailures() throws Exception {
        existingRepository();
        doThrow(new DockerBuildException(DockerBuildException.Kind.BUILD_FAILED, "docker push failed: connection reset"))
                .doNothing()
                .when(docker).push(anyString(), any(), any(), any(), any(), any());

        service.publish(request());

        verify(docker, times(2)).push(anyString(), any(), any(), any(), any(), any());
        assertThat(progress).contains("Push attempt 1 failed; retrying");
    }

    @Test
    void doesNotRetryRejectedCredentials() throws Exception {
        existingRepository();
        doThrow(new DockerBuildException(DockerBuildException.Kind.REGISTRY_AUTH, "denied"))
                .when(docker).push(anyString(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.publish(request()))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("ECR authentication failed");
        verify(docker, times(1)).push(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfPushAttempts() throws Exception {
        existingRepository();
        doThrow(new DockerBuildException(DockerBuildException.Kind.BUILD_FAILED, "docker push failed: EOF"))
                .when(docker).push(anyString(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.publish(request())).hasMessage("docker push failed: EOF");
        verify(docker, times(3)).push(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void missingPermissionIsReportedClearly() {
        doThrow(EcrException.builder().statusCode(400).awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("AccessDeniedException").errorMessage("not authorized").build()).build())
                .when(ecr).describeRepositories(any(DescribeRepositoriesRequest.class));

        assertThatThrownBy(() -> service.publish(request()))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("not authorized to perform ecr:DescribeRepositories");
    }

    private void existingRepository() {
        doReturn(DescribeRepositoriesResponse.builder().repositories(repository()).build())
                .when(ecr).describeRepositories(any(DescribeRepositoriesRequest.class));
    }

    private static Repository repository() {
        return Repository.builder().repositoryName("edgedeploy/" + PROJECT).repositoryUri(REPOSITORY_URI).build();
    }

    private ContainerRegistryService.PublishRequest request() {
        DockerBuildService.BuiltImage built = new DockerBuildService.BuiltImage(ImageName.of("edgedeploy", PROJECT, SHA),
                "sha256:local", "arm64");
        return new ContainerRegistryService.PublishRequest(UUID.randomUUID(), PROJECT, built, scratch, Duration.ofMinutes(5),
                () -> false, progress::add);
    }
}
