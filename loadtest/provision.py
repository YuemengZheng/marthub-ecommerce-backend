#!/usr/bin/env python3
"""Prepare N distinct buyers for a load run.

uq_user_item allows one order per (user, item), so a capacity test of the order write path needs a
fresh user for every request it intends to succeed. Reusing users would measure the bought-replay
fast path -- one Redis GET -- instead of the database write, and would look far faster for the
wrong reason.

Sessions have to be created through the application: Spring Session stores a JDK-serialised
security context, so they cannot be forged from a script. Eligibility tokens are plain Redis
strings and are bulk-loaded directly, which also keeps the unthrottled /eligibility endpoint out of
the provisioning cost.
"""
import concurrent.futures as cf
import os, subprocess, sys, time, urllib.request, uuid

BASE = os.environ.get("PROV_BASE", "http://localhost:8390")
ITEM = int(os.environ.get("PROV_ITEM", "101"))
NITEMS = int(os.environ.get("PROV_NITEMS", "1"))
N = int(os.environ.get("PROV_USERS", "1000"))
FIRST = int(os.environ.get("PROV_FIRST_USER", "2000000"))
OUT = os.environ.get("PROV_OUT", "pairs.csv")
WORKERS = int(os.environ.get("PROV_WORKERS", "48"))

import http.client, threading, urllib.parse
_local = threading.local()
_host = urllib.parse.urlparse(BASE)

def _conn():
    # One kept-alive connection per worker thread. Opening a TCP connection per login made
    # provisioning the slowest part of the whole exercise for no reason.
    c = getattr(_local, "c", None)
    if c is None:
        c = _local.c = http.client.HTTPConnection(_host.hostname, _host.port or 80, timeout=15)
    return c

def login(uid):
    for _ in range(5):
        try:
            c = _conn()
            c.request("POST", f"/api/auth/demo-login?userId={uid}&name=b{uid}")
            r = c.getresponse()
            sid = r.getheader("X-Auth-Token")
            r.read()
            if sid: return uid, sid
        except Exception:
            try: _local.c.close()
            except Exception: pass
            _local.c = None
            time.sleep(0.05)
    return uid, None

t0 = time.time()
pairs = []
with cf.ThreadPoolExecutor(max_workers=WORKERS) as ex:
    for uid, sid in ex.map(login, range(FIRST, FIRST + N)):
        if sid: pairs.append((uid, sid, uuid.uuid4().hex))
took = time.time() - t0
print(f"  sessions: {len(pairs)}/{N} in {took:.1f}s ({len(pairs)/took:.0f}/s)", flush=True)
if len(pairs) < N:
    print(f"  WARNING: {N - len(pairs)} logins failed", file=sys.stderr)

# Tokens straight into Redis. RESP protocol via --pipe so 100k keys cost one round trip, not 100k.
resp = []
for idx, (uid, _, tok) in enumerate(pairs):
    key = f"fs:eligibility:{ITEM + idx % NITEMS}:{uid}"
    resp.append(f"*5\r\n$3\r\nSET\r\n${len(key)}\r\n{key}\r\n${len(tok)}\r\n{tok}\r\n$2\r\nEX\r\n$5\r\n36000\r\n")
p = subprocess.run(["docker", "exec", "-i", "marthub-redis-1", "redis-cli", "--pipe"],
                   input="".join(resp).encode(), capture_output=True)
print("  tokens:", p.stdout.decode().strip().splitlines()[-1] if p.stdout else p.stderr.decode()[:120])

with open(OUT, "w") as f:
    f.write("user,session,token,item\n")
    for idx, (uid, sid, tok) in enumerate(pairs):
        f.write(f"{uid},{sid},{tok},{ITEM + idx % NITEMS}\n")
print(f"  wrote {OUT}: {len(pairs)} buyers")
