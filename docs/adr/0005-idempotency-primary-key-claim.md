# 0005 — Idempotency via a primary-key claim

Status: accepted

## Context

A user who double-clicks, or a client that retries a timed-out request, must not
create duplicate orders. The same holds for payment callbacks, which a gateway
retries until acknowledged.

## Decision

Make the operation idempotent by *claiming* a unique key rather than checking for it.
`POST /orders` requires a client-supplied `Idempotency-Key`; the handler inserts an
`IN_PROGRESS` row into an `idempotency` table in the same transaction as the order.
A duplicate submit conflicts on the primary key and rolls back cleanly instead of
creating a second order. The final outcome (success or business failure) is cached as
JSON, so replays return the identical result; an in-flight duplicate gets
`REQUEST_IN_PROGRESS`, and reusing a key with a different body is rejected via a
request hash. Payment callbacks apply the same claim on their payment number, and
stock operations use a `UNIQUE(order_id, sku_id, op_type)` ledger constraint.

## Consequences

- Concurrency is resolved by the database, atomically: a race between two identical
  submits is decided by which insert wins the primary key, not by application logic.
- Callers must supply a stable key; the server generating it would defeat the purpose
  because a client retry would send a new one.

## Alternatives considered

- **Read-then-check (`if (exists) return`) (rejected):** this is itself a
  check-then-act race — two concurrent requests both read "absent" and both proceed.
  A unique-constraint claim is atomic; an application-level `if` is not.
- **Deduplicate only at an API gateway (rejected):** helps with retries but not with
  genuine concurrent duplicates, and pushes a core correctness concern out of the
  service that owns the data.
