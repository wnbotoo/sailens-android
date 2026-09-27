"""Storage rate, cadence and gap accounting for Sailens field captures (M0a).

    python capture_stats.py <capture dir or folder of captures> [--json]

For each capture: duration, bytes and bytes per hour (the storage rate the recording manual
quotes), frame cadence from camera timestamps, source-side gaps (frame sequence numbers the
capture never saw), drops the capture itself counted, per-sensor cadence and gaps, and whether the
counters balance. For a complete capture they must: framesOffered = framesEncoded +
framesDroppedByEncoder, and one stored frame per encoded frame. A gap no counter explains means the
device delivered nothing (or, for an incomplete capture, records lost at the failure boundary).
"""
from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sailens_capture import find_captures, load_capture  # noqa: E402

# An interval longer than this multiple of the median counts as a gap.
GAP_FACTOR = 3.0


def _percentile(sorted_values, q):
    if not sorted_values:
        return None
    index = min(len(sorted_values) - 1, max(0, round(q * (len(sorted_values) - 1))))
    return sorted_values[index]


def cadence(timestamps_nanos):
    """Median / p95 / max interval (ms) and gaps longer than GAP_FACTOR × median."""
    ts = sorted(timestamps_nanos)
    intervals = sorted((b - a) / 1e6 for a, b in zip(ts, ts[1:]) if b > a)
    if not intervals:
        return {"count": len(ts), "medianMs": None, "p95Ms": None, "maxMs": None, "gaps": 0, "rateHz": None}
    median = _percentile(intervals, 0.5)
    return {
        "count": len(ts),
        "medianMs": round(median, 3),
        "p95Ms": round(_percentile(intervals, 0.95), 3),
        "maxMs": round(intervals[-1], 3),
        "gaps": sum(1 for i in intervals if i > GAP_FACTOR * median),
        "rateHz": round(1000.0 / median, 2) if median > 0 else None,
    }


def directory_bytes(directory):
    total = 0
    for current, _, files in os.walk(directory):
        total += sum(os.path.getsize(os.path.join(current, f)) for f in files)
    return total


def summarize(directory):
    capture = load_capture(directory)
    m = capture.manifest
    stats = m.get("stats", {})
    frames = capture.frames

    # Duration on the elapsed-realtime clock (anchors), falling back to wall time.
    if len(capture.anchors) >= 2:
        duration_s = (capture.anchors[-1]["elapsedRealtimeNanos"] - capture.anchors[0]["elapsedRealtimeNanos"]) / 1e9
    elif m.get("endedWallMs"):
        duration_s = (m["endedWallMs"] - m["startedWallMs"]) / 1000.0
    else:
        duration_s = None
    size = directory_bytes(directory)

    seqs = [f["seq"] for f in frames]
    seq_gaps = sum(b - a - 1 for a, b in zip(seqs, seqs[1:]) if b - a > 1)
    stored_files = sum(1 for f in frames if os.path.exists(capture.frame_path(f)))

    checks = []
    if capture.complete:
        offered, encoded, dropped = (stats.get(k, 0) for k in ("framesOffered", "framesEncoded", "framesDroppedByEncoder"))
        if offered != encoded + dropped:
            checks.append(f"framesOffered {offered} != framesEncoded {encoded} + framesDroppedByEncoder {dropped}")
        if encoded != len(frames):
            checks.append(f"framesEncoded {encoded} but {len(frames)} frame records")
        if stats.get("sensorEvents", 0) != len(capture.sensors):
            checks.append(f"sensorEvents {stats.get('sensorEvents', 0)} but {len(capture.sensors)} sensor records")
    if stored_files != len(frames):
        checks.append(f"{len(frames) - stored_files} frame record(s) without an image file")

    # A jumped wall clock shows as anchors whose wall and elapsed differences disagree.
    wall_jumps = [
        round((b["wallMs"] - a["wallMs"]) - (b["elapsedRealtimeNanos"] - a["elapsedRealtimeNanos"]) / 1e6)
        for a, b in zip(capture.anchors, capture.anchors[1:])
    ]
    wall_jumps = [j for j in wall_jumps if abs(j) > 1000]

    sensors = {}
    for name in sorted({s["sensor"] for s in capture.sensors}):
        sensors[name] = cadence([s["timestampNanos"] for s in capture.sensors_of(name)])

    return {
        "sessionId": m.get("sessionId"),
        "directory": directory,
        "mode": capture.mode,
        "complete": capture.complete,
        "failureReason": m.get("failureReason"),
        "device": f"{m.get('deviceManufacturer')} {m.get('deviceModel')} (SDK {m.get('sdkInt')})",
        "timestampSource": (m.get("camera") or {}).get("timestampSource"),
        "durationS": round(duration_s, 1) if duration_s is not None else None,
        "bytes": size,
        "mbPerHour": round(size / 1e6 / (duration_s / 3600.0), 1) if duration_s else None,
        "frames": {
            **cadence([f["sensorTimestampNanos"] for f in frames]),
            "sourceSeqGaps": seq_gaps,
            "droppedByEncoder": stats.get("framesDroppedByEncoder", 0),
        },
        "sensorsAvailable": m.get("sensorsAvailable", []),
        "sensors": sensors,
        "sensorEventsDropped": stats.get("sensorEventsDropped", 0),
        "markers": len(capture.markers),
        "wallClockJumpsMs": wall_jumps,
        "counterProblems": checks,
        "warnings": capture.warnings,
    }


def print_summary(s):
    print(f"== {s['sessionId']}  [{s['mode']}]  {'complete' if s['complete'] else 'INCOMPLETE: ' + str(s['failureReason'])}")
    print(f"   {s['device']}, camera timestamp source: {s['timestampSource']}")
    print(f"   duration {s['durationS']} s, {s['bytes'] / 1e6:.1f} MB, {s['mbPerHour']} MB/hour")
    f = s["frames"]
    print(f"   frames {f['count']} @ {f['rateHz']} Hz (median {f['medianMs']} ms, p95 {f['p95Ms']}, max {f['maxMs']}), "
          f"gaps {f['gaps']}, source seq gaps {f['sourceSeqGaps']}, dropped by encoder {f['droppedByEncoder']}")
    for name, c in s["sensors"].items():
        print(f"   {name}: {c['count']} @ {c['rateHz']} Hz (median {c['medianMs']} ms, max {c['maxMs']}), gaps {c['gaps']}")
    missing = sorted(set(("gravity", "game_rotation_vector", "gyroscope")) - set(s["sensorsAvailable"]))
    if missing:
        print(f"   sensors not available: {', '.join(missing)}")
    print(f"   sensor events dropped by capture: {s['sensorEventsDropped']}, markers: {s['markers']}")
    for jump in s["wallClockJumpsMs"]:
        print(f"   ! wall clock changed by {jump} ms mid-session")
    for problem in s["counterProblems"]:
        print(f"   ! {problem}")
    for warning in s["warnings"]:
        print(f"   ~ {warning}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("path", help="a capture directory, or a folder containing captures")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    args = parser.parse_args(argv)
    summaries = [summarize(d) for d in find_captures(args.path)]
    if not summaries:
        print(f"no captures under {args.path}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(summaries, indent=2))
    else:
        for s in summaries:
            print_summary(s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
