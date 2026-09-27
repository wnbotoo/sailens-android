"""Reads Sailens field captures (M0a) and Guidance traces on the PC.

Mirrors the JVM reader (`CaptureSessionReader`, sailens-guidance): the schema version is read from
the raw manifest before anything else, an unknown major version is refused as unsupported, a torn
JSONL line is skipped with a warning, unknown record types are counted, not fatal. Field names are
the ones `CaptureSchema` writes; see docs/local-navigation-implementation.md §4.

Time bases, kept apart on purpose:
- ``sensorTimestampNanos`` (frames) is the camera's own timestamp. Guidance traces carry the same
  value as ``frameTimestamp``, so prompts join captures exactly on this timeline.
- ``receivedElapsedRealtimeNanos`` (frames), marker and anchor ``elapsedRealtimeNanos`` are
  SystemClock.elapsedRealtimeNanos. Sensor ``timestampNanos`` share it only when the camera's
  timestamp source is REALTIME; the timing-sync burst measures that.
- Wall-clock ms (trace ``deliveredAt``, marker ``wallMs``) is mapped to elapsed time via the
  capture's clock anchors.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field

SUPPORTED_MAJOR = 1

MANIFEST_FILE = "manifest.json"
RECORD_FILES = ("frames.jsonl", "sensors.jsonl", "anchors.jsonl", "markers.jsonl")
RECORD_TYPES = {"frame": "frames", "sensor": "sensors", "clock_anchor": "anchors", "marker": "markers"}


class UnsupportedCapture(Exception):
    """The capture is from a schema this reader does not understand (not a damaged capture)."""


class UnreadableCapture(Exception):
    """The capture directory has no usable manifest."""


@dataclass
class Capture:
    directory: str
    manifest: dict
    frames: list = field(default_factory=list)
    sensors: list = field(default_factory=list)
    anchors: list = field(default_factory=list)
    markers: list = field(default_factory=list)
    warnings: list = field(default_factory=list)

    @property
    def mode(self) -> str:
        return self.manifest.get("captureMode", "")

    @property
    def complete(self) -> bool:
        return bool(self.manifest.get("complete", False))

    def frame_path(self, frame: dict) -> str:
        return os.path.join(self.directory, frame["file"])

    def sensors_of(self, name: str) -> list:
        return [s for s in self.sensors if s.get("sensor") == name]

    def wall_ms_to_elapsed_nanos(self, wall_ms: int) -> int:
        """Maps wall-clock ms to elapsed-realtime ns with the nearest clock anchor."""
        anchors = self.anchors or [{
            "wallMs": self.manifest["startedWallMs"],
            "elapsedRealtimeNanos": self.manifest["startedElapsedRealtimeNanos"],
        }]
        nearest = min(anchors, key=lambda a: abs(a["wallMs"] - wall_ms))
        return nearest["elapsedRealtimeNanos"] + (wall_ms - nearest["wallMs"]) * 1_000_000


def load_capture(directory: str) -> Capture:
    manifest_path = os.path.join(directory, MANIFEST_FILE)
    try:
        with open(manifest_path, encoding="utf-8") as f:
            raw = json.load(f)
    except (OSError, ValueError) as e:
        raise UnreadableCapture(f"{manifest_path}: {e}") from e
    major, minor = raw.get("schemaMajor"), raw.get("schemaMinor")
    if not isinstance(major, int) or not isinstance(minor, int):
        raise UnsupportedCapture(f"{manifest_path}: schemaMajor/schemaMinor missing")
    if major != SUPPORTED_MAJOR:
        raise UnsupportedCapture(f"{manifest_path}: schema major {major}, this reader supports {SUPPORTED_MAJOR}")

    capture = Capture(directory=directory, manifest=raw)
    if not capture.complete:
        capture.warnings.append(
            f"incomplete capture (failureReason={raw.get('failureReason')}); counters may not account for every record"
        )
    unknown: dict = {}
    for name in RECORD_FILES:
        path = os.path.join(directory, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            lines = f.read().split("\n")
        for index, line in enumerate(lines):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except ValueError:
                last = index == len(lines) - 1 or all(not rest.strip() for rest in lines[index + 1:])
                capture.warnings.append(f"{name}:{index + 1}: {'torn last line' if last else 'unparsable line'} skipped")
                continue
            kind = record.get("type")
            target = RECORD_TYPES.get(kind)
            if target is None:
                unknown[kind] = unknown.get(kind, 0) + 1
                continue
            getattr(capture, target).append(record)
    for kind, count in unknown.items():
        capture.warnings.append(f"{count} record(s) of unknown type '{kind}' ignored")
    capture.frames.sort(key=lambda r: r["seq"])
    capture.sensors.sort(key=lambda r: r["timestampNanos"])
    capture.anchors.sort(key=lambda r: r["elapsedRealtimeNanos"])
    return capture


def find_captures(root: str) -> list:
    """Capture directories under ``root`` (itself, its children, or an unpacked export ZIP)."""
    found = []
    for current, dirs, files in os.walk(root):
        if MANIFEST_FILE in files:
            found.append(current)
            dirs[:] = []
    return sorted(found)


@dataclass
class Trace:
    path: str
    frames_by_seq: dict = field(default_factory=dict)
    prompt_outcomes: list = field(default_factory=list)
    warnings: list = field(default_factory=list)


def load_trace(path: str) -> Trace:
    """A Guidance trace JSONL (`files/traces/trace_<sessionId>.jsonl`)."""
    trace = Trace(path=path)
    with open(path, encoding="utf-8") as f:
        for index, line in enumerate(f):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except ValueError:
                trace.warnings.append(f"line {index + 1} unparsable, skipped")
                continue
            kind = record.get("type")
            if kind == "frame":
                trace.frames_by_seq[record["sequenceNumber"]] = record
            elif kind == "prompt_outcome":
                trace.prompt_outcomes.append(record)
    return trace


def read_image(capture: Capture, frame: dict, upright: bool = True):
    """The stored frame as a uint8 numpy array (H×W luma, or H×W×3 RGB for JPEG)."""
    import numpy as np

    path = capture.frame_path(frame)
    if frame["encoding"] == "luma8":
        data = np.fromfile(path, dtype=np.uint8)
        expected = frame["width"] * frame["height"]
        if data.size != expected:
            raise ValueError(f"{path}: {data.size} bytes, expected {expected}")
        image = data.reshape(frame["height"], frame["width"])
    elif frame["encoding"] == "jpeg":
        from PIL import Image

        with Image.open(path) as im:
            image = np.asarray(im.convert("RGB"))
    else:
        raise ValueError(f"{path}: unknown encoding {frame['encoding']}")
    if upright:
        # Stored pixels are not rotated; rotationDegrees is clockwise to view upright.
        image = np.rot90(image, k=-(frame.get("rotationDegrees", 0) // 90) % 4)
    return image
