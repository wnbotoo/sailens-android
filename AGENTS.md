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
  Never add a `.tflite` to a commit; `app/src/main/assets/*.tflite` is git-ignored on purpose.
  Weights carry their own licenses and dataset terms independent of this code license.

## Big picture
- Modules (`settings.gradle.kts`): nine `sailens-*` libraries plus `:app`. Nothing else.
  The migration in `docs/architecture.md` §11 replaced the old layer-first split (`:domain`, `:presentation`, `:ux`, `:camera`).
- Direction: dependencies point downward and never come back up. `:sailens-core` has none.
  `sailens-guidance` and `sailens-describe` never depend on each other, and nothing shared depends on either.
- `:sailens-core` — the smallest shared contracts: `ImageFrame`/YUV planes, `NormalizedRect`, `BinaryMask`, `MlRuntimeInfo`, `LogService`. Keep it small; navigation meaning stays out.
- `:sailens-camera` — CameraX capture, `FrameSource` (continuous) and `FrameSnapshotProvider` (freshness-bounded snapshot), `CameraPreview`, and a camera-permission state primitive. The rationale dialog is *not* here; it is presentation policy in the shell.
- `:sailens-runtime` — LiteRT sessions, accelerator selection, model sources/metadata, YUV→tensor preprocessing, the shared preprocessing cache, device hardware profile. Native half `libsailens_runtime.so`, which also owns `litert_zero_copy.h` and `tensor_layout.h`, included by path by the vision and Guidance CMake builds.
- `:sailens-vision` — segmentation/detection runners, dataset taxonomies (`Taxonomy`, `CityscapesTaxonomy`, `CocoTaxonomy`), default postprocessors. Output carries no navigation meaning: a `Detection` is class id, label, confidence, box. Native half `libsailens_vision.so`.
- `:sailens-vlm` — the VLM engine contract and the LiteRT-shaped shell. Frame + complete prompt → streamed text; it does not know the user is blind. No GenAI dependency yet (`UnavailableVlmRuntimeFactory`).
- `:sailens-output` — output mechanism only: TTS, audio focus, screen-reader detection, clause buffering, `HapticPlayer`, `Announcement`. It must not learn what Guidance or Describe are (§6.6); its only Sailens dependency is `:sailens-core`.
- `:sailens-guidance` — all navigation logic: `NavigationSemantics`, the fused scoring kernel and connectivity (`kernel/`, native half `libsailens_guidance.so`), analysis, events, cooldown, tracking, depth, sensors, trace/replay. **No Compose or UI dependency** — that is what keeps it a separate module from the shell.
- `:sailens-describe` — Describe product logic: prompt policy, snapshot freshness, request scheduling.
- `:sailens-shell` — reusable presentation and composition: `SailensRoot()`, navigation, design system, Guidance UI, settings, diagnostics, about, trace replay UI, the Guidance haptic vocabulary (`GuidanceHaptic`), `SceneEvent`→`Announcement` mapping, camera-permission policy UI, `FileLogService`, and the capability model (`app/`).
- `:app` — the thin host: `MainApplication`, `MainActivity`, Koin wiring, runtime profile, and the edition spec (`SailensEdition.kt`) that says what this build offers and promises.
- Capability model (§5.2): *configured* (a null spec), *expected* (`CapabilityExpectations`), *available* (`PipelinePreflight`, cheap and static — it must never load a model), *runtime state*. Zero pipelines is legal; A ships no weights and lands there.

## Runtime flow to preserve
- `ImageAnalysis` outputs `YUV_420_888` frames -> `ImageFrameAnalyzer` -> `FrameSource.frames` (`SharedFlow<ImageFrame>`, `DROP_OLDEST`). The analyzer converts a frame only on demand, and demand is either a stream subscriber or an open `FrameLease`, so `FrameSnapshotProvider` works with Guidance stopped.
- `StartSceneAnalysisUseCase` initializes `PerceptionRepository`; in `DEFAULT` profile it also initializes the realtime obstacle (detection) provider.
- `StartSceneAnalysisUseCase` starts a trace session, maps each frame to `PerceptionResult`, `SceneResult`, and `FrameTrace`, then records runtime backend fields.
- `ProcessFrameUseCase` runs semantic segmentation, can reuse cached semantic analysis between scheduled runs, then runs obstacle detection extraction/tracking (det only; no instance-segmentation refinement).
- The perception profile decides which models run: `BASIC` = sem only (obstacles from the semantic mask), `DEFAULT` = sem + det (`DETECTION_MODEL`). There is no seg/refinement provider.
- `AnalyzeSceneUseCase` runs `ObstacleOcclusionAnalyzer` (carves corridor-overlapping tracked det-box ground-contact bands out of the sem passable mask — det∩sem cross-validation) then computes connectivity + road safety + ground transition + scene elements.
- `DecideEventsUseCase` order is fixed: `EventGenerator -> EventConflictResolver -> EventMerger -> CooldownManager`.
- `SceneAnalysisViewModel` consumes with `collectLatest`, updates masks/overlays/debug state, and triggers speech/haptics only when UI state enables them.

## Project-specific conventions
- Check `app/SailensRuntimeProfile.kt`, `app/DomainBindingsModule.kt`, `app/SailensEdition.kt`, `sailens-shell/di/ShellModule.kt` and `data/di/DataModule.kt` first; DI is explicit constructor injection via Koin.
- Constructor defaults in `PerceptionConfig` are conservative (`BASIC`, obstacle provider type `NONE`), but the app's runtime tiers override them to sem + realtime det mode.
- Runtime tiers are `standard` (sem/det on GPU) and `ultra` (future VLM on NPU, realtime vision models stay on GPU).
- Treat `SailensRuntimeProfile.kt` as the source of truth for runtime backend targets and cadence; physical model files are resolved by `ModelCatalog` / `ModelSourceResolver` from `(ModelType, actual accelerator)`.
- Runtime hardware label comes from `DeviceHardwareProfileProvider` (`Build.SOC_MANUFACTURER` + `Build.SOC_MODEL`, with board/model fallback), not a hardcoded SoC string.
- ML backend reporting is carried by `MlRuntimeInfo` through outputs, scene debug info, trace JSON, trace replay, and UI.
- Accelerator selection is explicit by default; do not hide initialization failures with backend fallback while debugging model/backend compatibility. The app trace label reports the requested/active LiteRT accelerator, not proof that every op stayed on that backend.
- Obstacle filtering uses a perspective-aware navigation corridor (`navigationCorridorFarWidth` -> `navigationCorridorCenterWidth` from `navigationCorridorHorizonY` to the bottom of frame); obstacle speech uses typed keys for `person` / `bicycle` / `vehicle` / `static`, with slightly later center-person gates, earlier side-person gates that allow medium-urgency side persons, and forward vehicle priority preserved before multi-zone merge.
- User-facing prompt policy defaults away from brittle lane/surface/intersection fallbacks: road warning, road exit, ground-change speech, and traffic-light/road-ratio intersection fallback are off unless explicitly enabled; daily prompts should prefer typed obstacles, high-certainty blocked, path-complex, and low-priority `event_traffic_light` from stable traffic-light evidence. Traffic-light evidence is gated by semantic pixel ratio, road context, and debounce; if intersection prompts are enabled from reliable evidence, keep broad "possible intersection" wording.
- If you add event categories/keys, update the Guidance event generation/merge logic and the shell string resources.
- `SceneEvent.messageKey` must stay aligned with `sailens-shell/src/main/res/values/strings.xml` and `values-zh/strings.xml`.
- `BinaryMask` is `BitSet`-based and used in hot loops; avoid allocation-heavy patterns in analysis code.

## Integrations and assets
- CameraX (`camera-core/camera2/camera-lifecycle/camera-compose`) in `:sailens-camera`.
- Native code is three libraries, one per owning module (§6.10): `libsailens_runtime.so` (YUV preprocessing + quantization), `libsailens_vision.so` (semantic argmax, detection decode/NMS), `libsailens_guidance.so` (fused navigation scoring, connectivity). Each binds through its own `JNI_OnLoad` + `RegisterNatives` and exports **no** `Java_<mangled>` symbols, so a renamed class fails the library load instead of surviving until a rare call. Check with `llvm-nm -D --defined-only <lib>.so | grep -E "Java_|JNI_OnLoad"`.
- LiteRT model execution with native YUV preprocessing (`native_yuv`), OpenCV fallback (`opencv_fallback`), and same-frame preprocessing cache hits (`shared_native_yuv` / `shared_quantized_native_yuv`).
- Semantic postprocess can use native fused score/stat extraction (`native_score`) or fallback argmax paths.
- Obstacle detection postprocess supports raw attribute-major tensors (`[1, 4+classCount, N]`) and end-to-end tensors (`[1, N, 6]`); layout is auto-resolved from the output tensor shape. The realtime path decodes straight from the output buffer handle (zero-copy) when available.
- No weights ship here. `ModelCatalog` resolves `sem` -> `app/src/main/assets/sem.tflite` and `det` -> `app/src/main/assets/det.tflite`; both are git-ignored, so a local working copy can hold weights that never reach a commit. Absent weights fail at init and surface as a start-analysis error — that is expected, not a bug to "fix" by committing a model.
- Shape, layout, dtype, and quantization are auto-resolved from the selected TFLite metadata and are not runtime profile fields, so swapping a conforming model needs no code change. To use separate GPU/NPU model files, update `ModelCatalog` / `ModelSourceResolver`, then set `acceleratorBackend` in `SailensRuntimeProfile.kt`. See `docs/models.md`.
- Class channel order is validated only by *count* and by the declared `TaxonomyId` (`NavigationSemanticsBinding`), never by meaning: a wrong-order model passes that check, runs silently and mislabels the scene for a user who cannot see it. Treat it as a safety property (`docs/models.md`).
- `FileLogService` writes JSONL logs under app internal `files/logs/`.
- `FileTraceService` writes trace JSONL sessions under app internal `files/traces/`; `FileTraceReplayService` reads them back.

## Developer workflows (pwsh)
```powershell
# from the repo root
.\gradlew.bat --no-daemon projects
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon :sailens-guidance:test
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:tasks
```

## Change guardrails
- **`main` is published and append-only. Never rewrite it** — no squash-and-force-push, no rebase of
  `main`, no amending a pushed commit. Fix mistakes with a new commit that reverts or corrects them.
  A force-push does not just inconvenience clones: anything built on a rewritten commit is orphaned
  from this history, and reconciling it costs far more than the tidy log was worth.
- Keep navigation logic free of UI: `sailens-guidance` has no Compose dependency, and that is what keeps it a separate module from the shell.
- If you change frame resolution/format, update both camera use-case config and ML preprocessing assumptions.
- If you change model files, update `ModelCatalog` / `ModelSourceResolver`; if you change class counts, mask coefficient counts, resize filter, or backend targets, update `SailensRuntimeProfile.kt` first. Only change `SemanticModelConfig` or `DetectionModelConfig` when the model contract/defaults change.
- If you change backend reporting fields, update `MlRuntimeInfo`, trace encoding/parsing/reporting, live debug UI, and trace replay UI together.
- Keep start/stop lifecycle symmetry: components with `reset()` should remain wired through `StopSceneAnalysisUseCase`.
- Present a plan and get agreement before starting a refactor.
