package com.ecommerce.inventory.infra.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    /** Dead-letter topic for events that could not be processed. */
    @Bean
    NewTopic orderEventsDlt() {
        return TopicBuilder.name("order.events.DLT").partitions(3).replicas(1).build();
    }

    /**
     * Retry a failed record 3 times with a 1s backoff, then publish it to
     * order.events.DLT (same partition). Deserialization failures are NOT retried —
     * a malformed message will never parse, so retrying just wastes time; it goes
     * straight to the DLT.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition("order.events.DLT", record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }
}
