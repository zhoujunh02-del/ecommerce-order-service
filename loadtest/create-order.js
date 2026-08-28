import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Baseline load test for the place-order pipeline (Phase 1: pure PostgreSQL,
// no Redis). Measures throughput and latency of: insert order + items (T1) ->
// HTTP reserve -> conditional stock deduct -> transition to PENDING_PAYMENT (T2).
//
// Run against the host services (both on the host) from a k6 Docker container:
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 \
//     grafana/k6 run - < loadtest/create-order.js

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SKU = __ENV.SKU || '2005';   // seed this SKU with plenty of stock first

const created = new Counter('orders_created');
const rejected = new Counter('orders_rejected');

export const options = {
  scenarios: {
    ramping: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '20s', target: 50 },
        { duration: '5s', target: 0 },
      ],
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],   // <1% transport errors
    http_req_duration: ['p(95)<500'], // informational baseline target
  },
};

export default function () {
  const payload = JSON.stringify({ items: [{ skuId: Number(SKU), quantity: 1 }] });
  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': '1001' },
  });

  const ok = check(res, { 'status is 201': (r) => r.status === 201 });
  if (ok) {
    created.add(1);
  } else {
    rejected.add(1);
  }
}
