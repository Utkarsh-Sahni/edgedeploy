package com.edgedeploy.dto;

import com.edgedeploy.entity.Framework;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String slug,
        String repository,
        String owner,
        Long githubRepositoryId,
        String branch,
        Framework framework,
        String defaultBuildCommand,
        String defaultStartCommand,
        Instant createdAt,
        Instant updatedAt) {
}
