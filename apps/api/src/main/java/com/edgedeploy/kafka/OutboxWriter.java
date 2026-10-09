package com.edgedeploy.kafka;

import com.edgedeploy.entity.OutboxEvent;
import com.edgedeploy.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Records an event in the outbox as part of the caller's transaction. The event becomes visible to
 * {@link OutboxRelay} only if that transaction commits, which is what makes "save + publish" atomic.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Fails fast if called outside a transaction: an outbox write without one is a bug. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, UUID aggregateId, String topic, String key, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + event.getClass().getSimpleName(), e);
        }
        outbox.save(new OutboxEvent(aggregateType, aggregateId, event.getClass().getSimpleName(),
                topic, key, payload, clock.instant()));
    }
}
