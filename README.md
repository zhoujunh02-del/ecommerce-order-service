# Distributed E-Commerce Order Service

A backend system that implements the core **order lifecycle** of an e-commerce
platform — placement, payment, timeout cancellation, and querying — with a focus
on the hard problems of distributed systems: idempotency, oversell prevention,
cross-service consistency, and eventual consistency.

## Architecture

Two independently deployable services, each owning its own database:

| Service | Port | Owns | Responsibility |
|---------|------|------|----------------|
| `order-service` | 8080 | `order_db` | Order lifecycle, Saga orchestration, transactional outbox |
| `inventory-service` | 8081 | `inventory_db` | Three-state stock (reserve / commit / release) |

Services communicate via **synchronous HTTP** (order placement) and
**Kafka events** (asynchronous downstream work and compensation). Neither service
reads the other's tables — data ownership is enforced at the database level.

### Key design choices

- **Consistency:** synchronous reserve over HTTP + Saga compensation + a
  transactional outbox, so an order commit always implies its event is delivered.
- **Oversell prevention:** two-tier deduction — Redis Lua pre-deduction as a
  performance layer, PostgreSQL conditional update as the source of truth.
  Any Redis fault can only cause under-selling, never overselling.
- **Timeout cancellation:** a database sweep using `FOR UPDATE SKIP LOCKED`
  (deliberately not a message-queue delay mechanism).
- **Idempotency:** enforced by database constraints (primary-key claim,
  `UNIQUE(order_id, sku_id, op_type)`), not application-level checks.

## Tech Stack

Java 17 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Kafka (KRaft) ·
MyBatis · Flyway · Docker Compose · Testcontainers · k6 · Prometheus + Grafana

## Getting Started

```bash
# 1. Start infrastructure (PostgreSQL, Redis, Kafka, Prometheus, Grafana)
docker compose up -d

# 2. Run the services
./gradlew :order-service:bootRun
./gradlew :inventory-service:bootRun

# 3. Health checks
curl localhost:8080/actuator/health
curl localhost:8081/actuator/health
```

## Status

Under active development. The service is being built in phases, each ending in a
verifiable, demonstrable state:

- [x] **Phase 0** — Project scaffolding, infrastructure, database schema
- [x] **Phase 1** — Single-node correctness baseline (order placement, stock, state machine)
- [x] **Phase 2** — Idempotency and transactional outbox
- [x] **Phase 3** — Saga compensation and timeout-uncertainty handling
- [x] **Phase 4** — Redis two-tier deduction and performance tuning
- [ ] **Phase 5** — Observability and documentation
