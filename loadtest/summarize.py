import json, sys
S='/private/tmp/claude-501/-Users-zhengyuemeng-Desktop-resume/baed149c-86ff-469e-8dbe-7773d450fcc5/scratchpad/load'
r = sys.argv[1]
k = json.load(open(f'{S}/out/k6_{r}.json'))['metrics']
d = k.get('order_duration', {})
ok = int(k.get('orders_created',{}).get('count',0))
rp = int(k.get('replayed',{}).get('count',0))
rej = int(k.get('rejected',{}).get('count',0))
err = int(k.get('server_errors',{}).get('count',0))
tot = ok+rp+rej+err
print(f"   实测 {k['http_reqs']['rate']:.0f}/s   p50={d.get('med',0):.0f}  p95={d.get('p(95)',0):.0f}  "
      f"p99={d.get('p(99)',0):.0f}  max={d.get('max',0):.0f} ms")
st=" ".join(f"{n.replace('status_','')}={int(v['count'])}" for n,v in k.items() if n.startswith('status_') and v.get('count',0)>0)
print(f"   状态码明细 {st or '(无)'}")
print(f"   新建订单 {ok}   拒绝 {rej}   5xx {err} ({100*err/max(tot,1):.2f}%)   "
      f"丢弃迭代 {int(k.get('dropped_iterations',{}).get('count',0))}")
s = json.load(open(f'{S}/out/srv_{r}.json'))
mid = [x for x in s if 'mysql' in x]
if len(mid) >= 4:
    a, b = mid[1], mid[-2]; dt = b['t']-a['t']
    print(f"   MySQL commit {int((b['mysql']['Com_commit']-a['mysql']['Com_commit'])/dt)}/s  "
          f"conn峰值 {max(x['mysql']['Threads_connected'] for x in mid)}  "
          f"running峰值 {max(x['mysql']['Threads_running'] for x in mid)}  "
          f"行锁等待 +{b['mysql']['Innodb_row_lock_waits']-a['mysql']['Innodb_row_lock_waits']}")
    peak = {}
    for x in mid:
        for n,v in x.get('containers',{}).items(): peak[n]=max(peak.get(n,0),v['cpu'])
    print("   CPU峰值 " + " ".join(f"{n}={v:.0f}%" for n,v in sorted(peak.items())))
