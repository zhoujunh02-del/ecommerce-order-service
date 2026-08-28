package com.ecommerce.order.app;

import com.ecommerce.order.api.dto.OrderResponse;

/**
 * The cached result of an idempotent request, stored as JSON in the idempotency
 * table. We cache BOTH success and business-failure outcomes, so replaying the
 * same key always yields the identical result — a repeat of an out-of-stock order
 * returns the same 409, never a new attempt.
 */
public record IdemOutcome(boolean success, OrderResponse order, String errorCode, String errorMessage) {

    static IdemOutcome ok(OrderResponse order) {
        return new IdemOutcome(true, order, null, null);
    }

    static IdemOutcome error(String errorCode, String errorMessage) {
        return new IdemOutcome(false, null, errorCode, errorMessage);
    }
}
