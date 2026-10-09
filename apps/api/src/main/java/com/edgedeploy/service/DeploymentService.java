package com.edgedeploy.service;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.contracts.event.DeploymentStatusEvent;
import com.edgedeploy.deployment.DeploymentStateService;
import com.edgedeploy.dto.DeploymentLogResponse;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.dto.TriggerDeploymentRequest;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.DeploymentLog;
import com.edgedeploy.entity.Project;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.exception.InvalidRequestException;
import com.edgedeploy.exception.ResourceNotFoundException;
import com.edgedeploy.github.GitHubApiException;
import com.edgedeploy.github.GitHubService;
import com.edgedeploy.kafka.OutboxWriter;
import com.edgedeploy.mapper.DeploymentMapper;
import com.edgedeploy.mapper.ProjectMapper;
import com.edgedeploy.repository.DeploymentLogRepository;
import com.edgedeploy.repository.DeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);
    static final String AGGREGATE_TYPE = "Deployment";

    private final DeploymentRepository deployments;
    private final DeploymentLogRepository logs;
    private final ProjectService projectService;
    private final DeploymentStateService stateService;
    private final GitHubService gitHub;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final Clock clock;

    public DeploymentService(DeploymentRepository deployments, DeploymentLogRepository logs,
                             ProjectService projectService, DeploymentStateService stateService, GitHubService gitHub,
                             OutboxWriter outbox, PlatformTransactionManager transactionManager, Clock clock) {
        this.deployments = deployments;
        this.logs = logs;
        this.projectService = projectService;
        this.stateService = stateService;
        this.gitHub = gitHub;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * Queues a deployment of an exact commit.
     *
     * <ol>
     *   <li>Load the project (ownership enforced).</li>
     *   <li>Resolve the commit on GitHub, outside any transaction: the requested SHA, or the branch tip.
     *       Pinning the SHA now makes the deployment reproducible even if the branch moves.</li>
     *   <li>In one transaction: insert the QUEUED deployment and its {@link DeploymentRequestedEvent}
     *       outbox row. The relay publishes it to Kafka after commit.</li>
     * </ol>
     */
    public DeploymentResponse trigger(UUID userId, UUID projectId, TriggerDeploymentRequest request) {
        ProjectResponse project = readOnlyTx.execute(status ->
                ProjectMapper.toResponse(projectService.getOwned(userId, projectId)));
        String commitSha = resolveCommit(userId, project, request.commitSha());

        return tx.execute(status -> {
            // Re-read with a row lock: the project may have been deleted meanwhile, and the lock makes
            // concurrent deploys of one project take numbers one after another (unique per project).
            Project current = projectService.getOwnedForUpdate(userId, projectId);
            int number = deployments.maxNumber(current.getId()) + 1;
            Deployment deployment = deployments.saveAndFlush(Deployment.queue(current, number, commitSha));
            logs.save(DeploymentLog.milestone(deployment.getId(), DeploymentStep.QUEUED, "Deployment #" + number
                    + " queued for " + current.getRepository() + "@" + current.getBranch() + " (" + commitSha.substring(0, 7) + ")",
                    clock.instant()));

            DeploymentRequestedEvent event = new DeploymentRequestedEvent(
                    UUID.randomUUID(),
                    deployment.getId(),
                    current.getId(),
                    current.getRepository(),
                    current.getBranch(),
                    commitSha,
                    clock.instant());
            outbox.enqueue(AGGREGATE_TYPE, deployment.getId(), KafkaTopics.DEPLOYMENT_REQUESTED,
                    deployment.getId().toString(), event);

            log.info("Queued deployment {} for project {} ({}@{} {})", deployment.getId(), current.getId(),
                    current.getRepository(), current.getBranch(), commitSha);
            return DeploymentMapper.toResponse(deployment);
        });
    }

    /**
     * Cancels a deployment that has not started rolling out yet (QUEUED, BUILDING, PUSHING -> STOPPED) and
     * announces it on deployment.status so live views update. The worker notices within seconds, kills its
     * git/docker process and cleans up. Once the deployment target is being updated, cancelling is refused:
     * stopping halfway would leave the running application in an unknown state.
     */
    @Transactional
    public DeploymentResponse cancel(UUID userId, UUID deploymentId) {
        Deployment deployment = getOwned(userId, deploymentId);
        DeploymentStatus from = deployment.getStatus();
        if (!from.isCancellable()) {
            throw new ConflictException(from.isInProgress()
                    ? "The deployment is already rolling out and can no longer be cancelled"
                    : "Only queued, building or pushing deployments can be cancelled (status is " + from + ")");
        }
        stateService.transition(deploymentId, from, DeploymentStatus.STOPPED);
        outbox.enqueue(AGGREGATE_TYPE, deploymentId, KafkaTopics.DEPLOYMENT_STATUS, deploymentId.toString(),
                new DeploymentStatusEvent(UUID.randomUUID(), deploymentId, deployment.getProject().getId(),
                        DeploymentStatus.STOPPED, null, "Cancelled by user", clock.instant()));
        return DeploymentMapper.toResponse(getOwned(userId, deploymentId));
    }

    @Transactional(readOnly = true)
    public DeploymentResponse get(UUID userId, UUID deploymentId) {
        return DeploymentMapper.toResponse(getOwned(userId, deploymentId));
    }

    @Transactional(readOnly = true)
    public List<DeploymentResponse> listForProject(UUID userId, UUID projectId, int limit) {
        projectService.getOwned(userId, projectId);
        return deployments.findByProject_IdOrderByCreatedAtDesc(projectId, Limit.of(limit)).stream()
                .map(DeploymentMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeploymentResponse> recent(UUID userId, int limit) {
        return deployments.findRecentForUser(userId, Limit.of(limit)).stream()
                .map(DeploymentMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeploymentLogResponse> logs(UUID userId, UUID deploymentId, long afterSeq, int limit) {
        getOwned(userId, deploymentId);
        return logs.findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(deploymentId, afterSeq, Limit.of(limit)).stream()
                .map(DeploymentMapper::toResponse)
                .toList();
    }

    /** Current database state, shaped like a live status event, for SSE (re)connects. */
    @Transactional(readOnly = true)
    public DeploymentStatusEvent snapshot(UUID userId, UUID deploymentId) {
        Deployment deployment = getOwned(userId, deploymentId);
        return new DeploymentStatusEvent(
                UUID.randomUUID(),
                deployment.getId(),
                deployment.getProject().getId(),
                deployment.getStatus(),
                deployment.getDeploymentUrl(),
                "snapshot",
                clock.instant());
    }

    private String resolveCommit(UUID userId, ProjectResponse project, String requestedSha) {
        String repoName = project.repository().substring(project.repository().indexOf('/') + 1);
        if (requestedSha != null) {
            try {
                return gitHub.getCommit(userId, project.owner(), repoName, requestedSha).sha();
            } catch (GitHubApiException e) {
                if (e.kind() == GitHubApiException.Kind.NOT_FOUND) {
                    throw new InvalidRequestException("commitSha",
                            "Commit " + requestedSha + " was not found in " + project.repository());
                }
                throw e;
            }
        }
        try {
            return gitHub.getBranch(userId, project.owner(), repoName, project.branch()).commit().sha();
        } catch (GitHubApiException e) {
            if (e.kind() == GitHubApiException.Kind.NOT_FOUND) {
                throw new ConflictException("Branch '" + project.branch() + "' no longer exists in "
                        + project.repository() + "; update the project's branch");
            }
            throw e;
        }
    }

    private Deployment getOwned(UUID userId, UUID deploymentId) {
        return deployments.findOwned(deploymentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", deploymentId));
    }
}
