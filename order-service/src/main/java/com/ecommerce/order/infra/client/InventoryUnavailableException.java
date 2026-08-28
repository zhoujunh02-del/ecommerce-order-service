package com.ecommerce.order.infra.client;

/**
 * A TECHNICAL, retryable failure talking to inventory-service (timeout, connection
 * refused, 5xx). Distinct from a business rejection like INSUFFICIENT_STOCK so that
 * Resilience4j can retry this and only this, and the circuit breaker counts it as a
 * failure while ignoring business errors.
 */
public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(String message) {
        super(message);
    }
}
