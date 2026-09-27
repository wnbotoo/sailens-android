"""Frame-to-gyroscope time alignment from a timing-sync burst (M0a).

    python timing_align.py <timing_sync capture dir> [--max-lag-ms 300] [--json]

Record the burst by arming it on the field capture page, starting Guidance, and turning the phone
slowly left-right then up-down at a detailed scene for the ~15 s it lasts.

Method. Between consecutive frames the image moves by about f·θ, θ being the camera rotation about
the two axes across the optical axis (roll moves nothing at the centre). So:
1. image motion per frame pair: phase correlation of the two luma frames (magnitude, pixels);
2. rotation per the same interval: the gyroscope's x/y rates (device axes; the back camera looks
   along −z) integrated between the two frame times shifted by a candidate offset δ;
3. δ is the lag that best correlates the two series (Pearson), refined to sub-sample.
Magnitudes are used, so no camera-to-device axis mapping is needed.

Result, per frame time base: ``gyro_time ≈ frame_time + δ``.
- camera timestamps (``sensorTimestampNanos``): δ≈0 is expected only for timestamp source
  REALTIME, and even then is offset by about half the exposure plus half the rolling-shutter
  readout (the camera stamps the start of exposure; the image moves mid-exposure);
- source receipt times (``receivedElapsedRealtimeNanos``): δ is negative by the camera-to-app
  delivery latency; jitter in that latency lowers its correlation.
The estimate is repeated on four consecutive parts of the burst; their spread is the error bar (hand
motion is smooth, so the correlation peak is broad) and would also reveal drift. It does not catch
a consistent bias: on synthetic bursts the estimate is within ~1.5 ms of the truth, so treat
anything under ~2 ms as zero.
"Insufficient motion" means record again.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sailens_capture import load_capture, read_image  # noqa: E402

MIN_PEAK = 0.05  # phase-correlation peak below which a pair is too blurred or bare to use
MIN_PAIRS = 30
MIN_MOVING_FRACTION = 0.3  # of pairs whose image moved by more than half a pixel
SEGMENTS = 4
MAX_SEGMENT_SPREAD_MS = 5.0


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


def analyse(directory, max_lag_ms=300.0):
    capture = load_capture(directory)
    if capture.mode != "timing_sync":
        print(f"~ capture mode is '{capture.mode}', not timing_sync; results will be weak", file=sys.stderr)
    gyro = capture.sensors_of("gyroscope")
    if len(gyro) < 10:
        raise SystemExit("no gyroscope samples in this capture")
    pairs = image_motion(capture)
    good = [p for p in pairs if p[3] >= MIN_PEAK]
    moving = sum(1 for p in good if p[2] > 0.5)
    t, theta = cumulative_rotation(gyro)
    frame_dt = np.diff([f["sensorTimestampNanos"] for f in capture.frames]) / 1e6
    gyro_dt = np.diff(t) / 1e6
    result = {
        "sessionId": capture.manifest.get("sessionId"),
        "timestampSource": (capture.manifest.get("camera") or {}).get("timestampSource"),
        "frames": len(capture.frames),
        "frameIntervalMedianMs": round(float(np.median(frame_dt)), 2) if len(frame_dt) else None,
        "gyroIntervalMedianMs": round(float(np.median(gyro_dt)), 2),
        "pairsUsable": len(good),
        "pairsMoving": moving,
        "warnings": list(capture.warnings),
        "camera": None,
        "received": None,
    }
    if len(good) < MIN_PAIRS or moving < MIN_MOVING_FRACTION * len(good):
        result["warnings"].append("insufficient motion or texture: record again, turning the phone steadily at a detailed scene")
    result["camera"] = align(good, t, theta, "sensorTimestampNanos", max_lag_ms)
    result["received"] = align(good, t, theta, "receivedElapsedRealtimeNanos", max_lag_ms)
    for name in ("camera", "received"):
        r = result[name]
        if r and r["atSearchEdge"]:
            result["warnings"].append(f"{name}: best offset is at the search edge; widen --max-lag-ms")
        if r and r["segmentSpreadMs"] is not None and r["segmentSpreadMs"] > MAX_SEGMENT_SPREAD_MS:
            result["warnings"].append(
                f"{name}: parts of the burst disagree by {r['segmentSpreadMs']} ms (drift, or too little motion in some parts)"
            )
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("capture", help="a timing_sync capture directory")
    parser.add_argument("--max-lag-ms", type=float, default=300.0)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    r = analyse(args.capture, args.max_lag_ms)
    if args.json:
        print(json.dumps(r, indent=2))
        return 0
    print(f"== {r['sessionId']}  camera timestamp source: {r['timestampSource']}")
    print(f"   {r['frames']} frames, median interval {r['frameIntervalMedianMs']} ms; gyro median interval {r['gyroIntervalMedianMs']} ms")
    print(f"   frame pairs usable {r['pairsUsable']}, of which moving {r['pairsMoving']}")
    for name, label in (("camera", "camera timestamp"), ("received", "source receipt time")):
        a = r[name]
        if a is None:
            print(f"   {label}: not enough overlapping data")
            continue
        print(f"   {label}: gyro ≈ frame {a['offsetMs']:+.2f} ms ± {a['segmentSpreadMs']} (r={a['peakCorrelation']}, "
              f"per segment {a['segmentOffsetsMs']}, pairs {a['pairs']}, focal ≈ {a['focalPxEstimate']} px)")
    for warning in r["warnings"]:
        print(f"   ! {warning}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
