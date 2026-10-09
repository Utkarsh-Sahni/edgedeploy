package com.edgedeploy.kafka;

import com.edgedeploy.config.WorkerProperties;
import com.edgedeploy.deployment.DeploymentStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class StatusEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final WorkerProperties properties;

    public StatusEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, WorkerProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    public void publish(UUID deploymentId, UUID projectId, DeploymentStatus status, String url, String message) {
        DeploymentStatusEvent event = new DeploymentStatusEvent(
                deploymentId,
                projectId,
                status,
                url,
                message,
                Instant.now()
        );
        kafkaTemplate.send(properties.getKafka().getTopics().getStatus(), deploymentId.toString(), event);
        if (status == DeploymentStatus.FAILED) {
            kafkaTemplate.send(
                    properties.getKafka().getTopics().getFailed(),
                    deploymentId.toString(),
                    new DeploymentFailedEvent(deploymentId, projectId, message, Instant.now())
            );
        }
    }
}
