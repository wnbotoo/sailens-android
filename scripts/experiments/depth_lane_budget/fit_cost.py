"""The ground fit's cost from the depth lane's trace sessions, before and after a change to the fitter.

Each directory holds one build's sessions with the lane on, run as the pairs of docs/experiments/depth-lane-device-2026-11.md
§1 (90 s each, the phone on a stand facing a fixed indoor scene); sessions without the lane are left out. Per directory: the fit's wall time (`fitMs`), its own CPU time (`fitCpuUs`, where the build records it), the per-run
difference between the two (the wall time that was not the fit's CPU time), the observation age on the traced frames,
sem and det, and the drop rate.
Usage: fit_cost.py <directory of trace_*.jsonl> [<another directory> ...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sessions as b


def pct(xs, p):
    xs = sorted(xs)
    if not xs:
        return float("nan")
    rank = max(1, -(-len(xs) * p // 100))  # nearest rank, as the trace report
    return xs[rank - 1]


def dist(xs):
    return f"p50={pct(xs, 50):7.1f} p95={pct(xs, 95):7.1f} max={max(xs) if xs else float('nan'):7.1f} n={len(xs)}"


def sem(f):
    return f["semanticPreprocessMs"] + f["semanticInferenceMs"] + f["semanticOutputReadMs"] + f["semanticPostprocessMs"]


def det(f):
    return f["obstaclePreprocessMs"] + f["obstacleInferenceMs"] + f["obstacleOutputReadMs"] + f["obstaclePostprocessMs"]


for directory in sys.argv[1:]:
    ss = [s for s in b.load(directory) if s["lane"] == "on"]
    pub = [d for s in ss for d in s["published"]]
    frames = [f for s in ss for f in s["frames"]]
    print(f"== {directory}: {len(ss)} sessions with the lane, {len(pub)} published runs")
    print(f"  fit wall ms        {dist([d['fitMs'] for d in pub])}")
    cpu = [d for d in pub if "fitCpuUs" in d]
    if cpu:
        print(f"  fit CPU ms         {dist([d['fitCpuUs'] / 1000 for d in cpu])}")
        # Per run, then the percentiles: never the difference of two percentiles.
        print(f"  wall - CPU ms      {dist([d['fitMs'] - d['fitCpuUs'] / 1000 for d in cpu])}")
    else:
        print("  fit CPU ms         not recorded by this build")
    print(f"  publish/s          {[round(len(s['published']) / s['durS'], 2) for s in ss]}")
    ages = [f["depthAgeMs"] for f in frames if "depthAgeMs" in f]
    print(f"  observation age ms {dist([abs(a) for a in ages])}")
    print(f"  sem ms             {dist([sem(f) for f in frames])}")
    print(f"  det ms             {dist([det(f) for f in frames])}")
    drops = sum(f["droppedFramesSinceLast"] for f in frames)
    print(f"  drop rate          {drops / (drops + len(frames)) * 100 if frames else float('nan'):.1f} %")
