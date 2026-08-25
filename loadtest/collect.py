#!/usr/bin/env python3
"""Server-side sampling during a run. Client latency alone cannot say which component saturated."""
import json, subprocess, sys, threading, time

def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout

def mysql_status():
    out = sh("docker","exec","marthub-mysql-1","mysql","-uroot","-proot","-N","-e",
             "SHOW GLOBAL STATUS WHERE Variable_name IN "
             "('Com_select','Com_update','Com_insert','Com_commit','Threads_connected',"
             "'Threads_running','Innodb_row_lock_waits','Innodb_row_lock_time','Aborted_clients')")
    return {k: int(v) for k, v in (l.split("\t") for l in out.strip().splitlines() if "\t" in l)}

def redis_info():
    out = sh("docker","exec","marthub-redis-1","redis-cli","INFO","stats")
    out += sh("docker","exec","marthub-redis-1","redis-cli","INFO","memory")
    d = {}
    for line in out.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            if k in ("instantaneous_ops_per_sec","total_commands_processed","used_memory","evicted_keys","rejected_connections"):
                d[k] = int(v.strip())
    return d

def docker_cpu():
    out = sh("docker","stats","--no-stream","--format","{{.Name}}\t{{.CPUPerc}}\t{{.MemPerc}}")
    d = {}
    for line in out.strip().splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0].startswith("marthub-"):
            d[parts[0].replace("marthub-","").replace("-1","")] = {
                "cpu": float(parts[1].rstrip("%")), "mem": float(parts[2].rstrip("%"))}
    return d

samples = []
stop = threading.Event()
def loop():
    while not stop.is_set():
        try:
            samples.append({"t": round(time.time(),2), "mysql": mysql_status(),
                            "redis": redis_info(), "containers": docker_cpu()})
        except Exception as e:
            samples.append({"t": round(time.time(),2), "error": str(e)})
        stop.wait(2.0)

if __name__ == "__main__":
    th = threading.Thread(target=loop, daemon=True); th.start()
    try:
        time.sleep(float(sys.argv[1]))
    finally:
        stop.set(); th.join(timeout=5)
        json.dump(samples, open(sys.argv[2], "w"), indent=1)
        print(f"  collected {len(samples)} samples -> {sys.argv[2]}")
