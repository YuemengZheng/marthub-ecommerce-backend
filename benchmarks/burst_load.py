#!/usr/bin/env python3
"""Burst load test: does the admission stage hold MySQL flat while offered load grows?

The earlier flash-sale numbers were functional. "50 of 50 invalid requests blocked" says the
code works; "100 requests, 20 admitted" restates the configured capacity. Neither says the
system survives a burst, and a bigger single ratio would not either -- driving 10,000 requests
at a 200/s limit and reporting 98% rejected is the same tautology with more zeros.

So this sweeps the offered load instead. Same mix, same concurrency, 1K then 5K then 10K
requests. The claim worth making is a shape: input grows tenfold, work arriving at the
database does not. Each level runs twice --

  baseline: every request reaches order processing, which does three SELECTs before it can
            tell whether the caller was even eligible
  gated:    eligibility token, then the per-user bucket, then the per-item bucket, all in
            Redis, so a rejected request costs the database nothing

Traffic is mixed: 30% of callers carry a token that was never issued, which is what an
admission stage is for. Every level uses a fresh user id range so no bucket carries state in
from the level before.

MySQL Com_select is read either side of each burst and reported as the delta -- FLUSH STATUS
does not reset that global on MySQL 8, so an absolute reading would be the server's lifetime
total. Latency is reported but it is the weakest number here: three JVMs, a
MySQL and a Redis share one laptop with the load generator, so p95 describes this machine more
than it describes the design.
"""
import concurrent.futures
import json
import math
import os
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get('MARTHUB_BASE_URL', 'http://localhost:8390').rstrip('/')
MYSQL_CONTAINER = os.environ.get('MARTHUB_MYSQL_CONTAINER', 'marthub-mysql-1')
REDIS_CONTAINER = os.environ.get('MARTHUB_REDIS_CONTAINER', 'marthub-redis-1')
ITEM_ID = 101
INVALID_FRACTION = 0.30
CONCURRENCY = 200
LEVELS = [1000, 5000, 10000]
USER_POOL = 500


def request(method, path, headers=None):
    req = urllib.request.Request(BASE + path, method=method, headers=headers or {})
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        body, status = e.read(), e.code
    except Exception as e:
        return (time.perf_counter() - t) * 1000, 0, str(e).encode()
    return (time.perf_counter() - t) * 1000, status, body


def p95(xs):
    xs = sorted(xs)
    return xs[max(0, math.ceil(len(xs) * .95) - 1)]


def mysql(sql):
    out = subprocess.run(
        ['docker', 'exec', MYSQL_CONTAINER, 'mysql', '-uroot', '-proot', '-N', '-B', '-e', sql],
        capture_output=True, text=True, check=True)
    return out.stdout.strip()


def status_value(name):
    row = mysql(f"SHOW GLOBAL STATUS LIKE '{name}'")
    return int(row.split('\t')[1]) if row else 0


def com_select():
    """FLUSH STATUS does not reset this global on MySQL 8, so the only honest reading is a
    delta around the burst rather than an absolute after a reset."""
    return status_value('Com_select')


def redis_cli(*args):
    subprocess.run(['docker', 'exec', REDIS_CONTAINER, 'redis-cli', *args],
                   capture_output=True, text=True, check=True)


def reset_gate():
    """The gate only issues tokens up to stock x multiplier, and earlier runs have spent some.
    Clearing the counter is setup for this test, not part of what it measures."""
    redis_cli('DEL', f'fs:gate:{ITEM_ID}')


def reset_rate_buckets():
    """Every bucket, item and per-user, so a level starts with full allowances."""
    redis_cli('EVAL',
              "local ks=redis.call('KEYS','fs:rate:*') for i=1,#ks do redis.call('DEL',ks[i]) end return #ks",
              '0')


def auth_token(user_id, name):
    _, s, b = request('POST', f'/api/auth/demo-login?userId={user_id}&name={name}')
    if s != 200:
        raise RuntimeError(f'login {user_id}: {s} {b[:120]}')
    return json.loads(b)['token']


def eligibility_token(auth):
    _, s, b = request('POST', f'/api/flash-sale/{ITEM_ID}/eligibility',
                      {'Authorization': 'Bearer ' + auth})
    if s != 200:
        raise RuntimeError(f'eligibility: {s} {b[:120]}')
    return json.loads(b)['token']


def build_headers(first_user_id):
    """A pool of real callers. 30% of them get a token that was never issued."""
    def one(i):
        uid = first_user_id + i
        auth = auth_token(uid, f'Burst{uid}')
        token = 'never-issued' if i % 10 < INVALID_FRACTION * 10 else eligibility_token(auth)
        return {'Authorization': 'Bearer ' + auth, 'X-Eligibility-Token': token}
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as ex:
        return list(ex.map(one, range(USER_POOL)))


def fire(path, headers_for_request, n, needs_headers):
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as ex:
        t0 = time.perf_counter()
        rows = list(ex.map(
            lambda i: request('POST', path, headers_for_request(i) if needs_headers else None),
            range(n)))
        elapsed = time.perf_counter() - t0
    return rows, elapsed


def classify(rows):
    out = {'invalid_token_rejections': 0, 'rate_limited': 0, 'admitted': 0,
           'rejected_after_db_work': 0, 'transport_errors': 0, 'other': {}}
    for _, status, body in rows:
        if status == 0:
            out['transport_errors'] += 1
            continue
        if status in (204, 400) and status == 204:
            out['admitted'] += 1
            continue
        code = ''
        if status == 400:
            try:
                code = json.loads(body or b'{}').get('code', '')
            except json.JSONDecodeError:
                code = ''
        if code == 'INVALID_TOKEN':
            out['invalid_token_rejections'] += 1
        elif code == 'RATE_LIMITED':
            out['rate_limited'] += 1
        elif status == 400 and not code:
            # The baseline shape: order processing ran its three SELECTs and only then could
            # say no. Counted separately because that is the work the gate exists to avoid.
            out['rejected_after_db_work'] += 1
        else:
            key = f'{status}:{code or "?"}'
            out['other'][key] = out['other'].get(key, 0) + 1
    return out


def run_level(n, pool):
    reset_rate_buckets()
    request('POST', '/internal/benchmark/metrics/reset')
    baseline = measure(
        '/internal/benchmark/flash-sale/baseline-invalid', None, n, needs_headers=False)

    reset_rate_buckets()
    request('POST', f'/internal/benchmark/flash-sale/rate/reset?itemId={ITEM_ID}')
    gated = measure(
        f'/internal/benchmark/flash-sale/admission?itemId={ITEM_ID}',
        lambda i: pool[i % USER_POOL], n, needs_headers=True)

    # The admission endpoint stops at the order boundary, so the gated case reads ~0 SELECTs.
    # Taking that at face value would claim the gate removes all database work, which is not
    # what it does: the requests it admits still have an order to place. The comparison worth
    # reporting projects the admitted ones back through the same three reads the baseline does.
    per_request = 3
    projected = gated['admitted'] * per_request
    measured_reduction = None
    projected_reduction = None
    if baseline['mysql_selects']:
        measured_reduction = round((baseline['mysql_selects'] - gated['mysql_selects'])
                                   * 100.0 / baseline['mysql_selects'], 2)
        projected_reduction = round((baseline['mysql_selects'] - projected)
                                    * 100.0 / baseline['mysql_selects'], 2)
    return {
        'offered_requests': n,
        'baseline_no_gate': baseline,
        'gated': gated,
        'selects_per_order_attempt': per_request,
        'projected_gated_selects_if_admitted_orders_run': projected,
        'projected_mysql_select_reduction_pct': projected_reduction,
        'measured_mysql_select_reduction_pct_admission_only': measured_reduction,
    }


def measure(path, headers_for_request, n, needs_headers):
    """One burst, with the database counter read either side of it and every response
    classified -- including the baseline's, so a request that never arrived cannot be
    mistaken for one the gate turned away."""
    before = com_select()
    rows, elapsed = fire(path, headers_for_request, n, needs_headers)
    selects = com_select() - before
    counts = classify(rows)
    delivered = n - counts['transport_errors']
    return {
        'mysql_selects': selects,
        'selects_per_delivered_request': round(selects / delivered, 2) if delivered else None,
        'max_used_connections_high_water': status_value('Max_used_connections'),
        'order_processor_entries': json.loads(request('GET', '/internal/benchmark/metrics')[2])['orderProcessorEntries'],
        'wall_seconds': round(elapsed, 2),
        'offered_rps': round(n / elapsed, 1),
        'p95_ms': round(p95([r[0] for r in rows]), 2),
        'delivered': delivered,
        **counts,
    }


def _sweep():
    # One pool of callers for the whole sweep: building it costs 500 logins and 350 token
    # issuances, and re-issuing per level would exhaust the eligibility gate.
    reset_gate()
    pool = build_headers(100000)
    return [run_level(n, pool) for n in LEVELS]


def main():
    results = {
        'measured_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'base_url': BASE,
        'workload': {
            'levels': LEVELS,
            'concurrency': CONCURRENCY,
            'invalid_fraction': INVALID_FRACTION,
            'distinct_callers': USER_POOL,
            'note': 'a fresh user id range per level, so no bucket carries state across levels',
        },
        'limiter_config': {
            'source': 'FLASH_RATE_PER_SECOND / FLASH_BURST_CAPACITY on the app containers',
            'item_rate_per_second': os.environ.get('REPORT_ITEM_RATE', 'see compose'),
            'item_burst_capacity': os.environ.get('REPORT_ITEM_BURST', 'see compose'),
            'user_rate_per_second': os.environ.get('REPORT_USER_RATE', 'default 5'),
            'user_burst_capacity': os.environ.get('REPORT_USER_BURST', 'default 5'),
        },
        'levels': _sweep(),
    }
    Path(__file__).with_name('burst_load_results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
