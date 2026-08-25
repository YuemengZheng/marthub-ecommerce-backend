# Claims that did not survive measurement

The benchmark tables live in `README.md` and the raw output in `benchmarks/*_results.json`.
This file is for the other half: numbers this project stopped reporting, and why. Each
one was believed, then measured, then dropped.

Keeping them written down is the point. A claim that quietly disappears looks like it
was never made; a claim that is recorded as retired is a statement about how the numbers
here were arrived at.

## 1. "The cache improves p95"

**Retired.** Measured with ApacheBench, 20,000 requests, concurrency 50, three
interleaved A/B rounds, warmed:

| Path | Throughput | Mean | p95 |
| --- | --- | --- | --- |
| DB-only | 12,837 rps | 3.90 ms | 6 ms |
| Multi-level cache | 13,819 rps | 3.62 ms | 6 ms |

About 8% throughput and **no p95 difference at all**. The read is a primary-key lookup
against a warm InnoDB buffer pool, so on loopback the query is a small fraction of the
HTTP round trip. Removing it does not move end-to-end latency; it moves database load,
which is what the project reports instead.

The large apparent win on the first run after a container start (27 ms → 13 ms in one
early run) is JVM warm-up and disappears on the second run. That number was the reason
the claim was believed in the first place.

Measured when the table held 3 rows. Growing it to 10,000 does not change the
conclusion — a B-tree lookup on 10,000 rows is still cheaper than a loopback round trip
— but that part is reasoning, not a re-measurement.

## 2. "The admission layer makes requests faster"

**Retired, and the sign is the other way.** The gated path is *slower* than the legacy
baseline on an idle laptop: it carries a session token and so pays a Redis session
lookup, then a token check, then throws; the baseline endpoint runs three trivial local
SELECTs and returns.

The admission layer's value is the work it refuses to do, not its latency. On a machine
where three JVMs, MySQL, Redis and the load client all share a laptop, latency figures
of any kind should be read as noise.

## 3. "The gate cuts database queries by N%"

**Retired 2026-08-24, and this one is the most interesting of the three.**

Admitted volume comes out of a single token bucket, so it is `rate × wall-clock + burst`.
`burst_load.py` now measures the ratio of measured admissions to that prediction, and it
comes out **0.985 / 0.994 / 0.997** across the sweep. A percentage built on that number
restates the configured capacity and how long the client happened to take — it is
arithmetic on the config, not a property of the system.

It was less obvious while two interacting buckets fed it. Removing the per-user bucket
made the design better and the metric worse, which is the honest way round to describe
what happened.

Replaced by the shape: baseline database cost stays flat at 3.0 reads per offered
request while the admitted share falls 27% → 9.7% → 7.8% as load rises. Database work
stops tracking arrivals — that is the claim, and it cannot be computed from the config.

## 4. "2,000 cached reads cost 0 database queries"

**Retired.** True, and empty. The 2,000 was the benchmark's own loop bound, and all
2,000 reads hit one key in a 3-row table. A metric whose magnitude is a parameter of the
benchmark says nothing about the system, and a hit rate measured on a single key invites
the one question that kills it: what was the key distribution?

Replaced by `cache_profile.py` — 10,000 shops, 20,000 Zipf reads, 3,464 distinct keys
touched, starting state controlled and stated.

## 5. "That request makes 8 Redis round trips"

**Retired 2026-08-24.** Counted out of `MONITOR`, which reports commands the *server*
executed. The client multiplexes and the framework may batch its writes, so command
count and round-trip count are different numbers. Reporting one as the other was an
inference presented as a measurement.

Replaced by `stage_latency.py`, which measures the wall-clock cost of each stage —
the thing the command count was standing in for. It found the session stage costs more
than the whole admission chain (+1.29 ms vs +0.86 ms at p50), and that both are around a
millisecond, which is why neither has been restructured.

## The one claim measurement supported

Not everything here got worse under a microscope. **Overselling** was asserted only by reading
the SQL until 2026-08-24, when it got a real test: 50,000 buyers released at 5,000 units against
a real MySQL, through a real transaction manager, all with distinct user ids so the unique
constraint cannot mask a miscount.

Result: **exactly 5,000 orders, stock at zero, no buyer with two, no lock-wait timeouts.**

What makes that worth stating is the control. The same test run against a read-then-write —
`SELECT stock`, check it, then `UPDATE` — produced **5,063 orders for 5,000 units** and left the
stock column at **-63**. At 1,000 units it was 1,031 and -31. So the test does discriminate
between the two implementations, which is the only thing that makes a green run mean anything.

## Bugs found by measuring, not by reading

- `BloomFilterRegistry.reload()` **did not compile** — the lambda passed to
  `JdbcTemplate.query` was ambiguous between `ResultSetExtractor` and
  `RowCallbackHandler`. Any status claimed before that was fixed described code that had
  never run.
- `FlashSaleMetrics` kept counters in JVM-local `AtomicLong`s. Behind Nginx the reset and
  the read landed on different instances, so the documented procedure reported 16/17
  instead of 50/0. Now Redis-backed.
- `FLUSH STATUS` **does not reset `Com_select`** on MySQL 8. The first burst run compared
  lifetime totals and produced "reductions" of −0.07%. Now measured as a delta either
  side of each burst.
- At concurrency 500 the load client itself failed: 1,042 of 10,000 requests never left,
  and were being **counted as rejections** — crediting the system for the client's
  failure. Dropped to 200 and every response is now classified, including transport
  errors and the baseline's.
- `DEBUG SLEEP` is disabled by default on Redis 7.4, and a refused DEBUG looks exactly
  like a healthy server, so the failure probe reported "no exception" for a case it never
  provoked. Uses `CLIENT PAUSE` now — which is server-wide, so it also had to be paired
  with `CLIENT UNPAUSE`, having leaked into the next test class.
- A Redis timeout does **not** always surface as `QueryTimeoutException`. A probe against
  a real server showed connect-time failures throw `RedisConnectionFailureException` and
  only a stall on an established connection throws `QueryTimeoutException`. Both map to
  503; the split was measured, not read off the class hierarchy.
- `@RestControllerAdvice` only sees exceptions after dispatch. The session lookup happens
  in a filter, so the most common Redis failure of all returned **500** until a filter
  ordered ahead of the security chain took over.
