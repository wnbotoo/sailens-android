"""Reads Sailens field captures (M0a), model-regression records (M0b) and Guidance traces on the PC.

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
import struct
import zlib
from dataclasses import dataclass, field

SUPPORTED_MAJOR = 1

MANIFEST_FILE = "manifest.json"
CLASS_MAPS_FILE = "classmaps.bin"
RECORD_FILES = (
    "frames.jsonl", "sensors.jsonl", "anchors.jsonl", "markers.jsonl", "regression.jsonl", "windows.jsonl",
)
RECORD_TYPES = {
    "frame": "frames", "sensor": "sensors", "clock_anchor": "anchors", "marker": "markers",
    "regression_frame": "regression_frames", "regression_revoke": "revokes", "window_frame": "window_frames",
    "regression_utterance": "utterances",
}

# det-first D1.8: a capture under a directory holding this file belongs to the sealed acceptance set.
SEALED_MARKER = "SEALED"

# classmaps.bin entry header (ClassMapStore, big-endian): magic, seq, width, height, crc32, length.
CLASS_MAP_MAGIC = 0x53434D31
CLASS_MAP_HEADER = struct.Struct(">IqiiIi")


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
    # model_regression only (schema 1.3)
    regression_frames: list = field(default_factory=list)
    revokes: list = field(default_factory=list)
    window_frames: list = field(default_factory=list)
    # the speech lifecycle (schema 1.5); empty also when the record keeps none -- see
    # manifest["regression"]["utteranceLifecycle"]
    utterances: list = field(default_factory=list)

    @property
    def mode(self) -> str:
        return self.manifest.get("captureMode", "")

    @property
    def complete(self) -> bool:
        return bool(self.manifest.get("complete", False))

    def frame_path(self, frame: dict) -> str:
        return os.path.join(self.directory, frame["file"])

    @property
    def group(self):
        return self.manifest.get("group")

    def class_map(self, regression_frame: dict):
        """The semantic class map of a regression frame as an H×W uint8 numpy array (not rotated),
        checked against its reference like the JVM reader; None when the model did not run."""
        ref = (regression_frame.get("semantic") or {}).get("classMap")
        if ref is None:
            return None
        return read_class_map(os.path.join(self.directory, CLASS_MAPS_FILE), ref, regression_frame["seq"])

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
    if capture.mode == "model_regression" and not raw.get("regression"):
        capture.warnings.append("model_regression capture without a regression block in the manifest")
    first_lost = ((raw.get("regression") or {}).get("stats") or {}).get("firstLostSeq")
    if first_lost is not None:
        capture.warnings.append(f"the regression record lost frames from seq {first_lost} on; replay is exact only before it")
    capture.frames.sort(key=lambda r: r["seq"])
    capture.sensors.sort(key=lambda r: r["timestampNanos"])
    capture.anchors.sort(key=lambda r: r["elapsedRealtimeNanos"])
    return capture


def read_class_map(path: str, ref: dict, seq: int):
    """One classmaps.bin entry, verified: it must be this frame's, of the recorded size and CRC."""
    import numpy as np

    with open(path, "rb") as f:
        f.seek(ref["offset"])
        entry = f.read(ref["length"])
    if len(entry) != ref["length"] or len(entry) < CLASS_MAP_HEADER.size:
        raise ValueError(f"frame {seq}: class map entry outside {os.path.basename(path)}")
    magic, entry_seq, width, height, crc, length = CLASS_MAP_HEADER.unpack_from(entry)
    if magic != CLASS_MAP_MAGIC:
        raise ValueError(f"frame {seq}: no class map entry at offset {ref['offset']}")
    if entry_seq != seq or width != ref["width"] or height != ref["height"] or crc != ref["crc32"]:
        raise ValueError(f"frame {seq}: class map entry is for frame {entry_seq} {width}x{height}, not the one recorded")
    if length != ref["length"] - CLASS_MAP_HEADER.size:
        raise ValueError(f"frame {seq}: class map entry length mismatch")
    raw = zlib.decompress(entry[CLASS_MAP_HEADER.size:])
    if len(raw) != width * height or zlib.crc32(raw) != ref["crc32"]:
        raise ValueError(f"frame {seq}: class map does not match its CRC")
    return np.frombuffer(raw, dtype=np.uint8).reshape(height, width)


def is_sealed(path: str) -> bool:
    """Whether ``path`` belongs to the det-first D1.8 sealed acceptance set: a ``SEALED`` file in it or
    any directory above it, above the path as given (normalized) or above its real location (links and
    junctions resolved). Either one seals. Mirrors ``SealedCaptures`` (sailens-guidance tests)."""
    for start in {os.path.normpath(os.path.abspath(path)), os.path.realpath(path)}:
        current = start
        while True:
            if os.path.isfile(os.path.join(current, SEALED_MARKER)):
                return True
            parent = os.path.dirname(current)
            if parent == current:
                break
            current = parent
    return False


def refuse_sealed(paths, unseal: bool) -> list:
    """The sealed ones among ``paths``, unless ``unseal``: a tool that shows results must refuse them."""
    return [] if unseal else [p for p in paths if is_sealed(p)]


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
