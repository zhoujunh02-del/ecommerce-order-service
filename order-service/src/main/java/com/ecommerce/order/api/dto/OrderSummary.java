package com.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Compact order view used in list responses. */
public record OrderSummary(
        UUID orderId,
        String orderNo,
        String status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt) {
}
