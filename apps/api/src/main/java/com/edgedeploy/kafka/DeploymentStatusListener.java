package com.edgedeploy.kafka;

import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentStatusEvent;
import com.edgedeploy.deployment.DeploymentEventBroadcaster;
import com.edgedeploy.deployment.DeploymentLogStreamBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Forwards worker status notifications to browsers subscribed on this api instance.
 *
 * <p>Uses a per-instance consumer group ({@code edgedeploy.kafka.status-consumer-group}) so every
 * instance sees every event, and starts from {@code latest}: missed events don't matter because SSE
 * clients receive a fresh database snapshot when they (re)connect.
 */
@Component
public class DeploymentStatusListener {

    private static final Logger log = LoggerFactory.getLogger(DeploymentStatusListener.class);

    private final DeploymentEventBroadcaster broadcaster;
    private final DeploymentLogStreamBroadcaster logStreams;

    public DeploymentStatusListener(DeploymentEventBroadcaster broadcaster, DeploymentLogStreamBroadcaster logStreams) {
        this.broadcaster = broadcaster;
        this.logStreams = logStreams;
    }

    @KafkaListener(
            id = "deployment-status-sse",
            topics = KafkaTopics.DEPLOYMENT_STATUS,
            groupId = "${edgedeploy.kafka.status-consumer-group}",
            properties = "auto.offset.reset=latest")
    public void onStatus(DeploymentStatusEvent event) {
        log.debug("Status event for deployment {}: {}", event.deploymentId(), event.status());
        broadcaster.publish(event);
        if (event.status().isSettled()) {
            // The worker writes the final log line in the same transaction as this status change,
            // so a database catch-up now is guaranteed to include it.
            logStreams.settle(event.deploymentId());
        }
    }
}
