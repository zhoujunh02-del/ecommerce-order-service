package com.ecommerce.common.error;

/**
 * Business error codes shared across services, each carrying the HTTP status it
 * maps to. Kept free of any framework type so the {@code common} module stays
 * dependency-light.
 */
public enum ErrorCode {

    INVALID_REQUEST(400),
    SKU_NOT_FOUND(404),
    ORDER_NOT_FOUND(404),
    INSUFFICIENT_STOCK(409),
    ORDER_STATE_CONFLICT(409),
    IDEMPOTENCY_KEY_REUSED(409),
    REQUEST_IN_PROGRESS(409),
    INVENTORY_UNAVAILABLE(503),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
