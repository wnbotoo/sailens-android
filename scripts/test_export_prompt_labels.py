"""Tests for export_prompt_labels.py on synthetic traces in the app's format.

    python -m unittest discover -s scripts -p "test_export_prompt_labels.py"
"""
from __future__ import annotations

import contextlib
import csv
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_prompt_labels  # noqa: E402

START = 1_700_000_000_000


def frame(seq, **extra):
    return {"type": "frame", "sessionId": "s", "sequenceNumber": seq, "frameTimestamp": seq * 33_000_000, **extra}


def outcome(event_id, seq, delivered=True, via=("speech", "haptics"), **extra):
    record = {"type": "prompt_outcome", "sessionId": "s", "eventId": event_id, "sourceSequenceNumber": seq,
              "messageKey": "event_obstacle_ahead_person", "category": "OBSTACLE", "priority": "HIGH",
              "speechEnabled": True, "screenReaderActive": False, "hapticsEnabled": True, **extra}
    if delivered:
        record.update(deliveredAt=START + 2_500, deliveredVia=list(via))
    else:
        record.update(revokedAt=START + 3_000, revokeReason="output_refused", deliveredVia=[])
    return record


class ExportPromptLabelsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def run_export(self, records, *flags):
        trace = os.path.join(self.tmp.name, "trace_s.jsonl")
        with open(trace, "w", encoding="utf-8") as f:
            f.write("\n".join(json.dumps(r) for r in records) + "\n")
        out = os.path.join(self.tmp.name, "out.csv")
        stderr = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            code = export_prompt_labels.main([trace, "-o", out, *flags])
        with open(out, encoding="utf-8-sig", newline="") as f:
            return code, list(csv.DictReader(f)), stderr.getvalue()

    def session(self):
        return [
            {"type": "session_start", "sessionId": "s", "startedAt": START},
            frame(7, groundRecognition="RECOGNIZED", unrecognizedGroundRatio=0.123, isBlocked=False,
                  trackedObstacleCategories=["person"], dominantClassPercentages=["road:40%", "sidewalk:30%"]),
            frame(9),
        ]

    def test_only_delivered_prompts_by_default_joined_to_their_frame(self):
        code, rows, _ = self.run_export(self.session() + [outcome("e1", 7), outcome("e2", 9, delivered=False)])
        self.assertEqual(code, 0)
        self.assertEqual([r["event_id"] for r in rows], ["e1"])
        row = rows[0]
        self.assertEqual(row["outcome"], "delivered")
        self.assertEqual(row["delivered_via"], "speech+haptics")
        self.assertEqual(row["output_settings"], "speech+haptics")
        self.assertEqual(row["seconds_into_session"], "2.5")
        self.assertEqual((row["ground_recognition"], row["unrecognized_ground_ratio"]), ("RECOGNIZED", "0.12"))
        self.assertEqual((row["tracked_obstacles"], row["dominant_classes"]), ("person", "road:40%|sidewalk:30%"))
        self.assertEqual((row["label"], row["note"]), ("", ""))

    def test_revoked_prompts_on_request_and_felt_only_delivery_is_visible(self):
        records = self.session() + [outcome("e1", 7, via=("haptics",)), outcome("e2", 9, delivered=False)]
        code, rows, _ = self.run_export(records, "--include-revoked")
        self.assertEqual(code, 0)
        by_id = {r["event_id"]: r for r in rows}
        self.assertEqual(by_id["e1"]["delivered_via"], "haptics")  # felt, not heard, with voice on
        self.assertEqual((by_id["e2"]["outcome"], by_id["e2"]["revoke_reason"]), ("revoked", "output_refused"))

    def test_a_prompt_whose_frame_record_is_missing_is_marked_and_fails_the_run(self):
        code, rows, stderr = self.run_export(self.session() + [outcome("e1", 8)])
        self.assertEqual(code, 1)
        self.assertEqual(rows[0]["note"], export_prompt_labels.MISSING_FRAME_NOTE)
        self.assertEqual(rows[0]["ground_recognition"], "")
        self.assertIn("frame 8", stderr)

    def test_a_trace_from_before_prompt_outcomes_gives_no_rows_and_a_warning(self):
        code, rows, stderr = self.run_export(self.session())
        self.assertEqual((code, rows), (0, []))
        self.assertIn("no prompt_outcome", stderr)


if __name__ == "__main__":
    unittest.main()
