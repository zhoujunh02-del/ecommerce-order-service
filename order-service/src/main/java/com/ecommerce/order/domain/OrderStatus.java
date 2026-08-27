package com.ecommerce.order.domain;

/**
 * Order lifecycle. Transitions are always done with a conditional UPDATE
 * (WHERE status = expected), so the number of affected rows decides who "wins"
 * a race — no distributed lock required.
 *
 * <pre>
 *   CREATING ──reserve ok──▶ PENDING_PAYMENT ──pay──▶ PAID       (terminal)
 *      │                          │
 *      │ reserve failed           │ timeout / cancel
 *      ▼                          ▼
 *    FAILED (terminal)         CANCELLED (terminal)
 * </pre>
 */
public enum OrderStatus {
    CREATING,
    PENDING_PAYMENT,
    PAID,
    CANCELLED,
    FAILED
}
