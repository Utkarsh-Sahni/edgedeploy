package com.edgedeploy.config;

import com.edgedeploy.contracts.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics the api produces to or consumes from. KafkaAdmin creates missing topics at startup;
 * existing topics are left untouched. Broker-side auto-creation is disabled in docker-compose.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

    @Bean
    NewTopic deploymentRequestedTopic(EdgeDeployProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_REQUESTED, properties);
    }

    @Bean
    NewTopic deploymentStatusTopic(EdgeDeployProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_STATUS, properties);
    }

    @Bean
    NewTopic deploymentLogsTopic(EdgeDeployProperties properties) {
        return topic(KafkaTopics.DEPLOYMENT_LOGS, properties);
    }

    private static NewTopic topic(String name, EdgeDeployProperties properties) {
        return TopicBuilder.name(name)
                .partitions(properties.kafka().partitions())
                .replicas(properties.kafka().replicas())
                .build();
    }
}
