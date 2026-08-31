# Distributed E-Commerce Order Service

A backend system implementing the core **order lifecycle** of an e-commerce
platform — placement, payment, timeout cancellation, and querying — built to solve
the hard problems of distributed systems rather than hide them: idempotency,
oversell prevention, cross-service consistency, timeout uncertainty, and eventual
consistency.

## Architecture

Two independently deployable services, each owning its own database. They
communicate by **synchronous HTTP** (stock reservation) and **Kafka events**
(asynchronous settlement and compensation). Neither service reads the other's
tables — data ownership is enforced at the build level (the modules have no
compile-time dependency on each other).

```
                          ┌──────────────────────┐
        HTTP  (reserve)   │    order-service      │   POST /orders, /payments/callback
   ┌─────────────────────▶│        :8080          │◀── clients (X-User-Id header)
   │                      │  Saga orchestration    │
   │                      │  Idempotency guard     │
   │  ┌───────────────┐   │  Outbox relay          │   ┌──────────────┐
   │  │ inventory-svc │   │  Timeout scanner       │──▶│  order_db     │
   │  │    :8081      │   │  Creating reconciler   │   └──────────────┘
   │  │ reserve/commit│   └───────────┬────────────┘
   │  │ /release      │               │ outbox → Kafka: order.events (key = orderId)
   │  │ two-tier stock│◀──────────────┘       ├─ inventory-group: OrderPaid→commit, Cancelled→release
   │  └──┬────────┬───┘   consume             └─ notification-group (fan-out)
   │     │        │                            ↳ order.events.DLT (dead letters)
   │ ┌───▼───┐ ┌──▼────────┐
   └─│ Redis │ │inventory_db│
     │(cache)│ └───────────┘
     └───────┘
```

| Service | Port | Owns | Responsibility |
|---------|------|------|----------------|
| `order-service` | 8080 | `order_db` | Order lifecycle, idempotency, Saga orchestration, transactional outbox, timeout close, reconciliation, payment callback |
| `inventory-service` | 8081 | `inventory_db` + Redis | Three-state stock (reserve / commit / release), two-tier deduction |

## What this system demonstrates

Each problem below has a design decision behind it, recorded under [`docs/adr/`](docs/adr/).

- **Oversell prevention** — a conditional `UPDATE ... WHERE available >= qty` makes
  the check and the write one atomic statement; the row lock serializes concurrent
  buyers. Proven by a 1000-way concurrency test.
- **Two-tier deduction** — Redis pre-deduction (performance) in front of the
  PostgreSQL source of truth, [ADR-0003](docs/adr/0003-two-tier-stock-deduction.md).
  Any Redis fault degrades to under-selling, never overselling.
- **Idempotency** — enforced by database claims, not read-then-check,
  [ADR-0005](docs/adr/0005-idempotency-primary-key-claim.md).
- **Cross-service consistency** — synchronous reserve + Saga compensation +
  transactional outbox, [ADR-0002](docs/adr/0002-consistency-saga-outbox.md).
  At-least-once delivery with idempotent consumers.
- **Timeout uncertainty** — when a reserve times out, the order stays `CREATING` and
  a reconciler asks inventory what actually happened (a status-query endpoint) rather
  than guessing. Reserve retry/circuit-breaking via Resilience4j.
- **TCC edge cases** — idempotence, empty rollback, and hanging are handled in the
  reserve/release ledger logic.
- **Timeout close** — a database scan with `FOR UPDATE SKIP LOCKED`, deliberately not
  a queue delay, [ADR-0004](docs/adr/0004-timeout-close-db-scan.md).
- **Realistic payment callback** — HMAC signature (constant-time), idempotent on
  payment number, amount-checked, with a refund path for the "paid but already
  cancelled" race.
- **Time-ordered ids** — UUID v7 primary keys,
  [ADR-0006](docs/adr/0006-uuid-v7-primary-keys.md).

## Tech Stack

Java 17 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Kafka (KRaft) · MyBatis ·
Flyway · Resilience4j · Docker Compose · Testcontainers · k6 · Prometheus + Grafana

## Getting Started

```bash
# 1. Start infrastructure (PostgreSQL, Redis, Kafka, Prometheus, Grafana)
docker compose up -d

# 2. Run the services
./gradlew :order-service:bootRun
./gradlew :inventory-service:bootRun

# 3. Place an order
curl -X POST localhost:8080/api/v1/orders \
  -H 'Idempotency-Key: 11111111-1111-1111-1111-111111111111' \
  -H 'X-User-Id: 1001' -H 'Content-Type: application/json' \
  -d '{"items":[{"skuId":2001,"quantity":1}]}'

# 4. Pay it (mock gateway)
curl -X POST localhost:8080/api/v1/mock-payment/<orderId>/pay
```

For a full walkthrough — idempotency, oversell prevention, timeout cancellation,
Kafka fault tolerance, live metrics, and Kubernetes autoscaling — see
[`docs/DEMO.md`](docs/DEMO.md).

### Kubernetes

The system also runs on Kubernetes (kind) with all middleware in-cluster and
CPU-based horizontal pod autoscaling. See [`k8s/`](k8s/) for manifests and steps.

## Key APIs

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/orders` | Place an order (requires `Idempotency-Key`) |
| GET | `/api/v1/orders/{id}` | Order detail |
| GET | `/api/v1/orders?cursor=&size=` | List orders (keyset pagination) |
| POST | `/api/v1/orders/{id}/cancel` | Cancel a pending order |
| POST | `/api/v1/payments/callback` | Payment gateway callback (HMAC signed) |
| GET | `/internal/inventory/reservations/{id}` | Reservation status (Saga convergence) |
| GET | `/internal/admin/orders/stuck` | Operational view: stuck orders, dead events, refunds |

## Observability

Both services expose `/actuator/prometheus`. Prometheus scrapes them and Grafana
loads a provisioned dashboard (**Order Service**) on startup at
`http://localhost:3000` (admin/admin). Custom metrics include order outcomes,
reserve results, timeout cancellations, reconciler outcomes, and outbox backlog
(pending/dead) gauges.

## Testing & fault injection

```bash
./gradlew test                 # unit + Testcontainers integration tests
k6 run loadtest/create-order.js
k6 run loadtest/hotspot-reserve.js
```

Key results and experiments are recorded under [`loadtest/results/`](loadtest/results/):

- **Oversell** — 1000 concurrent buyers vs 100 units → exactly 100 succeed.
- **Kafka outage** — with Kafka stopped, placement/payment still succeed and events
  buffer in the outbox; on recovery every event is delivered, zero loss.
- **Saga convergence** — with inventory down during reserve, the order stays
  `CREATING`; the reconciler resolves it correctly on recovery.
- **Redis benchmark** — hot-SKU flood: ~3563 req/s (Redis) vs ~1969 req/s (DB only),
  both reserving exactly the stock (no oversell). Inflated/absent Redis never
  oversells.

## Design decisions

See [`docs/adr/`](docs/adr/) for the reasoning — context, choice, consequences, and
the rejected alternatives — behind each significant decision.

## Status

Built in phases, each ending in a verifiable, demonstrable state:

- [x] **Phase 0** — Project scaffolding, infrastructure, database schema
- [x] **Phase 1** — Single-node correctness baseline (order placement, stock, state machine)
- [x] **Phase 2** — Idempotency and transactional outbox
- [x] **Phase 3** — Saga compensation and timeout-uncertainty handling
- [x] **Phase 4** — Redis two-tier deduction and performance tuning
- [x] **Phase 5** — Observability and documentation
- [x] **Phase 6** — Kubernetes deployment with horizontal autoscaling ([k8s/](k8s/))
