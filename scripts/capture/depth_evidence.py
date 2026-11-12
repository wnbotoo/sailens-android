"""Reruns a capture's depth runs on the PC from their recorded planes (docs/field-capture.md §11).

For every record of the depth evidence stream (``depth.jsonl`` + ``depth.bin``) it rebuilds the model input
the way the native kernel does (``sailens_runtime_jni.cpp``: BT.601 limited range, rotation, letterbox with
the content columns ``[ceil(pad), ceil(pad + extent))``, bilinear point sampling with edge clamp, chroma at
half the luma coordinate, black padding), runs the model in float32 on the CPU, and writes the output where
the JVM replay reads it::

    <out>/<sessionId>/g<generation>_r<runId>.f32     H x W float32, row-major, little-endian
    <out>/<sessionId>/index.json                      the model, its shapes and a per-run summary

It follows the depth contract (docs/models.md), not a particular model: the input size and layout and the
output shape come from the model file, so it compares any conforming model. The verdict -- the fit replayed
on the phone's and on these outputs, and the bands -- is the JVM's (``DepthEvidenceReplayTest``); the
summary printed here is the tensor comparison only, for a quick look.

    python scripts/capture/depth_evidence.py <capture or folder> --model depth.tflite --out <dir>

Captures under a directory holding ``SEALED`` are refused unless ``--unseal``.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sailens_capture as sc  # noqa: E402

RECORDS_FILE = "depth.jsonl"
DATA_FILE = "depth.bin"
F32 = np.float32


# ---- reading the stream ------------------------------------------------------------------------------------------

def read_records(directory: str) -> tuple[list, list]:
    """The stream's records in file order, and warnings (a torn last line is skipped, as the JVM reader does)."""
    path = os.path.join(directory, RECORDS_FILE)
    records, warnings = [], []
    if not os.path.exists(path):
        return records, warnings
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except ValueError:
            warnings.append(f"{RECORDS_FILE}:{index + 1}: unparsable line skipped")
            continue
        if record.get("type") == "depth_evidence":
            records.append(record)
    return records, warnings


def read_blob(directory: str, ref: dict) -> bytes:
    """The bytes a record points at in depth.bin, checked against their CRC32."""
    with open(os.path.join(directory, DATA_FILE), "rb") as f:
        f.seek(ref["offset"])
        data = f.read(ref["length"])
    if len(data) != ref["length"]:
        raise ValueError(f"depth blob {ref['offset']}+{ref['length']} is not wholly in {DATA_FILE}")
    if zlib.crc32(data) != ref["crc32"]:
        raise ValueError(f"depth blob at {ref['offset']}: CRC32 mismatch")
    return data


def read_output(directory: str, record: dict) -> np.ndarray:
    out = record["output"]
    data = read_blob(directory, out["data"])
    return np.frombuffer(data, dtype="<f4").reshape(out["height"], out["width"]).astype(F32)


def read_planes(directory: str, record: dict) -> dict:
    """The three planes as the native kernel was given them: bytes, size in samples, strides, default value."""
    frame = record["frame"]
    planes = {}
    for name, default in (("y", 16), ("u", 128), ("v", 128)):
        p = frame[name]
        planes[name] = {
            "data": np.frombuffer(read_blob(directory, p["data"]), dtype=np.uint8),
            "width": p["width"], "height": p["height"],
            "rowStride": p["rowStride"], "pixelStride": p["pixelStride"],
            "default": default,
        }
    return planes


# ---- the native preprocessing ------------------------------------------------------------------------------------

def _byte_at(plane: dict, x: np.ndarray, y: np.ndarray) -> np.ndarray:
    """``unsignedByteAt``: the sample at (x, y), or the plane's default when its index is past the array."""
    index = y.astype(np.int64) * plane["rowStride"] + x.astype(np.int64) * plane["pixelStride"]
    data = plane["data"]
    inside = index < data.size
    out = np.full(index.shape, plane["default"], dtype=F32)
    out[inside] = data[index[inside]]
    return out


def _sample_bilinear(plane: dict, x: np.ndarray, y: np.ndarray) -> np.ndarray:
    """``samplePlaneBilinear``: clamp to the plane, then interpolate the four neighbours (no area averaging)."""
    w, h = plane["width"], plane["height"]
    if w <= 0 or h <= 0:
        return np.full(x.shape, plane["default"], dtype=F32)
    cx = np.clip(x, F32(0), F32(w - 1)).astype(F32)
    cy = np.clip(y, F32(0), F32(h - 1)).astype(F32)
    x0 = np.floor(cx).astype(np.int64)
    y0 = np.floor(cy).astype(np.int64)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    dx = (cx - x0.astype(F32)).astype(F32)
    dy = (cy - y0.astype(F32)).astype(F32)
    v00, v10 = _byte_at(plane, x0, y0), _byte_at(plane, x1, y0)
    v01, v11 = _byte_at(plane, x0, y1), _byte_at(plane, x1, y1)
    top = v00 + (v10 - v00) * dx
    bottom = v01 + (v11 - v01) * dx
    return (top + (bottom - top) * dy).astype(F32)


def _yuv_to_rgb(y: np.ndarray, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    """``yuvToRgb``: BT.601 limited range, each channel clamped to [0, 255]."""
    c = np.maximum(y - F32(16), F32(0))
    d = u - F32(128)
    e = v - F32(128)
    r = np.clip(F32(1.164) * c + F32(1.596) * e, F32(0), F32(255))
    g = np.clip(F32(1.164) * c - F32(0.392) * d - F32(0.813) * e, F32(0), F32(255))
    b = np.clip(F32(1.164) * c + F32(2.017) * d, F32(0), F32(255))
    return np.stack([r, g, b], axis=-1).astype(F32)


def letterbox(frame: dict, planes: dict, target_width: int, target_height: int) -> np.ndarray:
    """The model input, H x W x 3 RGB in [0, 1], as ``preprocessYuv`` writes it (mean 0, std 1: the contract keeps
    any normalization inside the model). Float32 throughout, in the kernel's order of operations; the kernel may
    contract a multiply-add, so values can differ in the last bit."""
    width, height = frame["width"], frame["height"]
    rotation = frame["rotationDegrees"] % 360
    rotated = rotation in (90, 270)
    rotated_width, rotated_height = (height, width) if rotated else (width, height)
    scale = min(F32(target_width) / F32(rotated_width), F32(target_height) / F32(rotated_height))
    resized_width = F32(rotated_width) * scale
    resized_height = F32(rotated_height) * scale
    pad_x = (F32(target_width) - resized_width) * F32(0.5)
    pad_y = (F32(target_height) - resized_height) * F32(0.5)
    x_start = min(max(int(math.ceil(pad_x)), 0), target_width)
    x_end = min(max(int(math.ceil(F32(pad_x + resized_width))), x_start), target_width)
    y_start = min(max(int(math.ceil(pad_y)), 0), target_height)
    y_end = min(max(int(math.ceil(F32(pad_y + resized_height))), y_start), target_height)

    out = np.zeros((target_height, target_width, 3), dtype=F32)
    if x_end <= x_start or y_end <= y_start:
        return out
    ry = (np.arange(y_start, y_end, dtype=F32) - pad_y + F32(0.5)) / scale - F32(0.5)
    rx = (np.arange(x_start, x_end, dtype=F32) - pad_x + F32(0.5)) / scale - F32(0.5)
    grid_x, grid_y = np.meshgrid(rx.astype(F32), ry.astype(F32))
    if rotation == 90:
        source_x, source_y = grid_y, F32(height - 1) - grid_x
    elif rotation == 180:
        source_x, source_y = F32(width - 1) - grid_x, F32(height - 1) - grid_y
    elif rotation == 270:
        source_x, source_y = F32(width - 1) - grid_y, grid_x
    else:
        source_x, source_y = grid_x, grid_y
    source_x = source_x.astype(F32)
    source_y = source_y.astype(F32)
    luma = _sample_bilinear(planes["y"], source_x, source_y)
    half_x = (source_x * F32(0.5)).astype(F32)
    half_y = (source_y * F32(0.5)).astype(F32)
    u = _sample_bilinear(planes["u"], half_x, half_y)
    v = _sample_bilinear(planes["v"], half_x, half_y)
    out[y_start:y_end, x_start:x_end] = _yuv_to_rgb(luma, u, v) / F32(255)
    return out


# ---- the comparison (DisparityMap's content rule, compareDisparity's measure) -------------------------------------

def content_range(pad: float, extent: float, scale: float, size: int) -> range:
    """The tensor pixels holding what the model saw, as ``DisparityMap`` decides them: written by the kernel
    (left edge not in the padding) and centre strictly inside the content span."""
    first = min(max(int(math.ceil(F32(pad))), 0), size - 1)
    end = F32(F32(pad) + F32(extent) * F32(scale))
    last = min(max(int(math.ceil(F32(end - F32(0.5)))) - 1, first), size - 1)
    return range(first, last + 1)


def relative_error(reference: np.ndarray, candidate: np.ndarray, transform: dict) -> tuple[float, float]:
    """(median, p95) over the content pixels of |candidate - reference| / max(|reference|, 5 % of max |reference|), as
    ``compareDisparity`` computes it: where that denominator is 0, equal values are no error and different ones an
    infinite one; a non-finite value is infinitely off."""
    h, w = reference.shape
    cols = content_range(transform["padLeft"], transform["sourceWidth"], transform["scaleX"], w)
    rows = content_range(transform["padTop"], transform["sourceHeight"], transform["scaleY"], h)
    r = reference[rows.start:rows.stop, cols.start:cols.stop].astype(F32)
    c = candidate[rows.start:rows.stop, cols.start:cols.stop].astype(F32)
    floor = F32(0.05) * F32(np.max(np.abs(r))) if r.size else F32(0)
    denominator = np.maximum(np.abs(r), floor)
    with np.errstate(divide="ignore", invalid="ignore"):
        e = np.where(denominator > 0, np.abs(c - r) / denominator, np.where(c == r, F32(0), F32(np.inf)))
    e = np.where(np.isfinite(e), e, np.inf).ravel()
    if e.size == 0:
        return math.inf, math.inf
    e.sort()
    return float(e[e.size // 2]), float(e[((e.size - 1) * 95) // 100])


# ---- the model ---------------------------------------------------------------------------------------------------

def load_interpreter(model_path: str):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        try:
            from tflite_runtime.interpreter import Interpreter
        except ImportError:
            from tensorflow.lite import Interpreter  # type: ignore
    interpreter = Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    return interpreter


def input_readings(input_shape) -> list:
    """Every (width, height, layout) a float input reads as: ``[1,H,W,3]`` (NHWC), ``[1,3,H,W]`` (NCHW) or both, as
    ``DepthTensorContract.inputReadings``."""
    shape = list(input_shape)
    if len(shape) != 4 or shape[0] != 1 or any(d <= 0 for d in shape):
        return []
    readings = []
    if shape[3] == 3:
        readings.append((shape[2], shape[1], "NHWC"))
    if shape[1] == 3:
        readings.append((shape[3], shape[2], "NCHW"))
    return readings


def single_channel_sizes(output_shape) -> list:
    """Every (height, width) a ``[1,H,W]``, ``[1,1,H,W]`` or ``[1,H,W,1]`` output reads as, as
    ``DepthTensorContract.singleChannelSizes``: with one channel both four-dimensional layouts hold the values in the
    same order, so only the size matters."""
    shape = list(output_shape)
    if not shape or shape[0] != 1 or any(d <= 0 for d in shape):
        return []
    if len(shape) == 3:
        return [(shape[1], shape[2])]
    if len(shape) == 4:
        sizes = []
        if shape[1] == 1:
            sizes.append((shape[2], shape[3]))
        if shape[3] == 1 and (shape[1], shape[2]) not in sizes:
            sizes.append((shape[1], shape[2]))
        return sizes
    return []


def resolve_tensors(input_shape, output_shape) -> tuple[int, int, str]:
    """(width, height, input layout) under the depth contract (``DepthTensorContract.resolve``): the input reading
    whose size the output has. An input whose two layouts both fit cannot be told apart and is refused."""
    readings = input_readings(input_shape)
    if not readings:
        raise ValueError(f"expected [1,3,H,W] or [1,H,W,3], got {list(input_shape)}")
    sizes = single_channel_sizes(output_shape)
    if not sizes:
        raise ValueError(f"expected [1,H,W], [1,1,H,W] or [1,H,W,1], got {list(output_shape)}")
    fitting = [r for r in readings if (r[1], r[0]) in sizes]
    if not fitting:
        raise ValueError(f"output {list(output_shape)} differs from input {list(input_shape)} in size")
    if len(fitting) > 1:
        raise ValueError(f"input {list(input_shape)} reads as both [1,3,H,W] and [1,H,W,3] at the output's size; "
                         "its layout cannot be told")
    return fitting[0]


class Model:
    def __init__(self, path: str):
        self.path = path
        with open(path, "rb") as f:
            self.sha256 = hashlib.sha256(f.read()).hexdigest()
        self.interpreter = load_interpreter(path)
        inputs = self.interpreter.get_input_details()
        outputs = self.interpreter.get_output_details()
        if len(inputs) != 1 or len(outputs) != 1:
            raise ValueError(f"expected one input and one output, got {len(inputs)} and {len(outputs)}")
        self.input, self.output = inputs[0], outputs[0]
        if self.input["dtype"] != np.float32 or self.output["dtype"] != np.float32:
            raise ValueError("the depth contract needs a float32 input and output")
        self.width, self.height, self.layout = resolve_tensors(self.input["shape"], self.output["shape"])
        self.shape_label = "x".join(map(str, self.input["shape"])) + ">" + "x".join(map(str, self.output["shape"]))

    def run(self, rgb: np.ndarray) -> np.ndarray:
        tensor = rgb[None] if self.layout == "NHWC" else np.transpose(rgb, (2, 0, 1))[None]
        self.interpreter.set_tensor(self.input["index"], np.ascontiguousarray(tensor, dtype=np.float32))
        self.interpreter.invoke()
        return self.interpreter.get_tensor(self.output["index"]).reshape(self.height, self.width).astype(F32)


# ---- one capture -------------------------------------------------------------------------------------------------

def run_file(generation: int, run_id: int) -> str:
    return f"g{generation}_r{run_id}.f32"


def process_capture(directory: str, model: Model, out_root: str) -> dict:
    capture = sc.load_capture(directory)
    records, warnings = read_records(directory)
    session_id = capture.manifest["sessionId"]
    out_dir = os.path.join(out_root, session_id)
    os.makedirs(out_dir, exist_ok=True)
    recorded_sha = (capture.manifest.get("modelArtifacts") or {}).get("depth.tflite")
    if recorded_sha and recorded_sha != f"sha256:{model.sha256}":
        warnings.append(f"the model is not the one the capture packaged ({recorded_sha})")
    runs, errors = [], []
    for record in records:
        transform = record["transform"]
        if (transform["modelWidth"], transform["modelHeight"]) != (model.width, model.height):
            raise ValueError(f"run {record['runId']}: recorded for a {transform['modelWidth']}x{transform['modelHeight']} "
                             f"input, the model takes {model.width}x{model.height}")
        planes = read_planes(directory, record)
        output = model.run(letterbox(record["frame"], planes, model.width, model.height))
        output.astype("<f4").tofile(os.path.join(out_dir, run_file(record["generation"], record["runId"])))
        phone = read_output(directory, record)
        median, p95 = relative_error(output, phone, transform)
        errors.append(median)
        runs.append({
            "generation": record["generation"], "runId": record["runId"], "fitOrdinal": record.get("fitOrdinal"),
            "file": run_file(record["generation"], record["runId"]),
            "medianRelativeError": median, "p95RelativeError": p95,
        })
    summary = {
        "sessionId": session_id,
        "model": os.path.basename(model.path),
        "modelSha256": model.sha256,
        "modelShape": model.shape_label,
        "inputLayout": model.layout,
        "runs": runs,
        "medianOfRunMedians": float(np.median(errors)) if errors else None,
        "warnings": capture.warnings + warnings,
    }
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=1)
    return summary


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("path", help="a capture directory or a folder of them")
    parser.add_argument("--model", required=True, help="the depth model file the capture ran (depth.tflite)")
    parser.add_argument("--out", required=True, help="where the PC outputs go, one folder per capture")
    parser.add_argument("--unseal", action="store_true", help="also process sealed captures")
    args = parser.parse_args(argv)

    captures = [c for c in sc.find_captures(args.path) if os.path.exists(os.path.join(c, RECORDS_FILE))]
    sealed = sc.refuse_sealed(captures, args.unseal)
    if sealed:
        print(f"refusing {len(sealed)} sealed capture(s); pass --unseal to process them", file=sys.stderr)
        return 2
    if not captures:
        print(f"no capture with {RECORDS_FILE} under {args.path}", file=sys.stderr)
        return 1
    model = Model(args.model)
    for directory in captures:
        summary = process_capture(directory, model, args.out)
        median = summary["medianOfRunMedians"]
        print(f"{summary['sessionId']}: {len(summary['runs'])} run(s), median relative error "
              f"{'n/a' if median is None else f'{median:.4f}'} ({model.shape_label})")
        for warning in summary["warnings"]:
            print(f"  warning: {warning}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
