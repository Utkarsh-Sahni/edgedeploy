package com.edgedeploy.contracts.event;

import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.LogLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Newly persisted deployment log lines, published by the worker after they are committed to Postgres.
 * Lines are batched to keep Kafka traffic proportional to build output, not to line count.
 */
public record DeploymentLogEvent(UUID deploymentId, List<Line> lines) {

    /**
     * @param seq  database sequence: strictly increasing per deployment, used for ordering and SSE resume
     * @param step timeline milestone this line completes or fails, or null for ordinary output
     */
    public record Line(long seq, LogLevel level, DeploymentStep step, String message, Instant timestamp) {
    }
}
