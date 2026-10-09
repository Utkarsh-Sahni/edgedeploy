package com.edgedeploy.worker.logging;

import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.LogLevel;
import com.edgedeploy.contracts.event.DeploymentLogEvent;
import com.edgedeploy.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single entry point for user-visible deployment logs.
 *
 * <p>Worker -> {@code deployment_logs} (source of truth) -> {@code deployment.logs} Kafka event (after
 * commit) -> api -> SSE -> dashboard. Every message is redacted and length-bounded before it is stored.
 */
@Service
public class DeploymentLogService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentLogService.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final Pattern ANSI_ESCAPES = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");

    public record Entry(LogLevel level, DeploymentStep step, String message) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, Object> kafka;
    private final SecretRedactor redactor;
    private final WorkerProperties.Logs config;
    private final Clock clock;

    public DeploymentLogService(JdbcClient jdbc, KafkaTemplate<String, Object> kafka, SecretRedactor redactor,
                                WorkerProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.redactor = redactor;
        this.config = properties.logs();
        this.clock = clock;
    }

    public void info(UUID deploymentId, String message) {
        appendQuietly(deploymentId, List.of(new Entry(LogLevel.INFO, null, message)));
    }

    public void warn(UUID deploymentId, String message) {
        appendQuietly(deploymentId, List.of(new Entry(LogLevel.WARN, null, message)));
    }

    /** Completes a timeline milestone. */
    public void step(UUID deploymentId, DeploymentStep step, String message) {
        appendQuietly(deploymentId, List.of(new Entry(LogLevel.INFO, step, message)));
    }

    /** A sink for high-volume tool output (docker build): batched, capped, tail-preserving. */
    public BuildOutputSink outputSink(UUID deploymentId) {
        return new BuildOutputSink(deploymentId, this, config.maxOutputLines(), config.flushLines(), config.flushInterval());
    }

    /**
     * Persists entries in the caller's transaction (if any) and publishes them once committed. Throws on
     * failure, so callers that need the log line and a state change to be atomic can rely on it.
     */
    public void append(UUID deploymentId, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<DeploymentLogEvent.Line> lines = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            String message = sanitize(entry.message());
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            long seq = jdbc.sql("""
                            INSERT INTO deployment_logs (id, deployment_id, level, step, message, timestamp)
                            VALUES (:id, :deploymentId, :level, :step, :message, :ts)
                            RETURNING seq
                            """)
                    .param("id", UUID.randomUUID())
                    .param("deploymentId", deploymentId)
                    .param("level", entry.level().name())
                    .param("step", entry.step() != null ? entry.step().name() : null)
                    .param("message", message)
                    .param("ts", now)
                    .query(Long.class)
                    .single();
            lines.add(new DeploymentLogEvent.Line(seq, entry.level(), entry.step(), message, now.toInstant()));
        }
        publishAfterCommit(new DeploymentLogEvent(deploymentId, List.copyOf(lines)));
    }

    /** Best-effort variant for informational lines: losing one must never fail a deployment. */
    void appendQuietly(UUID deploymentId, List<Entry> entries) {
        try {
            append(deploymentId, entries);
        } catch (RuntimeException e) {
            log.warn("Could not persist {} log line(s) for deployment {}: {}", entries.size(), deploymentId, e.toString());
        }
    }

    String sanitize(String raw) {
        String text = raw == null ? "" : ANSI_ESCAPES.matcher(raw).replaceAll("");
        text = redactor.redact(text).replace('\0', ' ');
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    private void publishAfterCommit(DeploymentLogEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    /** Fire-and-forget: the rows are already committed; live viewers re-read from the database on reconnect. */
    private void publish(DeploymentLogEvent event) {
        try {
            kafka.send(KafkaTopics.DEPLOYMENT_LOGS, event.deploymentId().toString(), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.debug("Could not publish log event for {}: {}", event.deploymentId(), error.toString());
                        }
                    });
        } catch (RuntimeException e) {
            log.debug("Could not publish log event for {}: {}", event.deploymentId(), e.toString());
        }
    }
}
