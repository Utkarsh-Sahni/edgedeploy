package com.edgedeploy.kafka;

import java.time.Instant;
import java.util.UUID;

public record DeploymentFailedEvent(
        UUID deploymentId,
        UUID projectId,
        String reason,
        Instant timestamp
) {
}
