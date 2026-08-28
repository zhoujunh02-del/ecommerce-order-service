package com.ecommerce.inventory.infra.kafka;

import com.ecommerce.common.event.OrderEvent;
import com.ecommerce.inventory.app.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes order events and moves stock accordingly:
 *   OrderPaid      -> commit  (reserved -> sold)
 *   OrderCancelled -> release (reserved -> available)
 * Both StockService operations are idempotent, which is required because delivery
 * is at-least-once. Any exception thrown here is handled by the container's error
 * handler: retried a few times, then routed to the dead-letter topic.
 */
@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final StockService stockService;
    private final ObjectMapper objectMapper;

    public InventoryEventConsumer(StockService stockService, ObjectMapper objectMapper) {
        this.stockService = stockService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order.events", groupId = "inventory-group")
    public void onOrderEvent(String payload) throws Exception {
        OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);
        switch (event.type()) {
            case OrderEvent.ORDER_PAID -> stockService.commit(event.orderId());
            case OrderEvent.ORDER_CANCELLED -> stockService.release(event.orderId());
            default -> log.debug("ignoring event type {} for order {}", event.type(), event.orderId());
        }
    }
}
