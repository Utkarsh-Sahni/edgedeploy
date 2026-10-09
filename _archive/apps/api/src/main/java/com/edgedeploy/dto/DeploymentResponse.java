package com.edgedeploy.dto;

import com.edgedeploy.deployment.DeploymentStatus;

import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
        UUID id,
        UUID projectId,
        String commitSha,
        DeploymentStatus status,
        String imageUri,
        String deploymentUrl,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
}
