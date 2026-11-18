// Teste de carga progressiva do POST /transactions (L-06, docs/cenarios-teste.md).
//
// Carga de referência contra o ambiente local (Docker Compose) ou um runner dedicado.
// Os resultados dependem do hardware. A meta de 25.000 TPS só pode ser afirmada com medição em hardware
// equivalente ao descrito no ADR 0008, seção 5.
// Uma máquina de desenvolvimento não valida a meta de 25.000 TPS; o resultado é de referência.
//
// Uso (k6 >= 0.52):
//   k6 run -e BASE_URL=http://localhost:8081 \
//          -e CLIENT_CERT=/path/client.crt -e CLIENT_KEY=/path/client.key \
//          load-tests/transactions.js
//
// A autenticação do sistema chamador é mTLS no ALB (ADR 0008, seção 2.3).
//
// Thresholds de HTTP medem a resposta do POST, não o alerta. O SLA de 500 ms
// do alerta é medido pelo fraud.alert.latency (Micrometer) e pelo e2e.

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const CUSTOMERS = Number(__ENV.CUSTOMERS || 100000);

export const options = {
  tlsAuth: __ENV.CLIENT_CERT
    ? [{ cert: open(__ENV.CLIENT_CERT), key: open(__ENV.CLIENT_KEY) }]
    : [],
  scenarios: {
    progressive: {
      executor: 'ramping-arrival-rate',
      startRate: 500,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 4000,
      stages: [
        { target: 8000, duration: '5m' },   // carga média
        { target: 8000, duration: '15m' },  // sustentação na média
        { target: 25000, duration: '5m' },  // rampa até o pico
        { target: 25000, duration: '15m' }, // sustentação no pico
        { target: 0, duration: '2m' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<500'],
  },
};

// UUID v4 simples, suficiente para Idempotency-Key em carga.
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export default function () {
  const customerId = `c-${Math.floor(Math.random() * CUSTOMERS)}`;
  const amountInCents = Math.floor(Math.random() * 200000) + 100;
  const res = http.post(
    `${BASE_URL}/transactions`,
    JSON.stringify({ customerId, amountInCents, currency: 'BRL' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': uuid(),
      },
    },
  );
  check(res, { 'status 201': (r) => r.status === 201 });
}
