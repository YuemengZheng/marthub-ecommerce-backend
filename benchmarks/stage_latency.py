"""Stage decomposition by difference, straight against app1 so nginx is not in the path.

  A  GET  /api/shops/1                     public, warm L1  -> 0 Redis calls
  B  GET  /api/auth/me                     + session read and save
  C  POST /internal/benchmark/.../admission + full admission, no MySQL

session   ~= B - A
admission ~= C - B

The subtraction is not exact -- three different handlers serialise different bodies -- so
these are stage costs to an order of magnitude, not to a decimal place. That is enough to
answer which stage is worth optimising, which is the only question being asked.
"""
import json, os, statistics, time, urllib.request, urllib.error

# One instance directly, NOT Nginx: the edge rate limit would throttle a tight
# measurement loop and the numbers would describe limit_req instead. Requires the
# app containers to be published -- see the README on the local override.
BASE = os.environ.get("MARTHUB_INSTANCE_URL", "http://localhost:8091")
N, WARM = 400, 60

def call(method, path, headers=None, want=(200, 204)):
    req = urllib.request.Request(BASE + path, method=method, headers=headers or {})
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            body, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        body, status = e.read(), e.code
    ms = (time.perf_counter() - t) * 1000
    if status not in want:
        raise SystemExit(f"{method} {path} -> {status} {body[:200]!r}")
    return ms, body

def login(uid):
    req = urllib.request.Request(f"{BASE}/api/auth/demo-login?userId={uid}&name=p{uid}", method="POST")
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.headers["X-Auth-Token"]

def sample(label, method, path, headers=None, want=(200, 204)):
    for _ in range(WARM):
        call(method, path, headers, want)
    xs = sorted(call(method, path, headers, want)[0] for _ in range(N))
    return {"stage": label, "p50": round(statistics.median(xs), 3),
            "p95": round(xs[int(0.95 * len(xs))], 3), "mean": round(statistics.fmean(xs), 3)}

sess = login(901)
auth = {"X-Auth-Token": sess}
tok = json.loads(call("POST", "/api/flash-sale/101/eligibility", auth)[1])["token"]

rows = [
    sample("A  公开读(0 次 Redis,warm L1)", "GET", "/api/shops/1"),
    sample("B  A + session 读写", "GET", "/api/auth/me", auth),
    sample("C  B + 完整准入(无 MySQL)", "POST",
           "/internal/benchmark/flash-sale/admission?itemId=101",
           {**auth, "X-Eligibility-Token": tok}, want=(204, 400, 409)),
]
w = max(len(r["stage"]) for r in rows)
print(f"{'':<{w}}   {'p50':>8} {'p95':>8} {'mean':>8}")
for r in rows:
    print(f"{r['stage']:<{w}}   {r['p50']:>8} {r['p95']:>8} {r['mean']:>8}")
print()
a, b, c = rows
print(f"  session   阶段  ≈ B-A  p50 {b['p50']-a['p50']:+.3f} ms   p95 {b['p95']-a['p95']:+.3f} ms")
print(f"  admission 阶段  ≈ C-B  p50 {c['p50']-b['p50']:+.3f} ms   p95 {c['p95']-b['p95']:+.3f} ms")
