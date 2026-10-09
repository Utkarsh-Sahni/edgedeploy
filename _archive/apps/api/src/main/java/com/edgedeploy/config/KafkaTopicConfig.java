package com.edgedeploy.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic deploymentRequestedTopic(EdgeDeployProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopics().getRequested())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic deploymentRequestedDlt(EdgeDeployProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopics().getRequested() + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic deploymentStatusTopic(EdgeDeployProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopics().getStatus())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic deploymentFailedTopic(EdgeDeployProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopics().getFailed())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
