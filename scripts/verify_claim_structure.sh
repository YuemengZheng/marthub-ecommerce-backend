#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
grep -q 'Caffeine.newBuilder' src/main/java/dev/yuemeng/marthub/config/AppConfig.java
grep -q 'mightContainShop' src/main/java/dev/yuemeng/marthub/shop/ShopService.java
grep -q 'delayedEvictionMs' src/main/java/dev/yuemeng/marthub/config/MartHubProperties.java
grep -q 'ISSUE_LUA' src/main/java/dev/yuemeng/marthub/flashsale/EligibilityService.java
grep -q "local existing=redis.call('GET',tokenKey)" src/main/java/dev/yuemeng/marthub/flashsale/EligibilityService.java
grep -q 'RedisRateLimiter' src/main/java/dev/yuemeng/marthub/flashsale/FlashSaleService.java
grep -q 'benchmarkAdmission' src/main/java/dev/yuemeng/marthub/flashsale/FlashSaleService.java
grep -q 'X-Eligibility-Token' src/main/java/dev/yuemeng/marthub/flashsale/FlashSaleController.java
grep -q 'rate_limiter_benchmark' benchmarks/benchmark.py
grep -q 'eligibility_token_reuse_check' benchmarks/benchmark.py
grep -q 'RefreshTokenInterceptor' src/main/java/dev/yuemeng/marthub/config/WebConfig.java
grep -q 'LoginInterceptor' src/main/java/dev/yuemeng/marthub/config/WebConfig.java
grep -q '^  app1:' docker-compose.yml
grep -q '^  app2:' docker-compose.yml
grep -q '^  app3:' docker-compose.yml
echo 'Claim structure checks passed.'
