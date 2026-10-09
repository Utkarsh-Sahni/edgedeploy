package com.edgedeploy.contracts.event;

import com.edgedeploy.contracts.DeploymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the worker after every committed state transition.
 *
 * @param deploymentUrl set once the deployment reaches RUNNING
 * @param message       human-readable detail (stage description or failure reason)
 */
public record DeploymentStatusEvent(
        UUID eventId,
        UUID deploymentId,
        UUID projectId,
        DeploymentStatus status,
        String deploymentUrl,
        String message,
        Instant timestamp) {
}
