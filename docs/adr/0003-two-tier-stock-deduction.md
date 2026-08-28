# 0003 — Two-tier stock deduction (Redis + PostgreSQL)

Status: accepted

## Context

Under a hot-SKU flood (many more buyers than units), a single inventory row becomes
a contention point: every reserve — success or failure — serializes on that row's
lock. We want higher throughput without ever risking an oversell.

## Decision

Deduct stock in two tiers. First a Redis Lua script atomically checks and
pre-deducts (`stock:{skuId}`); if it says sold out, the request fast-fails in memory
with no database I/O. Only requests that pass Redis reach the authoritative
PostgreSQL conditional `UPDATE ... WHERE available >= qty`, which remains the source
of truth. Redis pre-deducts are refunded on failure; a checker re-syncs Redis to the
database and warms it at startup.

## Consequences

- Under a flood, the ~99% of requests that will fail are absorbed by Redis, roughly
  doubling reserve throughput in the benchmark (~1969 → ~3563 req/s), with no change
  to correctness.
- The design fails safe in every direction: Redis disabled/missing/down degrades to
  the database (correct, slower); an over-optimistic Redis is capped by the database.
  The only possible errors are under-selling or wasted database calls — never an
  oversell. The database is always the final arbiter.

## Alternatives considered

- **Database only (rejected for hot SKUs):** absolutely correct and simplest, and it
  is the fallback. But it makes the hot inventory row the throughput ceiling.
- **Redis as the source of truth, async write-behind to the DB (rejected):** highest
  throughput, but Redis persistence and replication are asynchronous, so a crash can
  lose deductions and oversell. Correctness must not depend on a cache.
