"""Report Guidance timing from complete, unthinned on-device pipeline traces."""

import argparse
from collections import Counter, defaultdict
import json
from math import ceil, isfinite
from pathlib import Path
from statistics import median

NANOS_PER_MS = 1_000_000
NANOS_PER_SECOND = 1_000_000_000


def load_trace(path):
    with path.open(encoding="utf-8-sig") as stream:
        rows = [json.loads(line) for line in stream if line.strip()]
    if not rows or rows[0]["type"] != "session_start" or rows[-1]["type"] != "session_summary":
        raise ValueError("expected one complete session")
    start, end = rows[0], rows[-1]
    if not start.get("pipelineTimingMode"):
        raise ValueError("pipeline timing was disabled")
    frames = [row for row in rows[1:-1] if row["type"] == "pipeline_timing"]
    if len(frames) != end.get("pipelineTimingCount") or any(
        frame["seq"] != seq or frame["sessionId"] != start["sessionId"]
        for seq, frame in enumerate(frames, 1)
    ):
        raise ValueError("pipeline timing records are missing or belong to another session")
    if end["sessionId"] != start["sessionId"] or any(
        row["type"] in ("session_start", "session_summary") for row in rows[1:-1]
    ):
        raise ValueError("expected one session per trace file")
    return start, frames


def stats_ns(samples):
    ordered = sorted(samples)
    return {
        "n": len(ordered),
        "p50": round(median(ordered) / NANOS_PER_MS, 3),
        "p95": round(ordered[ceil(len(ordered) * 0.95) - 1] / NANOS_PER_MS, 3),
        "max": round(ordered[-1] / NANOS_PER_MS, 3),
    }


def measure(metadata, frames, warmup_seconds):
    if any("offeredNanos" not in frame or "receivedNanos" not in frame for frame in frames):
        raise ValueError("expected the uncapped live camera stream with ingress timing")
    if any(
        second["frameId"] <= first["frameId"] or second["receivedNanos"] <= first["receivedNanos"]
        for first, second in zip(frames, frames[1:])
    ):
        raise ValueError("non-monotonic frame IDs or receipt timestamps")
    if not frames:
        raise ValueError("no profiled frames")
    cutoff = frames[0]["receivedNanos"] + int(warmup_seconds * NANOS_PER_SECOND)
    steady = [frame for frame in frames if frame["receivedNanos"] >= cutoff]
    if len(steady) < 2:
        raise ValueError("need at least two frame receipts after warm-up")

    first, last = steady[0], steady[-1]
    begin, end = first["receivedNanos"], last["receivedNanos"]
    seconds = (end - begin) / NANOS_PER_SECOND
    # The right anchor is excluded from the receipt cohort and the throughput window.
    cohort = steady[:-1]
    offered = last["frameId"] - first["frameId"]
    counters = Counter(input=offered, dequeued=len(cohort))
    timings = defaultdict(list)
    dims = Counter()
    backends = defaultdict(Counter)

    def within(timestamp):
        return timestamp is not None and begin <= timestamp < end

    def sample(name, duration):
        if duration < 0:
            raise ValueError(f"negative {name} duration")
        timings[name].append(duration)

    def between(name, source, start, finish):
        if start in source and finish in source:
            sample(name, source[finish] - source[start])

    # Throughput is measured on completion events, all on the same elapsed-realtime window.
    previous_decision = None
    for frame in frames:
        decision = frame.get("decidedNanos")
        if within(decision):
            counters["decisions_in_window"] += 1
            if previous_decision is not None:
                sample("decisionInterval", decision - previous_decision)
            previous_decision = decision
        for label in ("sem", "det"):
            run = frame.get(label, {})
            counters[f"{label}_calls_returned_in_window"] += within(run.get("inferenceReturnedNanos"))
            counters[f"{label}_results_in_window"] += within(run.get("postprocessFinishedNanos"))

    for frame in cohort:
        dims[f"{frame['width']}x{frame['height']}"] += 1
        between("cameraReceiptToCopy", frame, "receivedNanos", "copyStartedNanos")
        between("cameraCopy", frame, "copyStartedNanos", "copyFinishedNanos")
        between("cameraCopyToOffer", frame, "copyFinishedNanos", "offeredNanos")
        between("queue", frame, "offeredNanos", "dequeuedNanos")
        between("geometryAndSetup", frame, "dequeuedNanos", "perceptionStartedNanos")
        between("perception", frame, "perceptionStartedNanos", "perceptionFinishedNanos")
        between("sceneAnalysis", frame, "perceptionFinishedNanos", "decisionStartedNanos")
        between("decision", frame, "decisionStartedNanos", "decidedNanos")
        between("decisionToRelease", frame, "decidedNanos", "releasedNanos")

        if "decidedNanos" in frame:
            counters["decided_from_input"] += 1
            decision = frame["decidedNanos"]
            sample("receiptToDecision", decision - frame["receivedNanos"])
            if frame.get("cameraClock") == "REALTIME":
                sample("sensorToReceipt", frame["receivedNanos"] - frame["cameraTimestampNanos"])
                sample("sensorToDecision", decision - frame["cameraTimestampNanos"])
            for label in ("sem", "det"):
                source = frame.get(f"{label}Source")
                if source is None:
                    continue
                if source["frameId"] > frame["frameId"]:
                    raise ValueError(f"{label} source frame is in the future")
                counters[f"{label}_reused_at_decision"] += source["frameId"] != frame["frameId"]
                if "receivedNanos" in source:
                    sample(f"{label}.sourceReceiptToDecision", decision - source["receivedNanos"])
                if source.get("cameraClock") == "REALTIME":
                    sample(f"{label}.sourceSensorToDecision", decision - source["cameraTimestampNanos"])

        pair = tuple(frame.get(label, {}).get("preprocessBackend", "absent") for label in ("sem", "det"))
        backends["preprocessPair"][" / ".join(pair)] += 1
        for label in ("sem", "det"):
            run = frame.get(label)
            if run is None:
                continue
            for field in ("preprocessBackend", "postprocessBackend"):
                if field in run:
                    backends[f"{label}.{field}"][run[field]] += 1
            between(f"{label}.dispatch", run, "queuedNanos", "runnerStartedNanos")
            between(f"{label}.runnerSetup", run, "runnerStartedNanos", "preprocessStartedNanos")
            between(f"{label}.inputWrite", run, "preprocessFinishedNanos", "inferenceStartedNanos")
            between(f"{label}.inferenceCall", run, "inferenceStartedNanos", "inferenceReturnedNanos")
            if "preprocessFinishedNanos" in run:
                duration = run["preprocessFinishedNanos"] - run["preprocessStartedNanos"]
                sample(f"{label}.preprocess", duration)
                sample(f"{label}.cacheWait", run["cacheWaitNanos"])
                sample(f"{label}.cacheCopy", run["cacheCopyNanos"])
                sample(f"{label}.preprocessOther", duration - run["cacheWaitNanos"] - run["cacheCopyNanos"])
            if "postprocessFinishedNanos" in run:
                output_access = 0
                for field in ("outputLock", "outputUnlock", "outputCopy"):
                    duration = run.get(f"{field}Nanos", 0)
                    sample(f"{label}.{field}", duration)
                    output_access += duration
                sample(f"{label}.outputAccess", output_access)
                duration = run["postprocessFinishedNanos"] - run["inferenceReturnedNanos"]
                sample(f"{label}.postprocess", duration - output_access)

    dequeued = counters["dequeued"]
    decided = counters["decided_from_input"]
    counters["mailbox_replaced"] = offered - dequeued
    counters["dequeued_without_decision"] = dequeued - decided
    return {
        "mode": metadata["pipelineTimingMode"],
        "hardware": metadata["targetHardwareProfile"],
        "seconds": round(seconds, 6),
        "counts": counters,
        "rates_per_second": {
            "camera_input": round(offered / seconds, 3),
            "decisions": round(counters["decisions_in_window"] / seconds, 3),
            **{
                f"{label}.{kind}": round(counters[f"{label}_{kind}_in_window"] / seconds, 3)
                for label in ("sem", "det")
                for kind in ("calls_returned", "results")
            },
        },
        "drop_percent": round(100 * (offered - decided) / offered, 3),
        "analysis_frame_dimensions": dims,
        "backends": dict(backends),
        "timing_ms": {key: stats_ns(samples) for key, samples in sorted(timings.items())},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", nargs="+", type=Path)
    parser.add_argument("--warmup", type=float, default=15.0, help="discard this many seconds of receipts first")
    args = parser.parse_args()
    if not isfinite(args.warmup) or args.warmup < 0:
        parser.error("--warmup must be finite and non-negative")
    reports = []
    for path in args.trace:
        try:
            metadata, frames = load_trace(path)
            reports.append({"trace": path.name, **measure(metadata, frames, args.warmup)})
        except (OSError, ValueError, KeyError, TypeError) as error:
            parser.error(f"{path}: {error}")
    print(json.dumps(reports, indent=2))


if __name__ == "__main__":
    main()
