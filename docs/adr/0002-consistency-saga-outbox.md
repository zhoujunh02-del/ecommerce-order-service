# 0002 — Synchronous reserve + Saga compensation + transactional outbox

Status: accepted

## Context

Placing an order must reserve stock in another service. A local `@Transactional`
cannot span two databases, and writing to both a database and Kafka is a dual write
that can diverge. We need the user to learn the outcome promptly, and we need stock
and order state to stay consistent despite partial failures.

## Decision

- **Reserve synchronously over HTTP** during placement, so the user immediately
  learns success/failure, with a Saga that compensates (release) on cancel/timeout.
- **Publish events through a transactional outbox:** the state change and the event
  are written in one local transaction to an `outbox` table; a relay then publishes
  to Kafka and marks the row sent. This turns "send a message" into "write a row",
  which the database can make atomic with the state change.

## Consequences

- Delivery is at-least-once: events can be redelivered, so every consumer is
  idempotent. Duplicate messages are far easier to handle than lost ones.
- A broker outage cannot fail order placement or payment; events buffer in the
  outbox and drain on recovery (verified by fault injection — zero loss).
- The timeout-uncertainty of the synchronous reserve is handled separately (see the
  reconciler in ADR-0004's sibling code): the order stays `CREATING` and a status
  query decides its fate rather than guessing.

## Alternatives considered

- **Pure asynchronous placement (rejected):** reserving via events would make the
  service maximally decoupled, but the user gets "accepted" and only later "out of
  stock" — a poor experience for normal ordering. It is the right model for flash
  sales, not the default path.
- **A distributed-transaction framework, e.g. Seata / XA (rejected):** an
  annotation hides the mechanism; XA over Kafka performs poorly and couples
  everything to a transaction coordinator. Implementing the Saga by hand keeps the
  behaviour explicit and understood.
