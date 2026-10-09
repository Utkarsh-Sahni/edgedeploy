package com.edgedeploy.kafka;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.entity.OutboxEvent;
import com.edgedeploy.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls the outbox and publishes pending events to Kafka (at-least-once).
 *
 * <p>Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several api instances can relay in
 * parallel. A crash between "Kafka acked" and "row marked published" re-sends the event on the next
 * poll; consumers are idempotent, so duplicates are harmless.
 */
@Component
public class OutboxRelay {

    public static final String EVENT_TYPE_HEADER = "edgedeploy-event-type";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final EdgeDeployProperties.Outbox config;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, EdgeDeployProperties properties, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.config = properties.outbox();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${edgedeploy.outbox.poll-interval}")
    public void relayPending() {
        // Keep draining while full batches publish cleanly; stop on the first failure to avoid a hot loop.
        boolean drainMore;
        do {
            Boolean fullCleanBatch = transactions.execute(status -> relayBatch());
            drainMore = Boolean.TRUE.equals(fullCleanBatch);
        } while (drainMore);
    }

    /** @return true if a full batch was published without errors (more rows are probably waiting) */
    private boolean relayBatch() {
        List<OutboxEvent> batch = outbox.lockNextBatch(config.batchSize());
        if (batch.isEmpty()) {
            return false;
        }

        // Send everything first, then await: the producer pipelines the batch instead of one round-trip per row.
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            sends.add(kafka.send(toRecord(event)));
        }

        int failures = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                sends.get(i).get(config.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished(clock.instant());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("interrupted");
                failures++;
            } catch (ExecutionException | TimeoutException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                event.recordFailure(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                failures++;
                log.warn("Outbox publish failed for event {} (attempt {}) to {}: {}",
                        event.getId(), event.getAttempts(), event.getTopic(), cause.toString());
            }
        }
        if (failures == 0) {
            log.debug("Relayed {} outbox event(s)", batch.size());
        }
        return failures == 0 && batch.size() == config.batchSize();
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        record.headers().add(EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    public void purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(RETENTION)));
        log.info("Purged {} published outbox event(s) older than {}", deleted, RETENTION);
    }
}
