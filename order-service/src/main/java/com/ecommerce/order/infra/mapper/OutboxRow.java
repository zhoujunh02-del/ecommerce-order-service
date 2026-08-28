package com.ecommerce.order.infra.mapper;

import java.util.UUID;

/** A pending outbox event to be published. */
public record OutboxRow(long id, UUID aggregateId, String eventType, String payload, int retryCount) {
}
