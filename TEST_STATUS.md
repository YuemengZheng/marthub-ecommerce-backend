# Measurement report

What this project's benchmarks actually produced, including the number that did not
survive measurement.

Run on 2026-08-11, macOS (Apple silicon), Docker Desktop 29.4.3, full stack:
MySQL 8.4 + Redis 7.4 + 3 Spring Boot instances + Nginx.

## Verified

Structural: `scripts/verify_claim_structure.sh` → `Claim structure checks passed.`

Behaviour, via `benchmarks/benchmark.py` through Nginx (`http://localhost:8080`),
stable across 4 consecutive runs:

| Check | Result |
| --- | --- |
| Invalid requests reaching the order processor, legacy baseline | 50 |
| Invalid requests reaching the order processor, optimized pipeline | 0 |
| Eligibility token reused on retry | true |
| Token-bucket burst of 100 valid-token admissions | 20 admitted, 80 `RATE_LIMITED` |
| Order-processor entries vs admitted requests | equal (20 = 20) |
| Instances serving one Redis session token | 3 (`app-1`, `app-2`, `app-3`), all 200 |

Database load, via `benchmarks/db_load.py`, using MySQL's own `Com_select` counter.
The idle noise floor over the same window is 1, so every row below carries it:

| Workload | MySQL SELECTs |
| --- | --- |
| 2,000 reads, DB-only path | 2,007 |
| 2,000 reads, Caffeine L1 + Redis L2 path | 1 |
| 2,000 reads for an absent id (Bloom filter) | 1, all `400` |
| 50 invalid flash-sale requests, legacy baseline | 153 |
| 50 invalid flash-sale requests, optimized pipeline | 1 |

Cache coherence and sessions, through Nginx:

- After a `PUT /api/shops/1`, 45 reads spread over all 3 instances returned the new
  value: **0 stale reads**. Redis pub/sub invalidates each instance's Caffeine L1.
- Session TTL renewal: `1800s` → `1795s` after 5s idle → `1800s` after one request.
- `GET /api/shops/1` with no token and with a bad token both return `401`.

## The latency numbers do not show what you would expect

**There is no p95 improvement from the cache here, and the benchmark's latency fields
should not be quoted as if there were.** On a loopback Docker stack the shops table is
3 rows and the read is a primary-key lookup, so the DB query is a small fraction of
the HTTP round trip. Measured with ApacheBench (20,000 requests, concurrency 50,
three interleaved A/B rounds, warmed):

| Path | Throughput | Mean | p95 |
| --- | --- | --- | --- |
| DB-only | 12,837 rps | 3.90 ms | 6 ms |
| Multi-level cache | 13,819 rps | 3.62 ms | 6 ms |

About 8% throughput, and **no p95 difference**. The first run of `benchmark.py` after
a container start does show a large apparent cache win (e.g. 27ms → 13ms) — that is
JVM warm-up, and it disappears on the second run. What the cache demonstrably removes
is database work, which is why `db_load.py` exists.

Likewise `eligibility_pipeline.p95_after_ms` is usually *higher* than
`p95_before_ms` (~15ms vs ~12ms). The optimized path costs a Redis session lookup
plus a Redis token check and throws an exception, while the legacy baseline endpoint
runs three trivial local SELECTs. The pipeline's value is the work it refuses to do,
not its latency on an idle laptop.

## Fixed while measuring

- `BloomFilterRegistry.reload()` did not compile: the lambda passed to
  `JdbcTemplate.query` was ambiguous between `ResultSetExtractor` and
  `RowCallbackHandler`. Now cast explicitly.
- `FlashSaleMetrics` kept its counters in JVM-local `AtomicLong`s. Behind Nginx the
  reset and the read landed on different instances, so the documented procedure
  reported 16 baseline / 17 optimized entries instead of 50 / 0. Counters are now
  Redis-backed and correct cluster-wide.
