"""Storage rate, cadence and loss accounting for Sailens field captures (M0a).

    python capture_stats.py <capture dir or folder of captures> [--json]

For each capture: duration, bytes and bytes per hour (the storage rate the recording manual
quotes), frame cadence from camera timestamps, per-sensor cadence and gaps, and where frames were
lost. Frames can be lost at three places, and only two of them are counted:
- before the analyzer (the camera, or CameraX keeping only the latest image): the frame never gets
  a sequence number, so nothing counts it; for a timing_sync capture it is *estimated* from the
  camera-timestamp slots missing between stored frames that no sequence gap accounts for (the
  analyzer period comes from interval / sequence step, so steady loss after the analyzer cannot
  hide it; steady loss before the analyzer can, so check the period against the camera rate);
- in capture's own mailbox (timing_sync only): ``framesMissedBySubscriber``;
- in capture's encode queue: ``framesDroppedByEncoder``.
The known loss (the two counters) is reported on its own and never replaced by an estimate; the
overall fraction is known + estimated, so it is never lower than the known loss.
Field evidence samples about 5 frames a second, so its sequence and timestamp gaps are mostly by
design; only its encoder drops are losses.

For a complete capture the counters must balance: framesOffered = framesEncoded +
framesDroppedByEncoder, one frame record per encoded frame, and (timing_sync) the sequence gaps
between stored frames = framesMissedBySubscriber + framesDroppedByEncoder.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
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


def frame_losses(capture):
    """Where frames were lost; see the module docstring for the three places.

    Known loss (capture's mailbox + encoder queue) comes from the counters and is never guessed or
    overridden. Camera timestamps only estimate what no counter sees -- loss before the analyzer --
    and the overall fraction includes both, so it can never be lower than the known loss.
    """
    stats = capture.manifest.get("stats", {})
    frames = capture.frames
    dropped = stats.get("framesDroppedByEncoder", 0)
    missed = stats.get("framesMissedBySubscriber")
    losses = {
        "droppedByEncoder": dropped,
        "missedBySubscriber": missed,
        "knownLoss": None,
        "knownLossFraction": None,
        "analyzerPeriodMs": None,
        "upstreamEstimate": None,
        "overallLossFraction": None,
        "storedSeqGaps": None,
    }
    if capture.mode != "timing_sync" or len(frames) < 3:
        return losses
    known = (missed or 0) + dropped
    analyzer_frames = stats.get("framesEncoded", len(frames)) + known  # frames the analyzer offered capture
    steps = [(b["sensorTimestampNanos"] - a["sensorTimestampNanos"], b["seq"] - a["seq"]) for a, b in zip(frames, frames[1:])]
    steps = [(dt, ds) for dt, ds in steps if dt > 0 and ds > 0]
    if not steps:
        return losses
    # The analyzer's frame period: each interval divided by its sequence step. Steady loss *after*
    # the analyzer (say every other frame) leaves this intact; steady loss *before* it (the analyzer
    # itself sees every other camera frame) does not show here -- compare with the camera's rate.
    per_frame = sorted(dt / ds for dt, ds in steps)
    period = per_frame[len(per_frame) // 2]
    missing_slots = sum(max(0, round(dt / period) - 1) for dt, _ in steps)
    seq_gaps = sum(ds - 1 for _, ds in steps)
    # Seq gaps between stored frames are post-analyzer losses; the remaining missing slots had no
    # sequence number at all.
    upstream = max(0, missing_slots - seq_gaps)
    total = len(frames) + known + upstream
    losses.update(
        knownLoss=known,
        knownLossFraction=round(known / analyzer_frames, 3) if analyzer_frames else None,
        analyzerPeriodMs=round(period / 1e6, 3),
        upstreamEstimate=upstream,
        overallLossFraction=round((known + upstream) / total, 3),
        storedSeqGaps=seq_gaps,
    )
    return losses


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

    stored_files = sum(1 for f in frames if os.path.exists(capture.frame_path(f)))
    losses = frame_losses(capture)

    checks = []
    if capture.complete:
        offered, encoded, dropped = (stats.get(k, 0) for k in ("framesOffered", "framesEncoded", "framesDroppedByEncoder"))
        if offered != encoded + dropped:
            checks.append(f"framesOffered {offered} != framesEncoded {encoded} + framesDroppedByEncoder {dropped}")
        if encoded != len(frames):
            checks.append(f"framesEncoded {encoded} but {len(frames)} frame records")
        if stats.get("sensorEvents", 0) != len(capture.sensors):
            checks.append(f"sensorEvents {stats.get('sensorEvents', 0)} but {len(capture.sensors)} sensor records")
        if losses["storedSeqGaps"] is not None and losses["missedBySubscriber"] is not None:
            # Every missed or encoder-dropped frame leaves a hole between stored frames, except one
            # dropped before the first stored frame.
            counted = losses["missedBySubscriber"] + losses["droppedByEncoder"]
            if not counted - 1 <= losses["storedSeqGaps"] <= counted:
                checks.append(f"{losses['storedSeqGaps']} sequence gaps between stored frames, but {counted} frames counted as lost")
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
        "gitSha": m.get("gitSha"),
        "modelArtifacts": m.get("modelArtifacts", {}),
        "timestampSource": (m.get("camera") or {}).get("timestampSource"),
        "durationS": round(duration_s, 1) if duration_s is not None else None,
        "bytes": size,
        "mbPerHour": round(size / 1e6 / (duration_s / 3600.0), 1) if duration_s else None,
        "frames": {
            **cadence([f["sensorTimestampNanos"] for f in frames]),
            **losses,
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
    print(f"   {s['device']}, build {s['gitSha'] or 'unknown (no git SHA recorded)'}, camera timestamp source: {s['timestampSource']}")
    models = s["modelArtifacts"]
    print("   models: " + (", ".join(f"{name} {digest}" for name, digest in sorted(models.items())) or "not recorded"))
    print(f"   duration {s['durationS']} s, {s['bytes'] / 1e6:.1f} MB, {s['mbPerHour']} MB/hour")
    f = s["frames"]
    print(f"   frames {f['count']} @ {f['rateHz']} Hz (median {f['medianMs']} ms, p95 {f['p95Ms']}, max {f['maxMs']}), "
          f"timestamp gaps {f['gaps']}, dropped by encoder {f['droppedByEncoder']}")
    if f["knownLoss"] is not None:
        missed = f["missedBySubscriber"] if f["missedBySubscriber"] is not None else "not recorded"
        print(f"   frame loss: known {f['knownLossFraction'] * 100:.1f}% (capture mailbox {missed} + encoder {f['droppedByEncoder']}), "
              f"before the analyzer ~{f['upstreamEstimate']} (estimate, analyzer period {f['analyzerPeriodMs']} ms), "
              f"overall ~{f['overallLossFraction'] * 100:.1f}%")
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


def parse_baseline_tag(message):
    """Model hashes from a baseline tag's message: lines "model <file> sha256:<hex>"."""
    models = {}
    for line in message.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[0] == "model" and parts[2].startswith("sha256:"):
            models[parts[1]] = parts[2]
    return models


def read_baseline_tag(tag, repo="."):
    """(commit SHA, model hashes) of an annotated baseline tag, via git."""
    def run(*args):
        return subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True, text=True).stdout
    sha = run("rev-parse", f"{tag}^{{commit}}").strip()
    return sha, parse_baseline_tag(run("tag", "-l", "--format=%(contents)", tag))


def provenance_problems(summary, expected_sha, expected_models):
    """Why a capture does not come from the expected build: code commit and every model hash."""
    problems = []
    if expected_sha is not None and summary["gitSha"] != expected_sha:
        problems.append(f"built from {summary['gitSha'] or 'an unknown commit'}, baseline is {expected_sha}")
    recorded = summary["modelArtifacts"]
    for name, digest in sorted((expected_models or {}).items()):
        if recorded.get(name) != digest:
            problems.append(f"{name} is {recorded.get(name) or 'not recorded'}, baseline is {digest}")
    if expected_models:
        for name in sorted(set(recorded) - set(expected_models)):
            problems.append(f"{name} is packaged but not part of the baseline")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("path", help="a capture directory, or a folder containing captures")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    parser.add_argument("--baseline-tag", help="check every capture against this annotated tag: its commit "
                        "and the 'model <file> sha256:<hex>' lines in its message")
    parser.add_argument("--expect-git-sha", help="check every capture was built from this commit")
    parser.add_argument("--expect-model", action="append", default=[], metavar="FILE=sha256:HEX",
                        help="check every capture packaged this model (repeatable)")
    args = parser.parse_args(argv)
    summaries = [summarize(d) for d in find_captures(args.path)]
    if not summaries:
        print(f"no captures under {args.path}", file=sys.stderr)
        return 1

    expected_sha = args.expect_git_sha
    expected_models = dict(item.split("=", 1) for item in args.expect_model)
    if args.baseline_tag:
        expected_sha, expected_models = read_baseline_tag(args.baseline_tag)
        if not expected_models:
            print(f"{args.baseline_tag} lists no 'model <file> sha256:<hex>' lines", file=sys.stderr)
            return 1
    checking = expected_sha is not None or bool(expected_models)
    for s in summaries:
        s["provenanceProblems"] = provenance_problems(s, expected_sha, expected_models) if checking else []

    if args.json:
        print(json.dumps(summaries, indent=2))
    else:
        for s in summaries:
            print_summary(s)
            for problem in s["provenanceProblems"]:
                print(f"   ! NOT THE BASELINE: {problem}")
        if checking:
            bad = sum(1 for s in summaries if s["provenanceProblems"])
            print(f"== baseline check: {len(summaries) - bad} of {len(summaries)} captures match")
    return 3 if any(s["provenanceProblems"] for s in summaries) else 0


if __name__ == "__main__":
    sys.exit(main())
