package com.edgedeploy.dto;

import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.LogLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * @param seq  strictly increasing per deployment; pass it as {@code after} (or Last-Event-ID) to resume
 * @param step timeline milestone this line completes or fails, or null for ordinary output
 */
public record DeploymentLogResponse(UUID id, long seq, LogLevel level, DeploymentStep step, String message, Instant timestamp) {
}
