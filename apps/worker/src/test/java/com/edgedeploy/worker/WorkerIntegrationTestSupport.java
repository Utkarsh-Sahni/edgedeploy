package com.edgedeploy.worker;

import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared plumbing for worker integration tests: real Postgres + Kafka, with the schema created from
 * the api's Flyway migrations (the single source of truth).
 */
abstract class WorkerIntegrationTestSupport {

    static final String MIGRATIONS = "spring.flyway.locations=filesystem:../api/src/main/resources/db/migration";
    static final String COMMIT = "3f2a9c1d4e5b6a7980f1e2d3c4b5a69788776655";

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.10-alpine"));
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
        }
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    KafkaTemplate<String, Object> kafka;
    @Autowired
    KafkaProperties kafkaProperties;

    UUID seedQueuedDeployment() {
        return seedQueuedDeployment(null, COMMIT);
    }

    /** @param repository owner/name, or null for a unique fake one */
    UUID seedQueuedDeployment(String repository, String commitSha) {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                        INSERT INTO users (id, email, name, github_id, github_login, created_at, updated_at)
                        VALUES (?, ?, 'IT', ?, 'it-user', ?, ?)
                        """)
                .params(userId, userId + "@it.test", "gh-" + userId, now, now).update();
        jdbc.sql("""
                        INSERT INTO projects (id, user_id, name, slug, repository, owner, branch, framework, created_at, updated_at)
                        VALUES (?, ?, 'IT Project', 'it-project', ?, 'octocat', 'main', 'NODE', ?, ?)
                        """)
                .params(projectId, userId, repository != null ? repository : "octocat/" + projectId, now, now).update();
        jdbc.sql("""
                        INSERT INTO deployments (id, project_id, number, commit_sha, status, created_at, updated_at)
                        VALUES (?, ?, 1, ?, 'QUEUED', ?, ?)
                        """)
                .params(deploymentId, projectId, commitSha, now, now).update();
        return deploymentId;
    }

    DeploymentRequestedEvent requested(UUID deploymentId) {
        Map<String, Object> row = jdbc.sql("""
                        SELECT d.project_id, d.commit_sha, p.repository FROM deployments d JOIN projects p ON p.id = d.project_id
                         WHERE d.id = ?
                        """).param(deploymentId).query().singleRow();
        return new DeploymentRequestedEvent(UUID.randomUUID(), deploymentId, (UUID) row.get("project_id"),
                (String) row.get("repository"), "main", (String) row.get("commit_sha"), Instant.now());
    }

    String status(UUID deploymentId) {
        return jdbc.sql("SELECT status FROM deployments WHERE id = ?").param(deploymentId).query(String.class).single();
    }

    long countLogs(UUID deploymentId, String messagePattern) {
        return jdbc.sql("SELECT count(*) FROM deployment_logs WHERE deployment_id = ? AND message LIKE ?")
                .params(deploymentId, messagePattern).query(Long.class).single();
    }

    Consumer<String, byte[]> consumer(String topic) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, byte[]> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new ByteArrayDeserializer()).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
