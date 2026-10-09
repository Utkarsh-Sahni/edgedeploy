package com.edgedeploy.worker.config;

import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.worker.kafka.InvalidDeploymentEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    @Bean
    NewTopic deploymentRequestedTopic(WorkerProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_REQUESTED, properties);
    }

    /** Same partition count as the source topic: the recoverer writes to the record's original partition. */
    @Bean
    NewTopic deploymentRequestedDltTopic(WorkerProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_REQUESTED_DLT, properties);
    }

    @Bean
    NewTopic deploymentStatusTopic(WorkerProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_STATUS, properties);
    }

    @Bean
    NewTopic deploymentLogsTopic(WorkerProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_LOGS, properties);
    }

    @Bean
    NewTopic deploymentFailedTopic(WorkerProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_FAILED, properties);
    }

    /**
     * Event objects are written as JSON; raw {@code byte[]} values (records that could not even be
     * deserialised) are forwarded to the DLT untouched so nothing is lost or double-encoded.
     */
    @Bean
    ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();
        serializers.put(byte[].class, new ByteArraySerializer());
        // Boot's ObjectMapper writes Instants as ISO-8601, matching what the api's outbox produces.
        serializers.put(Object.class, new JsonSerializer<>(objectMapper).noTypeInfo());
        return new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(null),
                new StringSerializer(),
                new DelegatingByTypeSerializer(serializers, true));
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Picked up by Spring Boot's listener container factory.
     *
     * <p>Transient failures (database or broker hiccups) are retried with exponential backoff; when
     * retries run out the record goes to {@code deployment.requested.DLT} with exception headers.
     * Malformed events skip straight to the DLT: retrying them can never succeed.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate, WorkerProperties properties) {
        WorkerProperties.Retry retry = properties.kafka().retry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), backOff);
        handler.addNotRetryableExceptions(InvalidDeploymentEventException.class);
        return handler;
    }

    private static NewTopic topic(String name, WorkerProperties properties) {
        return TopicBuilder.name(name)
                .partitions(properties.kafka().partitions())
                .replicas(properties.kafka().replicas())
                .build();
    }
}
