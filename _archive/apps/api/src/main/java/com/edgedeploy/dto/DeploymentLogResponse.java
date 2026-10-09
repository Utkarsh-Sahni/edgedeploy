package com.edgedeploy.dto;

import java.time.Instant;
import java.util.UUID;

public record DeploymentLogResponse(
        UUID id,
        String level,
        String message,
        Instant timestamp
) {
}
