package com.edgedeploy.kafka;

import com.edgedeploy.config.EdgeDeployProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeploymentEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final EdgeDeployProperties properties;

    public DeploymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate, EdgeDeployProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    public void publishRequested(DeploymentRequestedEvent event) {
        kafkaTemplate.send(
                properties.getKafka().getTopics().getRequested(),
                event.deploymentId().toString(),
                event
        );
    }
}
