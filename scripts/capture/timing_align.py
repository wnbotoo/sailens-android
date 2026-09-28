"""Frame-to-gyroscope time alignment from a timing-sync burst (M0a).

    python timing_align.py <timing_sync capture dir> [--max-lag-ms 300] [--json] [--diagnose-camera-clock]

Record the burst by arming it on the field capture page, standing still, starting Guidance, and at
once turning the phone in place (briskly left-right about once a second, then up-down, pivoting at
the wrist) at a detailed scene a few metres away for the ~15 s it lasts. Walking forward is not
turning: the gyroscope sees almost nothing and the burst is rejected.

Method. Between consecutive frames the image moves by about f·θ, θ being the camera rotation about
the two axes across the optical axis (roll moves nothing at the centre). So:
1. image motion per frame pair: phase correlation of the two luma frames (magnitude, pixels);
2. rotation per the same interval: the gyroscope's x/y rates (device axes; the back camera looks
   along −z) integrated between the two frame times shifted by a candidate offset δ;
3. δ is the lag that best correlates the two series (Pearson), refined to sub-sample.
Magnitudes are used, so no camera-to-device axis mapping is needed.

Result, per frame time base: ``gyro_time ≈ frame_time + δ``.
- camera timestamps (``sensorTimestampNanos``) are measured **only when the camera's timestamp
  source is REALTIME**, the one Android defines as sharing SensorEvent's timebase. Then δ is about
  half the exposure plus half the rolling-shutter readout (the camera stamps the start of exposure;
  the image moves mid-exposure). For UNKNOWN (monotonic, unspecified origin) or an unreported
  source, the camera clock is never qualified as gyro-comparable, however good a correlation would
  look; ``--diagnose-camera-clock`` estimates it anyway, re-centred on the receipt clock, and marks
  the result diagnostic only;
- source receipt times (``receivedElapsedRealtimeNanos``) are on elapsedRealtime by construction:
  δ is negative by the camera-to-app delivery latency, and jitter in it lowers the correlation.
  This is the authoritative measurement when the camera clock is not REALTIME.
The estimate is repeated on four consecutive parts of the burst; their spread is the error bar (hand
motion is smooth, so the correlation peak is broad) and would also reveal drift. It does not catch
a consistent bias. On synthetic bursts (gyro at 200 Hz and at 50 Hz, the typical SENSOR_DELAY_GAME
rate) the estimate is within ~1.5 ms of the truth: that is the method's numerical accuracy only,
not a measurement floor on a device. The real error comes from the device's actual gyro cadence
and from repeated bursts (their spread), measured per target device in M0a.

``usableForQualification`` (exit code 0, else 2) needs a complete capture, no sensor samples
dropped by capture, the phone really turning (median gyro rate across the optical axis >= 0.3
rad/s), enough texture, and on the authoritative clock: correlation r >= 0.5 (a
negative control -- real image motion unrelated to the gyro -- gives about 0.3), all four parts
checked and agreeing within 5 ms, and the offset not at the search edge. Otherwise record the burst
again. Heavy frame loss is a warning. The thresholds are conservative until real bursts from the
target devices are in: a false rejection only costs another burst.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture_stats import frame_losses  # noqa: E402
from sailens_capture import load_capture, read_image  # noqa: E402

MIN_PEAK = 0.05  # phase-correlation peak below which a pair is too blurred or bare to use
MIN_PAIRS = 30
# Median gyro rate across the optical axis while frames were stored. Brisk turning is ~1-3 rad/s;
# holding the phone steady while walking gave 0.01-0.09 on SM8850.
MIN_TURN_RATE = 0.3
SEGMENTS = 4
MAX_SEGMENT_SPREAD_MS = 5.0
MAX_FRAME_LOSS = 0.10
# Pearson r between image motion and gyro rotation on the authoritative clock. Conservative until the
# two target devices give real numbers: a false rejection only costs recording the burst again.
MIN_QUALIFICATION_CORRELATION = 0.5


def phase_shift(a, b, window):
    """(dy, dx, peak) that best maps frame a onto frame b, sub-pixel."""
    fa = np.fft.fft2((a - a.mean()) * window)
    fb = np.fft.fft2((b - b.mean()) * window)
    cross = fa * np.conj(fb)
    cross /= np.abs(cross) + 1e-12
    surface = np.fft.ifft2(cross).real
    py, px = np.unravel_index(np.argmax(surface), surface.shape)
    h, w = surface.shape

    def refine(minus, centre, plus):
        # Gaussian (log-parabola) fit: less pixel-locking than a plain parabola, which biased the
        # offset by ~2 ms on synthetic bursts.
        minus, centre, plus = (np.log(max(v, 1e-9)) for v in (minus, centre, plus))
        denominator = minus - 2 * centre + plus
        return 0.0 if denominator == 0 else 0.5 * (minus - plus) / denominator

    dy = py + refine(surface[(py - 1) % h, px], surface[py, px], surface[(py + 1) % h, px])
    dx = px + refine(surface[py, (px - 1) % w], surface[py, px], surface[py, (px + 1) % w])
    dy = dy - h if dy > h / 2 else dy
    dx = dx - w if dx > w / 2 else dx
    return dy, dx, float(surface[py, px])


def image_motion(capture):
    frames = [f for f in capture.frames if f["encoding"] == "luma8"]
    if len(frames) < 2:
        raise SystemExit("no luma frames: is this a timing_sync capture?")
    first = read_image(capture, frames[0], upright=False).astype(np.float64)
    window = np.outer(np.hanning(first.shape[0]), np.hanning(first.shape[1]))
    previous, rows = first, []
    for a, b in zip(frames, frames[1:]):
        current = read_image(capture, b, upright=False).astype(np.float64)
        dy, dx, peak = phase_shift(previous, current, window)
        rows.append((a, b, float(np.hypot(dx, dy)), peak))
        previous = current
    return rows


def cumulative_rotation(gyro):
    """Times (ns) and cumulative x/y rotation (rad), trapezoid-integrated."""
    t = np.array([s["timestampNanos"] for s in gyro], dtype=np.float64)
    w = np.array([s["values"][:2] for s in gyro], dtype=np.float64)
    dt = np.diff(t) / 1e9
    theta = np.vstack([np.zeros(2), np.cumsum(0.5 * (w[1:] + w[:-1]) * dt[:, None], axis=0)])
    return t, theta


def rotation_between(t, theta, start, end):
    """|θ⊥| between two times (ns arrays), by linear interpolation of the cumulative rotation."""
    dx = np.interp(end, t, theta[:, 0]) - np.interp(start, t, theta[:, 0])
    dy = np.interp(end, t, theta[:, 1]) - np.interp(start, t, theta[:, 1])
    return np.hypot(dx, dy)


def best_lag(start, end, motion, t, theta, lags, step_ms):
    """(lag ms, correlation) maximising Pearson r between image motion and gyro rotation."""
    scores = []
    for lag in lags:
        rotation = rotation_between(t, theta, start + lag * 1e6, end + lag * 1e6)
        scores.append(np.corrcoef(motion, rotation)[0, 1] if rotation.std() > 0 and motion.std() > 0 else -1.0)
    scores = np.nan_to_num(np.array(scores), nan=-1.0)
    best = int(np.argmax(scores))
    lag = lags[best]
    if 0 < best < len(lags) - 1:
        m, c, p = scores[best - 1:best + 2]
        denominator = m - 2 * c + p
        if denominator != 0:
            lag += 0.5 * (m - p) / denominator * step_ms
    return float(lag), float(scores[best]), best in (0, len(lags) - 1)


def align(pairs, t, theta, key, max_lag_ms, step_ms=0.25, segments=SEGMENTS):
    start = np.array([a[key] for a, _, _, _ in pairs], dtype=np.float64)
    end = np.array([b[key] for _, b, _, _ in pairs], dtype=np.float64)
    motion = np.array([m for _, _, m, _ in pairs])
    lags = np.arange(-max_lag_ms, max_lag_ms + step_ms / 2, step_ms)
    usable = (start + lags[0] * 1e6 >= t[0]) & (end + lags[-1] * 1e6 <= t[-1])
    if usable.sum() < MIN_PAIRS:
        return None
    start, end, motion = start[usable], end[usable], motion[usable]
    lag, peak, at_edge = best_lag(start, end, motion, t, theta, lags, step_ms)
    # The same estimate on consecutive parts of the burst: their spread is an honest error bar
    # (smooth hand motion gives a broad correlation peak) and would also show drift.
    parts = [
        best_lag(start[i], end[i], motion[i], t, theta, lags, step_ms)[0]
        for i in np.array_split(np.arange(len(start)), segments)
        if len(i) >= MIN_PAIRS // 2
    ]
    rotation = rotation_between(t, theta, start + lag * 1e6, end + lag * 1e6)
    focal = float((motion @ rotation) / (rotation @ rotation)) if rotation @ rotation > 0 else float("nan")
    return {
        "offsetMs": round(lag, 2),
        "peakCorrelation": round(peak, 3),
        "segmentOffsetsMs": [round(p, 1) for p in parts],
        "segmentSpreadMs": round(float(np.std(parts)), 2) if len(parts) > 1 else None,
        "pairs": int(usable.sum()),
        "focalPxEstimate": round(focal, 1),
        "atSearchEdge": at_edge,
    }


def analyse(directory, max_lag_ms=300.0, diagnose_camera_clock=False):
    capture = load_capture(directory)
    warnings = list(capture.warnings)
    if capture.mode != "timing_sync":
        warnings.append(f"capture mode is '{capture.mode}', not timing_sync")
    gyro = capture.sensors_of("gyroscope")
    if len(gyro) < 10:
        raise SystemExit("no gyroscope samples in this capture")
    source = (capture.manifest.get("camera") or {}).get("timestampSource")
    # Android's contract: only REALTIME camera timestamps share SensorEvent's timebase. UNKNOWN is
    # monotonic with an unspecified origin; it must never be qualified as gyro-comparable, however
    # good a correlation looks.
    camera_comparable = source == "realtime"

    pairs = image_motion(capture)
    good = [p for p in pairs if p[3] >= MIN_PEAK]
    moving = sum(1 for p in good if p[2] > 0.5)
    t, theta = cumulative_rotation(gyro)
    # Whether the phone actually turned is the gyroscope's call: image motion cannot tell turning
    # from walking forward, and on a real device noise alone reaches about half a pixel per frame.
    first, last = capture.frames[0]["sensorTimestampNanos"], capture.frames[-1]["sensorTimestampNanos"]
    during = [s["values"] for s in gyro if first <= s["timestampNanos"] <= last]
    turn_rate = float(np.median([np.hypot(v[0], v[1]) for v in during])) if during else 0.0
    frame_dt = np.diff([f["sensorTimestampNanos"] for f in capture.frames]) / 1e6
    losses = frame_losses(capture)
    stats = capture.manifest.get("stats", {})
    result = {
        "sessionId": capture.manifest.get("sessionId"),
        "timestampSource": source,
        "cameraComparable": camera_comparable,
        # The clock whose offset M0 may record as the frame-to-gyro alignment.
        "authoritativeClock": "camera" if camera_comparable else "received",
        "frames": len(capture.frames),
        "frameIntervalMedianMs": round(float(np.median(frame_dt)), 2) if len(frame_dt) else None,
        "gyroIntervalMedianMs": round(float(np.median(np.diff(t) / 1e6)), 2),
        # Known from capture's counters, and known + estimated loss before the analyzer.
        "knownFrameLossFraction": losses["knownLossFraction"],
        "overallFrameLossFraction": losses["overallLossFraction"],
        "sensorEventsDropped": stats.get("sensorEventsDropped", 0),
        "pairsUsable": len(good),
        "pairsMoving": moving,
        "turnRateMedianRadPerS": round(turn_rate, 3),
        "camera": None,
        "received": None,
        "usableForQualification": False,
        "warnings": warnings,
    }

    blocking = []  # reasons this burst cannot qualify: record it again
    if not capture.complete:
        blocking.append("the capture is incomplete")
    if result["sensorEventsDropped"]:
        blocking.append(f"{result['sensorEventsDropped']} sensor samples were dropped by capture")
    if turn_rate < MIN_TURN_RATE:
        blocking.append(
            f"insufficient motion: the phone barely turned (median {turn_rate:.2f} rad/s, need {MIN_TURN_RATE}); stand still "
            "and turn it in place, briskly left-right about once a second, then up-down. Walking forward does not count"
        )
    if len(good) < MIN_PAIRS:
        blocking.append("too little texture or too much blur: point at a detailed scene a few metres away")
    if losses["overallLossFraction"] is not None and losses["overallLossFraction"] > MAX_FRAME_LOSS:
        warnings.append(
            f"~{losses['overallLossFraction'] * 100:.0f}% of frames were lost: known {losses['knownLossFraction'] * 100:.0f}% "
            f"(capture mailbox {losses['missedBySubscriber']}, encoder {losses['droppedByEncoder']}), before the analyzer "
            f"~{losses['upstreamEstimate']} (estimate); lower confidence"
        )

    result["received"] = align(good, t, theta, "receivedElapsedRealtimeNanos", max_lag_ms)
    if camera_comparable:
        result["camera"] = align(good, t, theta, "sensorTimestampNanos", max_lag_ms)
    else:
        warnings.append(
            f"camera timestamp source is {source or 'not reported'}: camera timestamps are not comparable with the "
            "gyroscope; receipt time is the authoritative measurement"
        )
        if diagnose_camera_clock:
            # Empirical only: re-centre the camera clock on the receipt clock, then correlate
            # locally. Never a qualification result.
            coarse = float(np.median([f["receivedElapsedRealtimeNanos"] - f["sensorTimestampNanos"] for f in capture.frames]))
            shifted = [
                ({**a, "sensorTimestampNanos": a["sensorTimestampNanos"] + coarse},
                 {**b, "sensorTimestampNanos": b["sensorTimestampNanos"] + coarse}, m, peak)
                for a, b, m, peak in good
            ]
            diagnostic = align(shifted, t, theta, "sensorTimestampNanos", max_lag_ms)
            if diagnostic:
                diagnostic["offsetMs"] = round(diagnostic["offsetMs"] + coarse / 1e6, 2)
                diagnostic["diagnosticOnly"] = True
            result["camera"] = diagnostic

    authoritative = result[result["authoritativeClock"]]
    for name in ("camera", "received"):
        r = result[name]
        if not r:
            continue
        problems = []
        if r["atSearchEdge"]:
            problems.append(f"{name}: best offset is at the search edge; widen --max-lag-ms")
        if r["peakCorrelation"] < MIN_QUALIFICATION_CORRELATION:
            problems.append(f"{name}: image motion and gyroscope correlate only r={r['peakCorrelation']} "
                            f"(< {MIN_QUALIFICATION_CORRELATION}): moving people, sliding instead of turning, blur or repetitive texture")
        if len(r["segmentOffsetsMs"]) < SEGMENTS or r["segmentSpreadMs"] is None:
            problems.append(f"{name}: only {len(r['segmentOffsetsMs'])} of {SEGMENTS} parts of the burst could be checked; record a longer burst")
        elif r["segmentSpreadMs"] > MAX_SEGMENT_SPREAD_MS:
            problems.append(f"{name}: parts of the burst disagree by {r['segmentSpreadMs']} ms (drift, or too little motion in parts)")
        (blocking if r is authoritative else warnings).extend(problems)
    if authoritative is None:
        blocking.append(f"not enough overlapping frame and gyroscope data on the {result['authoritativeClock']} clock")

    result["usableForQualification"] = not blocking
    result["warnings"] = blocking + warnings
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("capture", help="a timing_sync capture directory")
    parser.add_argument("--max-lag-ms", type=float, default=300.0)
    parser.add_argument("--diagnose-camera-clock", action="store_true",
                        help="also estimate a non-REALTIME camera clock empirically (diagnostic only)")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    r = analyse(args.capture, args.max_lag_ms, args.diagnose_camera_clock)
    exit_code = 0 if r["usableForQualification"] else 2
    if args.json:
        print(json.dumps(r, indent=2))
        return exit_code
    comparable = "comparable with the gyroscope" if r["cameraComparable"] else "NOT comparable with the gyroscope"
    print(f"== {r['sessionId']}  camera timestamp source: {r['timestampSource']} ({comparable})")
    print(f"   {r['frames']} frames, median interval {r['frameIntervalMedianMs']} ms, frame loss known {r['knownFrameLossFraction']} / overall ~{r['overallFrameLossFraction']}; "
          f"gyro median interval {r['gyroIntervalMedianMs']} ms, sensor samples dropped {r['sensorEventsDropped']}")
    print(f"   frame pairs usable {r['pairsUsable']}, of which moving {r['pairsMoving']}; phone turn rate median {r['turnRateMedianRadPerS']} rad/s")
    for name, label in (("camera", "camera timestamp"), ("received", "source receipt time")):
        a = r[name]
        if a is None:
            not_measured = name == "camera" and not r["cameraComparable"]
            print(f"   {label}: {'not measured' if not_measured else 'not enough overlapping data'}")
            continue
        if a.get("diagnosticOnly"):
            tag = "  [DIAGNOSTIC ONLY]"
        elif name == r["authoritativeClock"]:
            tag = "  [authoritative]"
        else:
            tag = ""
        print(f"   {label}: gyro = frame {a['offsetMs']:+.2f} ms +/- {a['segmentSpreadMs']} (r={a['peakCorrelation']}, "
              f"per segment {a['segmentOffsetsMs']}, pairs {a['pairs']}, focal ~{a['focalPxEstimate']} px){tag}")
    print(f"   usable for M0 qualification: {'yes' if r['usableForQualification'] else 'NO - record the burst again'}")
    for warning in r["warnings"]:
        print(f"   ! {warning}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
