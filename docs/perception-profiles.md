**English** | [简体中文](perception-profiles.zh-CN.md)

# Perception profiles and model scheduling (as-built)

This describes the `PerceptionProfile` architecture and the two vision models' scheduling as they
exist in the code today. Where anything disagrees, this document and the code win.

> History: there used to be a third tier, `HIGH_ACCURACY`, with a separate instance-segmentation
> model (`seg`) for high-risk refinement. On-device trace evaluation showed `seg` contributed
> nothing to the decision layer on real workloads while continuously burning readback and decode
> time, so it was removed wholesale (model, RiskAnalyzer, seg trigger scheduling, mask decode).
> Obstacle shape and occlusion are now derived by cross-validating det boxes against the sem mask —
> see `ObstacleOcclusionAnalyzer` in §2.

## 1. Two tiers

```text
BASIC:   sem only
DEFAULT: sem + det
```

Core principle: `sem` is the navigation floor (walkable area, road direction) and has the highest
priority; `det` is obstacle recall.

**Frequency philosophy: a tier decides the model combination; how fast it runs is best-effort per
device.** The pipeline is serial per frame and the camera side drops frames adaptively with
`KEEP_ONLY_LATEST`, so it is naturally self-limiting and cannot run away. `PerceptionConfig`'s
`semanticTargetFps` / `detectionTargetFps` default to 0 (unthrottled); positive values are a knob
reserved for **dynamic downgrade / per-device tuning** (copy and override `forProfile`'s return
value), not a product default. `detectionResultTtlMs` (500 ms) still applies: tracks on frames where
det did not run expire on time.

## 2. Scheduling model

The old `frameIndex % 2` odd/even alternation is gone (along with the `PerceptionMode` /
`InferenceStrategy` enums). Each processed frame is now decided independently:

```text
Camera Frame
  ↓
PerceptionProfileManager.activeConfig (fixed within a session)
  ↓
PerceptionScheduler: is sem due? is det due?
  ↓
sem ∥ obstacle slot (det; async parallel, each with limitedParallelism(1))
  ↓
ObstacleTracker (det output updates tracks; non-run frames use prediction, tracks expire on TTL)
  ↓
AnalyzeSceneUseCase: ObstacleOcclusionAnalyzer (det∩sem cross-validation) → connectivity /
analysis / decision / speech
```

Key implementation points:

- **The scheduler** (`PerceptionScheduler`) admits everything by default (unthrottled); when the
  corresponding `*Fps` field is > 0 it throttles on `nowMs - lastRun >= 1000/fps`. `markXxxRun`
  stamps after the model completes, so it reflects real throughput.
  The clock is injected by the app layer as **`SystemClock.elapsedRealtime()` (monotonic)** — not
  wall clock (`currentTimeMillis` jumps when the clock is corrected, and a backwards jump would
  break track TTL and keep announcing obstacles that are already gone), and not `frame.timestamp`
  (CameraX timestamps are nanoseconds).
- **Obstacle occlusion (det∩sem cross-validation)**: `ObstacleOcclusionAnalyzer` carves the
  ground-contact band of a tracked obstacle's bbox (a strip at the box bottom,
  `occlusionBandHeightRatio`) out of the sem passable mask, one-way, before computing connectivity.
  The case it earns its keep on: sem calls the ground under an obstacle walkable while det caught
  the obstacle — removing the contact band corrects that false clear, and an obstacle in the way
  shows up naturally as rising blockage. It only applies to obstacles close enough (bbox bottom
  edge ≥ `occlusionMinBottomY`), and takes only the contact band rather than the whole box so it
  does not over-clear distant walkable area. It is designed to only ever *subtract* walkable
  pixels, never add them, so it can only raise caution — it cannot lower safety. Input is the
  tracker's stable tracks (frames between det runs are covered by prediction, so the signal is
  continuous frame to frame).
- **Track TTL** (`ObstacleTracker.predict`): while det is not running, tracks expire on
  `detectionResultTtlMs` — by time, not frame count (frame counting would kill tracks once det is
  throttled). `missedFrames` only increments on frames where det actually ran an update.
- **BASIC tier**: obstacles are extracted from semantic mask connected components
  (`ObstacleExtractor.extractFromSemantic`); the det provider is not initialized.

## 3. How a tier is chosen and takes effect

```text
Settings screen (SettingsScreen, radio group)
  ↓ setPerceptionProfile
PerceptionSettingsStore (SharedPreferences persistence, presentation)
PerceptionProfileManager.selectProfile (domain, runtime selection)
  ↓ at the start of the next navigation session
StartSceneAnalysisUseCase → profileManager.activateSelected()
  (before the obstacle provider initializes, resolving the selection into this session's
   activeConfig)
  ↓
ProcessFrameUseCase reads activeConfig per frame
```

- Switching is funnelled to a **session boundary**: providers are initialized per tier at session
  start. Switching DEFAULT→BASIC mid-session would itself be safe, but funnelling to the session
  boundary avoids config drift within a session. The settings screen says "takes effect next time
  you start guidance".
- At app start, `profileBindingsModule` reads the initial tier from `PerceptionSettingsStore` and
  assembles `SailensRuntimeProfile`. The persisted default is **DEFAULT**
  (`PerceptionSettingsStore.DEFAULT_PROFILE`).
- The perception tier is orthogonal to the runtime profile (which decides each model's accelerator;
  today there is only `standard`).

## 4. Observability

- The live debug panel shows the perception tier (`SceneDebugInfo.perceptionProfile`).
- The trace's `pipelineMode` records the tier name lowercased (`basic` / `default`). In older trace
  files this field may be `combined` / `semantic_only` / `high_accuracy`.
- Each frame's trace records `obstacleRunKind` (`det` / `none`); the replay report's
  `obstacleRunFps` is det's real run frequency. Unthrottled it should approach `pipelineOutputFps`
  (with a cap configured, `min(cap, pipelineOutputFps)`). A `seg` appearing in an old trace counts
  as one obstacle run. For metric definitions see
  [`trace_metrics_guide.md`](trace_metrics_guide.md).

## 5. Planned (not implemented, by priority)

1. **Occlusion parameter calibration**: tune `occlusionMinBottomY` / `occlusionBandHeightRatio`
   against multiple field traces. The goal is fewer false alarms (carving out walkable pixels that
   were not an obstacle), not suppressing true alerts.
2. **Dynamic downgrade**: drop DEFAULT→BASIC automatically on thermal state / battery / inference
   latency / frame-drop rate, with a switch cooldown.
3. **imgsz tuning**: input size is the next lever on inference time (see [`models.md`](models.md)).
4. **0.2 model delivery**: VLM (NPU) + model downloader, see
   [`vlm-asr-assistant-plan.md`](vlm-asr-assistant-plan.md).
