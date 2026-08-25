#!/bin/bash
# A freshly restarted JVM is not the system under test. Without this, changing a setting and
# measuring immediately reports JIT compilation -- which is how an earlier A/B run produced 30%
# errors and 5.5s p95 for a configuration that actually does 2000 RPS at p95 24ms.
S=/private/tmp/claude-501/-Users-zhengyuemeng-Desktop-resume/baed149c-86ff-469e-8dbe-7773d450fcc5/scratchpad/load
"$S/reset.sh" >/dev/null 2>&1
docker exec marthub-mysql-1 mysql -uroot -proot marthub -e "UPDATE flash_sale_items SET stock=500000;" 2>/dev/null
python3 "$S/reload_tokens.py"
docker run --rm --cpus=3.0 --network=marthub_default -e BASE=http://nginx:80 -e ITEM=${1:-101} \
  -e RATE=400 -e DURATION=25s -e PAIRS=/s/pairs.csv -v "$S:/s" grafana/k6:latest \
  run --quiet /s/order_ramp.js >/dev/null 2>&1
