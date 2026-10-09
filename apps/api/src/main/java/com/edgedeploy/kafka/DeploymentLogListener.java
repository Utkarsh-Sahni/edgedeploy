package com.edgedeploy.kafka;

import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentLogEvent;
import com.edgedeploy.deployment.DeploymentLogStreamBroadcaster;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Forwards worker log lines to browsers streaming them from this api instance. Like status events, every
 * instance needs every line, so it uses its own consumer group, starting at the latest offset (anything
 * earlier is served from Postgres when a client connects).
 */
@Component
public class DeploymentLogListener {

    private final DeploymentLogStreamBroadcaster broadcaster;

    public DeploymentLogListener(DeploymentLogStreamBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @KafkaListener(
            id = "deployment-logs-sse",
            topics = KafkaTopics.DEPLOYMENT_LOGS,
            groupId = "${edgedeploy.kafka.status-consumer-group}-logs",
            properties = {
                    "auto.offset.reset=latest",
                    "spring.json.value.default.type=com.edgedeploy.contracts.event.DeploymentLogEvent"
            })
    public void onLogs(DeploymentLogEvent event) {
        broadcaster.publish(event);
    }
}
