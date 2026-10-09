package com.edgedeploy.deployment;

import com.edgedeploy.aws.EcrService;
import com.edgedeploy.aws.EcsService;
import com.edgedeploy.aws.S3Service;
import com.edgedeploy.build.DockerfileGenerator;
import com.edgedeploy.build.FrameworkDetector;
import com.edgedeploy.config.WorkerProperties;
import com.edgedeploy.docker.DockerBuildService;
import com.edgedeploy.docker.LocalContainerRuntime;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.EnvironmentVariable;
import com.edgedeploy.entity.Project;
import com.edgedeploy.git.GitCloneService;
import com.edgedeploy.health.HealthCheckService;
import com.edgedeploy.kafka.DeploymentRequestedEvent;
import com.edgedeploy.kafka.StatusEventPublisher;
import com.edgedeploy.repository.DeploymentRepository;
import com.edgedeploy.repository.EnvironmentVariableRepository;
import com.edgedeploy.repository.ProjectRepository;
import com.edgedeploy.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DeploymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DeploymentOrchestrator.class);

    private final DeploymentRepository deploymentRepository;
    private final DeploymentClaimService claimService;
    private final ProjectRepository projectRepository;
    private final EnvironmentVariableRepository environmentVariableRepository;
    private final IdempotencyService idempotencyService;
    private final GitCloneService gitCloneService;
    private final FrameworkDetector frameworkDetector;
    private final DockerfileGenerator dockerfileGenerator;
    private final DockerBuildService dockerBuildService;
    private final LocalContainerRuntime localContainerRuntime;
    private final HealthCheckService healthCheckService;
    private final StatusEventPublisher statusEventPublisher;
    private final DeploymentLogWriter logWriter;
    private final EncryptionService encryptionService;
    private final WorkerProperties properties;
    private final ObjectProvider<EcrService> ecrService;
    private final ObjectProvider<EcsService> ecsService;
    private final ObjectProvider<S3Service> s3Service;

    public DeploymentOrchestrator(
            DeploymentRepository deploymentRepository,
            DeploymentClaimService claimService,
            ProjectRepository projectRepository,
            EnvironmentVariableRepository environmentVariableRepository,
            IdempotencyService idempotencyService,
            GitCloneService gitCloneService,
            FrameworkDetector frameworkDetector,
            DockerfileGenerator dockerfileGenerator,
            DockerBuildService dockerBuildService,
            LocalContainerRuntime localContainerRuntime,
            HealthCheckService healthCheckService,
            StatusEventPublisher statusEventPublisher,
            DeploymentLogWriter logWriter,
            EncryptionService encryptionService,
            WorkerProperties properties,
            ObjectProvider<EcrService> ecrService,
            ObjectProvider<EcsService> ecsService,
            ObjectProvider<S3Service> s3Service
    ) {
        this.deploymentRepository = deploymentRepository;
        this.claimService = claimService;
        this.projectRepository = projectRepository;
        this.environmentVariableRepository = environmentVariableRepository;
        this.idempotencyService = idempotencyService;
        this.gitCloneService = gitCloneService;
        this.frameworkDetector = frameworkDetector;
        this.dockerfileGenerator = dockerfileGenerator;
        this.dockerBuildService = dockerBuildService;
        this.localContainerRuntime = localContainerRuntime;
        this.healthCheckService = healthCheckService;
        this.statusEventPublisher = statusEventPublisher;
        this.logWriter = logWriter;
        this.encryptionService = encryptionService;
        this.properties = properties;
        this.ecrService = ecrService;
        this.ecsService = ecsService;
        this.s3Service = s3Service;
    }

    public void handle(DeploymentRequestedEvent event) {
        if (!idempotencyService.tryLock(event.deploymentId())) {
            log.info("Skipping duplicate deployment event {}", event.deploymentId());
            return;
        }
        logWriter.clear();
        try {
            process(event);
        } catch (Exception ex) {
            log.error("Deployment {} failed", event.deploymentId(), ex);
            markFailed(event.deploymentId(), event.projectId(), ex.getMessage());
            throw new IllegalStateException("Deployment failed", ex);
        } finally {
            idempotencyService.unlock(event.deploymentId());
        }
    }

    private void process(DeploymentRequestedEvent event) throws Exception {
            Deployment deployment = claimService.claim(event.deploymentId());
        if (deployment == null) {
            log.info("Deployment {} already claimed or missing", event.deploymentId());
            return;
        }
        Project project = projectRepository.findWithUserById(event.projectId())
                .orElseThrow(() -> new IllegalStateException("Project not found"));
        Map<String, String> env = decryptEnv(project.getId());
        String githubToken = project.getUser().getGithubAccessToken() == null
                ? null
                : encryptionService.decrypt(project.getUser().getGithubAccessToken());

        emit(deployment, DeploymentStatus.BUILDING, null, "Cloning " + event.repository());
        Path workDir = gitCloneService.cloneRepository(
                event.deploymentId(), event.repository(), event.commitSha(), githubToken);
        logWriter.append(deployment, "INFO", "Checked out " + event.commitSha());

        String framework = frameworkDetector.detect(workDir);
        project.setFramework(framework);
        projectRepository.save(project);
        dockerfileGenerator.ensureDockerfile(workDir, framework);
        logWriter.append(deployment, "INFO", "Detected framework " + framework);

        String imageTag = "edgedeploy/" + project.getId() + ":" + event.commitSha().substring(0, 12);
        dockerBuildService.build(workDir, imageTag, line -> logWriter.append(deployment, "INFO", line));

        String imageUri = imageTag;
        String url;
        if (properties.getAws().isEnabled()) {
            emit(deployment, DeploymentStatus.PUSHING, null, "Pushing image to ECR");
            EcrService ecr = ecrService.getObject();
            imageUri = ecr.imageUri(project.getId(), event.commitSha());
            dockerBuildService.build(workDir, imageUri, line -> logWriter.append(deployment, "INFO", line));
            ecr.authenticateAndPush(imageUri, line -> logWriter.append(deployment, "INFO", line));

            emit(deployment, DeploymentStatus.DEPLOYING, null, "Updating ECS service");
            url = ecsService.getObject().deploy(project.getId(), deployment.getId(), imageUri, env);
        } else {
            emit(deployment, DeploymentStatus.DEPLOYING, null, "Starting local container");
            int port = "react-vite".equals(framework) ? 80 : 3000;
            url = localContainerRuntime.deploy(deployment.getId(), imageTag, port, env, line -> logWriter.append(deployment, "INFO", line));
        }

        deployment.setImageUri(imageUri);
        deployment.setDeploymentUrl(url);
        deploymentRepository.save(deployment);

        emit(deployment, DeploymentStatus.HEALTH_CHECK, url, "Running health check");
        healthCheckService.waitUntilHealthy(url);

        deployment.setStatus(DeploymentStatus.RUNNING);
        deployment.setCompletedAt(Instant.now());
        deploymentRepository.save(deployment);
        emit(deployment, DeploymentStatus.RUNNING, url, "Deployment is live at " + url);

        s3Service.ifAvailable(s3 -> s3.uploadLogs(deployment.getId(), logWriter.collected()));
    }

    private Map<String, String> decryptEnv(UUID projectId) {
        Map<String, String> env = new LinkedHashMap<>();
        for (EnvironmentVariable variable : environmentVariableRepository.findByProjectIdOrderByKeyAsc(projectId)) {
            env.put(variable.getKey(), encryptionService.decrypt(variable.getEncryptedValue()));
        }
        return env;
    }

    private void emit(Deployment deployment, DeploymentStatus status, String url, String message) {
        logWriter.append(deployment, "INFO", message);
        statusEventPublisher.publish(deployment.getId(), deployment.getProject().getId(), status, url, message);
    }

    private void markFailed(UUID deploymentId, UUID projectId, String message) {
        deploymentRepository.findById(deploymentId).ifPresent(deployment -> {
            deployment.setStatus(DeploymentStatus.FAILED);
            deployment.setErrorMessage(message);
            deployment.setCompletedAt(Instant.now());
            deploymentRepository.save(deployment);
            logWriter.append(deployment, "ERROR", message);
        });
        statusEventPublisher.publish(deploymentId, projectId, DeploymentStatus.FAILED, null, message);
        s3Service.ifAvailable(s3 -> s3.uploadLogs(deploymentId, logWriter.collected()));
    }
}
