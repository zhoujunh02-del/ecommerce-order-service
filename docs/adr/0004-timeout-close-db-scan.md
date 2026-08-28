# 0004 — Timeout close via a database scan, not a queue delay

Status: accepted

## Context

An unpaid order must be cancelled and its stock released after a deadline (e.g. 15
minutes). The trigger must never be lost — a missed close leaves stock reserved
forever — but it tolerates several seconds of latency, and it is low-frequency.

## Decision

Scan the database periodically for expired `PENDING_PAYMENT` orders and cancel them,
using `SELECT ... FOR UPDATE SKIP LOCKED` to claim a batch. `SKIP LOCKED` turns an
ordinary table into a concurrency-safe work queue: multiple scanner instances skip
each other's locked rows instead of blocking, so no distributed lock is needed. Each
cancel writes an `OrderCancelled` event to the outbox in the same transaction, and
the stock release flows through the existing Kafka path.

## Consequences

- The trigger is durable (state lives in the table) and naturally idempotent (the
  conditional transition only affects `PENDING_PAYMENT` rows). A partial index on
  pending orders keeps the scan cheap regardless of total order volume.
- There is a small, irrelevant latency (the scan interval).

## Alternatives considered

- **Kafka delay message (rejected):** Kafka has no native delayed delivery; emulating
  it with tiered topics and pause/seek is fragile and misuses the log. This need is
  low-frequency, latency-tolerant, must-not-be-lost, and the data already lives in
  the orders table — a database scan is the right tool.
- **Redis ZSet delay queue (rejected):** lower latency, but if Redis loses the entry
  the order never closes, so a database fallback is required anyway — two mechanisms
  for what one solves.
