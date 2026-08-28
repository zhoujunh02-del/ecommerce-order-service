import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Hot-SKU flood against inventory-service reserve, isolating the stock tier.
// Run twice — once with Redis on, once with it off — to compare how each copes
// with a flood that far exceeds available stock (the seckill scenario).
//
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -e BASE_URL=http://host.docker.internal:8081 -e SKU=2004 \
//     grafana/k6 run - < loadtest/hotspot-reserve.js

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const SKU = Number(__ENV.SKU || 2004);

const reserved = new Counter('reserved');
const rejected = new Counter('rejected');

export const options = {
  scenarios: {
    flood: { executor: 'constant-vus', vus: 80, duration: '15s' },
  },
};

// Self-contained UUIDv4 (no external jslib dependency).
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export default function () {
  const payload = JSON.stringify({
    orderId: uuidv4(),
    lines: [{ skuId: SKU, quantity: 1 }],
  });
  const res = http.post(`${BASE_URL}/internal/inventory/reserve`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  // 200 = reserved, 409 = sold out. Both are "handled"; a 5xx would be a real failure.
  check(res, { 'handled': (r) => r.status === 200 || r.status === 409 });
  if (res.status === 200) reserved.add(1);
  else if (res.status === 409) rejected.add(1);
}
