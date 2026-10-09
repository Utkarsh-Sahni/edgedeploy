package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.delivery.LocalContainerRegistry;
import com.edgedeploy.worker.delivery.NoDeploymentTarget;
import com.edgedeploy.worker.docker.DockerBuildException;
import com.edgedeploy.worker.docker.DockerBuildService;
import com.edgedeploy.worker.docker.DockerfileGenerator;
import com.edgedeploy.worker.docker.ImageName;
import com.edgedeploy.worker.framework.DetectedProject;
import com.edgedeploy.worker.framework.Framework;
import com.edgedeploy.worker.framework.FrameworkDetectionException;
import com.edgedeploy.worker.framework.FrameworkDetector;
import com.edgedeploy.worker.framework.PackageManager;
import com.edgedeploy.worker.github.GitException;
import com.edgedeploy.worker.github.GitService;
import com.edgedeploy.worker.logging.BuildOutputSink;
import com.edgedeploy.worker.logging.DeploymentLogService;
import com.edgedeploy.worker.service.DeploymentStatusService;
import com.edgedeploy.worker.support.TestProperties;
import com.edgedeploy.worker.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentPipelineTest {

    private static final String SHA = "a83f12c0a83f12c0a83f12c0a83f12c0a83f12c0";
    private static final UUID DEPLOYMENT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final DetectedProject VITE = new DetectedProject(Framework.VITE, PackageManager.PNPM, Set.of("build"),
            null, true, List.of("package.json", "pnpm-lock.yaml"), "\"vite\" dependency");

    @TempDir
    Path root;

    @Mock
    GitService git;
    @Mock
    FrameworkDetector detector;
    @Mock
    DockerfileGenerator dockerfiles;
    @Mock
    DockerBuildService docker;
    @Mock
    DeploymentLogService logs;
    @Mock
    DeploymentStatusService status;
    @Mock
    EnvironmentVariableReader environment;
    @Mock
    ProjectDeploymentLock locks;

    private DeploymentPipeline pipeline;
    private WorkerProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        properties = TestProperties.create(root, "https://github.com");
        pipeline = new DeploymentPipeline(git, detector, dockerfiles, docker, new LocalContainerRegistry(),
                new NoDeploymentTarget(), logs, status, environment, locks, properties);
        lenient().when(logs.outputSink(any())).thenReturn(mock(BuildOutputSink.class));
        lenient().when(git.checkoutCommit(any())).thenReturn(SHA);
        lenient().when(detector.detect(any(), anyString())).thenReturn(VITE);
        lenient().when(dockerfiles.generate(any(), any())).thenReturn(new DockerfileGenerator.GeneratedDockerfile(
                root.resolve("build/Dockerfile"), Framework.VITE, 8080, "FROM node"));
        lenient().when(docker.build(any())).thenAnswer(invocation -> {
            DockerBuildService.Request request = invocation.getArgument(0);
            return new DockerBuildService.BuiltImage(request.image(), "sha256:0123456789abcdef", "arm64");
        });
    }

    @Test
    void successfulPipelineEndsAtImageBuilt() throws Exception {
        DeploymentContext context = context(SHA, Duration.ofMinutes(5));

        pipeline.run(context);

        InOrder order = inOrder(git, detector, dockerfiles, docker, status);
        order.verify(git).cloneRepository(any());
        order.verify(git).checkoutCommit(any());
        order.verify(detector).detect(context.workspace().source(), "UNKNOWN");
        order.verify(dockerfiles).generate(any(), eq(context.workspace().build()));
        order.verify(docker).build(any());
        String image = ImageName.of("edgedeploy", PROJECT, SHA).reference();
        order.verify(status).imageBuilt(DEPLOYMENT, PROJECT, image, true);
        verify(status, never()).fail(any(), any(), any(), any(), any());

        verify(logs).step(eq(DEPLOYMENT), eq(DeploymentStep.CLONE), startsWith("Repository cloned"));
        verify(logs).step(eq(DEPLOYMENT), eq(DeploymentStep.COMMIT), startsWith("Commit a83f12c verified"));
        verify(logs).step(eq(DEPLOYMENT), eq(DeploymentStep.FRAMEWORK), startsWith("Detected framework: VITE"));
        verify(logs).info(DEPLOYMENT, "Detected package manager: PNPM (pnpm-lock.yaml)");
        assertThat(context.status()).isEqualTo(DeploymentStatus.IMAGE_BUILT);
    }

    @Test
    void gitFailureFailsTheCloneStepAndStopsThePipeline() throws Exception {
        doThrow(new GitException(GitException.Kind.REPOSITORY_NOT_ACCESSIBLE, "Repository octocat/app was not found"))
                .when(git).cloneRepository(any());

        assertThatThrownBy(() -> pipeline.run(context(SHA, Duration.ofMinutes(5))))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> {
                    assertThat(f.step()).isEqualTo(DeploymentStep.CLONE);
                    assertThat(f.getMessage()).contains("not found");
                });
        verifyNoInteractions(detector, docker);
    }

    @Test
    void unverifiableCommitFailsTheCommitStep() throws Exception {
        when(git.checkoutCommit(any())).thenThrow(new GitException(GitException.Kind.COMMIT_MISMATCH, "does not match"));

        assertThatThrownBy(() -> pipeline.run(context(SHA, Duration.ofMinutes(5))))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> assertThat(f.step()).isEqualTo(DeploymentStep.COMMIT));
        verifyNoInteractions(docker);
    }

    @Test
    void frameworkDetectionFailureIsReported() throws Exception {
        when(detector.detect(any(), anyString())).thenThrow(new FrameworkDetectionException("No package.json found"));

        assertThatThrownBy(() -> pipeline.run(context(SHA, Duration.ofMinutes(5))))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> {
                    assertThat(f.step()).isEqualTo(DeploymentStep.FRAMEWORK);
                    assertThat(f.getMessage()).isEqualTo("Framework detection failed: No package.json found");
                });
        verifyNoInteractions(docker);
    }

    @Test
    void dockerBuildFailureFailsTheImageStep() throws Exception {
        doThrow(new DockerBuildException(DockerBuildException.Kind.BUILD_FAILED,
                "Docker build failed: npm error code E404")).when(docker).build(any());

        assertThatThrownBy(() -> pipeline.run(context(SHA, Duration.ofMinutes(5))))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> {
                    assertThat(f.step()).isEqualTo(DeploymentStep.IMAGE);
                    assertThat(f.getMessage()).isEqualTo("Docker build failed: npm error code E404");
                });
        verify(status, never()).imageBuilt(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void cancellationDuringBuildStopsWithoutFailing() throws Exception {
        doThrow(new DockerBuildException(DockerBuildException.Kind.CANCELLED, "cancelled")).when(docker).build(any());

        assertThatThrownBy(() -> pipeline.run(context(SHA, Duration.ofMinutes(5))))
                .isInstanceOf(DeploymentCancelledException.class);
    }

    @Test
    void exhaustedDeploymentTimeoutStopsBeforeTheNextStep() {
        assertThatThrownBy(() -> pipeline.run(context(SHA, Duration.ZERO)))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> assertThat(f.getMessage()).contains("time limit"));
        verifyNoInteractions(git);
    }

    @Test
    void branchTipDeploymentRecordsTheResolvedCommit() throws Exception {
        pipeline.run(context(null, Duration.ofMinutes(5)));

        verify(status).recordResolvedCommit(DEPLOYMENT, SHA);
    }

    private DeploymentContext context(String sha, Duration timeout) {
        ClaimedDeployment claimed = new ClaimedDeployment(DEPLOYMENT, PROJECT, 42, "octocat/app", "main", sha, "UNKNOWN", null, null);
        return new DeploymentContext(claimed, new Workspace(root.resolve("ws")), timeout, () -> false, Clock.systemUTC());
    }
}
