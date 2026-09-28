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
from sailens_capture import UnsupportedCapture, find_captures, load_capture  # noqa: E402

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
    def test_losses_are_attributed_to_where_they_happened(self):
        with tempfile.TemporaryDirectory() as root:
            period = 33_000_000
            # Seq 11-13 were converted but lost in capture (2 mailbox, 1 encoder); one more frame never
            # reached the analyzer (no sequence number): seq 15 comes two camera slots after seq 14.
            layout = [(s, s) for s in range(1, 11)] + [(14, 14)] + [(s, s + 1) for s in range(15, 21)]
            frames = [(luma_record(seq, START_NS + slot * period, START_NS + slot * period, 4, 3), bytes(12))
                      for seq, slot in layout]
            directory = write_capture(
                root, "s", mode="timing_sync", frames=frames,
                stats={"framesOffered": 18, "framesEncoded": 17, "framesDroppedByEncoder": 1,
                       "framesMissedBySubscriber": 2, "sensorEvents": 0},
                anchors=[{"wallMs": START_WALL, "elapsedRealtimeNanos": START_NS, "reason": "start"},
                         {"wallMs": START_WALL + 3_600_000, "elapsedRealtimeNanos": START_NS + 3_600 * 10**9, "reason": "end"}],
            )
            s = capture_stats.summarize(directory)
            f = s["frames"]
            self.assertAlmostEqual(f["analyzerPeriodMs"], 33.0)
            self.assertEqual(f["knownLoss"], 3)
            self.assertAlmostEqual(f["knownLossFraction"], 3 / 20, places=3)
            self.assertEqual(f["missedBySubscriber"], 2)
            self.assertEqual(f["droppedByEncoder"], 1)
            self.assertEqual(f["upstreamEstimate"], 1)
            self.assertEqual(f["storedSeqGaps"], 3)
            self.assertAlmostEqual(f["overallLossFraction"], 4 / 21, places=3)
            self.assertEqual(s["counterProblems"], [])
            self.assertAlmostEqual(s["mbPerHour"], capture_stats.directory_bytes(directory) / 1e6, places=1)

    def test_steady_loss_is_never_hidden_by_the_cadence(self):
        with tempfile.TemporaryDirectory() as root:
            # 30 Hz camera; capture keeps up with only every other frame, every time.
            period = 33_000_000
            frames = [(luma_record(seq, START_NS + seq * period, START_NS + seq * period, 4, 3), bytes(12))
                      for seq in range(1, 61, 2)]
            directory = write_capture(root, "s", mode="timing_sync", frames=frames,
                                      stats={"framesOffered": 30, "framesEncoded": 30, "framesDroppedByEncoder": 0,
                                             "framesMissedBySubscriber": 29})
            f = capture_stats.summarize(directory)["frames"]
            self.assertAlmostEqual(f["medianMs"], 66.0)  # the cadence alone would call this normal
            self.assertAlmostEqual(f["analyzerPeriodMs"], 33.0)
            self.assertGreaterEqual(f["knownLossFraction"], 0.45)
            self.assertGreaterEqual(f["overallLossFraction"], f["knownLossFraction"])
            self.assertEqual(f["upstreamEstimate"], 0)

    def test_field_evidence_gaps_are_not_reported_as_losses(self):
        with tempfile.TemporaryDirectory() as root:
            frames = [(luma_record(seq, START_NS + seq * 200_000_000, START_NS, 4, 3), bytes(12)) for seq in (1, 7, 13)]
            directory = write_capture(root, "s", frames=frames,
                                      stats={"framesOffered": 3, "framesEncoded": 3, "framesDroppedByEncoder": 0})
            f = capture_stats.summarize(directory)["frames"]
            self.assertIsNone(f["knownLoss"])
            self.assertIsNone(f["upstreamEstimate"])

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


def synthetic_burst(root, offset_s=0.012, latency_s=0.028, focal=180.0, seconds=8.0, moving=True,
                    camera_origin_ns=0, timestamp_source="realtime", stats=None, gyro_hz=200.0,
                    gyro_independent=False, keep_every=1, gyro_still=False):
    """A timing_sync capture of a camera turning over a smooth texture. The frame at camera time t
    shows the orientation the gyroscope reports at t + offset_s; receipt = camera + latency_s. A
    non-REALTIME camera clock is modelled by camera_origin_ns added to every camera timestamp.
    gyro_independent: the gyroscope reports a different motion than the images show (a negative
    control: real image motion, no relation to the gyro). keep_every=2 stores every other frame (the
    rest lost in capture), sequence numbers staying those of all frames. gyro_still: the images move
    but the phone does not turn, as when walking forward holding it steady."""
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
        if i % keep_every:
            continue
        t_cam = i / 30.0
        ax, ay = angle(x_terms, t_cam + offset_s), angle(y_terms, t_cam + offset_s)
        shifted = np.fft.ifft2(spectrum * np.exp(-2j * np.pi * (fx * focal * ay + fy * focal * ax))).real
        top, left = (size - crop_h) // 2, (size - crop_w) // 2
        crop = shifted[top:top + crop_h, left:left + crop_w]
        crop = np.clip((crop - crop.min()) / (np.ptp(crop) + 1e-12) * 255, 0, 255).astype(np.uint8)
        t_ns = START_NS + int(t_cam * 1e9)
        received = t_ns + int((latency_s + rng.normal(0, 0.001)) * 1e9)
        frames.append((luma_record(i + 1, t_ns + camera_origin_ns, received, crop_w, crop_h), crop.tobytes()))
    gyro = []
    gyro_x, gyro_y = (y_terms[::-1], [(a, f * 1.37, p + 2.0) for a, f, p in x_terms]) if gyro_independent else (x_terms, y_terms)
    if gyro_still:
        gyro_x, gyro_y = [(0.01, 0.37, 0.0)], [(0.01, 0.53, 0.5)]
    for k in range(int((seconds + 0.4) * gyro_hz)):
        t = -0.2 + k / gyro_hz
        gyro.append({"sensor": "gyroscope", "timestampNanos": START_NS + int(t * 1e9), "accuracy": 3,
                     "values": [rate(gyro_x, t) + rng.normal(0, 0.005), rate(gyro_y, t) + rng.normal(0, 0.005), 0.0]})
    camera = {"cameraId": "0", "capturedAtElapsedRealtimeNanos": START_NS, "timestampSource": timestamp_source}
    return write_capture(root, "burst", mode="timing_sync", frames=frames, sensors=gyro, stats=stats,
                         manifest_overrides={"camera": camera})


class TimingAlignTest(unittest.TestCase):
    def test_recovers_a_known_offset_on_both_frame_clocks(self):
        # 50 Hz is what SENSOR_DELAY_GAME typically gives (a hint only; devices differ).
        for offset_ms, gyro_hz in ((12.0, 200.0), (-45.0, 200.0), (12.0, 50.0)):
            with self.subTest(offset_ms=offset_ms, gyro_hz=gyro_hz), tempfile.TemporaryDirectory() as root:
                directory = synthetic_burst(root, offset_s=offset_ms / 1000, gyro_hz=gyro_hz)
                result = timing_align.analyse(directory, max_lag_ms=100)
                self.assertTrue(result["usable"])
                self.assertAlmostEqual(result["camera"]["offsetMs"], offset_ms, delta=2.0)
                self.assertAlmostEqual(result["received"]["offsetMs"], offset_ms - 28.0, delta=3.0)
                self.assertGreater(result["camera"]["peakCorrelation"], 0.9)
                self.assertAlmostEqual(result["camera"]["focalPxEstimate"], 180.0, delta=180.0 * 0.15)
                self.assertFalse(result["warnings"])

    def test_walking_forward_is_not_turning(self):
        # Seen on SM8850: the phone held steady while walking. The image moves, the gyroscope does not.
        with tempfile.TemporaryDirectory() as root:
            result = timing_align.analyse(synthetic_burst(root, gyro_still=True), max_lag_ms=100)
        self.assertLess(result["turnRateMedianRadPerS"], 0.05)
        self.assertFalse(result["usable"])
        self.assertTrue(any("barely turned" in w for w in result["warnings"]))

    def test_negative_control_real_motion_unrelated_to_the_gyro_never_qualifies(self):
        with tempfile.TemporaryDirectory() as root:
            result = timing_align.analyse(synthetic_burst(root, gyro_independent=True), max_lag_ms=100)
        self.assertGreater(result["pairsMoving"], 200)  # passes the motion gate: this tests correlation
        self.assertFalse(result["usable"])
        self.assertTrue(any("correlate only" in w for w in result["warnings"]))

    def test_steady_frame_loss_is_reported_from_the_counters(self):
        with tempfile.TemporaryDirectory() as root:
            stats = {"framesOffered": 120, "framesEncoded": 120, "framesDroppedByEncoder": 0, "framesMissedBySubscriber": 119}
            result = timing_align.analyse(synthetic_burst(root, keep_every=2, stats=stats), max_lag_ms=100)
        self.assertGreaterEqual(result["knownFrameLossFraction"], 0.45)
        self.assertGreaterEqual(result["overallFrameLossFraction"], result["knownFrameLossFraction"])
        self.assertTrue(any("frames were lost" in w for w in result["warnings"]))

    def test_a_burst_too_short_for_four_checked_parts_never_qualifies(self):
        with tempfile.TemporaryDirectory() as root:
            result = timing_align.analyse(synthetic_burst(root, seconds=1.5), max_lag_ms=100)
        self.assertGreater(result["camera"]["peakCorrelation"], 0.9)  # otherwise a good result
        self.assertFalse(result["usable"])
        self.assertTrue(any("parts of the burst could be checked" in w for w in result["warnings"]))

    def test_a_burst_without_motion_asks_for_a_new_recording(self):
        with tempfile.TemporaryDirectory() as root:
            result = timing_align.analyse(synthetic_burst(root, moving=False), max_lag_ms=100)
        self.assertTrue(any("insufficient motion" in w for w in result["warnings"]))
        self.assertFalse(result["usable"])

    def test_an_unknown_camera_clock_is_never_qualified_and_receipt_time_is_authoritative(self):
        with tempfile.TemporaryDirectory() as root:
            # The camera's own clock starts 3.7 s away from elapsedRealtime, as UNKNOWN may.
            directory = synthetic_burst(root, camera_origin_ns=-3_700_000_000, timestamp_source="unknown")
            result = timing_align.analyse(directory, max_lag_ms=100)
            self.assertFalse(result["cameraComparable"])
            self.assertEqual(result["authoritativeClock"], "received")
            self.assertIsNone(result["camera"])
            self.assertAlmostEqual(result["received"]["offsetMs"], 12.0 - 28.0, delta=3.0)
            self.assertTrue(result["usable"])
            self.assertTrue(any("not comparable" in w for w in result["warnings"]))

            diagnostic = timing_align.analyse(directory, max_lag_ms=100, diagnose_camera_clock=True)["camera"]
            self.assertTrue(diagnostic["diagnosticOnly"])
            self.assertAlmostEqual(diagnostic["offsetMs"], 3_700 + 12.0, delta=3.0)

    def test_dropped_gyroscope_samples_disqualify_the_burst(self):
        with tempfile.TemporaryDirectory() as root:
            result = timing_align.analyse(synthetic_burst(root, stats={"sensorEventsDropped": 5}), max_lag_ms=100)
        self.assertFalse(result["usable"])
        self.assertTrue(any("dropped by capture" in w for w in result["warnings"]))


class DeviceSummaryTest(unittest.TestCase):
    """B': a device result is the median over repeated usable bursts, with their spread as the
    uncertainty; per-burst segment disagreement does not reject a burst."""

    def test_repeated_bursts_give_a_median_and_a_spread_and_rejected_ones_are_left_out(self):
        with tempfile.TemporaryDirectory() as root:
            for i, offset_ms in enumerate((10.0, 12.0, 16.0)):
                synthetic_burst(os.path.join(root, f"b{i}"), offset_s=offset_ms / 1000)
            synthetic_burst(os.path.join(root, "still"), moving=False)
            with redirect_stdout(io.StringIO()) as out:
                code = timing_align.main([root, "--max-lag-ms", "100"])
            results = [timing_align.analyse(d, 100) for d in find_captures(root)]
        device = timing_align.device_summary(results)[0]
        self.assertEqual(code, 0, out.getvalue())
        self.assertEqual((device["bursts"], device["usableBursts"]), (4, 3))
        self.assertTrue(device["enough"])
        self.assertEqual(device["clock"], "camera")
        self.assertAlmostEqual(device["offsetMedianMs"], 12.0, delta=2.0)
        self.assertAlmostEqual(device["offsetMaxMs"] - device["offsetMinMs"], 6.0, delta=3.0)
        self.assertAlmostEqual(device["receiptLatencyMedianMs"], 28.0, delta=3.0)

    def test_fewer_than_three_usable_bursts_give_no_device_result(self):
        with tempfile.TemporaryDirectory() as root:
            for i in range(2):
                synthetic_burst(os.path.join(root, f"b{i}"))
            with redirect_stdout(io.StringIO()):
                code = timing_align.main([root, "--max-lag-ms", "100"])
        self.assertEqual(code, 2)



if __name__ == "__main__":
    unittest.main()
