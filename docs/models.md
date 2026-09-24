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

Orthogonal to the tier is the **perception profile** (`PerceptionProfile`, user-selectable; see
[`perception-profiles.md`](perception-profiles.md)): `BASIC` runs `sem` only, `DEFAULT` runs
`sem + det`. Scheduling runs each model on its own target-FPS interval (`PerceptionScheduler`);
frames where det does not run are compensated by tracker prediction, and tracks expire on
`detectionResultTtlMs`.

## Where to put a model

```text
app/src/main/assets/sem.tflite     # semantic segmentation
app/src/main/assets/det.tflite     # obstacle detection
```

File names are fixed by `ModelCatalog`. Input/output tensor names, input type, NHWC/NCHW layout, and
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

`END_TO_END` is the natural shape for DETR-family exports. Multi-tensor outputs (separate
boxes / scores / class_idx) are **not currently supported**; they need a new `ObstacleDetectionLayout`
case plus a decode branch.

The runtime keeps only allowed obstacle classes and de-duplicates with class-aware NMS. Class order
is hardcoded COCO 80 (`CocoTaxonomy`, read for meaning through `CocoNavigationSemantics`) and, as
with sem, **a wrong order silently misbehaves**.

```kotlin
ObstacleModelConfig(
    classCount = 80,
)
```

When swapping a det model, **confirm the output shape first** so box/class dimensions are not
misinterpreted.

## Normalization

⚠️ **`/255` is already baked into float preprocessing** (`OpenCVImageProcessor`'s
`convertTo(..., 1.0/255.0)`).

`config.mean/std` are applied **on top of `[0,1]`** and are currently fixed at mean=(0,0,0),
std=(1,1,1). When wiring up any per-model normalization, **do not touch `/255` again — you will
double-scale.**

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
uint8 outputs or multi-tensor outputs fall back to the Kotlin decode path.

## Layout: prefer NHWC

NCHW exports with a float32 I/O wrapper cost a per-frame host transpose and fragment on the GPU/NPU
delegates. Prefer NHWC graphs.

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

Requesting NPU for a vision model is currently **unsupported (throws)** — the NPU is reserved for
the future VLM path.

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
