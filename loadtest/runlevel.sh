#!/bin/bash
# One level: reset to a clean state, sample the servers, drive open-loop load, summarise.
RATE=$1; DUR=$2; TAG=$3
S=/private/tmp/claude-501/-Users-zhengyuemeng-Desktop-resume/baed149c-86ff-469e-8dbe-7773d450fcc5/scratchpad/load

# Every level starts from the same state, or a level inherits the previous one's sold-out flag
# and measures the cheap rejection path instead.
"$S/reset.sh"
python3 "$S/reload_tokens.py" >/dev/null
sleep 2

SECS=$(echo "$DUR" | tr -d 's')
python3 "$S/collect.py" $((SECS+4)) "$S/out/srv_$TAG.json" >/dev/null &
COL=$!
sleep 1
docker run --rm --cpus=3.0 --network=marthub_default \
  -e BASE=http://nginx:80 -e ITEM=${ITEM_MODE:-101} -e RATE=$RATE -e DURATION=$DUR -e PAIRS=/s/pairs.csv \
  -v "$S:/s" grafana/k6:latest run --quiet --summary-export=/s/out/k6_$TAG.json /s/order_ramp.js >/dev/null 2>&1
wait $COL
