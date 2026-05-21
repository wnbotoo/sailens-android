# AGENTS.md

No prior agent-instruction files were found via `**/{.github/copilot-instructions.md,AGENT.md,AGENTS.md,CLAUDE.md,.cursorrules,.windsurfrules,.clinerules,.cursor/rules/**,.windsurf/rules/**,.clinerules/**,README.md}`; use this file as the repo's agent guide.

## Big picture
- Modules: `:app`, `:camera`, `:data`, `:domain`, `:presentation`, `:ux` (`settings.gradle.kts`).
- Direction: outer modules depend inward on `:domain` interfaces; wiring happens in `app/src/main/java/com/friady/sailens/app/DiModule.kt`.
- `:app` hosts Koin + root Compose (`MainApplication.kt`, `App.kt`, `MainActivity.kt`), not business logic.
- `:camera` owns CameraX capture + frame stream (`CameraViewModel.kt`, `ImageFrameAnalyzer.kt`).
- `:data` owns ML/depth/log implementations (`data/src/main/java/com/friady/sailens/data/di/DataModule.kt`).
- `:domain` owns perception/analysis/decision pipeline (`domain/src/main/java/com/friady/sailens/domain/usecase`).
- `:presentation` owns UI state, overlay rendering, and device feedback adapters (`SceneAnalysisViewModel.kt`, `device/*`).

## Runtime flow to preserve
- `ImageAnalysis` frames -> `ImageFrameAnalyzer` -> `SharedFlow<ImageFrame>` with `DROP_OLDEST`.
- `StartSceneAnalysisUseCase` initializes repositories and maps each frame to `SceneResult`.
- `ProcessFrameUseCase` runs segmentation every frame, then obstacle extraction/tracking.
- `AnalyzeSceneUseCase` computes connectivity + road safety + ground transition + scene elements.
- `DecideEventsUseCase` order is fixed: `EventGenerator -> EventConflictResolver -> EventMerger -> CooldownManager`.
- `SceneAnalysisViewModel` consumes with `collectLatest`, updates `segMask`, and emits UI effects.

## Project-specific conventions
- Check `*Module.kt` files first; DI is explicit constructor injection via Koin.
- Default operation is semantic-only: `PerceptionConfig.mode = SEMANTIC_ONLY`, `instanceProviderType = NONE`.
- Instance segmentation is not active in current wiring (`StartSceneAnalysisUseCase(..., instanceProvider = null, ...)`; YOLO provider file is commented).
- TTS/haptics are intentionally disabled in `SceneAnalysisViewModel`; `SpeechManager` currently speaks raw `messageKey`.
- `SceneEvent.messageKey` must stay aligned with `presentation/src/main/res/values/strings.xml`.
- `BinaryMask` is `BitSet`-based and used in hot loops; avoid allocation-heavy patterns in analysis code.

## Integrations and assets
- CameraX (`camera-core/camera2/camera-lifecycle/camera-compose`) in `:camera`.
- LiteRT + OpenCV segmentation path (`LiteRTSegmenter.kt`, `OpenCVImageProcessor.kt`).
- Model files live in `data/src/main/ml/` (`ddrnet23_slim_cityscapes_float_metadata.tflite`, `ffnet_78s_float.tflite`).
- `FileLogService` writes JSONL session logs under app internal `files/logs/`.

## Developer workflows (pwsh)
```powershell
Set-Location "C:\Users\wnbot\AndroidStudioProjects\Sailens"
.\gradlew.bat --no-daemon projects
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon :domain:test
.\gradlew.bat --no-daemon :app:tasks
```

## Change guardrails
- Keep Android/platform APIs out of `:domain`.
- If you add event categories/keys, update both domain event generation/merge logic and presentation string resources.
- If you change frame resolution/format, update both camera use-case config and ML preprocessing assumptions.
- Keep start/stop lifecycle symmetry: components with `reset()` should remain wired through `StopSceneAnalysisUseCase`.
