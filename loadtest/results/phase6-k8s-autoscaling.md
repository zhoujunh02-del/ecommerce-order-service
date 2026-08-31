# Phase 6 — Kubernetes deployment and horizontal autoscaling

Deploys both services to a local Kubernetes cluster (kind) with all middleware
in-cluster, and demonstrates CPU-based horizontal pod autoscaling — including a
real pitfall found and fixed along the way.

## Setup

- Cluster: kind (single node), `kubectl`, `metrics-server` (patched with
  `--kubelet-insecure-tls`, required on kind).
- In-cluster: PostgreSQL, Redis, Kafka (KRaft), plus `order-service` and
  `inventory-service` (2 replicas each), wired by Service DNS names.
- Each service: CPU `request: 250m`, and an HPA targeting 50% CPU, min 2, max 6.
- Load: an in-cluster k6 Job (`k8s/loadtest-k6-job.yaml`) floods
  `order-service:8080` with placements.

## Observations

**Autoscaling works.** Under load, `order-service` CPU rose to ~83% and then ~212%
of the 50% target; the HPA scaled both deployments from 2 to the max of 6 replicas.

**A pitfall surfaced.** On the first scale-up, two new `inventory-service` pods
entered `CrashLoopBackOff`. The logs showed:

```
FATAL: remaining connection slots are reserved for roles with the SUPERUSER attribute
```

PostgreSQL's `max_connections` (100) was exhausted: 6 + 6 pods × a Hikari pool of 10
per pod = up to 120 connections. New pods could not get a connection at startup, so
Flyway failed and the context did not start.

**The fix.** Reduce the per-pod pool: `spring.datasource.hikari.maximum-pool-size=5`
(set via the ConfigMap's `SPRING_APPLICATION_JSON`, no image rebuild). Now 12 pods ×
5 = 60 connections, comfortably under 100. After a rolling restart, a repeated load
run scaled to 6 replicas per service with **zero crashes** and PostgreSQL steady at
~66 connections.

## Takeaway

Horizontally scaling a stateful-backed service multiplies its demand on shared,
bounded resources — most commonly database connections. `pods × pool-size` must stay
under the database limit. The immediate fix is a smaller per-pod pool; the production
answer is a connection pooler (e.g. PgBouncer) so many pods share one server-side
pool. Autoscaling the stateless tier is easy; the database is the real constraint.
