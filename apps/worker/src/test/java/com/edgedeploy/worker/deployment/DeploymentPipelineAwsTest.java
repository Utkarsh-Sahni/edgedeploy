package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.worker.delivery.ContainerRegistryService;
import com.edgedeploy.worker.delivery.DeliveryException;
import com.edgedeploy.worker.delivery.DeploymentTargetService;
import com.edgedeploy.worker.docker.DockerBuildService;
import com.edgedeploy.worker.docker.DockerfileGenerator;
import com.edgedeploy.worker.framework.DetectedProject;
import com.edgedeploy.worker.framework.Framework;
import com.edgedeploy.worker.framework.FrameworkDetector;
import com.edgedeploy.worker.framework.PackageManager;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The pipeline with a remote registry and a deployment target (AWS mode), integrations mocked. */
@ExtendWith(MockitoExtension.class)
class DeploymentPipelineAwsTest {

    private static final String SHA = "a83f12c0a83f12c0a83f12c0a83f12c0a83f12c0";
    private static final UUID DEPLOYMENT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final String URL = "http://edgedeploy-123.elb.amazonaws.com:10003";
    private static final ContainerRegistryService.PublishedImage PUBLISHED = new ContainerRegistryService.PublishedImage(
            "123.dkr.ecr/edgedeploy/p:" + SHA, "123.dkr.ecr/edgedeploy/p@sha256:abc", "sha256:abc", true);
    private static final DeploymentTargetService.Rollout ROLLOUT = new DeploymentTargetService.Rollout("edgedeploy",
            "edgedeploy-p", "task-def:7", URL, "tg", 3000, Instant.now());

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
    ContainerRegistryService registry;
    @Mock
    DeploymentTargetService target;
    @Mock
    DeploymentLogService logs;
    @Mock
    DeploymentStatusService status;
    @Mock
    EnvironmentVariableReader environment;
    @Mock
    ProjectDeploymentLock locks;

    private DeploymentPipeline pipeline;
    private ProjectDeploymentLock.Handle lock;

    @BeforeEach
    void setUp() throws Exception {
        pipeline = new DeploymentPipeline(git, detector, dockerfiles, docker, registry, target, logs, status, environment,
                locks, TestProperties.create(root, "https://github.com"));
        lock = mock(ProjectDeploymentLock.Handle.class);
        lenient().when(logs.outputSink(any())).thenReturn(mock(BuildOutputSink.class));
        lenient().when(git.checkoutCommit(any())).thenReturn(SHA);
        lenient().when(detector.detect(any(), any())).thenReturn(new DetectedProject(Framework.NODE, PackageManager.NPM,
                Set.of("start"), null, true, List.of("package.json", "package-lock.json"), "\"start\" script"));
        lenient().when(dockerfiles.generate(any(), any())).thenReturn(new DockerfileGenerator.GeneratedDockerfile(
                root.resolve("Dockerfile"), Framework.NODE, 3000, "FROM node"));
        lenient().when(docker.build(any())).thenAnswer(invocation -> new DockerBuildService.BuiltImage(
                ((DockerBuildService.Request) invocation.getArgument(0)).image(), "sha256:local", "arm64"));
        lenient().when(registry.remote()).thenReturn(true);
        lenient().when(registry.description()).thenReturn("Amazon ECR");
        lenient().when(target.enabled()).thenReturn(true);
        lenient().when(registry.publish(any())).thenReturn(PUBLISHED);
        lenient().when(environment.read(PROJECT)).thenReturn(Map.of("API_URL", "https://api.example.com"));
        lenient().when(locks.acquire(eq(PROJECT), any(), any())).thenReturn(Optional.of(lock));
        lenient().when(status.newerDeploymentStarted(PROJECT, 42)).thenReturn(Optional.empty());
        lenient().when(status.runningVersion(PROJECT, DEPLOYMENT)).thenReturn(Optional.of("task-def:6"));
        lenient().when(target.start(any(), any())).thenReturn(ROLLOUT);
        lenient().when(target.awaitStable(any(), any(), any())).thenReturn(new DeploymentTargetService.Running("task-arn", URL));
    }

    @Test
    void walksBuildingPushingDeployingHealthCheckRunning() throws Exception {
        DeploymentContext context = context();

        pipeline.run(context);

        InOrder order = inOrder(status, registry, target, lock);
        order.verify(status).transition(DEPLOYMENT, PROJECT, DeploymentStatus.BUILDING, DeploymentStatus.PUSHING, "Pushing image to Amazon ECR");
        order.verify(registry).publish(any());
        order.verify(status).imagePushed(DEPLOYMENT, PUBLISHED.imageUri(), PUBLISHED.digest());
        order.verify(status).transition(eq(DEPLOYMENT), eq(PROJECT), eq(DeploymentStatus.PUSHING), eq(DeploymentStatus.DEPLOYING), anyString());
        order.verify(target).start(any(), any());
        order.verify(status).recordRollout(DEPLOYMENT, "edgedeploy", "edgedeploy-p", "task-def:7");
        order.verify(target).awaitStable(eq(ROLLOUT), any(), any());
        order.verify(status).transition(eq(DEPLOYMENT), eq(PROJECT), eq(DeploymentStatus.DEPLOYING), eq(DeploymentStatus.HEALTH_CHECK), anyString());
        order.verify(target).verifyHealth(eq(ROLLOUT), any(), any());
        order.verify(status).markRunning(DEPLOYMENT, PROJECT, 42, URL, "task-arn");
        order.verify(lock).close();
        verify(status, never()).imageBuilt(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(target, never()).restore(any(), any(), any());
        assertThat(context.status()).isEqualTo(DeploymentStatus.RUNNING);

        ArgumentCaptor<DeploymentTargetService.Release> release = ArgumentCaptor.forClass(DeploymentTargetService.Release.class);
        verify(target).start(release.capture(), any());
        assertThat(release.getValue().imageReference()).isEqualTo(PUBLISHED.deployReference()); // by digest
        assertThat(release.getValue().imageArchitecture()).isEqualTo("arm64");
        assertThat(release.getValue().containerPort()).isEqualTo(3000);
        assertThat(release.getValue().environment()).containsEntry("API_URL", "https://api.example.com");
        assertThat(release.getValue().toString()).doesNotContain("api.example.com");
        verify(logs).step(eq(DEPLOYMENT), eq(DeploymentStep.DEPLOY), anyString());
        verify(logs).step(eq(DEPLOYMENT), eq(DeploymentStep.HEALTH), anyString());
    }

    @Test
    void urlKnownOnlyOnceTheTaskRunsIsHealthCheckedAndRecorded() throws Exception {
        // Budget mode: no load balancer, the address is the running task's public IP.
        DeploymentTargetService.Rollout noUrlYet = new DeploymentTargetService.Rollout("edgedeploy", "edgedeploy-p",
                "task-def:7", null, null, 3000, ROLLOUT.startedAt());
        when(target.start(any(), any())).thenReturn(noUrlYet);
        when(target.awaitStable(eq(noUrlYet), any(), any()))
                .thenReturn(new DeploymentTargetService.Running("task-arn", "http://3.91.10.20:3000"));

        pipeline.run(context());

        verify(target).verifyHealth(eq(noUrlYet.withUrl("http://3.91.10.20:3000")), any(), any());
        verify(status).markRunning(DEPLOYMENT, PROJECT, 42, "http://3.91.10.20:3000", "task-arn");
    }

    @Test
    void pushFailureFailsThePushStepWithoutTouchingTheTarget() throws Exception {
        when(registry.publish(any())).thenThrow(new DeliveryException("ECR authentication failed while pushing"));
        DeploymentContext context = context();

        assertThatThrownBy(() -> pipeline.run(context))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> {
                    assertThat(f.step()).isEqualTo(DeploymentStep.PUSH);
                    assertThat(f.getMessage()).isEqualTo("ECR authentication failed while pushing");
                });
        assertThat(context.status()).isEqualTo(DeploymentStatus.PUSHING);
        verify(target, never()).start(any(), any());
    }

    @Test
    void failedRolloutRestoresThePreviousVersion() throws Exception {
        when(target.awaitStable(any(), any(), any())).thenThrow(new DeliveryException("Task stopped unexpectedly: exit code 1"));
        DeploymentContext context = context();

        assertThatThrownBy(() -> pipeline.run(context))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> {
                    assertThat(f.step()).isEqualTo(DeploymentStep.DEPLOY);
                    assertThat(f.getMessage()).isEqualTo("Task stopped unexpectedly: exit code 1");
                });
        assertThat(context.status()).isEqualTo(DeploymentStatus.DEPLOYING);
        verify(target).restore(eq(ROLLOUT), eq("task-def:6"), any());
        verify(lock).close();
        verify(status, never()).markRunning(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void failedHealthCheckRestoresAndFailsTheHealthStep() throws Exception {
        doThrow(new DeliveryException("Health check failed: answered HTTP 502")).when(target).verifyHealth(any(), any(), any());
        DeploymentContext context = context();

        assertThatThrownBy(() -> pipeline.run(context))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> assertThat(f.step()).isEqualTo(DeploymentStep.HEALTH));
        assertThat(context.status()).isEqualTo(DeploymentStatus.HEALTH_CHECK);
        verify(target).restore(eq(ROLLOUT), eq("task-def:6"), any());
    }

    @Test
    void olderDeploymentIsSkippedWhenANewerOneAlreadyRolledOut() throws Exception {
        when(status.newerDeploymentStarted(PROJECT, 42)).thenReturn(Optional.of(43));
        DeploymentContext context = context();

        pipeline.run(context);

        verify(status).stopSuperseded(DEPLOYMENT, PROJECT, DeploymentStatus.PUSHING, 43);
        verify(target, never()).start(any(), any());
        assertThat(context.status()).isEqualTo(DeploymentStatus.STOPPED);
    }

    @Test
    void undecryptableEnvironmentFailsBeforeDeploying() throws Exception {
        when(environment.read(PROJECT)).thenThrow(new EnvironmentVariableReader.DeploymentConfigurationException(
                "Environment variable API_URL could not be decrypted."));

        assertThatThrownBy(() -> pipeline.run(context()))
                .isInstanceOfSatisfying(DeploymentFailure.class, f -> assertThat(f.step()).isEqualTo(DeploymentStep.DEPLOY));
        verify(target, never()).start(any(), any());
    }

    private DeploymentContext context() {
        ClaimedDeployment claimed = new ClaimedDeployment(DEPLOYMENT, PROJECT, 42, "octocat/app", "main", SHA, "UNKNOWN", null, null);
        return new DeploymentContext(claimed, new Workspace(root.resolve("ws")), Duration.ofMinutes(30), () -> false, Clock.systemUTC());
    }
}
