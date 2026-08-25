import csv, subprocess
resp = []
for r in csv.DictReader(open('/private/tmp/claude-501/-Users-zhengyuemeng-Desktop-resume/baed149c-86ff-469e-8dbe-7773d450fcc5/scratchpad/load/pairs.csv')):
    k = f"fs:eligibility:{r['item']}:{r['user']}"; t = r['token']
    resp.append(f"*5\r\n$3\r\nSET\r\n${len(k)}\r\n{k}\r\n${len(t)}\r\n{t}\r\n$2\r\nEX\r\n$5\r\n36000\r\n")
subprocess.run(["docker","exec","-i","marthub-redis-1","redis-cli","--pipe"],
               input="".join(resp).encode(), capture_output=True)
