// One level of an open-loop ramp against the seckill order path.
//
// Each iteration uses a buyer nobody else uses: uq_user_item allows one order per (user, item), so
// a reused buyer would be answered by the bought-replay gate -- a single Redis GET -- and the run
// would report throughput for a path that never touches the database.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';
import exec from 'k6/execution';

const buyers = new SharedArray('buyers', () =>
  papaparse.parse(open(__ENV.PAIRS), { header: true }).data.filter((r) => r.session));

const created  = new Counter('orders_created');
const rejected = new Counter('rejected');
const replayed = new Counter('replayed');
const errors   = new Counter('server_errors');
const s503 = new Counter('status_503'); const s500 = new Counter('status_500');
const s401 = new Counter('status_401'); const s0   = new Counter('status_timeout');
const orderDur = new Trend('order_duration', true);

export const options = {
  scenarios: {
    level: {
      executor: 'constant-arrival-rate',
      rate: parseInt(__ENV.RATE),
      timeUnit: '1s',
      duration: __ENV.DURATION,
      // Latency past the knee runs into seconds, so sustaining R arrivals/s needs roughly
      // R x latency VUs. Under-allocating shows up as dropped_iterations, which means the offered
      // load was never actually offered and the level is not a valid data point.
      preAllocatedVUs: Math.min(6000, Math.max(400, parseInt(__ENV.RATE) * 4)),
      maxVUs: 10000,
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  // Sequential slice per iteration so no buyer is used twice inside a level.
  const b = buyers[exec.scenario.iterationInTest % buyers.length];
  // ITEM=0 means "use the item this buyer was provisioned for", which is how the spread run
  // removes the single hot row without changing anything else about the request.
  const item = __ENV.ITEM === '0' ? b.item : __ENV.ITEM;
  const res = http.post(`${__ENV.BASE}/api/flash-sale/${item}/orders`, null, {
    headers: { 'X-Auth-Token': b.session, 'X-Eligibility-Token': b.token },
    timeout: '20s',
  });
  orderDur.add(res.timings.duration);
  if (res.status === 200) {
    // A replay is a 200 too, but it writes nothing. Counting them together would credit the
    // system with orders it never created -- which is exactly what the first A/B run did.
    if (res.body && res.body.includes('"replayed":true')) replayed.add(1); else created.add(1);
  }
  else if (res.status === 400 || res.status === 409 || res.status === 429) rejected.add(1);
  else {
    errors.add(1);
    if (res.status === 503) s503.add(1);
    else if (res.status === 500) s500.add(1);
    else if (res.status === 401) s401.add(1);
    else if (res.status === 0) s0.add(1);
  }
}
