package com.edgedeploy.kafka;

import com.edgedeploy.deployment.DeploymentStatus;

import java.time.Instant;
import java.util.UUID;

public record DeploymentStatusEvent(
        UUID deploymentId,
        UUID projectId,
        DeploymentStatus status,
        String deploymentUrl,
        String message,
        Instant timestamp
) {
}
