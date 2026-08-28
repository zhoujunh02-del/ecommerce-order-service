package com.ecommerce.order.infra.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    /**
     * 3 partitions so different orders can be consumed in parallel, while events for
     * any single order (keyed by order id) always land on the same partition and
     * stay ordered.
     */
    @Bean
    NewTopic orderEventsTopic() {
        return TopicBuilder.name("order.events").partitions(3).replicas(1).build();
    }
}
