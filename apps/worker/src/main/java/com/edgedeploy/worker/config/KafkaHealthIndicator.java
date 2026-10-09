package com.edgedeploy.worker.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Exposes broker reachability at {@code /actuator/health} (Spring Boot has no built-in Kafka indicator). */
@Component("kafka")
public class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final AdminClient adminClient;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        super("Kafka health check failed");
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        DescribeClusterResult cluster = adminClient.describeCluster(
                new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()));
        String clusterId = cluster.clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        int nodes = cluster.nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
        builder.up().withDetail("clusterId", clusterId).withDetail("nodes", nodes);
    }

    @Override
    public void destroy() {
        adminClient.close(Duration.ofSeconds(2));
    }
}
