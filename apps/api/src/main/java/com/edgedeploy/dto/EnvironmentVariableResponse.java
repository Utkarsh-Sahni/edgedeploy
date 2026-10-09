package com.edgedeploy.dto;

import java.time.Instant;

/** An environment variable's metadata. The value is write-only and is never returned by the api. */
public record EnvironmentVariableResponse(String key, Instant createdAt, Instant updatedAt) {
}
