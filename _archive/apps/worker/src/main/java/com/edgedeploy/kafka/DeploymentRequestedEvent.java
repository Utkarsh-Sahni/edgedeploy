package com.edgedeploy.kafka;

import java.time.Instant;
import java.util.UUID;

public record DeploymentRequestedEvent(
        UUID deploymentId,
        UUID projectId,
        String repository,
        String branch,
        String commitSha,
        Instant timestamp
) {
}
