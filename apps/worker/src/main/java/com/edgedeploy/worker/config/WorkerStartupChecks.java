package com.edgedeploy.worker.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refuses to start with a configuration that would break processing in production:
 * if a deployment may legitimately run longer than Kafka's {@code max.poll.interval.ms}, the broker
 * evicts the consumer mid-build, re-assigns the partition and the next worker sees a deployment that
 * is already BUILDING.
 */
@Component
public class WorkerStartupChecks {

    static final Duration MARGIN = Duration.ofMinutes(1);

    public WorkerStartupChecks(KafkaProperties kafka, WorkerProperties properties) {
        Object configured = kafka.getConsumer().getProperties().get("max.poll.interval.ms");
        Duration maxPollInterval = configured == null ? Duration.ofMinutes(5) : Duration.ofMillis(Long.parseLong(configured.toString()));
        Duration required = properties.timeouts().deployment().plus(MARGIN);
        if (maxPollInterval.compareTo(required) < 0) {
            throw new IllegalStateException("max.poll.interval.ms (" + maxPollInterval.toMillis() + " ms) must exceed "
                    + "DEPLOYMENT_TIMEOUT_SECONDS plus " + MARGIN.toSeconds() + "s (" + required.toMillis() + " ms); "
                    + "raise WORKER_MAX_POLL_INTERVAL_MS or lower DEPLOYMENT_TIMEOUT_SECONDS");
        }
        WorkerProperties.Timeouts t = properties.timeouts();
        for (Duration step : java.util.List.of(t.dockerBuild(), t.gitOperation(), t.imagePush(), t.rollout(), t.healthCheck())) {
            if (step.compareTo(t.deployment()) > 0) {
                throw new IllegalStateException("No step timeout (build, git, push, rollout, health check) may exceed "
                        + "DEPLOYMENT_TIMEOUT_SECONDS");
            }
        }
    }
}
