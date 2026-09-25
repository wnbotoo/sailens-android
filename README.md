**English** | [简体中文](README.zh-CN.md)

# Sailens Android

**Sailens** — *Smart AI LENS*.

Android navigation assistance for blind and low-vision users. A camera-fed perception pipeline
turns the scene in front of you into speech and haptics: where you can walk, where the road edge is,
and what is in the way.

**License: Apache-2.0.** No model weights ship with this repository — see
[Bring your own model](#bring-your-own-model).

## Status

Pre-release. The pipeline, UI, and runtime are implemented; on-device tuning is ongoing.
Target devices are Snapdragon 8 Gen 1 and beyond class hardware.

## Repository role

This repository is **Sailens Android**: the Apache-2.0, model-neutral Android platform and
reference host that implements reusable Sailens capabilities. It is buildable and installable for
development and BYO-model validation, but it is **not** the app-store product.

The official first-party Android distribution is maintained separately as
`wnbotoo/sailens-app` and ships to users under the product name **Sailens**. This reference host
uses `applicationId = "com.sailens.reference"`; the official product owns
`applicationId = "com.sailens"`. The repository/product boundary, release model and decision to
defer Maven publication are documented in
[`docs/distribution-model.md`](docs/distribution-model.md).

## Bring your own model

This repository ships **no model weights**. The app resolves two graphs at runtime:

```text
app/src/main/assets/sem.tflite     # semantic walkable-area segmentation
app/src/main/assets/det.tflite     # obstacle detection
```

Both paths are git-ignored, so a working copy can carry weights without them entering a commit.
Drop in any TFLite graph that satisfies the contract and the pipeline picks it up — shape, layout,
dtype, and quantization are all read back from the model's metadata at load time, so **swapping a
model normally needs no code change**.

With no weights present, nothing fails: preflight finds no model and, since this build promises
nothing, the app opens on a zero-pipeline screen that points here. The official distribution, which declares
navigation required, treats a missing or mismatched model as a configuration failure
instead — a fatal screen, a haptic signal, and the reason spoken aloud. A model that passes preflight
but cannot start on a particular device is a runtime failure and shows the retryable start-analysis
error.

> **Read [`docs/models.md`](docs/models.md) before bringing a model.** The contract is not just
> shapes: class channel order is validated only by *count*, never by meaning. A model with the right
> shape but a different class order will run without any error and quietly mislabel the world for
> someone who cannot see it. That is a safety property, not a formatting preference.

Summary of the contract:

| | input | output | class order |
|---|---|---|---|
| `sem` | `[1, H, W, 3]` | `[1, h, w, 19]` dense scores (app argmaxes) | Cityscapes trainId |
| `det` | `[1, H, W, 3]` | `[1, 4+classCount, N]` or `[1, N, 6]`, single tensor | COCO 80 |

Model weights carry their own licenses and dataset terms, independent of this repository's
Apache-2.0 code license. Whatever you bring, that is yours to check.

## Architecture

Nine reusable `sailens-*` libraries plus the reference app, organised around the two product
pipelines rather than technical layers -- see [docs/architecture.md](docs/architecture.md).

```text
:sailens-core     shared contracts: ImageFrame, geometry, BinaryMask, MlRuntimeInfo, LogService
:sailens-camera   CameraX capture, FrameSource / FrameSnapshotProvider, preview, permission state
:sailens-runtime  LiteRT sessions, accelerator selection, model sources, YUV preprocessing
:sailens-vision   segmentation / detection runners, dataset taxonomies, postprocessors
:sailens-vlm      VLM engine contract: frame + prompt -> streamed text
:sailens-output   TTS, audio focus, screen-reader detection, haptic primitive
:sailens-guidance navigation logic: semantics, connectivity, safety, events, depth, trace
:sailens-describe Describe product logic: prompts, snapshot freshness, scheduling
:sailens-shell    reusable presentation: SailensRoot(), navigation, design system, Guidance + Describe UI
:app              Koin wiring, Application/MainActivity, runtime profile, edition spec
```

Dependencies point downward only; Guidance and Describe never depend on each other. Frames flow
`CameraX → ImageFrameAnalyzer → SharedFlow<ImageFrame> → ProcessFrameUseCase → AnalyzeSceneUseCase
→ DecideEventsUseCase → speech/haptics`.

Two orthogonal knobs:

- **Runtime profile** (`SailensRuntimeProfile`): which accelerator each model targets. Today there
  is one profile, `standard`, and every model targets the GPU.
- **Perception profile** (`PerceptionProfile`, user-selectable): `BASIC` runs `sem` only, `DEFAULT`
  runs `sem + det`.

## Build

```bash
./gradlew build          # compile + unit tests
./gradlew :app:assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 37, minSdk 31). Builds arm64-v8a only.

The Qualcomm NPU runtime `.so` files are not tracked; reconstruct them per
[`docs/npu-litert-qnn.md`](docs/npu-litert-qnn.md) if you need that path.

## Docs

Every document below has a Chinese version alongside it (`*.zh-CN.md`), linked from its header.

| | | |
|---|---|---|
| [`docs/architecture.md`](docs/architecture.md) | [中文](docs/architecture.zh-CN.md) | Sailens modular structure: module boundaries, seams, migration plan and verification |
| [`docs/distribution-model.md`](docs/distribution-model.md) | [中文](docs/distribution-model.zh-CN.md) | Platform vs official distribution: ownership, identity, source consumption, release/version policy |
| [`docs/models.md`](docs/models.md) | [中文](docs/models.zh-CN.md) | Model contract, backend config, performance red lines |
| [`docs/perception-profiles.md`](docs/perception-profiles.md) | [中文](docs/perception-profiles.zh-CN.md) | Perception tiers, scheduling, tracker TTL |
| [`docs/npu-litert-qnn.md`](docs/npu-litert-qnn.md) | [中文](docs/npu-litert-qnn.zh-CN.md) | Qualcomm NPU wiring, delivery, diagnosis |
| [`docs/trace_metrics_guide.md`](docs/trace_metrics_guide.md) | [中文](docs/trace_metrics_guide.zh-CN.md) | Trace / replay metric definitions |
| [`docs/trace_replay_workflow.md`](docs/trace_replay_workflow.md) | [中文](docs/trace_replay_workflow.zh-CN.md) | Observe → replay → evaluate loop |
| [`docs/vlm-asr-assistant-plan.md`](docs/vlm-asr-assistant-plan.md) | [中文](docs/vlm-asr-assistant-plan.zh-CN.md) | Planned VLM / ASR assistant path |
| [`docs/background-guidance-plan.md`](docs/background-guidance-plan.md) | [中文](docs/background-guidance-plan.zh-CN.md) | Planned camera foreground service (screen-off guidance) |
| [`docs/guidance-validation-roadmap.md`](docs/guidance-validation-roadmap.md) | [中文](docs/guidance-validation-roadmap.zh-CN.md) | Deferred device validation and spatial earcon gates |
| [`docs/field-recording-manual.md`](docs/field-recording-manual.md) | [中文](docs/field-recording-manual.zh-CN.md) | Stage 2 field recording: scenes, procedure, labelling (draft) |

`AGENTS.md` is the repo guide for coding agents (English only — it is read by tools, not people).

`LICENSE` and `NOTICE` are not translated: only their English text is legally operative, and an
unofficial translation would create ambiguity about which version controls.

## Contributing

Issues and pull requests are welcome. Contributions are accepted under Apache-2.0.

Because the repository ships no weights, running the app end-to-end means supplying your own
`sem.tflite` and `det.tflite` first. Unit tests (`./gradlew build`) run without any model.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
