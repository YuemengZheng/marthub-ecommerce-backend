# Load test

Finds the saturation point of the flash-sale order path, and which component causes it.

**Everything here ran on one laptop.** The load generator, three application instances, MySQL and
Redis share twelve cores. These numbers describe this configuration; they are not a claim about a
production deployment, and the absolute throughput would change on separate hardware. What does
transfer is the *shape*: where the knee is relative to the components, and which one is responsible.

## Method

Open-loop arrivals (`constant-arrival-rate`). A closed-loop harness — N workers each waiting for its
own response — throttles itself the moment latency rises, so it can never show a saturation point;
it just quietly stops offering the load it claims to offer. `dropped_iterations` is reported for
every run: above zero means the offered load was never actually offered and the level is not valid.

Each component has an explicit CPU limit. Without them every container competes for the same cores
and "which one saturated" has only one possible answer: the laptop.

Every level is preceded by a state reset and a 25-second warm-up. Both matter, and both were learned
the hard way — see *What went wrong* below.

## Harness ceiling, measured first

Against an endpoint nginx answers itself, so no JVM is involved:

| Offered | Achieved | Dropped iterations |
| --- | --- | --- |
| 6,000/s | 5,999 | 0 |
| 10,000/s | 9,999 | 0 |
| 15,000/s | 14,878 | 1,780 |
| 30,000/s | 25,732 | 58,162 |

**Clean to 10,000/s.** Every result below is far under that, so the harness is not the limit.

## 1. Ramp: one hot item

Rate limiter opened up, so the order path itself is what is being measured.

| Offered | Achieved | p50 | p95 | MySQL commits/s | `Threads_running` | Row lock waits |
| --- | --- | --- | --- | --- | --- | --- |
| 200 | 200/s | 3 ms | 6 ms | 106 | 2 | 0 |
| 500 | 500/s | 2 ms | 12 ms | 251 | 10 | +652 |
| **600** | **600/s** | **3 ms** | **39 ms** | 599 | 9 | +2,080 |
| 700 | 699/s | 13 ms | 288 ms | 692 | 60 | +6,424 |
| 800 | 749/s | 293 ms | 1,244 ms | 754 | 62 | +9,534 |
| 900 | 707/s | 2,231 ms | 5,153 ms | 710 | 62 | +9,176 |

**Sustained ≈600 RPS at p95 < 40 ms with no errors.** Past 800 the achieved rate *falls* while
latency keeps climbing — goodput collapse, not a plateau.

## 2. Which component

Not CPU. At the knee, MySQL sits at about half its 2.0-CPU limit, Redis at a third of its 1.0, and
the application instances at roughly half of their 2.0 each. What pegs is `Threads_running`, at 62
against a 60-connection pool: every connection is inside the database, waiting.

Two experiments separate cause from symptom.

**Enlarging the pool, 800 RPS:**

| Pool | Achieved | p50 | p95 | Commits/s |
| --- | --- | --- | --- | --- |
| 60 | 682/s | 1,495 ms | 3,225 ms | 704 |
| 150 | 655/s | 2,363 ms | 4,334 ms | 637 |

Two and a half times the connections bought nothing and cost 58% more latency. The pool is a queue
in front of the bottleneck, not the bottleneck.

**Removing the hot row — same 800 RPS spread over 500 items:**

| | Achieved | p50 | p95 | `Threads_running` | Row lock waits |
| --- | --- | --- | --- | --- | --- |
| One item | 682/s | 1,495 ms | 3,225 ms | 62 | +11,562 |
| 500 items | 800/s | 5 ms | **11 ms** | 4 | **0** |

Same code, same load, same everything else. **The bottleneck is row-lock serialisation on the single
inventory row** — average wait 105 ms, max 687 ms. Without it the ceiling moves to about 1,200 RPS
and the limit becomes application CPU.

That also says what would and would not help. Redis-based inventory reservation with asynchronous
settlement addresses this; more connections, more instances, or a bigger database do not.

## 3. Burst: correctness under concurrency

Fixed stock of 100. Buyers released together, each a distinct user.

| Buyers | Orders | Sold-out rejections | Other | Rows in `orders` | Stock left | Distinct buyers |
| --- | --- | --- | --- | --- | --- | --- |
| 100 | 100 | 0 | 0 | 100 | 0 | 100 |
| 500 | 100 | 400 | 0 | 100 | 0 | 100 |
| 1,000 | 100 | 900 | 0 | 100 | 0 | 100 |
| 5,000 | 100 | 4,900 | 0 | 100 | 0 | 100 |
| **10,000** | **100** | **9,900** | **0** | **100** | **0** | **100** |

Zero oversell and zero duplicates at every size. This answers a different question from the ramp and
the two are kept apart on purpose: capacity and correctness fail in different ways.

## 4. What the admission layer is worth

Same 2,000 RPS at one hot item — five times what the order path can absorb.

| | Limiter off | Limiter at 200/s |
| --- | --- | --- |
| Achieved | 1,520/s | **1,999/s** (all of it) |
| p95 | 7,972 ms | **16 ms** |
| Failed requests | **63.3%** | **0%** |
| Orders created | 14,668 | 4,185 |
| Fast rejections | 0 | 35,816 |
| MySQL commits/s | 534 | 199 |
| `Threads_running` | 62 | **3** |
| Row lock waits | +9,175 | **+66** |

Overload with the limiter is a fully served 2,000 RPS at 16 ms where two thirds of requests are
turned away cheaply. Overload without it is a collapse in which most requests fail after seconds of
waiting, and the database does *less* useful work than at 800 RPS.

## What went wrong, and what it cost

Kept because each one produced plausible-looking numbers that were wrong.

- **`FLUSHDB` between levels wiped the sessions.** Sessions and flash-sale keys share one Redis. The
  ramp then reported 200–1,000 RPS at p95 4 ms with zero database work — a very fast system, entirely
  made of 401s. The reset now deletes only `fs:*`.
- **`__ITER` is per-VU, not global.** With `shared-iterations` every VU sees 0 and picks the same
  buyer, so the first burst run was one user firing 10,000 concurrent orders. It produced 1 order and
  9,999 × 409, which is the processing lease behaving exactly as designed and answering a question
  nobody asked.
- **The buyer pool wrapped around.** 40,000 requests against 12,000 buyers meant two thirds were
  repeat purchases, answered as idempotent replays — 200s that write nothing, counted as orders. Runs
  now provision more buyers than the level will consume, and replays are counted separately.
- **Measuring immediately after a restart measures JIT.** An A/B arm reported 30% errors and 5.5 s p95
  for a configuration that actually sustains 2,000 RPS at 16 ms p95. Every run is now warmed first.
- **The metrics collector was suspected of distorting the results and was tested rather than assumed.**
  Four alternating runs with and without it: no significant difference. The suspicion was reasonable
  and wrong, which is why the check exists.

## Running it

Needs the bench topology (CPU limits, opened-up limiter) and a buyer pool:

```bash
COMPOSE_FILE=docker-compose.yml:docker-compose.bench.yml docker compose up -d
PROV_USERS=45000 python3 loadtest/provision.py     # sessions via HTTP, tokens straight into Redis
./loadtest/warmup.sh && ./loadtest/runlevel.sh 800 20s mylevel
python3 loadtest/summarize.py mylevel
```

Raw output in `loadtest_results.json`.
