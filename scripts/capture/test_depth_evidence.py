"""Tests for depth_evidence.py: the reproduced native preprocessing against a line-by-line transcription of
``preprocessYuv`` (sailens_runtime_jni.cpp), the content rule and the comparison, and a synthetic capture
written in the app's format (docs/field-capture.md §11)."""
import json
import math
import os
import random
import struct
import tempfile
import unittest
import zlib

import numpy as np

import depth_evidence as de

f = np.float32


# ---- the kernel, transcribed one pixel at a time -----------------------------------------------------------------

def _byte(plane, x, y):
    if x < 0 or y < 0 or x >= plane["width"] or y >= plane["height"]:
        return plane["default"]
    index = y * plane["rowStride"] + x * plane["pixelStride"]
    if index < 0 or index >= plane["data"].size:
        return plane["default"]
    return int(plane["data"][index])


def _bilinear(plane, x, y):
    w, h = plane["width"], plane["height"]
    cx = f(min(max(x, f(0)), f(w - 1)))
    cy = f(min(max(y, f(0)), f(h - 1)))
    x0, y0 = int(math.floor(cx)), int(math.floor(cy))
    x1, y1 = min(x0 + 1, w - 1), min(y0 + 1, h - 1)
    dx, dy = f(cx - f(x0)), f(cy - f(y0))
    v00, v10 = f(_byte(plane, x0, y0)), f(_byte(plane, x1, y0))
    v01, v11 = f(_byte(plane, x0, y1)), f(_byte(plane, x1, y1))
    top = f(v00 + f(f(v10 - v00) * dx))
    bottom = f(v01 + f(f(v11 - v01) * dx))
    return f(top + f(f(bottom - top) * dy))


def _rgb(yv, uv, vv):
    c = max(f(yv - f(16)), f(0))
    d, e = f(uv - f(128)), f(vv - f(128))
    r = min(max(f(f(f(1.164) * c) + f(f(1.596) * e)), f(0)), f(255))
    g = min(max(f(f(f(f(1.164) * c) - f(f(0.392) * d)) - f(f(0.813) * e)), f(0)), f(255))
    b = min(max(f(f(f(1.164) * c) + f(f(2.017) * d)), f(0)), f(255))
    return r, g, b


def kernel(planes, width, height, rotation, tw, th):
    rot = ((rotation % 360) + 360) % 360
    rotated = rot in (90, 270)
    rw, rh = (height, width) if rotated else (width, height)
    scale = min(f(f(tw) / f(rw)), f(f(th) / f(rh)))
    resized_w, resized_h = f(f(rw) * scale), f(f(rh) * scale)
    pad_x, pad_y = f(f(f(tw) - resized_w) * f(0.5)), f(f(f(th) - resized_h) * f(0.5))
    sx0 = min(max(int(math.ceil(pad_x)), 0), tw)
    ex = min(max(int(math.ceil(f(pad_x + resized_w))), sx0), tw)
    sy0 = min(max(int(math.ceil(pad_y)), 0), th)
    ey = min(max(int(math.ceil(f(pad_y + resized_h))), sy0), th)
    out = np.zeros((th, tw, 3), dtype=f)
    for y in range(sy0, ey):
        ry = f(f(f(f(y) - pad_y) + f(0.5)) / scale) - f(0.5)
        for x in range(sx0, ex):
            rx = f(f(f(f(x) - pad_x) + f(0.5)) / scale) - f(0.5)
            if rot == 90:
                sx, sy = ry, f(f(height - 1) - rx)
            elif rot == 180:
                sx, sy = f(f(width - 1) - rx), f(f(height - 1) - ry)
            elif rot == 270:
                sx, sy = f(f(width - 1) - ry), rx
            else:
                sx, sy = rx, ry
            yv = _bilinear(planes["y"], sx, sy)
            uv = _bilinear(planes["u"], f(sx * f(0.5)), f(sy * f(0.5)))
            vv = _bilinear(planes["v"], f(sx * f(0.5)), f(sy * f(0.5)))
            r, g, b = _rgb(yv, uv, vv)
            out[y, x] = (f(r / f(255)), f(g / f(255)), f(b / f(255)))
    return out


def camera_planes(width, height, seed=1, truncate_chroma=1):
    """Y with row padding; U and V as views into one interleaved buffer (pixel stride 2), each a byte short of a
    whole last row, as CameraX hands them over."""
    rng = random.Random(seed)
    y_stride = width + 6
    y = np.array([rng.randrange(256) for _ in range(y_stride * height)], dtype=np.uint8)
    cw, ch = (width + 1) // 2, (height + 1) // 2
    c_stride = cw * 2 + 4
    interleaved = np.array([rng.randrange(256) for _ in range(c_stride * ch)], dtype=np.uint8)
    u = interleaved[:c_stride * (ch - 1) + (cw - 1) * 2 + 1 - (truncate_chroma - 1)].copy()
    v = interleaved[1:][:c_stride * (ch - 1) + (cw - 1) * 2 + 1 - (truncate_chroma - 1)].copy()
    def plane(data, w, h, rs, ps, default):
        return {"data": data, "width": w, "height": h, "rowStride": rs, "pixelStride": ps, "default": default}
    return {
        "y": plane(y, width, height, y_stride, 1, 16),
        "u": plane(u, cw, ch, c_stride, 2, 128),
        "v": plane(v, cw, ch, c_stride, 2, 128),
    }


class LetterboxTest(unittest.TestCase):
    def test_matches_the_kernel_pixel_by_pixel_for_every_rotation(self):
        for width, height, tw, th in ((10, 6, 7, 9), (12, 7, 9, 16), (9, 9, 5, 8)):
            planes = camera_planes(width, height, seed=width * height)
            frame = {"width": width, "height": height}
            for rotation in (0, 90, 180, 270, -90):
                frame["rotationDegrees"] = rotation
                with self.subTest(size=(width, height), target=(tw, th), rotation=rotation):
                    np.testing.assert_array_equal(de.letterbox(frame, planes, tw, th), kernel(planes, width, height, rotation, tw, th))

    def test_a_sample_past_the_end_of_its_array_reads_as_the_plane_default(self):
        planes = camera_planes(10, 6, truncate_chroma=8)
        frame = {"width": 10, "height": 6, "rotationDegrees": 90}
        np.testing.assert_array_equal(de.letterbox(frame, planes, 7, 9), kernel(planes, 10, 6, 90, 7, 9))
        index = np.arange(3)
        u = de._byte_at(planes["u"], np.full(3, 4), np.full(3, 2))
        self.assertTrue(np.all(u == 128), u)
        self.assertEqual(de._byte_at(planes["u"], index[:1], index[:1])[0], planes["u"]["data"][0])

    def test_padding_is_black_and_content_starts_at_the_ceiling_of_the_pad(self):
        planes = camera_planes(16, 9)
        frame = {"width": 16, "height": 9, "rotationDegrees": 0}
        out = de.letterbox(frame, planes, 16, 16)
        # 16x9 into 16x16: pad 3.5 rows, content rows [4, 13).
        self.assertTrue(np.all(out[:4] == 0) and np.all(out[13:] == 0))
        self.assertTrue(np.any(out[4] > 0) and np.any(out[12] > 0))


class ComparisonTest(unittest.TestCase):
    def test_content_rule_matches_the_disparity_map(self):
        # 640x1280 into 518x518 pads 129.5: column 129 is padding, 388 holds the clamped edge.
        scale = min(f(518) / f(640), f(518) / f(1280))
        self.assertEqual(de.content_range(129.5, 640, scale, 518), range(130, 388))
        self.assertEqual(de.content_range(0.0, 720, 0.175, 126), range(0, 126))

    def test_relative_error_reads_only_what_the_model_saw(self):
        transform = {"padLeft": 2.0, "sourceWidth": 6.0, "scaleX": 1.0, "padTop": 0.0, "sourceHeight": 4.0, "scaleY": 1.0}
        reference = np.full((4, 10), 2.0, dtype=f)
        candidate = reference * f(1.01)
        candidate[:, :2] = 1e6
        candidate[:, 8:] = np.nan
        median, p95 = de.relative_error(reference, candidate, transform)
        self.assertAlmostEqual(median, 0.01, places=5)
        self.assertAlmostEqual(p95, 0.01, places=5)
        # A non-finite value inside the content is as wrong as a value can be: a row of them reaches the p95.
        candidate[0, 2:8] = np.inf
        self.assertEqual(de.relative_error(reference, candidate, transform)[1], math.inf)

    def test_identical_zero_outputs_agree_and_a_value_against_zero_is_infinitely_off(self):
        # compareDisparity's rule where the denominator is 0 (DepthConsistencyTest).
        transform = {"padLeft": 0.0, "sourceWidth": 4.0, "scaleX": 1.0, "padTop": 0.0, "sourceHeight": 4.0, "scaleY": 1.0}
        zeros = np.zeros((4, 4), dtype=f)
        self.assertEqual(de.relative_error(zeros, zeros.copy(), transform), (0.0, 0.0))
        self.assertEqual(de.relative_error(zeros, np.full((4, 4), 0.1, dtype=f), transform), (math.inf, math.inf))
        self.assertEqual(de.relative_error(zeros, np.full((4, 4), np.nan, dtype=f), transform), (math.inf, math.inf))

    def test_shapes_resolve_as_the_contract_resolves_them(self):
        # The cases of DepthTensorContractTest: (input, output) -> (width, height, layout), or refused.
        resolved = {
            ((1, 3, 392, 224), (1, 392, 224)): (224, 392, "NCHW"),
            ((1, 3, 512, 288), (1, 1, 512, 288)): (288, 512, "NCHW"),
            ((1, 392, 224, 3), (1, 392, 224, 1)): (224, 392, "NHWC"),
            # A shape that reads two ways takes the reading whose input and output sizes match.
            ((1, 3, 4, 3), (1, 4, 3)): (3, 4, "NCHW"),
            ((1, 3, 4, 3), (1, 3, 4)): (4, 3, "NHWC"),
            ((1, 1, 4, 3), (1, 1, 4, 1)): (4, 1, "NHWC"),
            ((1, 3, 4, 1), (1, 1, 4, 1)): (1, 4, "NCHW"),
        }
        for (input_shape, output_shape), expected in resolved.items():
            with self.subTest(input=input_shape, output=output_shape):
                self.assertEqual(de.resolve_tensors(input_shape, output_shape), expected)
        refused = [
            ((1, 3, 3, 3), (1, 3, 3)),        # both layouts fit: the layout cannot be told
            ((1, 3, 4, 3), (1, 5, 5)),        # neither fits
            ((1, 3, 224, 392), (1, 112, 112)),
            ((1, 4, 392, 224), (1, 392, 224)),
            ((1, 3, 0, 224), (1, 392, 224)),
            ((1, 3, 392, 224), (1, 2, 392, 224)),
            ((1, 3, 392, 224), (392, 224)),
        ]
        for input_shape, output_shape in refused:
            with self.subTest(input=input_shape, output=output_shape):
                with self.assertRaises(ValueError):
                    de.resolve_tensors(input_shape, output_shape)


# ---- a synthetic capture in the app's format ---------------------------------------------------------------------

class FakeModel:
    """Stands in for the interpreter: disparity = the input's red channel."""
    def __init__(self, width, height):
        self.path, self.sha256 = "fake.tflite", "00"
        self.width, self.height, self.layout = width, height, "NHWC"
        self.shape_label = f"1x{height}x{width}x3>1x{height}x{width}"

    def run(self, rgb):
        return rgb[:, :, 0].astype(f)


def write_capture(root, session_id, width=10, height=6, tw=7, th=9, records=2):
    directory = os.path.join(root, session_id)
    os.makedirs(directory)
    with open(os.path.join(directory, "manifest.json"), "w") as out:
        json.dump({"schemaMajor": 1, "schemaMinor": 9, "sessionId": session_id, "captureMode": "field_evidence",
                   "startedWallMs": 0, "startedElapsedRealtimeNanos": 0, "deviceManufacturer": "Acme",
                   "deviceModel": "Phone", "sdkInt": 36, "complete": True,
                   "depthEvidence": {"poolSize": 4, "queueCapacity": 4, "stats": {"recordsWritten": records}}}, out)
    data = bytearray()

    def blob(raw: bytes):
        ref = {"offset": len(data), "length": len(raw), "crc32": zlib.crc32(raw)}
        data.extend(raw)
        return ref

    lines = []
    for run_id in range(1, records + 1):
        planes = camera_planes(width, height, seed=run_id)
        frame = {"width": width, "height": height, "rotationDegrees": 90}
        # The phone's output: what the fake model gives on the kernel's input, a little off as FP16 would be.
        phone = (kernel(planes, width, height, 90, tw, th)[:, :, 0] * f(1.002)).astype("<f4")
        refs = {name: blob(planes[name]["data"].tobytes()) for name in ("y", "u", "v")}
        output = blob(phone.tobytes())
        scale = min(f(tw) / f(height), f(th) / f(width))
        lines.append(json.dumps({
            "type": "depth_evidence", "generation": 1, "runId": run_id, "fitOrdinal": run_id - 1, "outcome": "published",
            "frameSequence": run_id * 10, "sensorTimestampNanos": run_id * 333_000_000,
            "frame": {"width": width, "height": height, "rotationDegrees": 90, **{
                name: {"width": planes[name]["width"], "height": planes[name]["height"],
                       "rowStride": planes[name]["rowStride"], "pixelStride": planes[name]["pixelStride"], "data": refs[name]}
                for name in ("y", "u", "v")}},
            "geometry": {}, "gridWidth": 9, "gridHeight": 16,
            "transform": {"analysisWidth": height, "analysisHeight": width, "modelWidth": tw, "modelHeight": th,
                          "policy": "PAD_KEEP_ASPECT", "sourceLeft": 0, "sourceTop": 0, "sourceWidth": height,
                          "sourceHeight": width, "scaleX": float(scale), "scaleY": float(scale),
                          "padLeft": float((f(tw) - f(height) * scale) * f(0.5)), "padTop": float((f(th) - f(width) * scale) * f(0.5))},
            "output": {"width": tw, "height": th, "data": output},
            "modelShape": "fake",
        }))
    with open(os.path.join(directory, "depth.bin"), "wb") as out:
        out.write(bytes(data))
    with open(os.path.join(directory, "depth.jsonl"), "w") as out:
        out.write("\n".join(lines) + "\n" + '{"type":"depth_evid')  # a torn last line
    return directory


class CaptureTest(unittest.TestCase):
    def test_reads_records_planes_and_outputs_checked_by_crc(self):
        with tempfile.TemporaryDirectory() as root:
            directory = write_capture(root, "s1")
            records, warnings = de.read_records(directory)
            self.assertEqual([1, 2], [r["runId"] for r in records])
            self.assertEqual(1, len(warnings))
            planes = de.read_planes(directory, records[0])
            np.testing.assert_array_equal(planes["y"]["data"], camera_planes(10, 6, seed=1)["y"]["data"])
            self.assertEqual((9, 7), de.read_output(directory, records[0]).shape)
            records[0]["output"]["data"]["crc32"] ^= 1
            with self.assertRaises(ValueError):
                de.read_output(directory, records[0])

    def test_writes_each_run_where_the_jvm_replay_reads_it(self):
        with tempfile.TemporaryDirectory() as root:
            directory = write_capture(root, "s1")
            out = os.path.join(root, "pc")
            summary = de.process_capture(directory, FakeModel(7, 9), out)
            self.assertEqual(2, len(summary["runs"]))
            for run in summary["runs"]:
                self.assertAlmostEqual(run["medianRelativeError"], 0.002, places=4)
                path = os.path.join(out, "s1", f"g1_r{run['runId']}.f32")
                self.assertEqual(7 * 9 * 4, os.path.getsize(path))
            self.assertTrue(os.path.exists(os.path.join(out, "s1", "index.json")))

    def test_sealed_captures_are_refused(self):
        with tempfile.TemporaryDirectory() as root:
            write_capture(root, "s1")
            open(os.path.join(root, "SEALED"), "w").close()
            self.assertEqual(2, de.main([root, "--model", "unused.tflite", "--out", os.path.join(root, "pc")]))


if __name__ == "__main__":
    unittest.main()
