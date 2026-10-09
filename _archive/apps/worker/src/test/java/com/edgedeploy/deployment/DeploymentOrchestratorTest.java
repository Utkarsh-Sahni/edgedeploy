package com.edgedeploy.deployment;

import com.edgedeploy.build.DockerfileGenerator;
import com.edgedeploy.build.FrameworkDetector;
import com.edgedeploy.config.WorkerProperties;
import com.edgedeploy.docker.DockerBuildService;
import com.edgedeploy.docker.LocalContainerRuntime;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import com.edgedeploy.git.GitCloneService;
import com.edgedeploy.health.HealthCheckService;
import com.edgedeploy.kafka.DeploymentRequestedEvent;
import com.edgedeploy.kafka.StatusEventPublisher;
import com.edgedeploy.repository.DeploymentRepository;
import com.edgedeploy.repository.EnvironmentVariableRepository;
import com.edgedeploy.repository.ProjectRepository;
import com.edgedeploy.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentOrchestratorTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentClaimService claimService;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EnvironmentVariableRepository environmentVariableRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private GitCloneService gitCloneService;
    @Mock
    private FrameworkDetector frameworkDetector;
    @Mock
    private DockerfileGenerator dockerfileGenerator;
    @Mock
    private DockerBuildService dockerBuildService;
    @Mock
    private LocalContainerRuntime localContainerRuntime;
    @Mock
    private HealthCheckService healthCheckService;
    @Mock
    private StatusEventPublisher statusEventPublisher;
    @Mock
    private DeploymentLogWriter logWriter;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ObjectProvider<com.edgedeploy.aws.EcrService> ecrService;
    @Mock
    private ObjectProvider<com.edgedeploy.aws.EcsService> ecsService;
    @Mock
    private ObjectProvider<com.edgedeploy.aws.S3Service> s3Service;

    private DeploymentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new DeploymentOrchestrator(
                deploymentRepository,
                claimService,
                projectRepository,
                environmentVariableRepository,
                idempotencyService,
                gitCloneService,
                frameworkDetector,
                dockerfileGenerator,
                dockerBuildService,
                localContainerRuntime,
                healthCheckService,
                statusEventPublisher,
                logWriter,
                encryptionService,
                new WorkerProperties(),
                ecrService,
                ecsService,
                s3Service
        );
    }

    @Test
    void skipsDuplicateEventsWhenLockNotAcquired() {
        UUID deploymentId = UUID.randomUUID();
        when(idempotencyService.tryLock(deploymentId)).thenReturn(false);

        orchestrator.handle(new DeploymentRequestedEvent(
                deploymentId, UUID.randomUUID(), "acme/web", "main", "abc", Instant.now()
        ));

        verify(claimService, never()).claim(any());
    }

    @Test
    void buildsAndDeploysLocallyWhenAwsDisabled() throws Exception {
        UUID deploymentId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Deployment deployment = new Deployment();
        deployment.setId(deploymentId);
        Project project = new Project();
        project.setId(projectId);
        project.setUser(new User());
        deployment.setProject(project);

        when(idempotencyService.tryLock(deploymentId)).thenReturn(true);
        when(claimService.claim(deploymentId)).thenReturn(deployment);
        when(projectRepository.findWithUserById(projectId)).thenReturn(Optional.of(project));
        when(environmentVariableRepository.findByProjectIdOrderByKeyAsc(projectId)).thenReturn(List.of());
        Path workDir = Path.of("/tmp/edgedeploy-test");
        when(gitCloneService.cloneRepository(eq(deploymentId), eq("acme/web"), eq("abcdef123456"), any())).thenReturn(workDir);
        when(frameworkDetector.detect(workDir)).thenReturn("nextjs");
        when(localContainerRuntime.deploy(eq(deploymentId), any(), eq(3000), any(), any()))
                .thenReturn("http://abcdef12.localhost");

        orchestrator.handle(new DeploymentRequestedEvent(
                deploymentId, projectId, "acme/web", "main", "abcdef123456", Instant.now()
        ));

        verify(dockerBuildService).build(eq(workDir), eq("edgedeploy/" + projectId + ":abcdef123456"), any());
        verify(healthCheckService).waitUntilHealthy("http://abcdef12.localhost");
        verify(statusEventPublisher).publish(eq(deploymentId), eq(projectId), eq(DeploymentStatus.RUNNING), eq("http://abcdef12.localhost"), any());
        verify(idempotencyService).unlock(deploymentId);
    }
}
