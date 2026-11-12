**English** | [简体中文](models.zh-CN.md)

# Model contract and backend configuration

> **This repository ships no model weights.** The app is bring-your-own-model: supply a TFLite graph
> that satisfies the contract below, put it at the agreed path, and it runs.
> `app/src/main/assets/*.tflite` is git-ignored, so a working copy can carry weights without them
> entering any commit.
> With no weights present, nothing fails: preflight finds no model and, since this build promises
> nothing, the app opens on a zero-pipeline screen that points here. An edition that declares
> navigation required treats a missing or mismatched model as a configuration failure instead — a
> fatal screen, a haptic signal and the reason spoken aloud (architecture.md §5.2). A model that
> passes preflight but cannot start on a particular device is a runtime failure and shows the
> retryable start-analysis error.

Sailens splits the blind-navigation problem into two models:

- `sem` — semantic walkable-area model: where you can walk, road boundaries, ground classes.
- `det` — realtime obstacle model: bounding-box detection. Obstacle shape and occlusion are derived
  by cross-validating det boxes against the sem mask (see `ObstacleOcclusionAnalyzer`); there is no
  separate instance-segmentation model.

The runtime profile lives in `app/src/main/java/com/sailens/app/SailensRuntimeProfile.kt`; it
decides per-model role parameters, backend target, and pipeline cadence. There is one profile,
`standard`: every model, the VLM included, targets the GPU. An `ultra` tier that reserved the
NPU for the VLM was never selected and has been removed. Physical model files are resolved by `ModelSourceResolver` / `ModelCatalog` from
`(ModelType, actual accelerator)`.

Orthogonal to the runtime profile is the **perception profile** (`PerceptionProfile`, user-selectable; see
[`perception-profiles.md`](perception-profiles.md)): `BASIC` runs `sem` only, `DEFAULT` runs
`sem + det`. Scheduling runs each model on its own target-FPS interval (`PerceptionScheduler`);
frames where det does not run are compensated by tracker prediction, and tracks expire on
`detectionResultTtlMs`.

## Where to put a model

```text
app/src/main/assets/sem.tflite     # semantic segmentation
app/src/main/assets/det.tflite     # obstacle detection
```

File names are fixed by `ModelCatalog`, and which file a model is belongs to the host: it binds a
`ModelSourceResolver` (this repository's reference host binds `CatalogModelSourceResolver` in
`app/ProfileBindingsModule.kt`) and passes that same instance to its static preflight, asked for the
accelerator each model config runs on, so preflight reads the file the runner will load. The platform
modules never bind or default a resolver. A distribution that packages files differently — another
name, a download, a per-device choice — supplies its own resolver; nothing in the platform changes.

Input/output tensor names, input type, NHWC/NCHW layout, and
quantization scale/zero-point are **all read from the TFLite metadata automatically** — tensor names
are not a config field. The code resolves I/O by the contract's shape and role and executes via
LiteRT's list-based buffer API, so exporter-generated internal names never end up in configuration.

This means swapping a model normally **requires no code change**: a conforming graph just works.
Only changing the contract itself (input geometry, class set) requires touching `resizeFilter` /
`acceleratorBackend` in `SailensRuntimeProfile.kt`, or adding a decode branch.

## The `sem` contract

**Output must be 19 dense class scores; the app does its own argmax.** NHWC/NCHW is auto-detected.

```text
input    [1, H, W, 3]
output   [1, h, w, 19]  or  [1, 19, h, w]     # 19 = Cityscapes trainId class count
```

Pre-argmaxed label maps are **not supported** and fail cleanly at init (no 19-channel tensor found).

> 🔴 **Class order is validated by channel count only, never by meaning.** A wrong order does not
> error — it **silently misbehaves**: the model will call a sidewalk a road, out loud, to someone who
> cannot see it. This is a safety hazard, not a formatting preference.

Channel order must be Cityscapes trainId (the class list lives in `CityscapesTaxonomy`, the
navigation meaning in `CityscapesNavigationSemantics`):

```text
0  road          5  pole          10 sky          15 bus
1  sidewalk      6  traffic light 11 person       16 train
2  building      7  traffic sign  12 rider        17 motorcycle
3  wall          8  vegetation    13 car          18 bicycle
4  fence         9  terrain       14 truck
```

When bringing a new sem model, **verify the semantics of the argmax output on a known scene first**,
before discussing accuracy.

## The `det` contract

**A single output tensor**, in one of two layouts, auto-resolved from the output shape
(`ObstacleDetectionLayout`):

```text
RAW_TRANSPOSED   [1, 4 + classCount, N]     first 4 are cx, cy, w, h; then per-class scores
                                            (attribute-major)
END_TO_END       [1, N, 6]                  x1, y1, x2, y2, conf, classId
```

**Box units are declared, not inferred.** `DetectionModelConfig.coordinateSpace` says whether boxes
are `NORMALIZED` ([0, 1] of the model input; the default, and what Ultralytics TFLite exports
produce) or `MODEL_PIXELS` (0..640 for a 640 input). The output cannot tell you: a tiny box in the
top-left corner of a pixel-space head is numerically a normalised box. A wrong value misplaces every
box without an error, so, like the class order, check it by hand when you swap a det model.

`END_TO_END` is the natural shape for DETR-family exports. Multi-tensor outputs (separate
boxes / scores / class_idx) are **not currently supported**; they need a new `ObstacleDetectionLayout`
case plus a decode branch.

The runtime keeps only allowed obstacle classes and de-duplicates with class-aware NMS. Class order
is hardcoded COCO 80 (`CocoTaxonomy`, read for meaning through `CocoNavigationSemantics`) and, as
with sem, **a wrong order silently misbehaves**.

```kotlin
DetectionModelConfig(
    classCount = 80,
    coordinateSpace = DetectionCoordinateSpace.NORMALIZED,
)
```

When swapping a det model, **confirm the output shape and the box units first** so box/class
dimensions are not misinterpreted.

## The `depth` contract

An optional third model, `app/src/main/assets/depth.tflite` (git-ignored like the others), for the
ground geometry (`docs/geometry.md` §6). No file means depth is not configured; nothing else changes.
Swapping one conforming depth model for another is a file swap: the code names no model family and
has no branch per model. Shapes and layout come from the metadata; the normalization is inside the
model; the output's scale and shift are fitted per frame by `GroundFitter`, whose thresholds are depth
ratios. The runtime choices (accelerator, GPU precision and queue priority) are the host's
`DepthModelConfig` in its runtime profile.

```text
input    one float32 tensor  [1,3,H,W] or [1,H,W,3]   RGB in [0,1], any normalization inside the model
output   one float32 tensor  [1,H,W], [1,1,H,W] or [1,H,W,1]   the same H and W as the input
         relative disparity: larger is nearer, a/Z + b with a and b unknown per frame
```

- **Output size equals input size.** Output pixel (x, y) is input pixel (x, y), so the input's
  transform maps it. A model whose output is a half or a quarter of its input is refused by the
  preflight (`OutputSizeMismatch`), not remapped: that needs its own coverage rule first.
- **Disparity only.** A relative-depth output usually carries an unknown shift too, and its inverse is
  then not `a/Z + b`; such a model passes the preflight and is judged `INVERSE_OUTPUT` on every frame.
  Metric depth would need its own contract.
- **Finite values.** A run whose output holds a NaN or an infinity counts as a failed run.
- **A shape that reads two ways** (`[1,3,4,3]` is NCHW 3×4 or NHWC 4×3; `[1,1,4,1]` is a 4×1 or a 1×4
  map) is read the way under which input and output have the same size. An input whose two layouts both
  fit (`[1,3,3,3]`) is refused: its layout cannot be told.
- **Resize is fixed: `PAD_KEEP_ASPECT`**, the native letterbox, the only resize the native
  preprocessing does. A tensor with the frame's aspect (portrait 9:16) pads almost nothing; padding is
  unknown to the ground fit. A tensor pixel is image content only when the kernel wrote image data
  there (columns `[ceil(pad), ceil(pad + content))`) **and** its centre lies inside the content.
- **Native preprocessing only, no shared cache.** The OpenCV fallback truncates sizes and offsets to
  integers, which would not match the transform; a frame the native path cannot take is reported.
- **Normalization inside the model**: the input is `[0,1]` like the other models'. When a model expects
  ImageNet normalization, fold it in at export (the reference export below does). A black letterbox
  pixel, 0 before the fold, still meets the model as normalized black.
- **The class order problem does not exist here**; an inverted output is found per frame by the fit.
- **Must pass the accelerator consistency check** (below). Two exports have compiled or run on the
  LiteRT 2.1.5 GPU and answered wrongly without an error: one with the position-embedding
  interpolation left in the graph (`GATHER_ND`, `BROADCAST_TO`: the GPU does not compile it), and one
  whose token activations broadcast between rank 2 `[N,C]` and rank 3 `[1,N,C]` around
  `FULLY_CONNECTED` (the GPU treats N as a batch; logcat prints one `Shape mismatch ... conv_wave_memory`
  per block). [`scripts/depth/patch_rank3.py`](../scripts/depth/patch_rank3.py) keeps them rank 3.

**Reference model**: Depth Anything V2 Small (Apache-2.0 code and weights), portrait 392×224, GPU FP16
with LOW queue priority. In the app on SM8850 (3 Hz beside sem+det): inference and output read p95
43 ms (max 70 ms), the ground fit p50 146 ms on the CPU, and sem/det no slower than with the lane off.
On its own the model takes p95 23–28 ms at DEFAULT priority; at LOW priority with nothing else on the
GPU it runs five to six times slower, so LOW relies on sem+det keeping the GPU busy
([experiments/depth-lane-device-2026-11.md](experiments/depth-lane-device-2026-11.md)). Export it with
[`scripts/depth/dav2/export.py`](../scripts/depth/dav2/export.py), which folds the normalization and the
position embedding in, applies the rank-3 patch and checks on the CPU that none of it changed the
output. With `--fp16` it stores the weights as float16 (49.9 MB instead of 98.0 MB at 392×224; the CPU
output moves by a median 1.3e-4); the GPU computes in FP16 either way. Distill-Any-Depth Small has the same
architecture and fits the contract the same way.

**ZipDepth** (its repository, which holds the checkpoints, is MIT and states no separate terms for the
weights; distilled from Depth Anything V2 Large, whose weights are CC BY-NC 4.0) is a CNN that fits the contract as it is: its forward pass normalizes the input and outputs
affine-invariant inverse depth. Portrait 512×288 costs 2.9 GMACs against the reference's 13.7. Export it from
a checkout of its repository with [`scripts/depth/zipdepth/export.py`](../scripts/depth/zipdepth/export.py)
(24.7 MB, 12.5 MB with `--fp16`). Swapped in on SM8850 without a code change, it ran at inference and output
read p50 7 ms, p95 31 ms beside sem+det
([experiments/depth-lane-device-2026-11.md](experiments/depth-lane-device-2026-11.md) §5).

**Swapping a depth model** changes no code but needs the same acceptance as the first one:
1. the preflight reports `Compatible`;
2. the debug Model consistency check passes for depth on the target device;
3. its ground quality is measured offline on the device's preprocessing (reproduced by
   `scripts/capture/depth_evidence.py`, [field-capture.md](field-capture.md) §11), **including covered-lens
   frames** (a model can match the reference outdoors and still be far worse indoors and on a covered
   lens);
4. the device budget is met on the target device.

What a swap must **not** change: `GroundFitter`'s thresholds, the run-failure count, freshness, the
capability table, the consistency thresholds. If a model needs any of those changed it does not fit the
contract, or the contract has to change first. Which file a model is stays the host's choice
(`ModelSourceResolver`, above): a host that ships different depth models per device does it there.

## Normalization

⚠️ **`/255` is already baked into float preprocessing** (`OpenCVImageProcessor`'s
`convertTo(..., 1.0/255.0)`).

`config.mean/std` are applied **on top of `[0,1]`** and are currently fixed at mean=(0,0,0),
std=(1,1,1). When wiring up any per-model normalization, **do not touch `/255` again — you will
double-scale.** The OpenCV fallback ignores `mean/std` altogether and only divides by 255, so that
fixed value is also the only one the fallback supports; a model that needs normalization carries it
inside (as the `depth` contract requires).

GPU precision: the sem and det sessions are built without GPU options, so they run LiteRT's default
precision, which is FP16 on the GPUs measured so far (SM8850, LiteRT 2.1.5). Their consistency results
above are at that precision. The depth model asks for its precision and queue priority explicitly
(`DepthModelConfig`).

## int8 / uint8 / float32

`inputDataType = ModelInputDataType.AUTO` by default, inferred from the TFLite input tensor type.
Prefer `AUTO` when metadata is trustworthy.

Normalization and write format are bound to the **model's input tensor type**, not the backend:
FLOAT32 inputs write a `FloatArray`; INT8/UINT8 inputs write a quantized `ByteArray`. Scale and
zero-point are read from metadata; the config's `inputQuantization` is only a fallback for when
metadata is missing.

> LiteRT's buffer API only offers `writeInt8 / readInt8 / writeFloat / readFloat` — there are **no
> uint8-specific methods**. uint8 travels as raw bytes and signedness is interpreted on the
> Kotlin/native side. The uint8 path **deliberately bypasses the native int8 fast path**: signed and
> unsigned flip sign at 128, which would destroy the argmax.

`int8` / `quant` in a file name means nothing — input/output tensors may still be FLOAT32.
**Trust the metadata, not the name.**

Specify explicitly only when metadata is unreliable:

```kotlin
ObstacleModelConfig(
    inputDataType = ModelInputDataType.INT8,
    inputQuantization = ModelInputQuantization(scale = 1f / 255f, zeroPoint = -128),
)
```

`sem` is the same, via `inputDataType` / `acceleratorBackend` on `SemanticModelConfig`.

## 🔴 Performance red lines

Both sem and det have a **zero-copy native handle fast path** that skips roughly 30 MB of
`readFloat()` allocation per frame. These two trace fields are the regression gate:

```text
sem:  postprocessBackend = "native_score"                 and  outputReadTimeMs ≈ 0
det:  postprocessBackend = "native_bbox_nms_float_handle"
```

**No change should break them.** Note they only hold under a **float, single-tensor** contract:
uint8 outputs or multi-tensor outputs fall back to the Kotlin decode path. Their measured state on a
target device, with its conditions, is in [architecture.md](architecture.md) §12.3.

## 🔴 Accelerator result consistency

> 🔴 **A model that runs on the GPU without an error can still compute wrongly there.** Like the
> class order, nothing in the output looks broken; a user who cannot see the scene is the one
> misled. Before using a model on an accelerator, check that it gives the CPU's answer.

The sem and det exports this repository had been developed against (onnx2tf, float16,
NHWC) ran on the LiteRT GPU accelerator on SM8850 and SM8450 with no error, and answered wrongly:
indoor floors and pale paving came out as `building`, and an office chair the CPU detected at 92% on
every frame was never detected. Forcing FP32, LiteRT 2.3.0, the resize filter and the zero-copy read
were all ruled out. A different export of the same models answers correctly: `yolo26n-sem_w8a32.tflite`
and `yolo26n_w8a32.tflite` from release v0.6.6 of `ultralytics/yolo-flutter-app`, exported by
Ultralytics 8.4.104 with `format=litert` and `quantize=w8a32` (NCHW). On a OnePlus CPH2747 (SM8850,
Android 16, LiteRT 2.1.5 GPU) the check below gives:

| Export | sem pixel agreement (average / lowest frame) | det: CPU detections → found on GPU, extra | Result |
|---|---|---|---|
| Ultralytics w8a32 NCHW | 96.4% / 93.3% | 16 → 16 (recall 1.0), 0 extra | PASS |
| onnx2tf float16 NHWC | 39.1% average | 21 → 0 | fails |

The cause is in how a particular graph meets the accelerator, so it can reappear with any new model
file, LiteRT version or device.

**Check** with the debug build: Settings → Diagnostics → **Model consistency check**. Hold the phone
still, pointed at the floor with an object in view (a chair, a person). Each model runs twice on 8
camera frames, once on its configured accelerator and once on the CPU, and the two answers are
compared:

| Model | Pass | Fail | Otherwise |
|---|---|---|---|
| sem | pixel agreement ≥ 95% on average and ≥ 90% on every frame | average < 80% | inconclusive: usually a shaking phone or a blurred view (a correct model measured 86–96% while shaken); retry held still |
| det | of the CPU's detections, ≥ 90% also found on the accelerator (same class, IoU ≥ 0.5), and ≤ 10% extra | either bound missed | inconclusive when nothing was detected: point at objects |
| depth (when configured) | every frame's median relative disparity difference ≤ 2%, the two ground fits agree on validity on ≥ 7 of the 8 frames, and their labels agree ≥ 95% where both are valid | a NaN or infinite value in either output on any frame (that frame is not fitted), any frame > 10%, or the fits agree on ≤ 4 of 8 with geometry on ≥ 7 | inconclusive in between, and when fewer than 7 frames had orientation: the count stays out of 8 |

For depth the check holds the gravity sensor itself (Guidance is stopped) and resolves each frame's
orientation as the frame is taken, before any model runs; each side fits its own `GroundFitter`. The
relative difference is `|accelerator − CPU| / max(|CPU|, 5% of max|CPU|)` over the content pixels:
correct exports measured 0.3–0.5% median on the GPU in FP32 and FP16, the broken ones in the contract
above 60–97%. A host without `depth.tflite` gets no depth card and an unchanged overall verdict.

Detections are judged by the production decision at the threshold. A detection that crosses it on
one side only counts as jitter when the two confidences are within 0.10 of each other (0.41 against
0.36); 0.90 against 0.26 is an object the accelerator loses in production and fails the check.
PASS needs all 8 frames compared: a camera timeout, or a frame whose pixels changed during the
comparison, leaves the model inconclusive (still failed if the compared frames already fail).
Detections are compared model-wide, every class and up to 100 per frame, not Guidance's 10. PASS
overall means every model that runs on an accelerator was compared and passed; the report records
how many were. A correct accelerator differs from the CPU only
along class boundaries; a wrong one replaces whole regions, which the result names (for example
`car→building 31%`). The report is written to `files/diagnostics/consistency/` with the model
hashes, numbers only.

Run it whenever the model file, the LiteRT version or the target device changes. A distribution
that ships models is responsible for running it on the devices it supports.

## Layout: NHWC is faster, correctness first

NCHW exports with a float32 I/O wrapper cost a per-frame host transpose and fragment on the GPU/NPU
delegates, so an NHWC graph is usually faster. Speed only counts after the accelerator consistency
check passes: the fast NHWC exports above were the ones that computed wrongly on the GPU.

What correctness costs, on the same OnePlus CPH2747 (SM8850, Android 16, LiteRT 2.1.5 GPU) with the
same debug build and both models on the GPU:

| Exports (sem + det) | Pipeline median (p90) | Frames out per second | Camera frames dropped | sem postprocess median |
|---|---|---|---|---|
| Ultralytics w8a32 NCHW (correct) | 43 ms (58 ms) | 22 | 27% | 26 ms |
| onnx2tf float16 NHWC (wrong on the GPU) | 28 ms (37 ms) | 29 | 0.3% | 15 ms |

The gap is in the sem postprocess, which waits for the GPU's result.

## Backend selection

Each model names its preferred backend in the runtime profile; there is no benchmark-based
auto-selection:

```kotlin
ModelAcceleratorBackend.CPU
ModelAcceleratorBackend.GPU
ModelAcceleratorBackend.NPU
```

`acceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT` by default: only the named
backend is attempted, and init failure is a failure. LiteRT's NPU compile path may still partition
or fall back to CPU *inside* the model; the app layer will not silently switch NPU to GPU/CPU.
**Do not enable fallback while debugging model/backend compatibility** — it masks the real failure.

Requesting NPU for a vision model is currently **unsupported (throws)**: `ModelCatalog` has no NPU
model files. NPU support is a possible future capability, not a current allocation.

If `PREFER_BACKEND` / `FIRST_AVAILABLE` are enabled later, `LiteRtSessionFactory` calls
`ModelSourceResolver` for each actual accelerator attempt, so falling back to another accelerator
re-selects the model file and reads metadata from the final source.

## Preprocessing sampling

```kotlin
resizeFilter = ResizeFilter.NEAREST     // default: performance-first for the realtime path
resizeFilter = ResizeFilter.BILINEAR    // switch temporarily to compare model quality
```

For a 640×640 input, `NEAREST` avoids bilinear interpolation across the Y/U/V planes for every
output pixel. `resizeFilter` is part of the same-frame preprocessing cache key, so different
sampling strategies never reuse each other's input.

## Enabling / diagnosing the Qualcomm NPU

The NPU (Hexagon HTP) call chain, runtime delivery (dynamic feature, JIT vs AOT), local build and
install, diagnosis, and the case studies from getting it working are in a separate document:
**[npu-litert-qnn.md](npu-litert-qnn.md)**.

## Reading the UI and traces

The live debug panel and trace replay surface:

- semantic provider / obstacle provider
- accelerator: `CPU` / `GPU` / `NPU`
- accelerator selection: `explicit[...]` or `prefer_backend[...]`
- preprocess backend: e.g. `native_yuv`, `cached|native_yuv`, `shared_native_yuv`,
  `shared_quantized_native_yuv`
- postprocess backend: e.g. `native_score`, `native_score_int8`, `native_bbox_nms`,
  `native_bbox_nms_int8`

In the text trace report:

```text
runFps=sem:... obs:... runs=sem:... obs:...
backend=sem:... obs:...
logicMs=analyze:... decide:... total:...
semMs=total:... pre:... infer:... read:... post:...
obsMs=total:... pre:... infer:... read:... post:...
```

`obs` is this frame's obstacle model (det) slot.

## Inspecting a model

```bash
py scripts/inspect_tflite.py <path>
```

Prints input/output tensor shapes, dtypes, quantization parameters, and the op list — run it against
the contract above before wiring up a new model.
