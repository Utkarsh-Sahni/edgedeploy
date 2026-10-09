package com.edgedeploy.kafka;

import com.edgedeploy.deployment.DeploymentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeploymentRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRequestedListener.class);

    private final DeploymentOrchestrator orchestrator;

    public DeploymentRequestedListener(DeploymentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
            topics = "${edgedeploy.kafka.topics.requested}",
            groupId = "edgedeploy-worker",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(DeploymentRequestedEvent event) {
        log.info("Received deployment request {}", event.deploymentId());
        orchestrator.handle(event);
    }
}
