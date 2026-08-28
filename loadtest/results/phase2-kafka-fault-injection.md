# Phase 2 Fault Injection — Kafka outage, zero event loss

Validates that the transactional outbox tolerates a Kafka outage: order operations
keep succeeding while Kafka is down, events buffer durably in the outbox, and once
Kafka recovers every event is delivered exactly as expected — no loss.

## Procedure

1. Baseline: SKU 2002 at available=497, reserved=0, sold=3.
2. `docker stop ecommerce-kafka`.
3. With Kafka down: place an order (2 units) and pay it over HTTP.
4. Observe order state, inventory, and the outbox.
5. `docker start ecommerce-kafka`, wait for the relay to drain the outbox.
6. Observe final inventory and outbox.

## Observations

| Step | Result |
|------|--------|
| Place order (Kafka down) | HTTP 201 — succeeded |
| Pay order (Kafka down) | HTTP 200 — succeeded, order = PAID |
| Inventory during outage | reserved=2, sold=3 (stock NOT yet committed) |
| Outbox during outage | OrderCreated + OrderPaid = PENDING |
| After Kafka recovery | outbox → SENT (retry_count 2–3, i.e. it retried during the outage) |
| Final inventory | reserved=0, sold=5 — committed exactly once |

## Conclusions

- The order service does not call Kafka on the request path; it only writes to the
  outbox table (same DB, same transaction as the state change). A broker outage
  therefore cannot fail or block order placement or payment.
- The relay retries with exponential backoff; buffered events survive the outage and
  are delivered on recovery. Delivery is at-least-once, and the consumer's
  idempotent commit/release means a redelivered event moves stock exactly once.
- Net effect: temporary broker unavailability degrades to a delay in stock
  settlement, never data loss or inconsistency.
