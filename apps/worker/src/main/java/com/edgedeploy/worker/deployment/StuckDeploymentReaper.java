package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.LogLevel;
import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.kafka.DeploymentEventPublisher;
import com.edgedeploy.worker.logging.DeploymentLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Fails deployments left in progress (BUILDING ... HEALTH_CHECK) by a worker that crashed or was killed. A live worker
 * always records an outcome within the deployment timeout, so anything older than that (plus a grace
 * period) is abandoned. Safe with several workers: the UPDATE only matches rows still in progress.
 */
@Component
@ConditionalOnProperty(prefix = "edgedeploy", name = "mode", havingValue = "build", matchIfMissing = true)
public class StuckDeploymentReaper {

    static final String REASON = "The build worker stopped responding before the deployment finished. Try deploying again.";
    private static final Logger log = LoggerFactory.getLogger(StuckDeploymentReaper.class);
    private static final Duration GRACE = Duration.ofMinutes(5);

    private final DeploymentStore store;
    private final DeploymentLogService logs;
    private final DeploymentEventPublisher events;
    private final Duration threshold;
    private final Clock clock;

    public StuckDeploymentReaper(DeploymentStore store, DeploymentLogService logs, DeploymentEventPublisher events,
                                 WorkerProperties properties, Clock clock) {
        this.store = store;
        this.logs = logs;
        this.events = events;
        this.threshold = properties.timeouts().deployment().plus(GRACE);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void failStuckDeployments() {
        List<DeploymentStore.DeploymentSummaryWithId> reaped = store.failStuckDeployments(clock.instant().minus(threshold), REASON);
        for (DeploymentStore.DeploymentSummaryWithId deployment : reaped) {
            log.warn("Marked stuck deployment {} as FAILED", deployment.deploymentId());
            logs.append(deployment.deploymentId(), List.of(new DeploymentLogService.Entry(LogLevel.ERROR, null, REASON)));
            events.statusChanged(deployment.deploymentId(), deployment.projectId(), DeploymentStatus.FAILED, null, REASON);
            events.failed(deployment.deploymentId(), deployment.projectId(), DeploymentStatus.FAILED, REASON);
        }
    }
}
