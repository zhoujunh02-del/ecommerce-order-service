# Phase 1 Baseline — Place Order (pure PostgreSQL, no Redis)

Baseline throughput/latency of the place-order pipeline before any caching layer.
Each iteration exercises the full path: insert order + items (T1) → HTTP reserve
to inventory-service → conditional stock deduct + ledger insert → transition to
PENDING_PAYMENT (T2).

## Setup

- Both services on the host (`./gradlew bootRun`); PostgreSQL/Redis/Kafka in Docker Compose.
- Load generator: `grafana/k6` (Docker), script `loadtest/create-order.js`.
- Profile: ramping VUs 0→50 over 10s, hold 50 for 20s, ramp down 5s.
- Target SKU 2005 pre-seeded with abundant stock so every request hits the success path.

## Results

| Metric | Value |
|--------|-------|
| Throughput | ~1008 orders/sec (40,323 in 40s) |
| Latency avg | 33 ms |
| Latency p90 | 50 ms |
| Latency p95 | 56 ms |
| Latency max | 141 ms |
| HTTP failures | 0.00% |
| Peak VUs | 50 |

## Notes

- The success path here includes a synchronous cross-service HTTP hop per order,
  so latency reflects two services + two databases, not a single-process CRUD.
- This is the number to beat in Phase 4, where a Redis pre-deduction layer should
  raise throughput under hot-SKU contention (where the pure-PG conditional update
  serializes on a single row).
