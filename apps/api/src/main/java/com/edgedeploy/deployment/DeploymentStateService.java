package com.edgedeploy.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.repository.DeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The api's only way to change a deployment's status.
 *
 * <ol>
 *   <li>The transition is checked against the shared state machine before touching Postgres.</li>
 *   <li>The UPDATE is a compare-and-set on the expected current status, so it can't overwrite a
 *       change the worker made concurrently; that case surfaces as a 409 instead.</li>
 * </ol>
 * The worker applies the same rules in its own {@code DeploymentStore}.
 */
@Service
public class DeploymentStateService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentStateService.class);

    private final DeploymentRepository deployments;
    private final Clock clock;

    public DeploymentStateService(DeploymentRepository deployments, Clock clock) {
        this.deployments = deployments;
        this.clock = clock;
    }

    @Transactional
    public void transition(UUID deploymentId, DeploymentStatus from, DeploymentStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalDeploymentTransitionException(from, to);
        }
        Instant now = clock.instant();
        int updated = deployments.compareAndSetStatus(deploymentId, from, to, now, to.isSettled() ? now : null);
        if (updated == 0) {
            throw new ConflictException("Deployment " + deploymentId + " is no longer " + from + "; reload and retry");
        }
        log.info("Deployment {} transitioned {} -> {}", deploymentId, from, to);
    }
}
