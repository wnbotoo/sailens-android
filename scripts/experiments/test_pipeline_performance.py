import json
from pathlib import Path
import tempfile
import unittest

from pipeline_performance import load_trace, measure


class PipelinePerformanceTest(unittest.TestCase):
    def test_counts_completions_separately_from_decisions_and_reused_results(self):
        def frame(frame_id, at_ms):
            receipt = at_ms * 1_000_000
            return {
                "frameId": frame_id,
                "width": 1280,
                "height": 720,
                "receivedNanos": receipt,
                "cameraTimestampNanos": receipt - 10_000_000,
                "cameraClock": "REALTIME",
                "copyStartedNanos": receipt + 100,
                "copyFinishedNanos": receipt + 200,
                "offeredNanos": receipt + 300,
                "dequeuedNanos": receipt + 400,
            }

        frames = [frame(20, 1000), frame(22, 1066), frame(23, 1100), frame(26, 1200)]
        first, failed, reused, _ = frames
        first["sem"] = {
            "inferenceReturnedNanos": 1_025_000_000,
            "outputLockNanos": 4_000_000,
            "outputUnlockNanos": 100_000,
            "postprocessFinishedNanos": 1_035_000_000,
        }
        first["decidedNanos"] = 1_040_000_000
        failed["sem"] = {"inferenceReturnedNanos": 1_090_000_000}
        reused["decidedNanos"] = 1_125_000_000
        source = {key: first[key] for key in ("frameId", "receivedNanos", "cameraTimestampNanos", "cameraClock")}
        first["semSource"] = reused["semSource"] = source

        report = measure({"pipelineTimingMode": "race", "targetHardwareProfile": "test"}, frames, 0)

        self.assertEqual(report["rates_per_second"]["camera_input"], 30.0)
        self.assertEqual(report["rates_per_second"]["sem.calls_returned"], 10.0)
        self.assertEqual(report["rates_per_second"]["sem.results"], 5.0)
        self.assertEqual(report["rates_per_second"]["decisions"], 10.0)
        self.assertEqual(report["counts"]["mailbox_replaced"], 3)
        self.assertEqual(report["counts"]["dequeued_without_decision"], 1)
        self.assertEqual(report["drop_percent"], 66.667)
        self.assertEqual(report["timing_ms"]["sem.postprocess"]["p95"], 5.9)
        self.assertEqual(report["timing_ms"]["sem.sourceReceiptToDecision"]["p95"], 125.0)
        self.assertEqual(report["timing_ms"]["sem.sourceSensorToDecision"]["p95"], 135.0)

    def test_rejects_missing_timing_rows_before_computing_drops(self):
        rows = [
            {"type": "session_start", "sessionId": "s", "pipelineTimingMode": "race"},
            {"type": "pipeline_timing", "sessionId": "s", "seq": 1},
            {"type": "session_summary", "sessionId": "s", "pipelineTimingCount": 2},
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.jsonl"
            path.write_text("\n".join(map(json.dumps, rows)), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "timing records are missing"):
                load_trace(path)


if __name__ == "__main__":
    unittest.main()
