#!/usr/bin/env python3
"""Reproducible local benchmark harness. Writes only measurements actually observed."""
import concurrent.futures
import json
import math
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get('MARTHUB_BASE_URL', 'http://localhost:8080').rstrip('/')


def request(method, path, headers=None, body=b''):
    req = urllib.request.Request(
        BASE + path,
        data=(body if method != 'GET' else None),
        method=method,
        headers=headers or {},
    )
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = r.read()
            hs = dict(r.headers)
            status = r.status
    except urllib.error.HTTPError as e:
        data = e.read()
        hs = dict(e.headers)
        status = e.code
    return (time.perf_counter() - t) * 1000, status, data, hs


def p95(xs):
    xs = sorted(xs)
    return xs[max(0, math.ceil(len(xs) * .95) - 1)]


def run_parallel(method, path, n=200, workers=32, headers=None):
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        rows = list(ex.map(lambda _: request(method, path, headers), range(n)))
    return [r[0] for r in rows], rows


def auth_token(user_id=2, name='LoadTest'):
    _, s, b, _ = request('POST', f'/api/auth/demo-login?userId={user_id}&name={name}')
    if s != 200:
        raise RuntimeError((s, b))
    return json.loads(b)['token']


def eligibility_token(auth, item_id=101):
    headers = {'Authorization': 'Bearer ' + auth}
    _, s, b, _ = request('POST', f'/api/flash-sale/{item_id}/eligibility', headers)
    if s != 200:
        raise RuntimeError((s, b))
    return json.loads(b)['token']


def cache_benchmark():
    # Warm the optimized path before a steady-state read comparison.
    for _ in range(12):
        request('GET', '/internal/benchmark/shop/1/cached')
    db, _ = run_parallel('GET', '/internal/benchmark/shop/1/db')
    cached, _ = run_parallel('GET', '/internal/benchmark/shop/1/cached')
    return {
        'requests': 200,
        'workers': 32,
        'p95_before_ms': round(p95(db), 2),
        'p95_after_ms': round(p95(cached), 2),
    }


def invalid_pipeline_benchmark():
    request('POST', '/internal/benchmark/metrics/reset')
    base_lat, _ = run_parallel(
        'POST', '/internal/benchmark/flash-sale/baseline-invalid', n=50, workers=20)
    _, _, b, _ = request('GET', '/internal/benchmark/metrics')
    base_entries = json.loads(b)['orderProcessorEntries']

    request('POST', '/internal/benchmark/metrics/reset')
    auth = auth_token()
    headers = {
        'Authorization': 'Bearer ' + auth,
        'X-Eligibility-Token': 'definitely-invalid',
    }
    opt_lat, _ = run_parallel(
        'POST', '/api/flash-sale/101/orders', n=50, workers=20, headers=headers)
    _, _, b, _ = request('GET', '/internal/benchmark/metrics')
    m = json.loads(b)
    return {
        'invalid_requests': 50,
        'baseline_order_processor_entries': base_entries,
        'optimized_order_processor_entries': m['orderProcessorEntries'],
        'optimized_pre_order_rejections': m['preOrderRejections'],
        'p95_before_ms': round(p95(base_lat), 2),
        'p95_after_ms': round(p95(opt_lat), 2),
    }


def eligibility_token_reuse_check():
    auth = auth_token(user_id=2, name='EligibilityReuse')
    first = eligibility_token(auth)
    second = eligibility_token(auth)
    return {
        'same_token_reused': first == second,
        'token_length': len(first),
    }


def rate_limiter_benchmark():
    """Use a valid token so rejection can only come from the Redis rate limiter."""
    auth = auth_token(user_id=2, name='RateLimitTest')
    token = eligibility_token(auth)
    headers = {
        'Authorization': 'Bearer ' + auth,
        'X-Eligibility-Token': token,
    }
    request('POST', '/internal/benchmark/flash-sale/rate/reset?itemId=101', headers)

    latencies, rows = run_parallel(
        'POST', '/internal/benchmark/flash-sale/admission?itemId=101',
        n=100,
        workers=100,
        headers=headers,
    )
    allowed = sum(1 for _, status, _, _ in rows if status == 204)
    rejected = 0
    unexpected = []
    for _, status, body, _ in rows:
        if status == 400:
            try:
                payload = json.loads(body or b'{}')
            except json.JSONDecodeError:
                payload = {}
            if payload.get('code') == 'RATE_LIMITED':
                rejected += 1
            else:
                unexpected.append({'status': status, 'body': body.decode(errors='replace')[:200]})
        elif status != 204:
            unexpected.append({'status': status, 'body': body.decode(errors='replace')[:200]})

    _, _, b, _ = request('GET', '/internal/benchmark/metrics')
    m = json.loads(b)
    return {
        'requests': 100,
        'workers': 100,
        'allowed_to_order_boundary': allowed,
        'rate_limited_before_order': rejected,
        'order_processor_entries': m['orderProcessorEntries'],
        'pre_order_rejections': m['preOrderRejections'],
        'p95_ms': round(p95(latencies), 2),
        'unexpected_responses': unexpected[:5],
    }


def three_instance_auth_check():
    auth = auth_token()
    headers = {'Authorization': 'Bearer ' + auth}
    instances = set()
    statuses = []
    for _ in range(60):
        _, s, _, h = request('GET', '/api/shops/1', headers)
        statuses.append(s)
        instances.add(h.get('X-MartHub-Instance'))
    seen = sorted(x for x in instances if x)
    return {
        'requests': 60,
        'instances_seen': seen,
        'unique_instances': len(seen),
        'all_authorized': all(s == 200 for s in statuses),
    }


def main():
    results = {
        'measured_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'cache': cache_benchmark(),
        'eligibility_pipeline': invalid_pipeline_benchmark(),
        'eligibility_token_reuse': eligibility_token_reuse_check(),
        'rate_limiter': rate_limiter_benchmark(),
        'shared_auth': three_instance_auth_check(),
    }
    out = Path(__file__).with_name('results.json')
    out.write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
