#!/usr/bin/env python3
"""Measure the DB load each request path avoids, using MySQL's own counters.

On a loopback Docker stack a primary-key SELECT costs a fraction of the HTTP
round trip, so end-to-end p95 cannot show what the cache is worth. What the
cache actually removes is database work, and `SHOW GLOBAL STATUS LIKE
'Com_select'` is the database's own accounting of it.

The counter is server-wide, so this runs through Nginx like the rest of the suite:

    MARTHUB_BASE_URL=http://localhost:8080 python3 benchmarks/db_load.py
"""
import concurrent.futures
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get('MARTHUB_BASE_URL', 'http://localhost:8080').rstrip('/')
MYSQL_CONTAINER = os.environ.get('MARTHUB_MYSQL_CONTAINER', 'marthub-mysql-1')


def com_select():
    out = subprocess.run(
        ['docker', 'exec', MYSQL_CONTAINER, 'mysql', '-uroot', '-proot', '-N', '-B',
         '-e', "SHOW GLOBAL STATUS LIKE 'Com_select'"],
        capture_output=True, text=True, check=True).stdout
    return int(out.split()[-1])


def hit(path, method='GET', headers=None):
    req = urllib.request.Request(BASE + path, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def burst(path, n, workers, method='GET', headers=None):
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        return list(ex.map(lambda _: hit(path, method, headers), range(n)))


def measure(label, fn):
    """Bracket a workload with MySQL counter reads, with settle time on both sides."""
    time.sleep(1.0)
    before = com_select()
    extra = fn() or {}
    time.sleep(1.0)
    row = {'case': label, 'mysql_selects': com_select() - before}
    row.update(extra)
    print(json.dumps(row), flush=True)
    return row


def main():
    rows = []

    # Noise floor: whatever the idle stack costs over the same wall clock.
    rows.append(measure('idle_control', lambda: time.sleep(2.0) and None))

    # Read path A: straight to MySQL on every request.
    rows.append(measure('reads_db_only', lambda: {
        'requests': len(burst('/internal/benchmark/shop/1/db', 2000, 32))}))

    # Read path B: Caffeine L1 + Redis L2, pre-warmed.
    burst('/internal/benchmark/shop/1/cached', 50, 8)
    rows.append(measure('reads_multilevel_cache', lambda: {
        'requests': len(burst('/internal/benchmark/shop/1/cached', 2000, 32))}))

    # Cache penetration: an id that is not in the database at all.
    rows.append(measure('reads_absent_id_bloom_filter', lambda: {
        'requests': 2000,
        'statuses': sorted({s for s, _ in burst(
            '/internal/benchmark/shop/999999/cached', 2000, 32)})}))

    # Flash sale, legacy shape: invalid traffic validated inside order processing.
    hit('/internal/benchmark/metrics/reset', 'POST')
    rows.append(measure('flashsale_baseline_invalid', lambda: {
        'requests': 50,
        'order_processor_entries': json.loads(
            burst('/internal/benchmark/flash-sale/baseline-invalid', 50, 20, 'POST')
            and hit('/internal/benchmark/metrics')[1])['orderProcessorEntries']}))

    # Flash sale, optimized: same traffic, rejected before the order boundary.
    token = json.loads(hit('/api/auth/demo-login?userId=2&name=LoadTest', 'POST')[1])['token']
    headers = {'Authorization': 'Bearer ' + token, 'X-Eligibility-Token': 'definitely-invalid'}
    hit('/internal/benchmark/metrics/reset', 'POST')
    rows.append(measure('flashsale_optimized_invalid', lambda: {
        'requests': 50,
        'order_processor_entries': json.loads(
            burst('/api/flash-sale/101/orders', 50, 20, 'POST', headers)
            and hit('/internal/benchmark/metrics')[1])['orderProcessorEntries']}))

    out = Path(__file__).with_name('db_load_results.json')
    out.write_text(json.dumps(
        {'measured_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
         'base_url': BASE,
         'cases': rows}, indent=2) + '\n')


if __name__ == '__main__':
    main()
