# 0001 — Two services, each owning its database

Status: accepted

## Context

The system centres on the order lifecycle, which depends on stock. The two obvious
concerns — orders and inventory — could live together or apart. The goal is to face
the real problems of distributed data ownership, without operational sprawl.

## Decision

Split into exactly two services, `order-service` and `inventory-service`, each with
its own database. Neither service reads the other's tables; they communicate only
via synchronous HTTP (reserve) and Kafka events. The build enforces this — the two
service modules have no compile-time dependency on each other.

## Consequences

- Every hard distributed-systems problem becomes real: cross-service consistency,
  idempotency, timeout uncertainty, and eventual consistency all have to be solved
  rather than hidden behind a local transaction.
- Two services (not five) keep the whole system runnable with one `docker compose`,
  so effort goes into depth per problem rather than wiring many shallow services.
- Cross-service debugging is harder than in a monolith and needs good logs/metrics.

## Alternatives considered

- **Modular monolith (rejected):** a single process with internal modules keeps
  local transactions and is the correct choice for many real products, but it never
  exercises network failure, partial failure, or message duplication — the whole
  point here.
- **Full microservices, 5–6 services + gateway + registry (rejected):** the extra
  services would each be a thin CRUD shell, and most effort would go into
  infrastructure wiring. The distributed problems are already fully present with two
  services; more services repeat them without adding insight.
