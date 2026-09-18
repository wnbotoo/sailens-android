**English** | [简体中文](README.zh-CN.md)

# Sailens

**Sailens** — *Smart AI LENS*.

Android navigation assistance for blind and low-vision users. A camera-fed perception pipeline
turns the scene in front of you into speech and haptics: where you can walk, where the road edge is,
and what is in the way.

**License: Apache-2.0.** No model weights ship with this repository — see
[Bring your own model](#bring-your-own-model).

## Status

Pre-release. The pipeline, UI, and runtime are implemented; on-device tuning is ongoing.
Target devices are Snapdragon 8 Gen 1 and beyond class hardware.

## Bring your own model

This repository ships **no model weights**. The app resolves two graphs at runtime:

```text
data/src/main/assets/sem.tflite     # semantic walkable-area segmentation
data/src/main/assets/det.tflite     # obstacle detection
```

Both paths are git-ignored, so a working copy can carry weights without them entering a commit.
Drop in any TFLite graph that satisfies the contract and the pipeline picks it up — shape, layout,
dtype, and quantization are all read back from the model's metadata at load time, so **swapping a
model normally needs no code change**.

With no weights present, model loading fails at init and surfaces as a start-analysis error.

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

Clean architecture over four Gradle modules plus two support modules, wired with Koin:

```text
:domain        perception / analysis / decision use cases — no Android APIs
:data          LiteRT inference, depth, logging, trace
:presentation  UI state, overlay rendering, TTS, haptics
:app           Koin wiring, root Compose, runtime profile
:camera        CameraX capture and frame stream
:ux            design system
```

Outer modules depend inward on `:domain` interfaces. Frames flow
`CameraX → ImageFrameAnalyzer → SharedFlow<ImageFrame> → ProcessFrameUseCase → AnalyzeSceneUseCase
→ DecideEventsUseCase → speech/haptics`.

Two orthogonal knobs:

- **Runtime tier** (`SailensRuntimeProfile`): which accelerator each model targets. Vision runs on
  the GPU; the NPU is reserved for a future VLM path.
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
| [`docs/architecture.md`](docs/architecture.md) | [中文](docs/architecture.zh-CN.md) | Reviewed Sailens modular restructure proposal (not implemented) |
| [`docs/models.md`](docs/models.md) | [中文](docs/models.zh-CN.md) | Model contract, backend config, performance red lines |
| [`docs/perception-profiles.md`](docs/perception-profiles.md) | [中文](docs/perception-profiles.zh-CN.md) | Perception tiers, scheduling, tracker TTL |
| [`docs/npu-litert-qnn.md`](docs/npu-litert-qnn.md) | [中文](docs/npu-litert-qnn.zh-CN.md) | Qualcomm NPU wiring, delivery, diagnosis |
| [`docs/trace_metrics_guide.md`](docs/trace_metrics_guide.md) | [中文](docs/trace_metrics_guide.zh-CN.md) | Trace / replay metric definitions |
| [`docs/trace_replay_workflow.md`](docs/trace_replay_workflow.md) | [中文](docs/trace_replay_workflow.zh-CN.md) | Observe → replay → evaluate loop |
| [`docs/vlm-asr-assistant-plan.md`](docs/vlm-asr-assistant-plan.md) | [中文](docs/vlm-asr-assistant-plan.zh-CN.md) | Planned VLM / ASR assistant path |
| [`docs/background-guidance-plan.md`](docs/background-guidance-plan.md) | [中文](docs/background-guidance-plan.zh-CN.md) | Planned camera foreground service (screen-off guidance) |
| [`docs/guidance-validation-roadmap.md`](docs/guidance-validation-roadmap.md) | [中文](docs/guidance-validation-roadmap.zh-CN.md) | Deferred device validation and spatial earcon gates |

`AGENTS.md` is the repo guide for coding agents (English only — it is read by tools, not people).

`LICENSE` and `NOTICE` are not translated: only their English text is legally operative, and an
unofficial translation would create ambiguity about which version controls.

## Contributing

Issues and pull requests are welcome. Contributions are accepted under Apache-2.0.

Because the repository ships no weights, running the app end-to-end means supplying your own
`sem.tflite` and `det.tflite` first. Unit tests (`./gradlew build`) run without any model.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
