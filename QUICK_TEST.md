# MartHub quick test

## 1. Start the stack

Requirements: Docker Desktop with Docker Compose.

```bash
cd MartHub
docker compose up --build
```

Wait until MySQL/Redis are healthy and Nginx is listening on `http://localhost:8080`.

## 2. Run the structural checks

In a second terminal:

```bash
cd MartHub
bash scripts/verify_claim_structure.sh
```

Expected:

```text
Claim structure checks passed.
```

## 3. Run the behavioural benchmark

```bash
python3 benchmarks/benchmark.py
cat benchmarks/results.json
```

The fields that carry a claim:

- `eligibility_pipeline.baseline_order_processor_entries` should be `50`
- `eligibility_pipeline.optimized_order_processor_entries` should be `0`
- `eligibility_token_reuse.same_token_reused` should be `true`
- `rate_limiter.rate_limited_before_order` should be greater than `0`
- `rate_limiter.order_processor_entries` should equal `rate_limiter.allowed_to_order_boundary`
- `shared_auth.unique_instances` should be `3`
- `shared_auth.all_authorized` should be `true`

Ignore `cache.p95_*` and `eligibility_pipeline.p95_*`. They are dominated by HTTP
overhead and by JVM warm-up on the first run after `docker compose up`; see
`TEST_STATUS.md`. Run the script twice and the apparent cache win disappears.

## 4. Run the DB-load benchmark

This is the measurement the cache claim rests on. It reads MySQL's own
`Com_select` counter, which is cluster-wide, so it runs through Nginx like the rest:

```bash
MARTHUB_BASE_URL=http://localhost:8080 python3 benchmarks/db_load.py
cat benchmarks/db_load_results.json
```

Expected, against an idle noise floor of about `1`:

- `reads_db_only` → about `2000` MySQL SELECTs
- `reads_multilevel_cache` → noise floor
- `reads_absent_id_bloom_filter` → noise floor, all responses `400`
- `flashsale_baseline_invalid` → about `150` SELECTs, `50` order-processor entries
- `flashsale_optimized_invalid` → noise floor, `0` order-processor entries

## 5. What to send back

```text
benchmarks/results.json
benchmarks/db_load_results.json
```

Do not edit the numbers by hand.

## 6. Stop the stack

```bash
docker compose down -v
```
