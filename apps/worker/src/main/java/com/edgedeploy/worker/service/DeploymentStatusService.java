package com.edgedeploy.worker.service;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.LogLevel;
import com.edgedeploy.worker.deployment.ClaimedDeployment;
import com.edgedeploy.worker.deployment.DeploymentStore;
import com.edgedeploy.worker.kafka.DeploymentEventPublisher;
import com.edgedeploy.worker.logging.DeploymentLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The worker's counterpart of the api's {@code DeploymentStateService}: every status change made by the
 * worker goes through here.
 *
 * <p>Each transition is validated against the shared state machine, applied as a compare-and-set, and
 * recorded together with its timeline log line in one transaction. Status/log events are published
 * only after commit, so anything reacting to them reads consistent data.
 */
@Service
public class DeploymentStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentStatusService.class);

    private final DeploymentStore store;
    private final DeploymentLogService logs;
    private final DeploymentEventPublisher events;
    private final TransactionTemplate tx;

    public DeploymentStatusService(DeploymentStore store, DeploymentLogService logs, DeploymentEventPublisher events,
                                   PlatformTransactionManager transactionManager) {
        this.store = store;
        this.logs = logs;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * QUEUED -> BUILDING. The deployment id is the idempotency key: a duplicate or stale event finds the
     * deployment in another state, claims nothing, and is skipped.
     */
    public Optional<ClaimedDeployment> claim(UUID deploymentId, String workerName) {
        Optional<ClaimedDeployment> claimed = tx.execute(status -> {
            Optional<ClaimedDeployment> result = store.claim(deploymentId);
            result.ifPresent(c -> logs.append(deploymentId, List.of(new DeploymentLogService.Entry(LogLevel.INFO, null,
                    "Build started on worker " + workerName))));
            return result;
        });
        claimed.ifPresent(c -> events.statusChanged(deploymentId, c.projectId(), DeploymentStatus.BUILDING, null, "Build started"));
        return claimed;
    }

    /**
     * BUILDING -> IMAGE_BUILT, completing the IMAGE milestone.
     *
     * @param pipelineComplete true when nothing follows (no registry/target yet), which records completion
     */
    public void imageBuilt(UUID deploymentId, UUID projectId, String imageReference, boolean pipelineComplete) {
        tx.executeWithoutResult(status -> {
            if (!store.markImageBuilt(deploymentId, imageReference, pipelineComplete)) {
                throw new ConcurrentStatusChangeException(deploymentId, DeploymentStatus.BUILDING, DeploymentStatus.IMAGE_BUILT);
            }
            logs.append(deploymentId, List.of(new DeploymentLogService.Entry(LogLevel.INFO, DeploymentStep.IMAGE,
                    "Docker image built: " + imageReference)));
            afterCommit(() -> events.statusChanged(deploymentId, projectId, DeploymentStatus.IMAGE_BUILT, null, imageReference));
        });
    }

    /**
     * -> FAILED with a user-facing reason, recorded as an ERROR on the step that failed.
     *
     * @return false if the deployment had already moved on (e.g. cancelled), in which case nothing changes
     */
    public boolean fail(UUID deploymentId, UUID projectId, DeploymentStatus from, DeploymentStep failedStep, String reason) {
        Boolean failed = tx.execute(status -> {
            if (!store.markFailed(deploymentId, from, reason)) {
                return false;
            }
            logs.append(deploymentId, List.of(new DeploymentLogService.Entry(LogLevel.ERROR, failedStep, reason)));
            afterCommit(() -> {
                events.statusChanged(deploymentId, projectId, DeploymentStatus.FAILED, null, reason);
                events.failed(deploymentId, projectId, from, reason);
            });
            return true;
        });
        if (!Boolean.TRUE.equals(failed)) {
            log.warn("Could not mark deployment {} FAILED: no longer {}", deploymentId, from);
            return false;
        }
        return true;
    }

    /** A plain step forward in the state machine (e.g. BUILDING -> PUSHING), recorded with a log line. */
    public void transition(UUID deploymentId, UUID projectId, DeploymentStatus from, DeploymentStatus to, String message) {
        tx.executeWithoutResult(status -> {
            if (!store.transition(deploymentId, from, to)) {
                throw new ConcurrentStatusChangeException(deploymentId, from, to);
            }
            logs.append(deploymentId, List.of(new DeploymentLogService.Entry(LogLevel.INFO, null, message)));
            afterCommit(() -> events.statusChanged(deploymentId, projectId, to, null, message));
        });
    }

    /** The image is in the registry: remember tag and digest (for rollback) and complete the PUSH milestone. */
    public void imagePushed(UUID deploymentId, String imageUri, String digest) {
        tx.executeWithoutResult(status -> {
            store.recordImage(deploymentId, imageUri, digest);
            logs.append(deploymentId, List.of(new DeploymentLogService.Entry(LogLevel.INFO, DeploymentStep.PUSH,
                    "Image pushed: " + imageUri)));
        });
    }

    public void recordRollout(UUID deploymentId, String cluster, String service, String taskDefinitionArn) {
        store.recordRollout(deploymentId, cluster, service, taskDefinitionArn);
    }

    /**
     * HEALTH_CHECK -> RUNNING. The project's previously running deployment is now replaced (one service per
     * project), so it moves to STOPPED in the same transaction.
     */
    public void markRunning(UUID deploymentId, UUID projectId, int number, String url, String taskArn) {
        tx.executeWithoutResult(status -> {
            if (!store.markRunning(deploymentId, url, taskArn)) {
                throw new ConcurrentStatusChangeException(deploymentId, DeploymentStatus.HEALTH_CHECK, DeploymentStatus.RUNNING);
            }
            List<DeploymentStore.NumberedDeployment> replaced = store.stopOtherRunning(projectId, deploymentId);
            for (DeploymentStore.NumberedDeployment old : replaced) {
                logs.append(old.deploymentId(), List.of(new DeploymentLogService.Entry(LogLevel.INFO, null,
                        "Replaced by deployment #" + number)));
            }
            logs.append(deploymentId, List.of(new DeploymentLogService.Entry(LogLevel.INFO, DeploymentStep.LIVE,
                    "Deployment successful: " + url)));
            afterCommit(() -> {
                replaced.forEach(old -> events.statusChanged(old.deploymentId(), projectId, DeploymentStatus.STOPPED, null,
                        "Replaced by deployment #" + number));
                events.statusChanged(deploymentId, projectId, DeploymentStatus.RUNNING, url, "Deployment successful");
            });
        });
    }

    /** A newer deployment of the project is already live: rolling this one out would downgrade it. */
    public void stopSuperseded(UUID deploymentId, UUID projectId, DeploymentStatus from, int newerNumber) {
        String message = "Skipped: deployment #" + newerNumber + " of this project is newer and already rolled out";
        tx.executeWithoutResult(status -> {
            if (!store.markStopped(deploymentId, from)) {
                throw new ConcurrentStatusChangeException(deploymentId, from, DeploymentStatus.STOPPED);
            }
            logs.append(deploymentId, List.of(new DeploymentLogService.Entry(LogLevel.WARN, null, message)));
            afterCommit(() -> events.statusChanged(deploymentId, projectId, DeploymentStatus.STOPPED, null, message));
        });
    }

    /** The version the project serves right now (to roll back to if this deployment fails). */
    public Optional<String> runningVersion(UUID projectId, UUID except) {
        return store.runningTaskDefinition(projectId, except);
    }

    public Optional<Integer> newerDeploymentStarted(UUID projectId, int number) {
        return store.newerDeploymentStarted(projectId, number);
    }

    public void recordResolvedCommit(UUID deploymentId, String commitSha) {
        store.recordCommit(deploymentId, commitSha);
    }

    public Optional<DeploymentStatus> currentStatus(UUID deploymentId) {
        return store.currentStatus(deploymentId);
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** The deployment left the expected state concurrently (typically: the user cancelled it). */
    public static final class ConcurrentStatusChangeException extends RuntimeException {
        public ConcurrentStatusChangeException(UUID deploymentId, DeploymentStatus from, DeploymentStatus to) {
            super("deployment " + deploymentId + " left " + from + " before it could move to " + to);
        }
    }
}
