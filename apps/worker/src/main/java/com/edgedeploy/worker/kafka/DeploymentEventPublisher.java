package com.edgedeploy.worker.kafka;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentFailedEvent;
import com.edgedeploy.contracts.event.DeploymentStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Publishes worker-side events, keyed by deployment id so they stay ordered per deployment.
 *
 * <p>Fire-and-forget by design: these are notifications about state already committed to Postgres,
 * so a publish failure is logged and never fails the deployment.
 */
@Component
public class DeploymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEventPublisher.class);

    private final KafkaTemplate<String, Object> kafka;
    private final Clock clock;

    public DeploymentEventPublisher(KafkaTemplate<String, Object> kafka, Clock clock) {
        this.kafka = kafka;
        this.clock = clock;
    }

    public void statusChanged(UUID deploymentId, UUID projectId, DeploymentStatus status, String deploymentUrl,
                              String message) {
        send(KafkaTopics.DEPLOYMENT_STATUS, deploymentId, new DeploymentStatusEvent(
                UUID.randomUUID(), deploymentId, projectId, status, deploymentUrl, message, clock.instant()));
    }

    public void failed(UUID deploymentId, UUID projectId, DeploymentStatus failedDuring, String reason) {
        send(KafkaTopics.DEPLOYMENT_FAILED, deploymentId, new DeploymentFailedEvent(
                UUID.randomUUID(), deploymentId, projectId, failedDuring, reason, clock.instant()));
    }

    private void send(String topic, UUID deploymentId, Object event) {
        try {
            kafka.send(topic, deploymentId.toString(), event).whenComplete((result, error) -> {
                if (error != null) {
                    log.warn("Failed to publish {} for deployment {}: {}", topic, deploymentId, error.toString());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Failed to publish {} for deployment {}: {}", topic, deploymentId, e.toString());
        }
    }
}
