package com.edgedeploy.worker.deployment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Idempotent-consumer ledger ({@code processed_events}). The primary key (consumer, event_id) makes
 * "have I handled this event?" and "mark it handled" a single atomic statement.
 */
@Repository
public class ProcessedEventLedger {

    private final JdbcClient jdbc;
    private final Clock clock;

    public ProcessedEventLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true if this is the first time {@code consumer} sees {@code eventId} */
    public boolean recordIfFirst(String consumer, UUID eventId, UUID deploymentId) {
        return jdbc.sql("""
                        INSERT INTO processed_events (consumer, event_id, deployment_id, processed_at)
                        VALUES (:consumer, :eventId, :deploymentId, :now)
                        ON CONFLICT (consumer, event_id) DO NOTHING
                        """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("deploymentId", deploymentId)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update() == 1;
    }
}
