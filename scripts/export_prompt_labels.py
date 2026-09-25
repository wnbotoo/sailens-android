#!/usr/bin/env python3
"""Turn Sailens trace files into a sheet for labelling the prompts the user actually received.

Usage:
    py scripts/export_prompt_labels.py trace_<session>.jsonl [more.jsonl ...] [-o prompts.csv]
        [--include-revoked]

One row per `prompt_outcome` record (the one prompt a frame offered), joined to its `frame`
record by sequence number. By default only delivered prompts are listed: a revoked prompt was
never heard, so it cannot be a false alarm. The `label` and `note` columns are left empty for
the person labelling; the field-recording manual (docs/field-recording-manual.md) defines the
label values.

Frame `messageKeys` are candidates after cooldown, not what the user received; they are not used
here. Traces recorded before `prompt_outcome` existed produce no rows (a warning is printed).

Writes UTF-8 with a BOM so spreadsheet apps open Chinese text correctly.
"""

import argparse
import csv
import json
import sys
from datetime import datetime
from pathlib import Path

COLUMNS = [
    "segment",
    "session_id",
    "event_id",
    "seconds_into_session",
    "local_time",
    "outcome",
    "revoke_reason",
    "message_key",
    "category",
    "priority",
    "delivered_via",
    "output_settings",
    "frame_sequence",
    "ground_recognition",
    "unrecognized_ground_ratio",
    "is_blocked",
    "tracked_obstacles",
    "dominant_classes",
    # Filled in by the person labelling.
    "label",
    "note",
]


def read_records(path):
    with open(path, encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as error:
                raise SystemExit(f"{path}:{line_number}: not JSON ({error})")


def delivered_via(outcome):
    """The channels that actually accepted the prompt (`deliveredVia`), e.g. "speech+haptics".

    This is the evidence of heard vs felt. The output *settings* are not: with speech and haptics
    both on, a prompt can still reach the user by vibration alone while the speech engine starts.
    """
    return "+".join(outcome.get("deliveredVia", []))


def settings(outcome):
    """The output settings at the time, for context only."""
    names = [
        name for name, key in (
            ("speech", "speechEnabled"),
            ("screen_reader", "screenReaderActive"),
            ("haptics", "hapticsEnabled"),
        ) if outcome.get(key)
    ]
    return "+".join(names) or "none"


def rows_for(path, include_revoked):
    session_start = None
    frames = {}
    outcomes = []
    for record in read_records(path):
        kind = record.get("type")
        if kind == "session_start":
            session_start = record.get("startedAt")
        elif kind == "frame":
            frames[record["sequenceNumber"]] = record
        elif kind == "prompt_outcome":
            outcomes.append(record)

    if not outcomes:
        print(f"warning: {path} has no prompt_outcome records (recorded before they existed?)",
              file=sys.stderr)

    segment = Path(path).stem
    for outcome in outcomes:
        delivered = outcome.get("deliveredAt") is not None
        if not delivered and not include_revoked:
            continue
        at = outcome["deliveredAt"] if delivered else outcome["revokedAt"]
        frame = frames.get(outcome["sourceSequenceNumber"], {})
        yield {
            "segment": segment,
            "session_id": outcome["sessionId"],
            "event_id": outcome["eventId"],
            "seconds_into_session": (
                f"{(at - session_start) / 1000:.1f}" if session_start is not None else ""
            ),
            "local_time": datetime.fromtimestamp(at / 1000).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
            "outcome": "delivered" if delivered else "revoked",
            "revoke_reason": outcome.get("revokeReason", ""),
            "message_key": outcome["messageKey"],
            "category": outcome["category"],
            "priority": outcome["priority"],
            "delivered_via": delivered_via(outcome),
            "output_settings": settings(outcome),
            "frame_sequence": outcome["sourceSequenceNumber"],
            "ground_recognition": frame.get("groundRecognition", ""),
            "unrecognized_ground_ratio": (
                f"{frame['unrecognizedGroundRatio']:.2f}" if "unrecognizedGroundRatio" in frame else ""
            ),
            "is_blocked": frame.get("isBlocked", ""),
            "tracked_obstacles": "|".join(frame.get("trackedObstacleCategories", [])),
            "dominant_classes": "|".join(frame.get("dominantClassPercentages", [])),
            "label": "",
            "note": "",
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("traces", nargs="+", help="trace_<session>.jsonl files")
    parser.add_argument("-o", "--output", default="prompts.csv", help="CSV to write")
    parser.add_argument("--include-revoked", action="store_true",
                        help="also list prompts that were revoked (never heard)")
    args = parser.parse_args()

    rows = []
    for path in args.traces:
        rows.extend(rows_for(path, args.include_revoked))

    with open(args.output, "w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)

    print(f"{len(rows)} prompts -> {args.output}")


if __name__ == "__main__":
    main()
