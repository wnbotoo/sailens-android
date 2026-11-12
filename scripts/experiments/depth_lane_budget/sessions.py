"""Loads Guidance trace sessions (`files/traces/*.jsonl`) into per-session summaries, ordered by start.

Used by `analyze.py` and `idle.py` (docs/experiments/depth-lane-device-2026-11.md). Run alone, it prints one
line per session: start, duration, whether the depth lane ran, frames and depth publishes, and how the
lane's depth session ended.
"""
import datetime
import glob
import json
import os
import sys


def load(directory):
    sessions = []
    for path in glob.glob(os.path.join(directory, "*.jsonl")):
        s = {"file": os.path.basename(path), "frames": [], "depth": [], "safety": [], "prompts": []}
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                o = json.loads(line)
                t = o["type"]
                if t == "session_start":
                    s["start"] = o
                elif t == "session_summary":
                    s["summary"] = o
                elif t == "frame":
                    s["frames"].append(o)
                elif t == "depth_run":
                    s["depth"].append(o)
                elif t == "safety_state":
                    s["safety"].append(o)
                elif t == "prompt_outcome":
                    s["prompts"].append(o)
        s["startedAt"] = s["start"]["startedAt"]
        s["opened"] = s["start"]["openedElapsedMs"]
        s["closed"] = s.get("summary", {}).get("closedElapsedMs")
        s["durS"] = (s["closed"] - s["opened"]) / 1000 if s["closed"] else None
        caps = [f.get("capabilities", {}).get("depth_geometry", {}).get("status") for f in s["frames"]]
        # A session without the lane has no depth_run record and reports the capability as not configured.
        s["lane"] = "on" if s["depth"] else ("off" if all(c == "not_configured" for c in caps) else "?")
        s["closedRec"] = next((d for d in s["depth"] if d["outcome"] == "closed"), None)
        s["published"] = [d for d in s["depth"] if d["outcome"] == "published"]
        sessions.append(s)
    sessions.sort(key=lambda x: x["startedAt"])
    return sessions


def wall(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S")


if __name__ == "__main__":
    for i, s in enumerate(load(sys.argv[1]), 1):
        c = s["closedRec"] or {}
        print(f"{i:2d} {wall(s['startedAt'])} dur={s['durS']:7.1f}s lane={s['lane']:3s} frames={len(s['frames']):5d} "
              f"pub={len(s['published']):4d} final={c.get('finalState', '-'):12s} "
              f"cancel={c.get('cancelledStage', '-')}/{c.get('cancelledRunId', '-')} gen={c.get('generation', '-')}")
