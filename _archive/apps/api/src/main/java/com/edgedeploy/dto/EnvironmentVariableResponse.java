package com.edgedeploy.dto;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentVariableResponse(
        UUID id,
        String key,
        Instant createdAt,
        Instant updatedAt
) {
}
