# Kubernetes deployment

Runs the whole system — both services plus PostgreSQL, Redis, and Kafka — in a local
Kubernetes cluster, with CPU-based horizontal pod autoscaling.

## Prerequisites

- Docker, `kubectl`, and [kind](https://kind.sigs.k8s.io/).

## Deploy

```bash
# 1. Cluster
kind create cluster --name ecommerce

# 2. metrics-server (needed by the HPA); kind requires the insecure-tls flag
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl patch -n kube-system deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

# 3. Build images and load them into the cluster
./gradlew :order-service:bootJar :inventory-service:bootJar
docker build -t order-service:phase6 order-service/
docker build -t inventory-service:phase6 inventory-service/
kind load docker-image order-service:phase6 inventory-service:phase6 --name ecommerce

# 4. Middleware first, then the services
kubectl apply -f k8s/00-namespace.yaml -f k8s/10-postgres.yaml -f k8s/11-redis.yaml -f k8s/12-kafka.yaml
kubectl wait -n ecommerce --for=condition=available --timeout=120s deployment/postgres deployment/redis deployment/kafka
kubectl apply -f k8s/20-inventory-service.yaml -f k8s/21-order-service.yaml
```

## Try it

```bash
kubectl port-forward -n ecommerce svc/order-service 18080:8080 &
curl -X POST localhost:18080/api/v1/orders \
  -H 'Idempotency-Key: 11111111-1111-1111-1111-111111111111' \
  -H 'X-User-Id: 1001' -H 'Content-Type: application/json' \
  -d '{"items":[{"skuId":2001,"quantity":1}]}'
```

## Watch autoscaling

```bash
kubectl apply -f k8s/loadtest-k6-job.yaml   # in-cluster load generator
watch kubectl get hpa,pods -n ecommerce     # replicas grow 2 -> 6 under load
kubectl delete -f k8s/loadtest-k6-job.yaml  # stop; replicas scale back down
```

See [`loadtest/results/phase6-k8s-autoscaling.md`](../loadtest/results/phase6-k8s-autoscaling.md)
for the autoscaling results and the database-connection-limit pitfall found and fixed.

## Notes

- Middleware runs as single-replica Deployments with no persistence — enough to
  demonstrate the cluster, not production-grade.
- `pods × Hikari pool-size` must stay under PostgreSQL `max_connections`; the pool is
  capped at 5 per pod for this reason (a real deployment would front PostgreSQL with
  a pooler such as PgBouncer).

## Tear down

```bash
kind delete cluster --name ecommerce
```
