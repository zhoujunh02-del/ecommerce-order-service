# Phase 3 — Saga convergence under an uncertain reserve

Validates that an order whose reserve outcome was never learned (downstream
unavailable/timeout) is resolved to a correct terminal state by the reconciler,
rather than hanging forever.

## The hard case

A synchronous reserve can time out or fail after retries. At that moment the order
service does NOT know whether stock was reserved: the request may not have arrived,
or it succeeded and the response was lost. Guessing is unsafe — guess "failed" when
it succeeded and stock is leaked; guess "succeeded" when it failed and you oversell.

The resolution is a status-query endpoint plus a reconciler that asks inventory what
actually happened and drives the order accordingly.

## Procedure

1. Restart order-service with a fast reconcile (stuck-after 5s, interval 3s).
2. `GET /internal/inventory/reservations/{orderId}` for a normal order returns RESERVED.
3. Stop inventory-service.
4. Place an order: reserve retries 3× (Resilience4j) then fails.
5. Observe the HTTP response and the order state.
6. Start inventory-service; wait for the reconciler.
7. Observe the final order state.

## Observations

| Step | Result |
|------|--------|
| Status query (normal order) | `{"status":"RESERVED"}` |
| Place order (inventory down) | HTTP 503 INVENTORY_UNAVAILABLE |
| Order state right after | CREATING (not guessed to a terminal state) |
| After inventory recovery + reconcile | FAILED (`RESERVE_NOT_FOUND`) |

Reconciler log: `order ... resolved to FAILED (RESERVE_NOT_FOUND)`.

## Conclusions

- The order service never guesses a reserve outcome it did not observe. It leaves the
  order CREATING and returns 503, and the reconciler queries inventory's ledger-derived
  status to make the final decision (RESERVED/COMMITTED → confirm; NOT_FOUND/RELEASED →
  fail), also completing the idempotency record so client retries converge.
- Reserve is idempotent (keyed by order id), so retries and reconciliation are safe.
- The TCC edge cases are covered in code and tests: idempotence (UNIQUE ledger op),
  empty rollback (release without reserve records a marker, adds no stock), and
  hanging (a late reserve after release is rejected).
