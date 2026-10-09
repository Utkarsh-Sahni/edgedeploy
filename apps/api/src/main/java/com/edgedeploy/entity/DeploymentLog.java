package com.edgedeploy.entity;

import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.LogLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Summarised deployment log line, written by the worker. Read-only from the api's perspective;
 * full build output will live in S3.
 */
@Entity
@Immutable
@Table(name = "deployment_logs")
public class DeploymentLog extends BaseEntity {

    @Column(name = "deployment_id", nullable = false, updatable = false)
    private UUID deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private LogLevel level;

    @Column(nullable = false)
    private String message;

    @Column(nullable = false)
    private Instant timestamp;

    /** Database-assigned insert order; strictly increasing per deployment. */
    @Column(insertable = false, updatable = false)
    private Long seq;

    /** Timeline milestone this line completes (INFO) or fails (ERROR); null for ordinary output. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, updatable = false)
    private DeploymentStep step;

    protected DeploymentLog() {
    }

    /** Lines written by the api itself (e.g. "queued"); the worker writes the rest directly. */
    public static DeploymentLog milestone(UUID deploymentId, DeploymentStep step, String message, Instant timestamp) {
        DeploymentLog log = new DeploymentLog();
        log.deploymentId = deploymentId;
        log.level = LogLevel.INFO;
        log.step = step;
        log.message = message;
        log.timestamp = timestamp;
        return log;
    }

    public Long getSeq() {
        return seq;
    }

    public DeploymentStep getStep() {
        return step;
    }

    public UUID getDeploymentId() {
        return deploymentId;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
