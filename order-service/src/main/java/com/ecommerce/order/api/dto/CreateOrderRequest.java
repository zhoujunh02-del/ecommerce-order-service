package com.ecommerce.order.api.dto;

import java.util.List;

public record CreateOrderRequest(List<OrderItemRequest> items) {

    public record OrderItemRequest(long skuId, int quantity) {
    }
}
