package com.edgedeploy.dto;

import com.edgedeploy.contracts.DeploymentStatus;

import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
        UUID id,
        int number,
        UUID projectId,
        String projectName,
        String commitSha,
        DeploymentStatus status,
        String imageUri,
        String deploymentUrl,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
}
