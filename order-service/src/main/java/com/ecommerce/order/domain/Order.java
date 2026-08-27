package com.ecommerce.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The order aggregate root. Field order matches the SELECT column order used by
 * the mappers so MyBatis maps rows onto this record positionally.
 */
public record Order(
        UUID id,
        String orderNo,
        long userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        OffsetDateTime expireAt,
        String failReason,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
