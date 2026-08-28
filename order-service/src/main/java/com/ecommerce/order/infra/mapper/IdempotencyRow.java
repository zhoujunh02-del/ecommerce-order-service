package com.ecommerce.order.infra.mapper;

/**
 * A row of the idempotency table.
 *
 * @param status   IN_PROGRESS (claimed, still running) or COMPLETED (final result cached)
 * @param response the cached outcome as JSON text (null while IN_PROGRESS)
 */
public record IdempotencyRow(String idemKey, String requestHash, String status, String response) {
}
