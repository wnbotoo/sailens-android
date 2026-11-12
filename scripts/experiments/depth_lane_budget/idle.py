"""Sampler rows (sampler.ps1) taken while no Guidance session ran, 3 s margin, grouped into idle stretches; then the peaks.

Usage: idle.py <directory of trace_*.jsonl> <sampler csv>
"""
import csv
import datetime
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sessions as b

ss = b.load(sys.argv[1])
day = datetime.date.fromtimestamp(ss[0]["startedAt"] / 1000)
spans = [(s["startedAt"] / 1000 - 3, s["startedAt"] / 1000 + (s["durS"] or 0) + 3, i + 1) for i, s in enumerate(ss)]
rows = list(csv.DictReader(open(sys.argv[2], encoding="utf-8-sig")))
groups = []
for r in rows:
    t = datetime.datetime.combine(day, datetime.time.fromisoformat(r["wall"])).timestamp()
    inside = next((n for a, z, n in spans if a <= t <= z), None)
    after = max((n for a, z, n in spans if z < t), default=0)
    key = ("run", inside) if inside else ("idle", after)
    if not groups or groups[-1][0] != key:
        groups.append([key, []])
    groups[-1][1].append(r)
for key, rs in groups:
    if key[0] != "idle":
        continue
    def col(c):
        return [int(r[c]) for r in rs if r[c]]
    print(f"idle after session {key[1]:2d}: {rs[0]['wall']}-{rs[-1]['wall']} n={len(rs):3d} threads={min(col('threads'))}-{max(col('threads'))} "
          f"gfxMB={min(col('graphicsKb'))/1024:.0f}-{max(col('graphicsKb'))/1024:.0f} glKB={min(col('glMtrackKb'))}-{max(col('glMtrackKb'))} "
          f"pssMB={min(col('totalPssKb'))/1024:.0f}-{max(col('totalPssKb'))/1024:.0f} T={rs[-1]['batteryTempC']}")
allr = [r for r in rows if r["threads"]]
print("peak threads", max(int(r["threads"]) for r in allr), "peak gfxMB", max(int(r["graphicsKb"]) for r in allr) / 1024,
      "peak pssMB", max(int(r["totalPssKb"]) for r in allr) / 1024, "peak T", max(float(r["batteryTempC"]) for r in allr),
      "thermal", sorted({r["thermalStatus"] for r in rows}))
