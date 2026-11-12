"""The depth lane's device budget from the trace sessions of the protocol in docs/experiments/depth-lane-device-2026-11.md.

Sessions are taken in start order: 3 pairs lane off / on (90 s each), one 15-minute session with the lane, 10 cold
starts with it and 10 without, then 10 stop/restarts with it (5 stopped within 1-2 s, 5 after about 10 s).
Usage: analyze.py <directory of trace_*.jsonl>
"""
import collections
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sessions as b

ss = b.load(sys.argv[1])
assert len(ss) == 37, len(ss)
G = {
    "A_off": [ss[i] for i in (0, 2, 4)],
    "A_on": [ss[i] for i in (1, 3, 5)],
    "A15": [ss[6]],
    "B_on": ss[7:17],
    "B_off": ss[17:27],
    "C_short": ss[27:32],
    "C_long": ss[32:37],
}
for k, v in G.items():
    lanes = {s["lane"] for s in v}
    assert len(lanes) == 1, (k, lanes)


def pct(xs, p):
    xs = sorted(xs)
    if not xs:
        return float("nan")
    k = (len(xs) - 1) * p / 100
    lo, hi = math.floor(k), math.ceil(k)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def dist(xs):
    return f"p50={pct(xs,50):6.1f} p95={pct(xs,95):6.1f} max={max(xs) if xs else float('nan'):6.1f} n={len(xs)}"


def sem(f):
    return f["semanticPreprocessMs"] + f["semanticInferenceMs"] + f["semanticOutputReadMs"] + f["semanticPostprocessMs"]


def det(f):
    return f["obstaclePreprocessMs"] + f["obstacleInferenceMs"] + f["obstacleOutputReadMs"] + f["obstaclePostprocessMs"]


def frame_stats(sessions, frames_of=lambda s: s["frames"]):
    fr = [f for s in sessions for f in frames_of(s)]
    out = {}
    out["sem"] = [sem(f) for f in fr]
    out["det"] = [det(f) for f in fr]
    out["inference"] = [f["inferenceMs"] for f in fr]
    out["total"] = [f["totalPipelineMs"] for f in fr]
    out["r2d"] = [f["receiptToDecisionMs"] for f in fr]
    iv = []
    for s in sessions:
        fs = frames_of(s)
        iv += [f["pipelineOutputIntervalMs"] for f in fs[1:]]
    out["interval"] = iv
    drops = sum(f["droppedFramesSinceLast"] for f in fr)
    out["dropRate"] = drops / (drops + len(fr)) if fr else float("nan")
    out["fps"] = len(fr) / sum((s["durS"] or 0) for s in sessions) if frames_of is FRAMES else float("nan")
    return out


FRAMES = lambda s: s["frames"]


def report(name, st):
    print(f"[{name}] drop={st['dropRate']*100:5.2f}%  fps={st['fps']:5.2f}")
    for k in ("sem", "det", "inference", "total", "r2d"):
        print(f"   {k:9s} {dist(st[k])}")
    iv = st["interval"]
    print(f"   interval  {dist(iv)}  >500ms={sum(1 for x in iv if x > 500)}")


print("=== Frame budget (sem/det, drops, receipt->decision, output interval) ===")
stats = {}
for k in G:
    stats[k] = frame_stats(G[k], FRAMES)
    report(k, stats[k])


def ratio(a, b_):
    return (a / b_ - 1) * 100


print("\n=== Lane on against the alternating baseline A_off ===")
base = stats["A_off"]
for on in ("A_on", "A15"):
    st = stats[on]
    for k in ("sem", "det", "inference", "total", "r2d"):
        print(f"  {on:5s} {k:9s} p95 {pct(st[k],95):6.1f} vs {pct(base[k],95):6.1f}  -> {ratio(pct(st[k],95), pct(base[k],95)):+6.1f}%")
    print(f"  {on:5s} drop {st['dropRate']*100:5.2f}% vs {base['dropRate']*100:5.2f}% -> {(st['dropRate']-base['dropRate'])*100:+5.2f} pp")
    print(f"  {on:5s} stalls>500 {sum(1 for x in st['interval'] if x > 500)} (base {sum(1 for x in base['interval'] if x > 500)}), max interval {max(st['interval'])} (base {max(base['interval'])})")

print("\n=== Per-session p95 (sem, det, r2d), drop%, fps ===")
for i, s in enumerate(ss[:17], 1):
    st = frame_stats([s], FRAMES)
    print(f"  {i:2d} lane={s['lane']:3s} dur={s['durS']:6.1f} sem95={pct(st['sem'],95):5.1f} det95={pct(st['det'],95):5.1f} r2d95={pct(st['r2d'],95):6.1f} drop={st['dropRate']*100:5.2f}% fps={st['fps']:5.2f} maxIv={max(st['interval'])}")


def first_result(s):
    init = s["safety"][0]
    t0 = init["stateSinceElapsedMs"]
    f = s["frames"][0]
    decided = f["receivedElapsedMs"] + f["receiptToDecisionMs"]
    guiding = next((x for x in s["safety"] if x["state"] == "guiding"), None)
    return decided - t0, (guiding["elapsedMs"] - t0) if guiding else None, s["opened"] - t0


print("\n=== Cold start: first Guidance result (ms from start of initializing) ===")
for k in ("B_on", "B_off", "C_short", "C_long"):
    fr = [first_result(s) for s in G[k]]
    a = [x[0] for x in fr]
    g = [x[1] for x in fr if x[1] is not None]
    print(f"  {k:7s} firstDecision {dist(a)} | guidingState {dist(g)} | each={[round(x) for x in a]}")
on = [first_result(s)[0] for s in G["B_on"]]
off = [first_result(s)[0] for s in G["B_off"]]
print(f"  B_on - B_off: median {pct(on,50)-pct(off,50):+.1f} ms, max {max(on)-max(off):+.1f} ms")


def compile_end(s):
    """Elapsed time of the last frame whose depth capability still says initializing (the model compiling)."""
    t = [f["receivedElapsedMs"] for f in s["frames"]
         if "initializing" in f.get("capabilities", {}).get("depth_geometry", {}).get("issues", [])]
    return max(t) if t else None


print("\n=== Depth compile window (B_on) vs the same window from start in B_off ===")
cw = []
for s in G["B_on"]:
    e = compile_end(s)
    cw.append(e - s["opened"])
print(f"  compile window ms: {dist(cw)}")
mcw = pct(cw, 50)
win_on = frame_stats(G["B_on"], lambda s: [f for f in s["frames"] if f["receivedElapsedMs"] <= compile_end(s)])
win_off = frame_stats(G["B_off"], lambda s: [f for f in s["frames"] if f["receivedElapsedMs"] - s["opened"] <= mcw])
report("B_on compile window", win_on)
report("B_off same window", win_off)
rest_on = frame_stats(G["B_on"], lambda s: [f for f in s["frames"] if f["receivedElapsedMs"] > compile_end(s)])
report("B_on after compile", rest_on)
report("B_off whole", stats["B_off"])

print("\n=== Depth runs ===")
for k in ("A_on", "A15", "B_on", "C_short", "C_long"):
    pub = [d for s in G[k] for d in s["published"]]
    if not pub:
        print(f"  {k}: no published runs")
        continue
    gpu = [d["inferMs"] + d["outputReadMs"] for d in pub]
    print(f"  {k:7s} gpu(infer+read) {dist(gpu)}")
    for fld in ("prepareMs", "admitWaitMs", "inferMs", "outputReadMs", "fitMs"):
        print(f"           {fld:12s} {dist([d[fld] for d in pub])}")
    tot = [d["prepareMs"] + d["admitWaitMs"] + d["inferMs"] + d["outputReadMs"] + d["fitMs"] for d in pub]
    print(f"           total        {dist(tot)}")
    ages = [f["depthAgeMs"] for s in G[k] for f in s["frames"] if "depthAgeMs" in f]
    if ages:
        print(f"           obsAge       {dist(ages)}  >667={sum(1 for a in ages if a > 667)}")
    rates = [len(s["published"]) / s["durS"] for s in G[k]]
    print(f"           publish/s per session {[round(r, 2) for r in rates]}")
    vals = collections.Counter(d["validity"] for d in pub)
    print(f"           validity {dict(vals)} shapes {set(d['modelShape'] for d in pub)} sel {set(d['acceleratorSelection'] for d in pub)}")

pub = [d for s in ss for d in s["published"]]
gpu = [d["inferMs"] + d["outputReadMs"] for d in pub]
print(f"  ALL     gpu(infer+read) {dist(gpu)}  >60={sum(1 for x in gpu if x > 60)} >35={sum(1 for x in gpu if x > 35)}")
print(f"  ALL     fit {dist([d['fitMs'] for d in pub])}")
ages = [f["depthAgeMs"] for s in ss for f in s["frames"] if "depthAgeMs" in f]
print(f"  ALL     obsAge {dist(ages)}")

print("\n=== 15-minute session over time (per 60 s) ===")
s = ss[6]
t0 = s["opened"]
buckets = collections.defaultdict(lambda: {"pub": 0, "gpu": [], "fit": [], "frames": 0, "drops": 0, "sem": [], "age": []})
for d in s["published"]:
    bk = buckets[(d["atElapsedMs"] - t0) // 60000]
    bk["pub"] += 1
    bk["gpu"].append(d["inferMs"] + d["outputReadMs"])
    bk["fit"].append(d["fitMs"])
for f in s["frames"]:
    bk = buckets[(f["receivedElapsedMs"] - t0) // 60000]
    bk["frames"] += 1
    bk["drops"] += f["droppedFramesSinceLast"]
    bk["sem"].append(sem(f))
    if "depthAgeMs" in f:
        bk["age"].append(f["depthAgeMs"])
for m in sorted(buckets):
    bk = buckets[m]
    print(f"  min {m:2d}: pub/s={bk['pub']/60:4.2f} gpu95={pct(bk['gpu'],95):5.1f} fit95={pct(bk['fit'],95):6.1f} fps={bk['frames']/60:5.2f} "
          f"drop={bk['drops']/max(1,bk['drops']+bk['frames'])*100:5.2f}% sem95={pct(bk['sem'],95):5.1f} age95={pct(bk['age'],95):6.1f}")

print("\n=== Stop/restart: late publishes, cross-session leakage, failures ===")
problems = []
for i, s in enumerate(ss, 1):
    if s["lane"] != "on":
        continue
    c = s["closedRec"]
    gen = c["generation"]
    after = [d for d in s["depth"] if d["seq"] > c["seq"]]
    late = [d for d in s["published"] if d["atElapsedMs"] > c["atElapsedMs"]]
    othergen = [d for d in s["depth"] if d["generation"] != gen]
    runs = {d["runId"] for d in s["published"]}
    stray = {f["depthRunId"] for f in s["frames"] if "depthRunId" in f} - runs
    firstpub = min((d["atElapsedMs"] for d in s["published"]), default=None)
    early = [f for f in s["frames"] if f.get("capabilities", {}).get("depth_geometry", {}).get("status") == "available"
             and (firstpub is None or f["receivedElapsedMs"] + f["receiptToDecisionMs"] < firstpub - 1)]
    if c.get("failuresTotal"):
        problems.append((i, "failures", c["failuresTotal"]))
    if after or late or othergen or stray or early:
        problems.append((i, len(after), len(late), len(othergen), stray, len(early)))
    if s["closed"] is not None and c["atElapsedMs"] > s["closed"]:
        problems.append((i, "closed record after trace close"))
print("  problems:", problems or "none")
gens = [s["closedRec"]["generation"] for s in ss if s["lane"] == "on"]
print("  generations in order:", gens, "monotonic" if gens == sorted(gens) and len(set(gens)) == len(gens) else "NOT monotonic")
print("  cancel stages:", collections.Counter(s["closedRec"].get("cancelledStage", "none") for s in ss if s["lane"] == "on"))
print("  final states:", collections.Counter(s["closedRec"]["finalState"] for s in ss if s["lane"] == "on"))

print("\n=== Safety states, prompts ===")
print("  states:", collections.Counter(x["state"] for s in ss for x in s["safety"]))
print("  stalls:", collections.Counter(x["stall"] for s in ss for x in s["safety"]))
print("  phases:", collections.Counter(x.get("phase") for s in ss for x in s["safety"]))
print("  prompts per group:", {k: sum(len(s["prompts"]) for s in v) for k, v in G.items()})
print("  depth caps (all frames):", collections.Counter(
    (f.get("capabilities", {}).get("depth_geometry", {}).get("status"), tuple(f.get("capabilities", {}).get("depth_geometry", {}).get("issues", [])))
    for s in ss for f in s["frames"]))
print("  frame quality:", collections.Counter(f.get("qualification") for s in ss for f in s["frames"]))
