# Phase 4 — Redis two-tier deduction under a hot-SKU flood

Measures the effect of the Redis pre-deduction layer on the inventory reserve path
under a seckill-style flood (far more buyers than stock), and confirms correctness
is unaffected by Redis being wrong or down.

## Setup

- Load generator: `grafana/k6` (Docker), script `loadtest/hotspot-reserve.js`,
  posting to inventory-service `/internal/inventory/reserve` directly (isolates the
  stock tier from order-db writes).
- Profile: 80 constant VUs for 15s, all targeting one SKU seeded with 500 units.
- A/B: `inventory.redis.enabled=true` vs `false`, everything else equal.

## Results

| Metric | Redis ON | Redis OFF (DB only) | Change |
|--------|----------|---------------------|--------|
| Throughput | ~3563 req/s | ~1969 req/s | ~1.8x |
| Latency avg | 6.3 ms | 11.3 ms | ~1.8x lower |
| Latency p95 | 15.7 ms | 21.3 ms | lower |
| Reserved (correct) | 500 | 500 | no oversell either way |

Once the 500 units are gone, ~99.7% of requests are rejections. With Redis, those
rejections fast-fail in memory and never touch the database; without Redis, every
one contends on the single hot inventory row, so throughput roughly halves.

## Correctness under Redis faults

- **Inflated Redis:** a test sets Redis to 1,000,000 for a 50-unit SKU and floods
  it; exactly 50 succeed. The DB conditional update is the source of truth, so an
  over-optimistic Redis cannot cause overselling.
- **Redis down:** stopping the Redis container mid-run, a reserve still returns 200
  and the DB deducts correctly (degraded path). A Redis outage lowers throughput but
  never breaks correctness.

## Takeaway

Redis is the performance layer, PostgreSQL is the source of truth. Every Redis
failure mode degrades to "slower" or "under-sell", never "oversell".
