// Harness ceiling. Not a test of MartHub -- a test of what any number here can mean.
//
// Open loop on purpose: constant-arrival-rate keeps issuing requests at the configured rate no
// matter how slow the responses are. A closed-loop harness (N workers, each waiting for its own
// response) throttles itself the moment latency rises, so it can never show a saturation point --
// it just quietly stops offering the load it claims to offer.
import http from 'k6/http';
import { check } from 'k6';

const TARGET = __ENV.TARGET;
const RATE = parseInt(__ENV.RATE);

export const options = {
  scenarios: {
    ramp: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '20s',
      // Enough VUs that k6 never runs out and starts dropping iterations; a dropped_iterations
      // count above zero means the harness could not keep up and the run is not valid.
      preAllocatedVUs: Math.min(2000, Math.max(100, RATE)),
      maxVUs: 4000,
    },
  },
  thresholds: { checks: ['rate>0.99'] },
};

export default function () {
  const res = http.get(TARGET, { timeout: '10s' });
  check(res, { ok: (r) => r.status === 200 });
}
