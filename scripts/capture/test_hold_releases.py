import json
import os
import tempfile
import unittest

import hold_releases
from sailens_capture import load_trace

SESSION = "s1"
SINCE = 10_000


def frame(seq, received=None, session=SESSION):
    record = {"type": "frame", "sessionId": session, "sequenceNumber": seq}
    if received is not None:
        record["receivedElapsedMs"] = received
    return record


def state(seq, elapsed, hold=None, released=None, cause="result", judgement="qualified"):
    record = {"type": "safety_state", "sessionId": SESSION, "seq": seq, "elapsedMs": elapsed, "cause": cause, "judgement": judgement}
    if hold is not None:
        record["hold"] = hold
    if released is not None:
        record["holdReleased"] = released
    return record


def hold(stopping, fresh=0, first=None, last=None, fallbacks=0, last_frame=None, cause="camera_obstructed", since=SINCE):
    h = {"cause": cause, "sinceElapsedMs": since, "stopping": stopping, "freshResults": fresh,
         "freshSpanMs": (last - first) if first is not None else 0, "receiptFallbacks": fallbacks}
    if first is not None:
        h["firstFreshReceivedElapsedMs"] = first
        h["lastFreshReceivedElapsedMs"] = last
        h["lastFreshSequenceNumber"] = last_frame
    return h


def release(fresh, first, last, source, fallbacks=0, cause="camera_obstructed"):
    return {"cause": cause, "sinceElapsedMs": SINCE, "freshResults": fresh, "firstFreshReceivedElapsedMs": first,
            "lastFreshReceivedElapsedMs": last, "freshSpanMs": last - first, "receiptFallbacks": fallbacks,
            "sourceSequenceNumber": source}


def session(hold_trace=True):
    start = {"type": "session_start", "sessionId": SESSION, "safetyTrace": True}
    if hold_trace:
        start["holdTrace"] = True
    return start


def released_after_five(frames=None):
    """A stop at 10000 (record seq 7), five fresh results 11000-11500 on frames 41-45, released by the fifth: the third
    has three but only 200 ms, the fifth both. Record seqs are not frame seqs."""
    records = [session()]
    receipts = {41: 11_000, 42: 11_100, 43: 11_200, 44: 11_300, 45: 11_500}
    records += frames if frames is not None else [frame(s, r) for s, r in receipts.items()]
    records.append(state(7, SINCE, hold(True)))
    for i, s in enumerate([41, 42, 43, 44]):
        records.append(state(8 + i, receipts[s] + 30, hold(False, i + 1, 11_000, receipts[s], last_frame=s)))
    records.append(state(12, 11_530, released=release(5, 11_000, 11_500, 45)))
    return records


def insert_after(records, seq, record):
    """[record] as the safety record after record [seq], the later ones renumbered: the window stays complete."""
    for r in records:
        if r.get("type") == "safety_state" and r["seq"] > seq:
            r["seq"] += 1
    record["seq"] = seq + 1
    at = next(i for i, r in enumerate(records) if r.get("type") == "safety_state" and r["seq"] == seq)
    return records[:at + 1] + [record] + records[at + 1:]


def safety_record(records, seq):
    return next(r for r in records if r.get("type") == "safety_state" and r["seq"] == seq)


class HoldReleasesTest(unittest.TestCase):
    def check(self, records):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "trace.jsonl")
            with open(path, "w", encoding="utf-8") as f:
                for r in records:
                    f.write(json.dumps(r) + "\n")
            return hold_releases.check_releases(load_trace(path))

    def verdict(self, records):
        verdicts = self.check(records)
        self.assertEqual(1, len(verdicts))
        return verdicts[0]

    def test_the_last_frame_meets_both_thresholds(self):
        v = self.verdict(released_after_five())
        self.assertEqual(hold_releases.CONSISTENT, v.verdict, v.reasons)
        self.assertEqual(45, v.source_frame)

    def test_an_unknown_receipt_counted_at_its_arrival_is_accepted(self):
        records = released_after_five(frames=[frame(41, 11_000), frame(42), frame(43, 11_200), frame(44, 11_300), frame(45, 11_500)])
        # Frame 42 has no receipt: it counted at its arrival, the progress record's elapsedMs.
        for r in records:
            if r.get("type") == "safety_state" and r.get("hold", {}).get("lastFreshSequenceNumber") == 42:
                r["hold"]["lastFreshReceivedElapsedMs"] = r["elapsedMs"]
                r["hold"]["freshSpanMs"] = r["elapsedMs"] - 11_000
        for r in records:
            if r.get("type") == "safety_state":
                h = r.get("hold") or r.get("holdReleased")
                if h and h.get("freshResults", 0) >= 2 and not h.get("stopping", False):
                    h["receiptFallbacks"] = 1
        v = self.verdict(records)
        self.assertEqual(hold_releases.CONSISTENT, v.verdict, v.reasons)

    # ---- inconsistent: the evidence is complete and contradicts itself

    def test_a_release_count_one_short_is_inconsistent(self):
        records = released_after_five()
        records[-1]["holdReleased"]["freshResults"] = 4
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_receipt_before_the_stop_is_inconsistent(self):
        records = released_after_five(frames=[frame(41, 9_000), frame(42, 11_100), frame(43, 11_200), frame(44, 11_300), frame(45, 11_500)])
        for r in records:
            if r.get("type") == "safety_state" and r.get("hold", {}).get("freshResults") == 1:
                r["hold"]["firstFreshReceivedElapsedMs"] = 9_000
                r["hold"]["lastFreshReceivedElapsedMs"] = 9_000
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_fallback_for_a_frame_with_a_receipt_is_inconsistent(self):
        records = released_after_five()
        for r in records:
            if r.get("type") == "safety_state":
                h = r.get("hold") or r.get("holdReleased")
                if h and h.get("freshResults", 0) >= 2 and not h.get("stopping", False):
                    h["receiptFallbacks"] = 1
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_record_for_another_reason_that_keeps_the_progress_is_consistent(self):
        # The output changed between the second and third results: the same progress, written again.
        records = insert_after(released_after_five(), 9, state(0, 11_150, hold(False, 2, 11_000, 11_100, last_frame=42), cause="output"))
        v = self.verdict(records)
        self.assertEqual(hold_releases.CONSISTENT, v.verdict, v.reasons)

    def test_a_record_for_another_reason_that_changes_the_progress_is_inconsistent(self):
        # Still two results, but the last receipt before the stop and a fallback from nowhere.
        records = insert_after(released_after_five(), 9, state(0, 11_150, hold(False, 2, 11_000, 9_000, fallbacks=1, last_frame=42), cause="output"))
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)
        # Only the frame of the last result changes.
        records = insert_after(released_after_five(), 9, state(0, 11_150, hold(False, 2, 11_000, 11_100, last_frame=40), cause="output"))
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_count_on_an_input_that_brought_no_result_is_inconsistent(self):
        records = released_after_five()
        safety_record(records, 9)["cause"] = "output"
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_count_on_an_unqualified_result_is_inconsistent(self):
        records = released_after_five()
        safety_record(records, 9)["judgement"] = "unqualified"
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)
        # A degraded result counts.
        records = released_after_five()
        safety_record(records, 9)["judgement"] = "degraded"
        self.assertEqual(hold_releases.CONSISTENT, self.verdict(records).verdict)

    def test_a_release_on_an_unqualified_result_is_inconsistent(self):
        records = released_after_five()
        records[-1]["judgement"] = "unqualified"
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_count_without_its_frame_is_inconsistent(self):
        records = released_after_five()
        del safety_record(records, 9)["hold"]["lastFreshSequenceNumber"]
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_stop_that_begins_with_progress_is_inconsistent(self):
        records = released_after_five()
        safety_record(records, 7)["hold"]["receiptFallbacks"] = 1
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_stopping_again_after_the_recovery_began_without_a_stop_of_its_own_is_inconsistent(self):
        # The progress is kept, so only the stop that has no beginning of its own is wrong.
        records = insert_after(released_after_five(), 9, state(0, 11_150, hold(True, 2, 11_000, 11_100, last_frame=42), cause="poll"))
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_no_fallback_for_a_frame_without_a_receipt_is_inconsistent(self):
        records = released_after_five(frames=[frame(41, 11_000), frame(42), frame(43, 11_200), frame(44, 11_300), frame(45, 11_500)])
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    def test_a_stale_frame_counted_is_inconsistent(self):
        records = released_after_five(frames=[frame(41, 11_000), frame(42, SINCE), frame(43, 11_200), frame(44, 11_300), frame(45, 11_500)])
        self.assertEqual(hold_releases.INCONSISTENT, self.verdict(records).verdict)

    # ---- unverifiable: evidence missing

    def test_a_record_missing_in_the_window_is_unverifiable(self):
        records = [r for r in released_after_five() if not (r.get("type") == "safety_state" and r["seq"] == 9)]
        v = self.verdict(records)
        self.assertEqual(hold_releases.UNVERIFIABLE, v.verdict)

    def test_the_record_that_began_the_stop_missing_is_unverifiable(self):
        records = [r for r in released_after_five() if not (r.get("type") == "safety_state" and r["seq"] == 7)]
        self.assertEqual(hold_releases.UNVERIFIABLE, self.verdict(records).verdict)

    def test_a_counted_frame_left_out_is_unverifiable_not_inconsistent(self):
        records = released_after_five(frames=[frame(41, 11_000), frame(43, 11_200), frame(44, 11_300), frame(45, 11_500)])
        v = self.verdict(records)
        self.assertEqual(hold_releases.UNVERIFIABLE, v.verdict)
        self.assertIn("frame 42", v.reasons[0])

    def test_a_trace_without_holds_is_unverifiable(self):
        records = released_after_five()
        records[0] = session(hold_trace=False)
        self.assertEqual(hold_releases.UNVERIFIABLE, self.verdict(records).verdict)

    # ---- joins

    def test_frames_join_by_their_sequence_number_in_the_session_not_by_record_seq(self):
        # A frame whose sequence number equals a record's seq, with a receipt that would contradict, is not consulted.
        records = released_after_five() + [frame(8, 1)]
        self.assertEqual(hold_releases.CONSISTENT, self.verdict(records).verdict)
        # A frame of another session with the counted frame's number is not that frame.
        foreign = released_after_five(frames=[frame(41, 11_000), frame(42, 11_100, session="other"), frame(43, 11_200), frame(44, 11_300), frame(45, 11_500)])
        self.assertEqual(hold_releases.UNVERIFIABLE, self.verdict(foreign).verdict)


if __name__ == "__main__":
    unittest.main()
