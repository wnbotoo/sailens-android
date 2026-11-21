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
            "preprocessStartedNanos": 1_002_000_000,
            "preprocessFinishedNanos": 1_022_000_000,
            "preprocessBackend": "native_yuv",
            "cacheWaitNanos": 2_000_000,
            "cacheAwaitNanos": 1_500_000,
            "cacheCopyNanos": 0,
            "jniArrayGetNanos": 2_000_000,
            "jniArrayReleaseNanos": 3_000_000,
            "nativeYuvBodyNanos": 8_000_000,
            "nativeResizeFilter": "NEAREST",
            "nativeOutputLayout": "NHWC",
            "kotlinHwcToNchwNanos": 4_000_000,
            "jniArrayIsCopy": {"y": True, "u": False, "v": False, "output": True},
            "inferenceReturnedNanos": 1_025_000_000,
            "outputLockNanos": 4_000_000,
            "outputUnlockNanos": 100_000,
            "postprocessFinishedNanos": 1_035_000_000,
            "semanticScorePhases": {
                "argmaxImplementation": "scalar",
                "arraysGetNanos": 100_000,
                "fusedKernelNanos": 4_800_000,
                "arraysReleaseNanos": 200_000,
                "kotlinPrepareNanos": 100_000,
                "kotlinAssembleNanos": 200_000,
                "sampledPixels": 20,
                "sampledArgmaxNanos": 1_200,
                "sampledMaskWritesNanos": 100,
                "sampledStatisticsNanos": 300,
                "sampledClockFloorNanos": 400,
            },
        }
        first["det"] = {
            "preprocessStartedNanos": 1_002_000_000,
            "preprocessFinishedNanos": 1_023_000_000,
            "preprocessBackend": "shared_native_yuv",
            "cacheWaitNanos": 20_000_000,
            "cacheAwaitNanos": 19_500_000,
            "cacheCopyNanos": 1_000_000,
            "inferenceReturnedNanos": 1_026_000_000,
            "outputCopyNanos": 3_000_000,
            "postprocessFinishedNanos": 1_033_000_000,
        }
        first["decidedNanos"] = 1_040_000_000
        failed["sem"] = {
            "preprocessStartedNanos": 1_067_000_000,
            "preprocessFinishedNanos": 1_087_000_000,
            "cacheWaitNanos": 0,
            "cacheCopyNanos": 0,
            "inferenceReturnedNanos": 1_090_000_000,
        }
        failed["det"] = {
            "inferenceReturnedNanos": 1_095_000_000,
            "outputLockNanos": 2_000_000,
            "outputCopyNanos": 4_000_000,
            "postprocessFinishedNanos": 1_107_000_000,
        }
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
        timing = report["timing_ms"]
        self.assertEqual(timing["sem.jniArrayLockRelease"]["p95"], 5.0)
        self.assertEqual(timing["sem.nativeNearestBody"]["n"], 1)
        self.assertEqual(timing["sem.nativeNearestBody"]["p95"], 8.0)
        self.assertEqual(timing["sem.kotlinHwcToNchw"]["p95"], 4.0)
        self.assertEqual(timing["sem.preprocessOther"]["p50"], 10.5)
        self.assertEqual(timing["det.cacheAwait"]["p95"], 19.5)
        self.assertEqual(timing["det.cacheMonitorWait"]["p95"], 0.5)
        self.assertEqual(timing["det.cacheCopy"]["p95"], 1.0)
        self.assertEqual(timing["det.preprocessOther"]["p95"], 0.0)
        self.assertEqual(timing["det.outputLock"]["n"], 1)
        self.assertEqual(timing["det.outputCopy"]["n"], 2)
        self.assertNotIn("det.outputUnlock", timing)
        self.assertNotIn("sem.outputCopy", timing)
        self.assertEqual(timing["det.outputAccess"]["p95"], 6.0)
        self.assertEqual(timing["det.postprocess"]["p95"], 6.0)
        self.assertNotIn("det.nativeNearestBody", timing)
        self.assertNotIn("det.kotlinHwcToNchw", timing)
        self.assertEqual(report["jni_copy_counts"]["sem.output"], {"copy": 1})
        self.assertEqual(report["jni_copy_counts"]["sem.u"], {"direct": 1})
        self.assertEqual(timing["sem.score.fusedKernel"]["p95"], 4.8)
        self.assertEqual(report["backends"]["sem.score.argmaxImplementation"], {"scalar": 1})
        self.assertEqual(report["backends"]["sem.nativeOutputLayout"], {"NHWC": 1})
        self.assertEqual(timing["sem.score.other"]["p95"], 0.5)
        self.assertEqual(timing["sem.score.sampledKernel"]["n"], 1)
        self.assertNotIn("sem.score.unsampledKernel", timing)
        self.assertNotIn("sem.score.fusedKernelThreadCpu", timing)
        self.assertEqual(report["semantic_score_pixel_sampling"], {
            "frames": 1,
            "pixels": 20,
            "nanos_per_sampled_pixel": {
                "argmax": 60.0,
                "maskWrites": 5.0,
                "statistics": 15.0,
                "clockFloor": 20.0,
            },
        })

    def test_pairs_wall_and_thread_cpu_for_the_same_scored_frame(self):
        first = {"frameId": 1, "width": 1280, "height": 720, "receivedNanos": 0, "offeredNanos": 1}
        first["sem"] = {
            "inferenceReturnedNanos": 2_000_000,
            "postprocessFinishedNanos": 11_000_000,
            "semanticScorePhases": {
                "argmaxImplementation": "neon",
                "fusedKernelNanos": 8_200_000,
                "fusedKernelThreadCpuNanos": 6_700_000,
                "arraysGetNanos": 100_000,
                "arraysReleaseNanos": 100_000,
                "kotlinPrepareNanos": 100_000,
                "kotlinAssembleNanos": 100_000,
            },
        }
        last = {"frameId": 2, "receivedNanos": 33_000_000, "offeredNanos": 33_000_001}
        report = measure({"pipelineTimingMode": "sem-nchw-indexed", "targetHardwareProfile": "test"}, [first, last], 0)
        timing = report["timing_ms"]
        self.assertEqual(timing["sem.score.fusedKernel"]["p50"], 8.2)
        self.assertEqual(timing["sem.score.fusedKernelThreadCpu"]["p50"], 6.7)
        self.assertEqual(timing["sem.score.fusedKernelOffCpu"]["p50"], 1.5)

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
