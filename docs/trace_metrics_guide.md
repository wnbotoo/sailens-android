**English** | [简体中文](trace_metrics_guide.zh-CN.md)

# Trace metrics guide

This explains what each metric in a Sailens trace / replay report means, how it is computed, and
what it is worth when tuning. The goal is that someone new can look at one report and tell whether
the bottleneck is camera input, model inference, postprocessing, decision logic, or UI mask
rendering.

## Where trace data comes from

A trace is a JSONL file; each line is one event:

- `session_start` — a navigation session began.
- `frame` — one full pipeline result for a frame.
- `overlay_render` — the UI finished one mask overlay render.
- `prompt_outcome` — what became of the one prompt a frame offered the user: `deliveredAt` with
  `deliveredVia` (the channels that accepted it: `speech`, `screen_reader`, `haptics`,
  `status_card`), or `revokedAt` with a `revokeReason` (`waiting_for_description`,
  `output_refused`). The output settings at the time are recorded too, as context only: only
  `deliveredVia` says whether a prompt was heard or just felt. Keyed by `eventId`, joined to its frame
  by `sourceSequenceNumber`; written after that frame, and that frame is always recorded whatever the
  sampling. A `frame` record's `messageKeys` are candidates after cooldown, not what the user
  received — label false alarms against `prompt_outcome`.
- `session_summary` — the aggregate summary after a session ends.
- `error` — an exception in the pipeline or in tracing.

Runtime write location is managed by `FileTraceService`; replay reports are recomputed from the
JSONL by `TraceReplayParser` and `BuildTraceReplayReportUseCase`.

## Frames and frame drops

### `frames`

What it means: how many frames the pipeline actually finished processing.

How it is computed: `session_summary.totalFrames` — the number of successfully recorded `frame`
events.

Use: how many result frames the pipeline actually produced.

Reading it:

- Few `frames` over a long test: the pipeline is too slow, usually blocked on model inference or
  output readback.
- `frames` normal but few prompts: check decision-layer thresholds, event cooldown, or
  semantic/instance recognition quality.

### `observed`

What it means: how many frames appeared in the camera analysis stream, inferred from sequence
numbers.

How it is computed:

```text
observed = frames + droppedFrames
```

Use: estimates how many frames CameraX actually delivered.

Reading it:

- High `observed`, low `frames`: camera input far outpaces what the pipeline can digest.
- Look at `perceptionMs`, `semMs`, `obsMs`, `logicMs` first, then consider lowering analysis
  resolution, sharing preprocessing, or lowering the perception tier / per-model target FPS
  (`semanticTargetFps` / `detectionTargetFps`).

### `dropped` / `droppedFrameRate`

What it means: frames the pipeline could not keep up with, dropped by
`ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` and Flow backpressure.

How it is computed:

```text
droppedFramesSinceLast = currentSequenceNumber - previousSequenceNumber - 1
droppedFrameRate = droppedFrames / observed
```

Use: measures realtime-ness. An obstacle-avoidance app does not need every frame, but heavy drops
make prompts about moving objects unstable.

Reading it:

- `< 10%`: ideal.
- `10% – 50%`: usable, but watch dynamic obstacles.
- `> 50%`: the pipeline is clearly overloaded. Prioritize model size, output readback, native
  preprocessing, and inference strategy.

## FPS metrics

### `cameraInputFps`

What it means: input FPS of the CameraX analysis stream.

How it is computed:

```text
cameraDurationMs = lastFrameTimestamp - firstFrameTimestamp
observedIntervals = processedFrameIntervals + droppedFrames
cameraInputFps = observedIntervals * 1000 / cameraDurationMs
```

Note: CameraX's `imageInfo.timestamp` is usually nanoseconds; the report converts to milliseconds.
Tests or old traces that wrote milliseconds are still handled.

Use: confirms the camera side is producing what you expect — 30 FPS, 24 FPS, or lower.

Reading it:

- High `cameraInputFps`, low `pipelineOutputFps`: the pipeline is the bottleneck.
- Low `cameraInputFps` itself: check CameraX resolution, exposure, device thermals, background load.

### `pipelineOutputFps`

What it means: the FPS at which the pipeline actually produces a `SceneResult`.

How it is computed:

```text
pipelineOutputFps = (frames - 1) * 1000 / (lastPipelineCompletedAt - firstPipelineCompletedAt)
```

Use: this is the refresh rate of environmental understanding the user actually feels.

Reading it:

- For assistive obstacle avoidance, `5–10 FPS` is already useful, though dynamic targets jitter.
- Below `3 FPS`, prompts lag badly and a passing vehicle or person gets announced only sometimes.

### `pipelineThroughputFps`

What it means: theoretical pipeline throughput derived from average perception time.

How it is computed:

```text
pipelineThroughputFps = 1000 / avgInferenceMs
```

Note: `avgInferenceMs` here is `ProcessFrameUseCase`'s total perception time — semantic
segmentation, instance segmentation, output readback, postprocessing and tracking — not any single
model's pure inference time.

Use: a quick check on whether the pipeline can keep up with camera input at all.

Reading it:

- `pipelineThroughputFps` far below `cameraInputFps`: drops are guaranteed.
- A large gap between `pipelineThroughputFps` and `pipelineOutputFps` suggests extra cost in
  scheduling, `collectLatest`, UI, or another async stage.

### `semanticRunFps`

What it means: how often the sem model actually runs.

How it is computed:

```text
semanticRunFps = semanticRunCount * 1000 / durationMs
```

Use: confirms semantic frame skipping is working.

Reading it:

- With `enableSemanticFrameSkipping=false` it should approach `pipelineOutputFps`.
- With skipping on, it is roughly `pipelineOutputFps / semanticFrameInterval`.
- When the road mask visibly jitters, do not lower this blindly.

### `semMs`

What it means: end-to-end stage breakdown for frames where the sem model actually ran.

Report format:

```text
semMs=total:<pre+infer+read+post> pre:<avgSemanticPreprocessMs> infer:<avgSemanticInferenceMs> read:<avgSemanticOutputReadMs> post:<avgSemanticPostprocessMs>
```

These averages only count frames where semantic actually ran; skipped frames' 0 ms are not mixed in.
`infer` is pure `model.run()` time and cannot stand for end-to-end performance on its own — the
usual GPU/NPU bottleneck lands in `pre`, `read`, or `post`.

Use: tells you whether sem's bottleneck is preprocessing, inference, output readback, or
postprocessing.

Reading it:

- High `pre`: prioritize native YUV, shared preprocessing, camera analysis resolution, or model
  input size.
- High `infer`: consider a lower-resolution sem model, quantization, delegate, output downsampling.
- High `read`: the bottleneck is output tensor readback — common with large semantic outputs.
- High `post`: check whether native fused score/stat postprocessing is actually active.

### `obstacleRunFps`

What it means: how often the det obstacle model actually runs.

How it is computed:

```text
obstacleRunFps = obstacleRunCount * 1000 / durationMs
```

Use: confirms the obstacle slot (det running periodically) runs as often as expected. It is decided
by each frame's `obstacleRunKind` field (`det` / `none`): any non-`none` value counts as one run
(including the retired `seg` in old traces). Old traces without the field fall back to a
stage-timing heuristic.

Reading it:

- Unthrottled it should approach `pipelineOutputFps`; with `detectionTargetFps > 0` it should
  approach `min(detectionTargetFps, pipelineOutputFps)`. Consistently above the cap means the
  scheduling interval is not taking effect.
- When dynamic obstacles are visibly missed, first check whether `obstacleRunFps` has been crushed
  by pipeline throughput (needing smaller input size or resolution — see `obsMs`).

### `obsMs`

What it means: end-to-end stage breakdown for frames where the obstacle provider actually ran.

Report format:

```text
obsMs=total:<pre+infer+read+post> pre:<avgObstaclePreprocessMs> infer:<avgObstacleInferenceMs> read:<avgObstacleOutputReadMs> post:<avgObstaclePostprocessMs>
```

Use: tells you whether the det slot's bottleneck is preprocessing, inference, output readback, or
bbox postprocessing.

Reading it:

- High `pre`: prioritize native YUV, shared preprocessing, or a smaller input size.
- High `infer`: reduce obstacle input size, quantize, or use a lighter det model.
- High `read`: on the zero-copy handle path, `read` should be near 0. Significantly positive means
  handle reflection failed and it fell back to `readFloat()` — check the LiteRT version and
  JniHandle reflection.
- High `post`: confirm native bbox postprocessing is active; reduce `maxDetections` or raise the
  confidence threshold.

### `maskRenderFps`

What it means: the FPS at which the UI successfully generates and updates the mask bitmap.

How it is computed:

```text
maskRenderFps = maskRenderCount * 1000 / durationMs
```

`maskRenderCount` only counts overlay render events with `bitmapRendered=true`.

Related fields:

- `avgMaskRenderMs` — average time to generate one overlay bitmap.
- `sourceAgeAvg/sourceAgeMax` — how long after that `SceneResult`'s pipeline completion the overlay
  bitmap finished rendering.

Use: tells you whether overlay rendering is slowing the UI down, or whether the UI is genuinely
showing stale pipeline results.

Reading it:

- The overlay is throttled; `bitmapRenderIntervalMs = 100ms` by default, so ~10 FPS is the ceiling.
- `maskRenderFps` well under 10 with high `avgMaskRenderMs`: bitmap generation is expensive —
  optimize `visualizeForAspect` / `visualizeSemanticClassesForAspect`.
- Low `maskRenderFps` with very low `avgMaskRenderMs`: usually throttling or nothing to render, not
  a performance problem.
- `sourceAgeMax` in the seconds: the UI overlay may be showing old results — check render job
  cancellation, UI main-thread blocking, and overlay request override logic.

## Pipeline timing metrics

### `avgProcessFrameMs`

What it means: average time from a frame entering `StartSceneAnalysisUseCase` to
`ProcessFrameUseCase` completing.

Includes:

- semantic segment
- segmentation analyze
- obstacle detect
- obstacle extraction
- tracking

Use: total time of the perception main chain.

Reading it:

- Above 100 ms: hard to stay realtime.
- Break it down with `semMs` and `obsMs` to find the slow stage.

### `avgInferenceMs`

What it means: currently identical to `ProcessFrameUseCase`'s total perception time; the name is
kept for historical compatibility.

Use: for old reports and budget checks.

Reading it:

- Close to `avgProcessFrameMs`: the bottleneck is mostly perception.
- Well below `avgTotalPipelineMs`: the bottleneck is analysis, decision, trace, UI collect, or
  scheduling.

### `avgPipelineMs`

What it means: average time for one complete pipeline frame.

Includes:

- `processFrameMs`
- `analyzeSceneMs`
- `decideEventsMs`
- the synchronous path before the trace record

Use: end-to-end processing pressure.

### `p95PipelineMs`

What it means: 95th-percentile complete pipeline time.

How it is computed: sort all `totalPipelineMs` and take the 95% position.

Use: **realtime systems care about tail latency, not the average.** In blind navigation, an
occasional long delay is more dangerous than being uniformly slow — the user is moving, and a late
warning arrives after the step.

Reading it:

- Acceptable average but high p95: suspect GC, tensor allocation, model delegate jitter, log/trace
  IO, UI contention.
- Look first for per-frame allocation, bitmap re-creation, and large output tensor copies.

## Semantic stage timing

Shown in the report as:

```text
semMs=total:<avgSemanticTotalMs> pre:<avgSemanticPreprocessMs> infer:<avgSemanticInferenceMs> read:<avgSemanticOutputReadMs> post:<avgSemanticPostprocessMs>
```

These averages only count semantic runs where the time was actually > 0; frames reusing a cached
result under semantic skipping are not averaged in.

### `avgSemanticPreprocessMs`

What it means: time to turn a camera frame into the sem model's input tensor.

Current main path:

```text
YUV planes -> native rotation + letterbox + resize + normalize/quantize -> TensorBuffer
```

Reading it:

- High: check whether YUV-native is active, whether input size is too large, or whether it fell back
  to OpenCV.
- The OpenCV fallback adds a YUV -> RGBA -> Mat -> RGB -> resize -> float chain.

### `avgSemanticInferenceMs`

What it means: sem model `model.run()` time.

Reading it:

- High: sem model size/resolution is the main bottleneck.
- For a 1024×1024 sem on an 8 Gen 1, high is expected. A lower-resolution sem or downsampled output
  helps more.

### `avgSemanticOutputReadMs`

What it means: time to read sem output from the LiteRT output buffer into a JVM array.

Reading it:

- High: usually means the output tensor is huge, e.g. `1024 * 1024 * 19`.
- Directions: lower-resolution output, native postprocessing straight off the output buffer, fewer
  large JVM array copies.

### `avgSemanticPostprocessMs`

What it means: time to argmax the sem output into a class mask.

Reading it:

- High: first confirm native argmax is active.
- If output read exceeds postprocess, optimizing argmax will not help much — cut readback cost
  first.

## Obstacle stage timing

Shown in the report as:

```text
obsMs=total:<avgObstacleTotalMs> pre:<avgObstaclePreprocessMs> infer:<avgObstacleInferenceMs> read:<avgObstacleOutputReadMs> post:<avgObstaclePostprocessMs>
```

These averages only count frames where the obstacle provider actually ran; frames where det was not
due and tracker prediction covered do not drag the average down.

### `avgObstaclePreprocessMs`

What it means: time to turn a camera frame into the obstacle model's input tensor.

Reading it:

- High: check whether YUV-native is active, or whether the obstacle input size is too large.
- When sem and obstacle share the same input geometry, the latest-frame preprocessing cache is
  attempted; on a hit the backend reads `shared_native_yuv` or `shared_quantized_native_yuv`.

### `avgObstacleInferenceMs`

What it means: det model `model.run()` time.

Reading it:

- High: reduce det input size, quantize, or use a lighter detection model.
- If it is far below sem, det is not where to optimize first.

### `avgObstacleOutputReadMs`

What it means: time to read the obstacle detection tensor.

Reading it:

- High: check whether the detection tensor is too large, or whether output dequantization landed in
  Kotlin.
- The current main chain is bbox/class/confidence only; it does not read a prototype tensor.

### `avgObstaclePostprocessMs`

What it means: time for bbox decode, confidence filter, NMS, and class mapping.

Reading it:

- High: confirm the native postprocessor is active.
- Reduce `maxDetections`, raise the confidence threshold, or narrow the allowed classes.

## Scene-understanding metrics

### `navPassable`

What it means: the passable-area ratio inside the navigation corridor.

Use: whether road/sidewalk recognition is stable.

Reading it:

- Persistently low while the scene is walkable: check the class mapper, passable classes, corridor
  region, or sem model output.
- Jittery: consider temporal smoothing, mask morphology, or less semantic skipping.

### `blockageConfidence`

What it means: how confident connectivity analysis is that the way ahead is blocked.

Reading it:

- Too high, causing false "not passable": check the obstacle mask, connectivity threshold, bottom
  coverage.
- Too low, causing misses: check obstacle class mapping and corridor overlap.

### `verticalReach`

What it means: how far the passable area extends from the bottom of the frame into the distance.

Use: whether "from your feet to the distance" is connected.

Reading it:

- High: the way ahead is usually continuous.
- Low: possibly a road break, occlusion, mask jitter, or misclassified ground.

### `floodReach`

What it means: the passable ratio reachable by flood fill from the bottom seed region.

Use: cares about connected paths, unlike a plain road ratio.

Reading it:

- High `verticalReach` but low `floodReach`: the passable area may be cut by thin breaks — consider
  morphology close or bridge tolerance.

### `widthRetentionP25`

What it means: a low-percentile measure of how well passable width is retained.

Use: whether the corridor is continuously narrowing.

The computation is already perspective-normalized: the closer to the horizon, the smaller the
expected width, so natural distant narrowing is not counted as width loss. The value is clamped to
`0..1`, so the report should show `0..100%`.

Note: this metric should not drive a "the road is narrowing" prompt on its own — it is a debug
signal only. Judging blocked also needs `verticalReach` and `floodReach`.

## Stability metrics

### `stabilityDelta`

What it means: absolute change in core recognition metrics between adjacent processed frames. The
live debug panel shows the current frame's delta; the trace replay report shows avg/max.

Includes:

```text
navPassableDelta
roadRatioDelta
blockageConfidenceDelta
verticalReachDelta
floodReachDelta
widthRetentionP25Delta
```

Use: locates whether the road / walkable area is flickering, breaking up, or repeatedly triggering
blocked.

Reading it:

- High `navPassableDelta` or `roadRatioDelta`: suspect sem mask jitter, too long a semantic skipping
  reuse interval, or unstable road class mapping.
- High `verticalReachDelta` / `floodReachDelta`: the walkable area may be cut by thin breaks — look
  at the semantic class overlay and passable mask overlay.
- High `blockageConfidenceDelta` with low `navPassableDelta`: the connectivity threshold or obstacle
  occlusion subtraction may be flipping near a boundary.
- max much higher than avg: usually an occasional single-frame error. Both high: more likely a
  persistent scene, model, or threshold problem.

### `obstacleStability`

What it means: change in obstacle detection count between adjacent processed frames.

Includes:

```text
avgRawObstacleDetectionCount
avgRawObstacleDetectionCountDelta
maxRawObstacleDetectionCountDelta
avgObstacleCountDelta
maxObstacleCountDelta
```

Use: whether det flickers, and whether the tracker absorbs raw detection jitter.

Note: `avgRawObstacleDetectionCount` and the raw deltas only count frames where the obstacle
provider actually ran — `tracker_predict` skipped frames are not treated as raw=0 in the delta. This
matters once det is throttled to a target FPS, or the metric would report scheduled skips as
detection flicker.

Reading it:

- High raw delta, low tracked delta: the model output jitters but the tracker is absorbing it; the
  event layer is usually stable.
- Both high: consider detection confidence hysteresis, class/region voting, IoU thresholds, or more
  conservative event gates.
- Persistently high raw count: possibly false positives on static background objects — check the
  perspective corridor (`navigationCorridorFarWidth` / `navigationCorridorHorizonY`), corridor
  overlap, bottom Y, class filtering, or NMS thresholds.
- Raw count near 0 with obstacles actually present: check the model asset, backend, input
  shape/quant, class mapping, and confidence threshold.

### `rawObstacleClasses` / `trackedObstacleCategories`

What they mean:

- `rawObstacleClasses` — the obstacle provider's raw detection class distribution, e.g.
  `car:60%, person:20%, stop_sign:20%`.
- `trackedObstacleCategories` — the domain category distribution after the tracker / event side,
  e.g. `VEHICLE:70%, PERSON:30%`.

Use: work out what an obstacle prompt came from without reading logs. For roadside furniture like
poles, barriers, or low walls, first check whether raw class has a matching or near class, then
whether tracked category became `STATIC_OBSTACLE`.

Reading them:

- Raw class is `car` / `person` but the scene has neither: address obstacle model false positives,
  NMS, or event gates.
- Raw class is `stop_sign` / `bench` / `chair` / `potted_plant` and tracked is `STATIC_OBSTACLE`:
  the static-object chain is working; look at bottom Y, perspective corridor, and cooldown next.
- No matching raw class, but semantic dominant classes show `pole` / `wall` / `fence`: those more
  likely affect passable/connectivity rather than being announceable objects from obstacle det.
- `wall` / `fence` / `building` should **not** be crudely mapped to obstacle events, or roadside
  background walls and building facades become high-frequency false alarms.

### `occludedPassable`

What it means: the ratio of passable pixels `ObstacleOcclusionAnalyzer` subtracted from the passable
mask — the share carved out of the sem walkable area by tracked obstacle bbox ground-contact bands
(det∩sem cross-validation). The report shows avg/max plus adjacent-frame delta avg/max.

Use: explains blocked spikes, `widthRetentionP25` jumps, and whether det box contact bands are
over-occluding the walkable area.

Reading it:

- High `occludedPassable` max coinciding with a blocked spike: check whether `occlusionMinBottomY` /
  `occlusionBandHeightRatio` are too aggressive (a contact band tall enough to carve out distant
  walkable area), and whether det has false boxes.
- `occludedPassable` persistently near 0: blocked is mostly coming from the semantic passable mask /
  connectivity rather than det box subtraction — usually because the walkable area is already small,
  or det obstacles do not land on it.
- High delta with low avg: usually a single-frame occlusion hit from an obstacle briefly entering
  the corridor's contact band; check the overlay for oversized boxes.

### `blockageReasons`

What it means: the distribution of primary connectivity reasons on blocked frames. Possible values:

- `vertical` — the layered scan found too few forward-reachable layers.
- `flood` — insufficient forward flood connectivity from the bottom walkable region.
- `width` — passable width retention too low.
- Combinations like `vertical+flood` / `flood+width`.
- `suppressed_road_connected:*` — a marginal raw blocked fired, but CrossValidator suppressed it
  with strong connectivity evidence in a road context.

Use: locates whether blocked comes from semantic mask breaks, bottom seed problems, width
thresholds, or marginal false positives in a road context.

Reading it:

- High `flood`: check whether the passable mask is cut by thin breaks, or the flood seed landed on
  the wrong bottom run.
- High `width`: check perspective/width retention thresholds — do not treat natural distant
  narrowing as impassable.
- High `vertical+flood`: passable mask continuity is genuinely poor; check semantic classes.
- Lots of `suppressed_road_connected`: CrossValidator is catching marginal road-context blocked;
  consider lowering upstream sensitivity further.

### `roadVehicleSources`

What it means: the distribution of vehicle evidence sources that triggered a road warning.

- `raw` — raw vehicle detections from the current obstacle provider, passing raw vehicle debounce.
- `tracked` — stable vehicles from tracker output.
- `raw_and_tracked` — both support it.

Use: explains whether `event_road_warning_vehicle` came from a distant raw detection, a stable
tracked vehicle, or both.

Note: the default user speech policy already disables lane-semantic prompts like road warning / road
exit. `roadVehicleSources` and `roadVehicleGeometry` remain as debug metrics for judging road
vehicle recognition stability, but do not imply `event_road_warning_vehicle` will be announced.

Related fields:

- `roadVehicleGeometry.reasons` — geometric admission reason distribution. `near_bottom` means the
  vehicle bbox is already near the bottom of frame; `center_band` means the vehicle is not yet close
  but overlaps the navigation center band enough.
- `bottomY` — average bbox bottom position of triggering vehicles; closer to `100%` is closer to the
  user.
- `centerOverlap` — average lateral overlap between a vehicle bbox and the center navigation band,
  for judging whether it is mid-road / directly ahead.
- `area` — average bbox area ratio of triggering vehicles, to separate distant small targets from
  near large ones.

Reading it:

- High `raw` share with many false alarms: tune `rawVehicleOnRoadMinConfidence`,
  `rawVehicleOnRoadMinAreaRatio`, `rawVehicleOnRoadMinBottomY`,
  `rawVehicleOnRoadDebounceFrames`.
- High `tracked` share with many false alarms: tune `trackedVehicleOnRoadMinStableFrames`, tracker
  matching / missed frames, or the tracked vehicle's area, bottomY, center-band overlap gates.
- High `raw_and_tracked`: both model and tracker believe the vehicle is on the road; false alarms
  more likely come from the semantic road base sample or vehicle class misdetection.
- High `center_band` with low `bottomY`: prompts mostly come from distant but centered vehicles. If
  it feels intrusive, consider raising center-band overlap or min bottom Y.
- High `near_bottom` with high `area`: prompts more likely come from near vehicles — do not just
  threshold them away; confirm against real scenes.

## Event metrics

### `events`

What it means: total events produced in this session.

Reading it:

- Too many: cooldown too short, insufficient event merging, oversensitive thresholds.
- Too few: confidence threshold too high, tracker stable frames too high, events filtered by the
  conflict resolver.

### `blocked`

What it means: the ratio of frames judged blocked.

Reading it:

- High while actually passable: connectivity threshold too strict, missing passable class, obstacle
  mask too large.
- `event_blocked` is now only for high-certainty severe blockage. If connectivity says blocked but
  evidence is not strong enough to assert impassability, the event layer turns it into
  `event_path_complex`, so complex road conditions are not announced as "the way ahead is blocked".

### `danger`

What it means: the ratio of road-dangerous frames, mostly from road safety / vehicle road warning.

Reading it:

- Missed when vehicles pass: lower the vehicle confidence threshold, reduce tracker stable frames,
  raise det run FPS.
- Too many false alarms: raise road-warning confidence or lengthen cooldown.

### `messageKeys`

What it means: which prompt keys occurred in this session.

Use: whether prompts are monotonous, or whether some events never fire at all.

Reading it:

- Only `event_obstacle_center`: scene decisions are over-concentrated; you may need richer event
  categories or finer obstacle position judgement.
- `event_obstacle_center_person` / `event_obstacle_center_vehicle` appearing: obstacle prompts are
  retaining category. Only two category suffixes exist now — announcement categories are grouped by
  what the user has to *do*, not by model class:
  - `_bicycle` is gone; bicycles and motorcycles announce as `_vehicle`. Both mean stop and yield,
    and the model cannot tell a parked bicycle from an approaching one, so it groups to the more
    conservative side. `ObstacleCategory.BICYCLE` still exists in perception because
    `RoadSafetyAnalyzer` needs it — a parked bicycle must not raise a vehicle-on-road warning.
  - `_static` is gone; static and unknown obstacles announce with no suffix
    (`event_obstacle_center`). The old `_static` strings were word-for-word identical to the
    suffix-less ones.
- Multi-direction category keys like `event_obstacle_center_right_person` /
  `event_obstacle_left_center_vehicle`: several merged directions share one category, and the
  category is still announced.
- Forward person prompts are more conservative than side ones: a person straight ahead must be
  nearer or larger to fire, while left / front-left / front-right have their own earlier side gate
  and allow medium-urgency side persons — avoiding "early ahead, too late at the side".
- Front / front-left / front-right vehicles escalate to `CRITICAL`, and when a vehicle competes with
  a person/bicycle at the same urgency in the same zone, the vehicle wins. Mixed vehicle-and-other
  zones do not merge into a generic key, so vehicle semantics are not swallowed.
- Generic keys like `event_obstacle_center_right` / `event_obstacle_multiple`: usually the merged
  directions had inconsistent categories, or the upstream event had no category suffix.
- `event_path_complex`: forward connectivity evidence is complex or unstable but has not reached
  high-certainty `event_blocked`. Judge the scene together with a specific obstacle key, e.g. a
  concurrent `event_obstacle_center_person`.
- `event_road_warning_vehicle` / `event_ground_to_*` should not appear by
  default. These lane and surface-change semantics mislead easily and are kept behind explicit
  config switches; day-to-day experience relies on concrete obstacles, complex road conditions, and
  intersection prompts. `event_road_exit` and `event_suggest_*` no longer exist: the former fired
  after the user had already passed the spot. The latter relied on `suggestedBias`, which has no
  side-specific connectivity proof, so no executable direction suffix is emitted.
- **Direction suffixes are currently disabled for every event.** `suggestedBias` is
  a weighted centroid offset of the widest passable run per scan layer
  (`ConnectivityStatsExtractor.computeDirectionBias`); `layer.isValid` is a width check, not a
  connectivity check. So several mutually disconnected fragments leaning left still yield
  `bias = LEFT`. Since "blocked" means forward flood fill could not advance, "path blocked, keep
  left" would present a statistic as a walkable route. A global positive flood reach only proves
  that some forward corridor exists, not that the side named by the bias is connected.
  *Deferred capability:* per-side flood connectivity and safety judgement is needed so a direction
  names a route that actually goes somewhere. `SceneEvent.directionHint` remains only for
  trace/replay compatibility until then.
- `event_camera_blocked` / `event_low_light`: the input frame is unusable (covered lens, too dark).
  These suppress every other event for that frame — any judgement built on that frame is untrusted,
  and staying silent would read to the user as "nothing ahead". A run with a non-trivial share of
  these needs the capture re-checked before its other metrics mean anything.
- `event_intersection`: a scene element judged an intersection. The copy no longer hedges
  ("possible intersection" became "intersection ahead"): a blind user cannot verify the
  uncertainty, and the action is the same either way, so the hedge only cost speaking time.
  Certainty is now expressed by the threshold, not the wording — the shipping profile does not
  enable the traffic-light/road-ratio fallback because it false-positives too often, and only
  explicitly reliable intersection signals should reach the user.
- `event_traffic_light`: semantic segmentation stably detected a traffic light without escalating it
  to "possible intersection". This is a low-priority, long-cooldown, broad traffic hint and should
  rank after vehicles, people, and blocked / path-complex. It requires traffic-light pixel ratio,
  road context, and stable-frame gates, so distant small targets or single-frame missegmentation do
  not announce prematurely.
- Disabled-policy prompts appearing: check `EventGenerator` / `EventMerger` config.

## Quick diagnosis paths

### High frame drops

Look at:

```text
droppedFrameRate
cameraInputFps
pipelineOutputFps
avgPipelineMs
semMs
obsMs
```

Then:

- `cameraInputFps` normal, `pipelineOutputFps` low: the pipeline cannot keep up.
- High `semMs.infer`: use a lower-resolution sem or optimize the delegate.
- High `semMs.read`: sem output is too large — consider a lower-resolution output or native output
  postprocessing.
- High `obsMs.infer`: reduce obstacle input size, or raise the det scheduling interval
  (`detectionTargetFps`).

### Road mask jitter

Look at:

```text
semanticRunFps
avgSemanticInferenceMs
navPassable
stabilityDelta
verticalReach
floodReach
```

Then:

- `semanticRunFps` too low: reduce semantic frame skipping.
- Large nav/road/vertical/flood swings in `stabilityDelta`: needs mask temporal smoothing,
  morphology, or less semantic skipping.
- Low `floodReach`: connectivity is affected by breaks.

### Vehicles announced only sometimes

Look at:

```text
obstacleRunFps
obsMs
danger
obstacleStability
messageKeys
```

Then:

- Low `obstacleRunFps`: the moving target may land exactly on frames the obstacle provider skipped.
- High `obstacleStability` raw delta: det output itself flickers — check confidence, NMS, region
  filtering, tracker matching thresholds.
- `obsMs.total` acceptable: you can raise `detectionTargetFps`.
- Very low `danger` while obstacle detections exist: `RoadSafetyAnalyzer` thresholds or event
  cooldown may be too strict.

### UI mask updates slowly

Look at:

```text
maskRenderFps
avgMaskRenderMs
sourceAgeAvg/sourceAgeMax
pipelineOutputFps
```

Then:

- High `pipelineOutputFps` but low `maskRenderFps`: UI overlay throttling or render cost.
- High `avgMaskRenderMs`: optimize bitmap generation — fewer per-pixel Kotlin loops, cache the
  palette.
- `sourceAgeMax` over 1000 ms: this is not a normal low refresh rate; the overlay or UI update chain
  is showing stale results.
- Very low `sourceAgeAvg` but it still looks smeared: usually visual lag from the mask refreshing
  slower than the camera preview, not pipeline queuing.
- `maskRenderCount` of 0: the current overlay mode has no renderable mask, or the pipeline produced
  none.

## Suggested pre-release thresholds

Not absolute standards, but good regression checks:

- `droppedFrameRate < 50%` — the current staged goal under high-resolution sem.
- `pipelineOutputFps >= 3` — minimum usable.
- `pipelineOutputFps >= 5` — steadier for outdoor obstacle avoidance.
- `p95PipelineMs < 300ms` — dynamic obstacle prompts start being useful.
- `avgSemanticOutputReadMs` should not approach or exceed `avgSemanticInferenceMs`, or output
  readback is a clear bottleneck.
- `maskRenderFps` near `10 FPS` — matches the current 100 ms overlay throttle ceiling.
- `sourceAgeMax < 1000ms` — no second-scale stale overlay frames.
