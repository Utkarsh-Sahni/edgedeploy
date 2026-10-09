package com.edgedeploy.worker;

import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Phase 2 default mode: the worker consumes, verifies and records the request without changing state. */
@SpringBootTest(properties = {WorkerIntegrationTestSupport.MIGRATIONS, "edgedeploy.mode=acknowledge"})
@Import(WorkerIntegrationTestSupport.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class WorkerAcknowledgeIntegrationTest extends WorkerIntegrationTestSupport {

    @Test
    void consumesTheEventOnceAndLeavesTheDeploymentQueued() {
        UUID deploymentId = seedQueuedDeployment();
        DeploymentRequestedEvent event = requested(deploymentId);

        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, deploymentId.toString(), event);
        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, deploymentId.toString(), event); // redelivery

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(countLogs(deploymentId, "Deployment request received by worker%")).isEqualTo(1));
        // Let the duplicate be consumed too, then check nothing changed.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(countLogs(deploymentId, "Deployment request received by worker%")).isEqualTo(1);
            assertThat(status(deploymentId)).isEqualTo("QUEUED");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = ?")
                .param(event.eventId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void eventForADeletedDeploymentIsIgnored() {
        UUID missing = UUID.randomUUID();
        DeploymentRequestedEvent event = new DeploymentRequestedEvent(UUID.randomUUID(), missing, UUID.randomUUID(),
                "octocat/x", "main", COMMIT, java.time.Instant.now());

        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, missing.toString(), event);

        // Followed by a valid event on the same partition key space: proves the consumer kept going.
        UUID deploymentId = seedQueuedDeployment();
        kafka.send(KafkaTopics.DEPLOYMENT_REQUESTED, deploymentId.toString(), requested(deploymentId));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(countLogs(deploymentId, "Deployment request received by worker%")).isEqualTo(1));
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = ?")
                .param(event.eventId()).query(Long.class).single()).isZero();
    }
}
