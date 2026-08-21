#!/usr/bin/env python3
"""Profile the cache under a hot-key distribution instead of a single key.

`db_load.py` reads one shop id 2,000 times. That proves the tiers are wired up, but
it cannot produce a meaningful hit rate: hammering one key makes any cache look
perfect, and "99.9% hit rate" invites the only question that matters — what was the
key distribution? This script answers that question up front.

10,000 shops, Zipf-distributed reads (a few very hot ids, a long tail), so a cold
miss on first touch is part of the workload rather than something the benchmark
arranges away. Three numbers come out, all from counters this process does not own:

* MySQL `Com_select` — database reads the cache removed
* Caffeine `stats()` — L1 hits, i.e. reads that never reached Redis either
* Redis `total_commands_processed` — the L2 traffic that survived L1

Caffeine is per-process, so when load goes through Nginx its counters have to be
summed over every instance -- hence MARTHUB_INSTANCE_URLS. The script controls its own
starting state (it flushes Redis and restarts the app containers between cases),
because "hit rate" means nothing without saying what was warm when the clock started.

    MARTHUB_BASE_URL=http://localhost:8080 \
    MARTHUB_INSTANCE_URLS=http://localhost:8081,http://localhost:8082,http://localhost:8083 \
    python3 benchmarks/cache_profile.py
"""
import concurrent.futures
import json
import os
import random
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get('MARTHUB_BASE_URL', 'http://localhost:8091').rstrip('/')
# When load goes through the load balancer, Caffeine stats have to be summed over
# every instance -- each one keeps its own. Set this to the per-instance URLs.
INSTANCES = [u.strip().rstrip('/') for u in
             os.environ.get('MARTHUB_INSTANCE_URLS', BASE).split(',') if u.strip()]
MYSQL_CONTAINER = os.environ.get('MARTHUB_MYSQL_CONTAINER', 'marthub-mysql-1')
REDIS_CONTAINER = os.environ.get('MARTHUB_REDIS_CONTAINER', 'marthub-redis-1')

SHOPS = 10000
REQUESTS = 20000
WORKERS = 32
ZIPF_S = 1.1


def com_select():
    out = subprocess.run(
        ['docker', 'exec', MYSQL_CONTAINER, 'mysql', '-uroot', '-proot', '-N', '-B',
         '-e', "SHOW GLOBAL STATUS LIKE 'Com_select'"],
        capture_output=True, text=True, check=True).stdout
    return int(out.split()[-1])


def redis_commands():
    out = subprocess.run(
        ['docker', 'exec', REDIS_CONTAINER, 'redis-cli', 'INFO', 'stats'],
        capture_output=True, text=True, check=True).stdout
    for line in out.splitlines():
        if line.startswith('total_commands_processed:'):
            return int(line.split(':', 1)[1])
    raise RuntimeError('total_commands_processed not found')


def get(path):
    try:
        with urllib.request.urlopen(BASE + path, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def l1_stats():
    """Summed across instances, so the counters line up with the request count."""
    total = {'hitCount': 0, 'missCount': 0, 'estimatedSize': 0}
    for base in INSTANCES:
        with urllib.request.urlopen(base + '/internal/benchmark/cache/l1-stats', timeout=30) as r:
            st = json.loads(r.read())
        for k in total:
            total[k] += st[k]
    return total


def flush_l2():
    subprocess.run(['docker', 'exec', REDIS_CONTAINER, 'redis-cli', 'FLUSHALL'],
                   capture_output=True, text=True, check=True)


def restart_instances():
    """Drop every Caffeine L1 by restarting the JVMs, then wait for them back."""
    subprocess.run(['docker', 'compose', 'restart', 'app1', 'app2', 'app3'],
                   capture_output=True, text=True, check=True)
    for base in INSTANCES:
        for _ in range(60):
            try:
                urllib.request.urlopen(base + '/internal/benchmark/cache/l1-stats', timeout=5)
                break
            except Exception:
                time.sleep(2)
        else:
            raise RuntimeError('instance never came back: ' + base)
    time.sleep(2)


def zipf_keys(n, population, s, seed=20260811):
    """Zipf-weighted ids in [1, population]. Pure stdlib, so no numpy dependency."""
    rng = random.Random(seed)
    weights = [1.0 / (rank ** s) for rank in range(1, population + 1)]
    total = 0.0
    cumulative = []
    for w in weights:
        total += w
        cumulative.append(total)
    keys = []
    for _ in range(n):
        target = rng.random() * total
        lo, hi = 0, population - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if cumulative[mid] < target:
                lo = mid + 1
            else:
                hi = mid
        keys.append(lo + 1)
    return keys


def run(path_template, keys):
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        return list(ex.map(lambda k: get(path_template.format(k)), keys))


def profile(label, path_template, keys):
    time.sleep(1.0)
    mysql_before, redis_before = com_select(), redis_commands()
    l1_before = l1_stats()
    statuses = run(path_template, keys)
    time.sleep(1.0)
    l1_after = l1_stats()
    row = {
        'case': label,
        'requests': len(statuses),
        'distinct_keys_requested': len(set(keys)),
        'mysql_selects': com_select() - mysql_before,
        'redis_commands': redis_commands() - redis_before,
        'l1_hits': l1_after['hitCount'] - l1_before['hitCount'],
        'l1_misses': l1_after['missCount'] - l1_before['missCount'],
        'non_200': sorted({s for s in statuses if s != 200}),
    }
    # An L1 miss that did not become a MySQL read was served by Redis. That is the
    # only place the second tier shows up as a number.
    row['served_by_l2'] = max(0, row['l1_misses'] - row['mysql_selects'])
    row['db_reads_per_1000_requests'] = round(row['mysql_selects'] * 1000 / row['requests'], 1)
    row['l1_hit_rate_pct'] = round(row['l1_hits'] * 100 / row['requests'], 2)
    row['redis_commands_per_1000_requests'] = round(row['redis_commands'] * 1000 / row['requests'], 1)
    print(json.dumps(row), flush=True)
    return row


def main():
    keys = zipf_keys(REQUESTS, SHOPS, ZIPF_S)
    hottest = sorted({k: keys.count(k) for k in set(keys)}.items(), key=lambda kv: -kv[1])[:5]
    cached = '/internal/benchmark/shop/{}/cached'

    rows = []

    # Baseline: no cache in the path at all.
    flush_l2()
    restart_instances()
    rows.append(profile('db_only', '/internal/benchmark/shop/{}/db', keys))

    # Cold start: nothing cached anywhere. Three independent L1 caches all miss, so
    # this is where L2 stops the same key being loaded once per instance.
    flush_l2()
    restart_instances()
    rows.append(profile('cold_l1_cold_l2', cached, keys))

    # Steady state: same distribution again, everything warm.
    rows.append(profile('warm_l1_warm_l2', cached, keys))

    # Redeploy: every instance restarted, so every L1 is empty, but Redis still holds
    # what the previous processes wrote. This is the case that justifies the L2 tier.
    restart_instances()
    rows.append(profile('cold_l1_warm_l2', cached, keys))

    baseline = rows[0]['mysql_selects']
    result = {
        'measured_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'base_url': BASE,
        'instances_polled': INSTANCES,
        'workload': {
            'shops_in_table': SHOPS,
            'requests': REQUESTS,
            'distribution': f'zipf s={ZIPF_S}',
            'distinct_keys_touched': len(set(keys)),
            'top_5_keys_by_request_count': hottest,
        },
        'cases': rows,
        'db_read_reduction_pct': {
            r['case']: round((baseline - r['mysql_selects']) * 100 / baseline, 2)
            for r in rows[1:]
        },
    }
    Path(__file__).with_name('cache_profile_results.json').write_text(
        json.dumps(result, indent=2) + '\n')
    print(json.dumps(result['workload'], indent=2))
    print(json.dumps(result['db_read_reduction_pct'], indent=2))


if __name__ == '__main__':
    main()
