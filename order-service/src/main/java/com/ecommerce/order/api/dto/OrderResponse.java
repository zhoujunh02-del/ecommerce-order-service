package com.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String orderNo,
        String status,
        BigDecimal totalAmount,
        String currency,
        OffsetDateTime expireAt,
        List<OrderItemResponse> items) {

    public record OrderItemResponse(long skuId, String skuName, BigDecimal unitPrice, int quantity) {
    }
}
