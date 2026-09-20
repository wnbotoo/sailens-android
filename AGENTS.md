# AGENTS.md

Sailens is Android navigation assistance for blind and low-vision users: a camera-fed perception
pipeline that turns the scene ahead into speech and haptics. This file is the repo's agent guide;
`README.md` is the human entry point.

## License and provenance guardrails
- **This repository is Apache-2.0.** Every contribution must be licensable under Apache-2.0.
- Do not copy, translate, or derive code from copyleft-licensed (GPL/AGPL) sources into this repo.
  Supporting a published *tensor layout* or a public *dataset class order* is a format fact and is
  fine; porting someone else's implementation is not.
- **No model weights are committed here** — the app is bring-your-own-model (`docs/models.md`).
  Never add a `.tflite` to a commit; `data/src/main/assets/*.tflite` is git-ignored on purpose.
  Weights carry their own licenses and dataset terms independent of this code license.

## Big picture
- Modules: `:app`, `:data`, `:domain`, `:sailens-core`, `:sailens-camera`, `:sailens-runtime`, `:sailens-shell` (`settings.gradle.kts`). The migration in `docs/architecture.md` §11 is replacing the layer-first split; `:sailens-core`, `:sailens-camera`, `:sailens-runtime` and `:sailens-shell` are target modules already in place (`:sailens-shell` absorbed the old `:presentation` and `:ux`), while `:domain` and `:data` still await theirs.
- Direction: dependencies point downward. `:sailens-core` has none; `:sailens-camera` depends only on it. `:domain` stays free of Android/platform APIs.
- `:app` hosts Koin + root Compose (`MainApplication.kt`, `MainActivity.kt`, `app/App.kt`) and runtime wiring.
- Runtime profile lives in `app/SailensRuntimeProfile.kt`; Koin bindings live in `app/DomainBindingsModule.kt`, `app/DiModule.kt`, and each feature module's `*Module.kt`.
- `:sailens-runtime` owns LiteRT sessions, accelerator selection, model sources/metadata, YUV→tensor preprocessing and the shared preprocessing cache, plus the device hardware profile. Its native half is `libsailens_runtime.so`.
- `:sailens-core` holds the small shared contracts: `ImageFrame`/YUV planes, `NormalizedRect`, `BinaryMask`, `MlRuntimeInfo`, `LogService`. Keep it small — navigation semantics stay out of it.
- `:sailens-runtime` owns LiteRT sessions, accelerator selection, model sources/metadata, YUV→tensor preprocessing, the shared preprocessing cache and the device hardware profile. Its native half is `libsailens_runtime.so` (`sailens-runtime/src/main/cpp`); the vision/Guidance kernels stay in `libsailens_ml.so` under `:data` until steps 6 and 7 split them.
- `:sailens-camera` owns CameraX capture and the frame contracts: `FrameSource` (continuous) and `FrameSnapshotProvider` (freshness-bounded snapshot), `CameraViewModel.kt`, `ImageFrameAnalyzer.kt`, plus `CameraPreview` and the camera-permission state primitive.
- `:data` owns ML/depth/log/trace implementations (`data/di/DataModule.kt`).
- `:domain` owns perception/analysis/decision/trace use cases (`domain/src/main/java/com/sailens/domain/usecase`).
- `:sailens-shell` owns reusable presentation and composition: `SailensRoot()`, navigation, design system (`design/`), Guidance UI (`guidance/screen`, `guidance/overlay`, `guidance/settings`), settings, diagnostics, about, trace replay UI, TTS and haptics (`guidance/screen/SceneAnalysisViewModel.kt`, `device/*`). Camera permission *policy* UI lives here too (`camera/CameraViewWithPermission.kt`); `:sailens-camera` only exposes the preview and a permission-state primitive.

## Runtime flow to preserve
- `ImageAnalysis` outputs `YUV_420_888` frames -> `ImageFrameAnalyzer` -> `FrameSource.frames` (`SharedFlow<ImageFrame>`, `DROP_OLDEST`). The analyzer also records the latest frame for `FrameSnapshotProvider`, but only while something collects the stream.
- `StartSceneAnalysisUseCase` initializes `PerceptionRepository`; in `DEFAULT` profile it also initializes the realtime obstacle (detection) provider.
- `StartSceneAnalysisUseCase` starts a trace session, maps each frame to `PerceptionResult`, `SceneResult`, and `FrameTrace`, then records runtime backend fields.
- `ProcessFrameUseCase` runs semantic segmentation, can reuse cached semantic analysis between scheduled runs, then runs obstacle detection extraction/tracking (det only; no instance-segmentation refinement).
- The perception profile decides which models run: `BASIC` = sem only (obstacles from the semantic mask), `DEFAULT` = sem + det (`DETECTION_MODEL`). There is no seg/refinement provider.
- `AnalyzeSceneUseCase` runs `ObstacleOcclusionAnalyzer` (carves corridor-overlapping tracked det-box ground-contact bands out of the sem passable mask — det∩sem cross-validation) then computes connectivity + road safety + ground transition + scene elements.
- `DecideEventsUseCase` order is fixed: `EventGenerator -> EventConflictResolver -> EventMerger -> CooldownManager`.
- `SceneAnalysisViewModel` consumes with `collectLatest`, updates masks/overlays/debug state, and triggers speech/haptics only when UI state enables them.

## Project-specific conventions
- Check `SailensRuntimeProfile.kt`, `DomainBindingsModule.kt`, and `data/di/DataModule.kt` first; DI is explicit constructor injection via Koin.
- Constructor defaults in `PerceptionConfig` are conservative (`BASIC`, obstacle provider type `NONE`), but the app's runtime tiers override them to sem + realtime det mode.
- Runtime tiers are `standard` (sem/det on GPU) and `ultra` (future VLM on NPU, realtime vision models stay on GPU).
- Treat `SailensRuntimeProfile.kt` as the source of truth for runtime backend targets and cadence; physical model files are resolved by `ModelCatalog` / `ModelSourceResolver` from `(ModelType, actual accelerator)`.
- Runtime hardware label comes from `DeviceHardwareProfileProvider` (`Build.SOC_MANUFACTURER` + `Build.SOC_MODEL`, with board/model fallback), not a hardcoded SoC string.
- ML backend reporting is carried by `MlRuntimeInfo` through outputs, scene debug info, trace JSON, trace replay, and UI.
- Accelerator selection is explicit by default; do not hide initialization failures with backend fallback while debugging model/backend compatibility. The app trace label reports the requested/active LiteRT accelerator, not proof that every op stayed on that backend.
- Obstacle filtering uses a perspective-aware navigation corridor (`navigationCorridorFarWidth` -> `navigationCorridorCenterWidth` from `navigationCorridorHorizonY` to the bottom of frame); obstacle speech uses typed keys for `person` / `bicycle` / `vehicle` / `static`, with slightly later center-person gates, earlier side-person gates that allow medium-urgency side persons, and forward vehicle priority preserved before multi-zone merge.
- User-facing prompt policy defaults away from brittle lane/surface/intersection fallbacks: road warning, road exit, ground-change speech, and traffic-light/road-ratio intersection fallback are off unless explicitly enabled; daily prompts should prefer typed obstacles, high-certainty blocked, path-complex, and low-priority `event_traffic_light` from stable traffic-light evidence. Traffic-light evidence is gated by semantic pixel ratio, road context, and debounce; if intersection prompts are enabled from reliable evidence, keep broad "possible intersection" wording.
- If you add event categories/keys, update domain event generation/merge logic and the shell string resources.
- `SceneEvent.messageKey` must stay aligned with `sailens-shell/src/main/res/values/strings.xml` and `values-zh/strings.xml`.
- `BinaryMask` is `BitSet`-based and used in hot loops; avoid allocation-heavy patterns in analysis code.

## Integrations and assets
- CameraX (`camera-core/camera2/camera-lifecycle/camera-compose`) in `:sailens-camera`.
- LiteRT model execution with native YUV preprocessing (`native_yuv`), OpenCV fallback (`opencv_fallback`), and same-frame preprocessing cache hits (`shared_native_yuv` / `shared_quantized_native_yuv`).
- Semantic postprocess can use native fused score/stat extraction (`native_score`) or fallback argmax paths.
- Obstacle detection postprocess supports raw attribute-major tensors (`[1, 4+classCount, N]`) and end-to-end tensors (`[1, N, 6]`); layout is auto-resolved from the output tensor shape. The realtime path decodes straight from the output buffer handle (zero-copy) when available.
- No weights ship here. `ModelCatalog` resolves `sem` -> `data/src/main/assets/sem.tflite` and `det` -> `data/src/main/assets/det.tflite`; both are git-ignored, so a local working copy can hold weights that never reach a commit. Absent weights fail at init and surface as a start-analysis error — that is expected, not a bug to "fix" by committing a model.
- Shape, layout, dtype, and quantization are auto-resolved from the selected TFLite metadata and are not runtime profile fields, so swapping a conforming model needs no code change. To use separate GPU/NPU model files, update `ModelCatalog` / `ModelSourceResolver`, then set `acceleratorBackend` in `SailensRuntimeProfile.kt`. See `docs/models.md`.
- Class channel order is validated only by *count*, never by meaning: a wrong-order model runs silently and mislabels the scene for a user who cannot see it. Treat it as a safety property (`docs/models.md`).
- `FileLogService` writes JSONL logs under app internal `files/logs/`.
- `FileTraceService` writes trace JSONL sessions under app internal `files/traces/`; `FileTraceReplayService` reads them back.

## Developer workflows (pwsh)
```powershell
# from the repo root
.\gradlew.bat --no-daemon projects
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon :domain:test
.\gradlew.bat --no-daemon :data:test
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:tasks
```

## Change guardrails
- **`main` is published and append-only. Never rewrite it** — no squash-and-force-push, no rebase of
  `main`, no amending a pushed commit. Fix mistakes with a new commit that reverts or corrects them.
  A force-push does not just inconvenience clones: anything built on a rewritten commit is orphaned
  from this history, and reconciling it costs far more than the tidy log was worth.
- Keep Android/platform APIs out of `:domain`.
- If you change frame resolution/format, update both camera use-case config and ML preprocessing assumptions.
- If you change model files, update `ModelCatalog` / `ModelSourceResolver`; if you change class counts, mask coefficient counts, resize filter, or backend targets, update `SailensRuntimeProfile.kt` first. Only change `SemanticModelConfig` or `ObstacleModelConfig` when the model contract/defaults change.
- If you change backend reporting fields, update `MlRuntimeInfo`, trace encoding/parsing/reporting, live debug UI, and trace replay UI together.
- Keep start/stop lifecycle symmetry: components with `reset()` should remain wired through `StopSceneAnalysisUseCase`.
- Present a plan and get agreement before starting a refactor.
