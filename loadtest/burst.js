// Correctness under concurrency, not capacity. Everything arrives at once against a small stock;
// the question is whether the count of orders equals the stock, not how fast it got there.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';
import exec from 'k6/execution';

const buyers = new SharedArray('buyers', () =>
  papaparse.parse(open(__ENV.PAIRS), { header: true }).data.filter((r) => r.session));

const ok = new Counter('ok'), soldout = new Counter('soldout'), other = new Counter('other');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: Math.min(parseInt(__ENV.BUYERS), 2500),
      iterations: parseInt(__ENV.BUYERS),
      maxDuration: '3m',
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'max'],
};

export default function () {
  // __ITER is per-VU, so with shared-iterations every VU sees 0 and they all pick the same
  // buyer -- which the processing lease then correctly refuses with 409. Global index instead.
  const b = buyers[exec.scenario.iterationInTest % buyers.length];
  const res = http.post(`${__ENV.BASE}/api/flash-sale/${__ENV.ITEM}/orders`, null, {
    headers: { 'X-Auth-Token': b.session, 'X-Eligibility-Token': b.token },
    timeout: '60s',
  });
  if (res.status === 200) ok.add(1);
  else if (res.status === 400 && res.body && res.body.includes('SOLD_OUT')) soldout.add(1);
  else other.add(1);
}
