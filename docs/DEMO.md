# Demo Guide

A copy-paste walkthrough to run the system and see its core behaviours: order
placement, idempotency, oversell prevention, timeout cancellation, Kafka fault
tolerance, and Kubernetes autoscaling.

## Prerequisites

- Java 17, Docker. (For the Kubernetes section: `kubectl` and
  [kind](https://kind.sigs.k8s.io/).)

## 1. Run locally

```bash
# Start infrastructure (PostgreSQL, Redis, Kafka, Prometheus, Grafana)
docker compose up -d

# Start the services (two terminals, or append & to background them)
./gradlew :inventory-service:bootRun     # :8081
./gradlew :order-service:bootRun         # :8080

# Health
curl localhost:8080/actuator/health
curl localhost:8081/actuator/health
```

## 2. Core flow — place, reserve, pay

```bash
KEY=$(uuidgen)

# Place an order (2x keyboard); stock is reserved
curl -s -X POST localhost:8080/api/v1/orders -H 'X-User-Id: 1001' \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"items":[{"skuId":2001,"quantity":2}]}'

curl -s localhost:8081/api/v1/inventory/2001   # available 100 -> 98, reserved 0 -> 2

# Grab the order id from the response above, then pay it (mock gateway)
# curl -s -X POST localhost:8080/api/v1/mock-payment/<orderId>/pay
# -> after ~1s the Kafka consumer commits: reserved 2 -> 0, sold 0 -> 2
```

## 3. Idempotency — a retried request is applied once

```bash
# Send the SAME Idempotency-Key three more times
for i in 1 2 3; do
  curl -s -o /dev/null -w "attempt $i\n" -X POST localhost:8080/api/v1/orders \
    -H 'X-User-Id: 1001' -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
    -d '{"items":[{"skuId":2001,"quantity":2}]}'
done
curl -s localhost:8081/api/v1/inventory/2001   # reserved is STILL 2 — deducted once
```

## 4. Oversell prevention

Fire many more buyers than there is stock at one SKU and confirm the reserved
count never exceeds the available stock. (For exact numbers start from a fresh
database: `docker compose down -v && docker compose up -d`, so SKU 2004 = 50.)

```bash
for i in $(seq 1 200); do
  curl -s -o /dev/null -X POST localhost:8081/internal/inventory/reserve \
    -H 'Content-Type: application/json' \
    -d "{\"orderId\":\"$(uuidgen)\",\"lines\":[{\"skuId\":2004,\"quantity\":1}]}" &
done; wait

curl -s localhost:8081/api/v1/inventory/2004   # available 0, reserved 50 — never oversold
```

The same property is proven deterministically by the concurrency test
(1,000 concurrent buyers vs 100 units → exactly 100 succeed):

```bash
./gradlew :inventory-service:test --tests '*StockServiceConcurrencyTest'
```

> If Testcontainers cannot find Docker, point it at your Docker socket, e.g.
> `export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` (Docker Desktop on macOS),
> and, on a very new Docker Engine, `export DOCKER_API_VERSION=1.44`.

## 5. Timeout cancellation

Run order-service with a short payment window, place an order, and watch it get
cancelled and its stock released automatically.

```bash
./gradlew :order-service:bootRun --args='--order.payment-timeout=PT5S'
# Place an order, wait ~12s (5s timeout + 5s scan + Kafka release), then GET it:
# status becomes CANCELLED and the reserved stock returns to available.
```

## 6. Kafka fault tolerance — zero event loss

```bash
docker stop ecommerce-kafka          # break the broker

# Place an order and pay it — both still succeed; events buffer in the outbox
# (order-service never calls Kafka on the request path).

docker start ecommerce-kafka         # recover
# The OutboxRelay redelivers the buffered events and inventory commits — no loss.
```

## 7. Live metrics — Grafana

Open http://localhost:3000 (admin / admin) → the provisioned **Order Service**
dashboard. Generate some traffic and watch order outcomes, reserve results, and
the outbox backlog update live.

## 8. Kubernetes + autoscaling

Deploy to a local cluster and watch the HPA scale pods under load — see
[`k8s/README.md`](../k8s/README.md).

```bash
kubectl apply -f k8s/loadtest-k6-job.yaml    # in-cluster load
watch kubectl get hpa,pods -n ecommerce      # replicas grow 2 -> 6
kubectl delete -f k8s/loadtest-k6-job.yaml   # stop; they scale back down
```

## Teardown

```bash
docker compose down -v                 # local infra + data
kind delete cluster --name ecommerce   # kubernetes cluster
```
