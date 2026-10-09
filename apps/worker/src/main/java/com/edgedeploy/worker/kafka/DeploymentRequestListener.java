package com.edgedeploy.worker.kafka;

import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.worker.deployment.DeploymentRequestHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Entry point for deployment jobs. Consumer group {@code edgedeploy-worker}: each request is handled by
 * exactly one worker instance (modulo redelivery, which every {@link DeploymentRequestHandler} makes harmless).
 */
@Component
public class DeploymentRequestListener {

    public static final String MDC_KEY = "deploymentId";
    private static final Logger log = LoggerFactory.getLogger(DeploymentRequestListener.class);

    private final DeploymentRequestHandler handler;

    public DeploymentRequestListener(DeploymentRequestHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(id = "deployment-requested", topics = KafkaTopics.DEPLOYMENT_REQUESTED)
    public void onDeploymentRequested(ConsumerRecord<String, DeploymentRequestedEvent> record) {
        DeploymentRequestedEvent event = validate(record);
        MDC.put(MDC_KEY, event.deploymentId().toString());
        try {
            log.info("Received deployment request {} (partition {}, offset {})",
                    event.eventId(), record.partition(), record.offset());
            handler.handle(event);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static DeploymentRequestedEvent validate(ConsumerRecord<String, DeploymentRequestedEvent> record) {
        DeploymentRequestedEvent event = record.value();
        if (event == null) {
            throw new InvalidDeploymentEventException("Empty deployment.requested record at offset " + record.offset());
        }
        if (event.deploymentId() == null || event.projectId() == null) {
            throw new InvalidDeploymentEventException("deployment.requested event " + event.eventId()
                    + " is missing deploymentId or projectId");
        }
        if (record.key() != null && !record.key().equals(event.deploymentId().toString())) {
            log.warn("Record key {} does not match deploymentId {}; processing by payload",
                    record.key(), event.deploymentId());
        }
        return event;
    }
}
