**English** | [简体中文](geometry-probe-2026-09.zh-CN.md)

# Experiment note: geometry ground probe (2026-09-24)

The evidence behind the ground-geometry choice in
[`../local-navigation-roadmap.md`](../local-navigation-roadmap.md) §5. PC only, no device. Scripts:
[`scripts/experiments/geometry_probe/`](../../scripts/experiments/geometry_probe/). Raw summary:
[`results/summary-2026-09-24.txt`](../../scripts/experiments/geometry_probe/results/summary-2026-09-24.txt),
curb rows: [`results/curb-2026-09-24.json`](../../scripts/experiments/geometry_probe/results/curb-2026-09-24.json).
No model or dataset is committed.

## Question

Can a class-agnostic ground estimate be built from monocular relative depth, and which of three
routes holds up: (1) relative disparity only, (2) gravity + assumed camera height, (3) ARCore?
Route 3 cannot run on a PC and was assessed on paper.

## Environment

- WSL Ubuntu, Python 3.12.3, CPU only (8 threads per interpreter; three runs shared the CPU, so
  timings are indicative only and not used for any decision).
- `ai-edge-litert` 2.2.0, `numpy` 2.5.3, `opencv-python-headless` 5.0.0.93.
- Export environment: `torch` 2.13.0, `transformers` 5.17.0, `litert-torch` 0.9.4.

## Models (sha256 of the files actually run)

| File | Source | sha256 |
|---|---|---|
| `pg_depth.tflite` | Project Guideline `vision/models/depth.tflite` @ `d268de6` | `55e7465a8470f8d3f96fcac0e03b42f1812c86bd79058eb767120e4b5bfc4e31` |
| `dav2s_wi8.tflite` (518×686, int8 weights) | `litert-community/depth-anything-v2-small` @ `178427e`, `tflite/depth_anything_v2_small_wi8_afp32.tflite` | `f74509422e4a9270a354b249a9193abdd4903354be63701262238a7f4b869611` |
| `dav2s_392x518.tflite` (fp32) | `export_dav2.py 392 518` from `depth-anything/Depth-Anything-V2-Small-hf` @ `5426e4f` | `f0e3714c3c5ae7b6ee541cf8102d1a97265c785a716004be29b28a8b104285ce` |
| `dav2s_266x350.tflite` (fp32) | `export_dav2.py 266 350`, same revision | `165b33832d97524d7e877ae1d169bcb11432a83e2bca23f702de1f3077fe40ba` |

Arithmetic ops reported by the converter: 29.1 G (266×350), 66.1 G (392×518), 122.2 G (518×686).

## Data

- **DIODE val** (`http://diode-dataset.s3.amazonaws.com/val.tar.gz`): 325 indoor + 446 outdoor frames,
  1024×768, laser depth + validity mask, intrinsics fx = fy = 886.81, cx = 512, cy = 384. One indoor
  frame was dropped because no floor-plane candidate was found. The resolution sweep used every
  third frame.
- **Cityscapes8** (the 8-frame Ultralytics sample, `aachen_000000…07`): published camera intrinsics,
  height 1.22 m, pitch 0.038 rad; labelIds 7 (road) and 8 (sidewalk). Cityscapes is non-commercial;
  it was used only for this local measurement.

## Method

Per frame, on an 8-pixel grid:

1. **Ground truth plane**: back-project the laser depth of the lower 45% of the image; RANSAC planes
   with a normal within 35° of camera-down; the one with the most support is the ground (a "lowest
   plane" rule was tried and rejected because it selected spurious planes). `h_true` = camera
   distance to it. GT ground = pixels within 5 cm. A frame is *floor-visible* when GT ground covers
   more than 25% of the lower region and 0.4 m < `h_true` < 3 m.
2. **Linearity**: least-squares plane in (u, v, disparity) over GT-ground pixels; median relative
   residual.
3. **Route 1 (P1)**: RANSAC free plane in (u, v, disparity) over the lower region, relative inlier
   threshold 4%; predicted ground compared with GT ground (IoU, precision, recall).
4. **Route 1 + gravity (P1g)**: RANSAC `disparity = A·q + t` with `q = g · K⁻¹p`, `g` = the GT plane
   normal (oracle gravity), optionally perturbed by 2° or 5°.
5. **Route 2 (P2)**: from the P1g fit, metric depth `Z = A·h / (d − t)` with `h = h_true · (1 + e)`,
   `e ∈ {−15%, 0, +15%}`. Metrics: median relative depth error on GT ground within 10 m; on GT
   obstacles (more than 0.25 m above ground, within 6 m); pixel precision/recall of "more than
   0.25 m above ground"; nearest-obstacle distance in a 1 m corridor.
6. **Curbs**: road plane fitted on road pixels with gravity from the published pitch; median height
   of sidewalk pixels within 15 m; sign of the sidewalk residual.

Commands (`GEOPROBE_ROOT` holds `models/` and `data/val/`):

```
python probe.py indoors && python probe.py outdoor
python res.py
CITYSCAPES8=/path/to/cityscapes8 python curb.py
python summ.py > results/summary.txt
```

## Results (medians over floor-visible frames)

| Metric | DA V2 Small 518×686 | PG depth |
|---|---|---|
| Ground linearity, outdoor / indoor | 0.5% / 3.8% | 1.8% / 5.1% |
| P1 IoU, outdoor / indoor | 0.68 / 0.18 | 0.59 / 0.24 |
| P1 predicted ground on floor-less frames (GT ≈ 5–16%) | 49% | 48–52% |
| P1g (true gravity) IoU, outdoor / indoor | 0.66 / 0.33 | 0.50 / 0.23 |
| P1g predicted ground on floor-less frames | 17–23% | 22–26% |
| P2 ground depth error, correct h, outdoor / indoor | 0.9% / 2.3% | 1.8% / 7.3% |
| P2 ground depth error, h ± 15% | ≈ 15% | ≈ 15–18% |
| P2 ground depth error, gravity 2° / 5° off (correct h, outdoor) | 5.7% / 11.7% | 5.5% / 13.0% |
| P2 obstacle depth error, outdoor / indoor | 40% / 11% | 47% / 26% |
| "> 0.25 m above ground" precision, outdoor / indoor | 1.00 / 0.98 | 1.00 / 0.72 |
| Curb: sidewalk above road (sign); height (5 frames with sidewalk) | 93–100%; 0.07–0.22 m | 100%; 0.10–0.36 m, road inliers 22–60% |

Resolution (DA V2 Small, every third frame): 266×350 ground linearity 0.7% outdoor / 5.2% indoor vs
0.4% / 3.9% at 518×686; ground depth error with correct h 1.0% / 5.9% vs 0.7% / 3.3%.

## Caveats

- **Preprocessing is this experiment's own policy.** DA V2 inputs were centre-cropped to the tensor
  aspect (686:518) and then resized (bicubic); PG inputs were centre-cropped to a square and resized
  to 192. This differs from the `litert-community` export's documented policy (stretch to 686×518
  without keeping aspect) and from the upstream HF processor (`keep_aspect_ratio=true`, short side
  518, multiple of 14). For DIODE (4:3) the crop removes < 1% of the width; for Cityscapes (2:1) it
  removes the sides. The numbers are therefore "DA V2 Small under centre-crop preprocessing", not
  model metrics under an official policy; the production contract makes the policy explicit
  (implementation §9, depth input transform).

- DIODE is a tripod laser scanner (median outdoor height 2.4 m, indoor ~1.2 m), not a handheld phone;
  camera-height effects for a phone are therefore reported as a relative error (h ± 15%), not by
  running with 1.3 m against DIODE.
- Gravity is the GT plane normal (an oracle) plus synthetic noise; real IMU error and time alignment
  are untested.
- DIODE has few real drops, so the "below ground" metrics are not meaningful; step evidence comes
  from 5 Cityscapes frames only.
- The corridor nearest-distance metric compares different pixel sets between prediction and GT and is
  noisy; it was used only to confirm the direction of the obstacle-depth bias (too far).
- Indoor floor-visible frames are few (≈ 75), because DIODE indoor views are mostly horizontal.
