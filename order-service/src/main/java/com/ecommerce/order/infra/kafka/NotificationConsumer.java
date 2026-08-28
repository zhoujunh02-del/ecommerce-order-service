package com.ecommerce.order.infra.kafka;

import com.ecommerce.common.event.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A SECOND, independent consumer group on the same order.events topic. Its only job
 * here is to demonstrate fan-out: the inventory-service (inventory-group) consumes
 * these same events to move stock, while this notification-group consumes them
 * independently. One event, many consumers — the whole point of a log over RPC.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final ObjectMapper objectMapper;

    public NotificationConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${outbox.topic}", groupId = "notification-group")
    public void onOrderEvent(String payload) throws Exception {
        OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);
        log.info("[notification] order {} -> {}", event.orderId(), event.type());
    }
}
