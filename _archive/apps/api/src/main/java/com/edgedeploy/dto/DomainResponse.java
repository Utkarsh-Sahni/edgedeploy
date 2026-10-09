package com.edgedeploy.dto;

import java.time.Instant;
import java.util.UUID;

public record DomainResponse(
        UUID id,
        String hostname,
        String status,
        Instant createdAt
) {
}
