"""Tests for the capture tools, on synthetic captures written in the app's format.

    python -m unittest discover -s scripts/capture
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import capture_stats  # noqa: E402
import contact_sheet  # noqa: E402
import timing_align  # noqa: E402
from sailens_capture import UnsupportedCapture, load_capture  # noqa: E402

START_NS = 5_000_000_000
START_WALL = 1_700_000_000_000


def write_capture(root, session_id, mode="field_evidence", frames=(), sensors=(), markers=(), anchors=None,
                  complete=True, stats=None, extra_lines=None, manifest_overrides=None):
    directory = os.path.join(root, session_id)
    os.makedirs(os.path.join(directory, "frames"), exist_ok=True)
    manifest = {
        "schemaMajor": 1, "schemaMinor": 0, "sessionId": session_id, "captureMode": mode,
        "startedWallMs": START_WALL, "startedElapsedRealtimeNanos": START_NS,
        "deviceManufacturer": "Test", "deviceModel": "Phone", "sdkInt": 35,
        "camera": {"cameraId": "0", "capturedAtElapsedRealtimeNanos": START_NS, "timestampSource": "realtime"},
        "sensorsAvailable": ["gravity", "game_rotation_vector", "gyroscope"],
        "complete": complete, "pinned": False, "stats": stats or {},
    }
    manifest.update(manifest_overrides or {})
    with open(os.path.join(directory, "manifest.json"), "w") as f:
        json.dump(manifest, f)
    frame_lines = []
    for record, payload in frames:
        with open(os.path.join(directory, record["file"]), "wb") as f:
            f.write(payload)
        frame_lines.append(json.dumps({"type": "frame", **record}))
    files = {
        "frames.jsonl": frame_lines,
        "sensors.jsonl": [json.dumps({"type": "sensor", **s}) for s in sensors],
        "markers.jsonl": [json.dumps({"type": "marker", **m}) for m in markers],
        "anchors.jsonl": [json.dumps({"type": "clock_anchor", **a}) for a in (anchors or [
            {"wallMs": START_WALL, "elapsedRealtimeNanos": START_NS, "reason": "start"},
        ])],
    }
    for name, lines in (extra_lines or {}).items():
        files[name] = files.get(name, []) + lines
    for name, lines in files.items():
        with open(os.path.join(directory, name), "w") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))
    return directory


def luma_record(seq, t_ns, received_ns, w, h):
    return {"seq": seq, "sensorTimestampNanos": t_ns, "receivedElapsedRealtimeNanos": received_ns,
            "sourceWidth": w * 4, "sourceHeight": h * 4, "rotationDegrees": 90, "encoding": "luma8",
            "file": f"frames/{seq:06d}.y", "width": w, "height": h}


class ReaderTest(unittest.TestCase):
    def test_unknown_major_is_unsupported_and_torn_last_line_is_skipped(self):
        with tempfile.TemporaryDirectory() as root:
            future = write_capture(root, "future", manifest_overrides={"schemaMajor": 2})
            with self.assertRaises(UnsupportedCapture):
                load_capture(future)
            ok = write_capture(root, "ok", extra_lines={"markers.jsonl": ['{"type": "marker", "wal'],
                                                         "anchors.jsonl": ['{"type": "future_record"}']})
            capture = load_capture(ok)
            self.assertTrue(any("torn last line" in w for w in capture.warnings))
            self.assertTrue(any("future_record" in w for w in capture.warnings))


class StatsTest(unittest.TestCase):
    def test_rate_gaps_and_counter_balance(self):
        with tempfile.TemporaryDirectory() as root:
            frames = []
            for i, seq in enumerate([1, 2, 3, 7, 8]):  # seq 4-6 never reached the capture
                t = START_NS + i * 200_000_000 + (600_000_000 if seq >= 7 else 0)  # one 800 ms gap
                frames.append((luma_record(seq, t, t, 4, 3), bytes(12)))
            directory = write_capture(
                root, "s", frames=frames,
                stats={"framesOffered": 6, "framesEncoded": 5, "framesDroppedByEncoder": 1, "sensorEvents": 0},
                anchors=[{"wallMs": START_WALL, "elapsedRealtimeNanos": START_NS, "reason": "start"},
                         {"wallMs": START_WALL + 3_600_000, "elapsedRealtimeNanos": START_NS + 3_600 * 10**9, "reason": "end"}],
            )
            s = capture_stats.summarize(directory)
            self.assertEqual(s["frames"]["sourceSeqGaps"], 3)
            self.assertEqual(s["frames"]["gaps"], 1)
            self.assertEqual(s["frames"]["rateHz"], 5.0)
            self.assertEqual(s["counterProblems"], [])
            self.assertAlmostEqual(s["mbPerHour"], capture_stats.directory_bytes(directory) / 1e6, places=1)

    def test_unbalanced_counters_are_reported(self):
        with tempfile.TemporaryDirectory() as root:
            directory = write_capture(root, "s", stats={"framesOffered": 3, "framesEncoded": 1, "framesDroppedByEncoder": 0})
            problems = capture_stats.summarize(directory)["counterProblems"]
            self.assertTrue(any("framesOffered 3" in p for p in problems))


class ContactSheetTest(unittest.TestCase):
    def test_marker_sheet_centres_on_the_press_and_prompt_sheet_joins_the_offering_frame(self):
        with tempfile.TemporaryDirectory() as root:
            frames = []
            for seq in range(1, 11):  # 5 Hz, camera and receipt clocks 30 ms apart
                t = START_NS + seq * 200_000_000
                frames.append((luma_record(seq, t, t + 30_000_000, 8, 6), bytes(range(48))))
            marker = {"kind": "missed_alert", "wallMs": START_WALL + 1_000, "elapsedRealtimeNanos": START_NS + 1_030_000_000,
                      "lastStoredFrameSeq": 5, "source": "volume_down"}
            directory = write_capture(root, "s", frames=frames, markers=[marker])
            trace = os.path.join(root, "trace_s.jsonl")
            with open(trace, "w") as f:
                f.write(json.dumps({"type": "frame", "sequenceNumber": 8, "frameTimestamp": START_NS + 1_600_000_000}) + "\n")
                f.write(json.dumps({"type": "prompt_outcome", "eventId": "e1", "sourceSequenceNumber": 8,
                                    "messageKey": "event_person_ahead", "priority": "HIGH",
                                    "deliveredAt": START_WALL + 1_700, "deliveredVia": ["speech"]}) + "\n")

            capture = load_capture(directory)
            around = contact_sheet.frames_around(capture, marker["elapsedRealtimeNanos"], 0.45, "elapsed")
            self.assertEqual([f["seq"] for _, f in around], [3, 4, 5, 6, 7])
            self.assertAlmostEqual(min(abs(o) for o, _ in around), 0.0)

            out = os.path.join(root, "sheets")
            with redirect_stdout(io.StringIO()):
                code = contact_sheet.main([directory, "--trace", trace, "--prompts", "--markers", "--window", "0.5", "--out", out])
            self.assertEqual(code, 0)
            self.assertEqual(sorted(os.listdir(out)), ["s_marker_001.png", "s_prompt_e1.png"])


def synthetic_burst(root, offset_s=0.012, latency_s=0.028, focal=180.0, seconds=8.0, moving=True):
    """A timing_sync capture of a camera turning over a smooth texture. The frame at camera time t
    shows the orientation the gyroscope reports at t + offset_s; receipt = camera + latency_s."""
    rng = np.random.default_rng(7)
    size, crop_h, crop_w = 256, 96, 128
    spectrum = np.fft.fft2(rng.random((size, size)))
    fy, fx = np.meshgrid(np.fft.fftfreq(size), np.fft.fftfreq(size), indexing="ij")
    spectrum *= np.exp(-(fx ** 2 + fy ** 2) / (2 * 0.08 ** 2))  # smooth, trackable texture
    gain = 1.0 if moving else 0.0
    # (amplitude rad/s, frequency Hz, phase) per axis; angle is the analytic integral of the rate.
    x_terms = [(0.5 * gain, 0.37, 0.0), (0.25 * gain, 1.3, 1.0)]
    y_terms = [(0.4 * gain, 0.53, 0.5), (0.2 * gain, 1.7, 0.0)]

    def rate(terms, t):
        return sum(a * np.sin(2 * np.pi * f * t + p) for a, f, p in terms)

    def angle(terms, t):
        return sum(-a / (2 * np.pi * f) * np.cos(2 * np.pi * f * t + p) for a, f, p in terms)

    frames = []
    for i in range(int(seconds * 30)):
        t_cam = i / 30.0
        ax, ay = angle(x_terms, t_cam + offset_s), angle(y_terms, t_cam + offset_s)
        shifted = np.fft.ifft2(spectrum * np.exp(-2j * np.pi * (fx * focal * ay + fy * focal * ax))).real
        top, left = (size - crop_h) // 2, (size - crop_w) // 2
        crop = shifted[top:top + crop_h, left:left + crop_w]
        crop = np.clip((crop - crop.min()) / (np.ptp(crop) + 1e-12) * 255, 0, 255).astype(np.uint8)
        t_ns = START_NS + int(t_cam * 1e9)
        received = t_ns + int((latency_s + rng.normal(0, 0.001)) * 1e9)
        frames.append((luma_record(i + 1, t_ns, received, crop_w, crop_h), crop.tobytes()))
    gyro = []
    for k in range(int((seconds + 0.4) * 200)):
        t = -0.2 + k / 200.0
        gyro.append({"sensor": "gyroscope", "timestampNanos": START_NS + int(t * 1e9), "accuracy": 3,
                     "values": [rate(x_terms, t) + rng.normal(0, 0.005), rate(y_terms, t) + rng.normal(0, 0.005), 0.0]})
    return write_capture(root, "burst", mode="timing_sync", frames=frames, sensors=gyro)


class TimingAlignTest(unittest.TestCase):
    def test_recovers_a_known_offset_on_both_frame_clocks(self):
        for offset_ms in (12.0, -45.0):
            with self.subTest(offset_ms=offset_ms), tempfile.TemporaryDirectory() as root:
                result = timing_align.analyse(synthetic_burst(root, offset_s=offset_ms / 1000), max_lag_ms=100)
                self.assertAlmostEqual(result["camera"]["offsetMs"], offset_ms, delta=2.0)
                self.assertAlmostEqual(result["received"]["offsetMs"], offset_ms - 28.0, delta=3.0)
                self.assertGreater(result["camera"]["peakCorrelation"], 0.9)
                self.assertAlmostEqual(result["camera"]["focalPxEstimate"], 180.0, delta=180.0 * 0.15)
                self.assertFalse(result["warnings"])

    def test_a_burst_without_motion_asks_for_a_new_recording(self):
        with tempfile.TemporaryDirectory() as root:
            result = timing_align.analyse(synthetic_burst(root, moving=False), max_lag_ms=100)
        self.assertTrue(any("insufficient motion" in w for w in result["warnings"]))


if __name__ == "__main__":
    unittest.main()
