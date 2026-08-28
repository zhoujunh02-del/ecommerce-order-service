package com.ecommerce.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire contract for order lifecycle events on the {@code order.events} topic.
 * Shared by both services on purpose: this is a communication schema, not internal
 * logic. {@code eventId} lets consumers deduplicate; {@code orderId} is the Kafka
 * partition key so all events for one order stay ordered on one partition.
 */
public record OrderEvent(String eventId, String type, UUID orderId, long userId, Instant occurredAt) {

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_PAID = "OrderPaid";
    public static final String ORDER_CANCELLED = "OrderCancelled";

    public static OrderEvent of(String type, UUID orderId, long userId) {
        return new OrderEvent(UUID.randomUUID().toString(), type, orderId, userId, Instant.now());
    }
}
