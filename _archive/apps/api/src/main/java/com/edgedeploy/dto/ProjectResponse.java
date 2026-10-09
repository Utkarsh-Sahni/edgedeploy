package com.edgedeploy.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String repository,
        String branch,
        String framework,
        String latestStatus,
        String latestUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
