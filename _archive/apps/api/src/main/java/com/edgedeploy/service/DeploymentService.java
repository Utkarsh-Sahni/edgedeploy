package com.edgedeploy.service;

import com.edgedeploy.deployment.DeploymentStatus;
import com.edgedeploy.dto.CreateDeploymentRequest;
import com.edgedeploy.dto.DeploymentLogResponse;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import com.edgedeploy.exception.ApiException;
import com.edgedeploy.github.GitHubClient;
import com.edgedeploy.kafka.DeploymentEventProducer;
import com.edgedeploy.kafka.DeploymentRequestedEvent;
import com.edgedeploy.mapper.EntityMappers;
import com.edgedeploy.repository.DeploymentLogRepository;
import com.edgedeploy.repository.DeploymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository deploymentLogRepository;
    private final ProjectService projectService;
    private final UserService userService;
    private final GitHubClient gitHubClient;
    private final DeploymentEventProducer eventProducer;
    private final DeploymentCacheService cacheService;
    private final EntityMappers mappers;

    public DeploymentService(
            DeploymentRepository deploymentRepository,
            DeploymentLogRepository deploymentLogRepository,
            ProjectService projectService,
            UserService userService,
            GitHubClient gitHubClient,
            DeploymentEventProducer eventProducer,
            DeploymentCacheService cacheService,
            EntityMappers mappers
    ) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentLogRepository = deploymentLogRepository;
        this.projectService = projectService;
        this.userService = userService;
        this.gitHubClient = gitHubClient;
        this.eventProducer = eventProducer;
        this.cacheService = cacheService;
        this.mappers = mappers;
    }

    @Transactional(readOnly = true)
    public List<DeploymentResponse> list(UUID userId, UUID projectId) {
        projectService.requireOwned(userId, projectId);
        return deploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(mappers::toDeployment)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeploymentResponse get(UUID userId, UUID deploymentId) {
        Deployment deployment = deploymentRepository.findByIdAndProjectUserId(deploymentId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deployment not found"));
        cacheService.getStatus(deploymentId).ifPresent(deployment::setStatus);
        return mappers.toDeployment(deployment);
    }

    @Transactional(readOnly = true)
    public List<DeploymentLogResponse> logs(UUID userId, UUID deploymentId) {
        get(userId, deploymentId);
        return deploymentLogRepository.findByDeploymentIdOrderByTimestampAsc(deploymentId).stream()
                .map(mappers::toLog)
                .toList();
    }

    @Transactional
    public DeploymentResponse create(UUID userId, UUID projectId, CreateDeploymentRequest request) {
        Project project = projectService.requireOwned(userId, projectId);
        User user = userService.getRequired(userId);
        String token = userService.decryptGithubToken(user);
        String commitSha = request.commitSha();
        if (commitSha == null || commitSha.isBlank()) {
            commitSha = gitHubClient.resolveCommitSha(token, project.getRepository(), project.getBranch());
        }

        Deployment deployment = new Deployment();
        deployment.setProject(project);
        deployment.setCommitSha(commitSha);
        deployment.setStatus(DeploymentStatus.QUEUED);
        Deployment saved = deploymentRepository.save(deployment);
        cacheService.cacheStatus(saved.getId(), DeploymentStatus.QUEUED);

        eventProducer.publishRequested(new DeploymentRequestedEvent(
                saved.getId(),
                project.getId(),
                project.getRepository(),
                project.getBranch(),
                commitSha,
                Instant.now()
        ));
        return mappers.toDeployment(saved);
    }

    @Transactional
    public DeploymentResponse createFromWebhook(String repository, String branch, String commitSha) {
        Project project = projectService.findByRepository(repository);
        if (!project.getBranch().equals(branch)) {
            return null;
        }
        Deployment deployment = new Deployment();
        deployment.setProject(project);
        deployment.setCommitSha(commitSha);
        deployment.setStatus(DeploymentStatus.QUEUED);
        Deployment saved = deploymentRepository.save(deployment);
        eventProducer.publishRequested(new DeploymentRequestedEvent(
                saved.getId(),
                project.getId(),
                project.getRepository(),
                project.getBranch(),
                commitSha,
                Instant.now()
        ));
        return mappers.toDeployment(saved);
    }

    @Transactional
    public DeploymentResponse stop(UUID userId, UUID deploymentId) {
        Deployment deployment = deploymentRepository.findByIdAndProjectUserId(deploymentId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deployment not found"));
        deployment.setStatus(DeploymentStatus.STOPPED);
        deployment.setCompletedAt(Instant.now());
        cacheService.cacheStatus(deployment.getId(), DeploymentStatus.STOPPED);
        return mappers.toDeployment(deploymentRepository.save(deployment));
    }
}
