package com.edgedeploy.contracts.event;

import com.edgedeploy.contracts.DeploymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the worker when a deployment ends in FAILED (a business outcome, e.g. a build error),
 * for consumers such as notifications or analytics.
 *
 * @param failedDuring the status the deployment was in when it failed (which stage broke)
 * @param reason       sanitised, user-facing failure reason; never contains secrets
 */
public record DeploymentFailedEvent(
        UUID eventId,
        UUID deploymentId,
        UUID projectId,
        DeploymentStatus failedDuring,
        String reason,
        Instant timestamp) {
}
