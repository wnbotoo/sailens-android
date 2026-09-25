**English** | [简体中文](local-navigation-implementation.zh-CN.md)

# Local Navigation — Implementation Plan

> Status: **proposal, review-first**. Companion to
> [`local-navigation-roadmap.md`](local-navigation-roadmap.md), which explains *why* and in what
> order. This document says *how*, per milestone: contracts, placement, algorithms, fallbacks, trace
> fields and tests. Type sketches are illustrative; names and fields are settled in each
> milestone's review. Before implementing a milestone, re-read the current code — where it has
> drifted from this document, the code is the fact and this document gets corrected.

## 1. Placement

No new Gradle modules. New packages inside existing modules, following the dependency direction in
[`architecture.md`](architecture.md) §4.2.

| Concern | Module | Package (proposal) | Why there |
|---|---|---|---|
| Frame capture metadata (sensor timestamp base, intrinsics) | `sailens-camera` | `com.sailens.camera` | Only the camera module talks to CameraX/Camera2 |
| Gravity / rotation sensors, `MotionTracker` | `sailens-guidance` | `…guidance.motion` (absorbs `sensors/`) | Navigation meaning; no UI |
| Camera geometry maths (rays, ground plane, contact distance) | `sailens-guidance` | `…guidance.geometry` | Guidance-specific use of generic maths |
| Depth model runner | `sailens-vision` | `…vision.depth` | Model output with no navigation meaning, like sem/det |
| Depth `ModelType`, catalog entry, preprocessing | `sailens-runtime` | existing | Model sources and preprocessing live here |
| Ground fit, ground observation | `sailens-guidance` | `…guidance.geometry` | Navigation meaning |
| Local world model | `sailens-guidance` | `…guidance.world` | |
| Qualification (may Guidance guide?) | `sailens-guidance` | `…guidance.safety` | Knows perception/geometry/motion health only |
| Safety state (adds stall + output health) | `sailens-shell` | `…shell.guidance.safety` | Only the shell sees both Guidance and output |
| Simulator | `sailens-guidance` test fixtures | `…guidance.simulation` (test source set) | JVM, deterministic |
| Field capture writer | `sailens-shell` debug source set | `…shell.debug.capture` | Debug-only, like trace UI |
| Field capture format + reader | `sailens-guidance` | `…guidance.trace.capture` | JVM-readable for replay and simulator |
| Planner, control signal (M7) | `sailens-guidance` | `…guidance.planning`, `…guidance.control` | |

`sailens-core` gains nothing unless a type is genuinely shared by two modules below Guidance
(candidate: a small `CameraIntrinsics` value if `sailens-vision` needs it for depth crop mapping).

## 2. Coordinate frames and time (applies to every milestone)

- **Analysis image frame**: the upright analysis image in which the semantic mask and detection
  boxes already share one coordinate space (letterbox cropped by `SemanticContentRegion`). All new
  geometry works in this frame. Intrinsics must be mapped from the sensor active array through the
  CameraX crop and the analysis rotation (`AnalysisRotation`) into it.
- **Camera frame**: x right, y down, z forward, attached to the analysis image frame above.
- **Gravity**: a unit vector `g` in the camera frame pointing down. Derived from the device-frame
  gravity/rotation sensor by the fixed back-camera orientation (`SENSOR_ORIENTATION`) plus the
  analysis rotation.
- **Time**: Guidance time is `SystemClock.elapsedRealtime()` end to end. `SensorEvent.timestamp` is
  in the elapsed-realtime base. `ImageProxy.imageInfo.timestamp` is in that base only when
  `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`; otherwise the frame is stamped at arrival and the
  alignment error is bounded and traced (`timestampSource`).

## 3. Contracts (sketches)

```kotlin
// sailens-camera: attached to every ImageFrame the analyzer emits (or carried alongside).
data class FrameCaptureInfo(
    val sensorTimestampNanos: Long,
    val timestampSource: TimestampSource,          // REALTIME | ARRIVAL
    val intrinsics: CameraIntrinsics?,             // in analysis-image pixels; null if unknown
    val intrinsicsSource: IntrinsicsSource,        // CALIBRATION | FOCAL_LENGTH_ESTIMATE | NONE
)

data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float,
                            val width: Int, val height: Int)

// sailens-guidance.geometry
data class CameraGeometry(               // one per frame
    val frameTimestampMs: Long,
    val intrinsics: CameraIntrinsics,
    val gravityInCamera: Vec3,           // unit, down
    val gravityAgeMs: Long,              // distance between frame time and nearest sensor sample
    val phoneHeightMeters: Float,        // assumed; default 1.3
    val quality: GeometryQuality,        // OK | DEGRADED(reason) | UNAVAILABLE(reason)
)

data class GroundContactDistance(
    val meters: Float?,                  // null: contact at/above horizon or truncated
    val lowerBoundMeters: Float?, val upperBoundMeters: Float?,
    val source: DistanceSource,          // GROUND_CONTACT | IMAGE_POSITION_FALLBACK
)

data class GroundObservation(            // M3b, from a depth frame
    val frameTimestampMs: Long,
    val groundMask: BinaryMask,          // analysis-image frame, same space as sem mask
    val aboveGroundMask: BinaryMask,
    val belowGroundMask: BinaryMask,
    val disparityScale: Float, val disparityShift: Float,   // fitted A, t
    val inlierRatio: Float,
    val planeAgreesWithGravity: Boolean,
    val confidence: Float,
)

// sailens-guidance.safety
sealed interface GuidanceQualification {
    data object Qualified : GuidanceQualification
    data class Degraded(val reasons: Set<DegradeReason>) : GuidanceQualification
    data class Unqualified(val reason: StopReason) : GuidanceQualification
}
// sailens-shell: GuidanceQualification + stall detector + output health
sealed interface GuidanceSafetyState { /* Initializing, Guiding, Degraded, StopRequired(reason),
                                          Interrupted(reason) */ }
```

`BinaryMask` stays the representation for per-pixel results (BitSet, no per-pixel objects).

## 4. M0 — Baseline and field capture

**Split of ownership to avoid duplicate work**: the Stage 2 workstream owns the baseline freeze,
the trace "delivered / revoked" fields and the recording handbook; the capture below is owned here.
The delivery fields land first (small), then capture.

**Trace delivery fields.** `FrameTrace.messageKeys` today are candidates after cooldown. Add per
event: `deliveredAt` / `revokedAt` / `revokeReason` as reported by the output coordinator, written
as a separate trace record keyed by event id (delivery happens after the frame trace is written).

**Field capture (debug builds only).**
- Subscribes to `FrameSource` as an ordinary subscriber (demand-driven, so it cannot force frames
  when Guidance is stopped unless capture is explicitly on). Must respect the frame-pool release
  contract from the P3 camera-frame-pool PR.
- Stores: frames downsampled to 640 px long side, JPEG, at 5 Hz (configurable), with sequence
  number and timestamps; every sensor sample of gravity, game rotation vector and gyroscope at
  `SENSOR_DELAY_GAME`; `FrameCaptureInfo` once per change; the regular trace session. One directory
  per session under app-internal `files/captures/`, JSONL + JPEG files; exported manually.
- Reader in `…guidance.trace.capture` yields a time-ordered stream of frames and sensor samples on
  the JVM, used by the simulator (M2) and by geometry replay tests (M3).
- Privacy: captures contain faces and places; they never leave the device automatically, are listed
  and deletable in the debug UI, and are excluded from release builds.

**Operating envelope doc** (`docs/guidance-operating-envelope.md`): phone placement and orientation,
walking only, lighting, weather, indoor/outdoor, stairs, road crossings, crowd density, headphones,
hardware classes, screen-off. Anything not validated is listed as unsupported.

**Tests**: reader round-trip on a synthetic capture; timestamp ordering; capture writer releases
every frame it receives (pool contract).

## 5. M1 — Qualification and safety state

**Guidance side (`GuidanceQualification`)**, computed once per pipeline result from:
frame age (now − frame timestamp) vs a budget; `FrameQuality` (covered / dark / blur);
perception failures (consecutive frame failures already counted against
`PipelinePerformanceBudget.maxConsecutiveFrameFailures`); ground-recognition state from #5;
later geometry and motion quality. `Unqualified` reasons: `FRAME_STALE`, `CAMERA_UNUSABLE`,
`PERCEPTION_FAILED`, later `ORIENTATION_LOST`, `NO_SAFE_PATH`.

**Shell side (`GuidanceSafetyState`)** composes qualification with `GuidanceStallDetector`
(no results at all) and output health (speech unavailable, audio route). It is the single input to
the alarm/haptic policy that today lives across `SceneAnalysisViewModel` and `GuidanceHaptic`.

**Rules**
- `StopRequired` / `Interrupted` pre-empt every normal event; queued normal events are revoked.
- Leaving `StopRequired` needs N consecutive qualified results (hysteresis), not one.
- Every transition is traced with reason and timestamp.

**No behaviour change in M1**: the mapping reproduces today's alarms exactly; the proof is a
before/after comparison of alarm timelines on simulator scenarios and one device run.

**Tests**: exhaustive `when` over states; transition table tests; "normal event cannot be delivered
while StopRequired"; "stale result cannot re-qualify".

## 6. M2 — Simulator

- A scenario is a script over a fake clock: synthetic perception results (masks, detections,
  frame quality), sensor timeline (gravity, rotation), failures (dropped frames, stale frames,
  model failure), and expected outputs (qualification states, events, later control signals).
- Runs the real `AnalyzeSceneUseCase` → `DecideEventsUseCase` path plus new components with fakes
  only at the model boundary. Deterministic: fixed seeds, injected clock (already injectable).
- Golden outputs are checked in; a golden change must be explained in the PR.
- First set: straight clear path, centre / left / right obstacle, narrowing, full blockage,
  segmentation flicker, false detection for one frame, stale frame burst, camera covered, phone
  tilt up / down with a fixed obstacle (for M3a), recovery after stop.
- Later: a capture adapter feeds M0 recordings through the same harness.

## 7. M3a — Ground-contact distance

**Inputs**: `CameraGeometry` for the frame; a box in analysis-image coordinates.

**Algorithm**
1. Contact pixel `p` = bottom centre of the box. If the box touches the bottom image edge, the
   contact point is below the frame: report `meters = null` with `upperBound` = distance of the
   bottom edge (the obstacle is at least that near) → NEAR.
2. Ray `r = K⁻¹ [u, v, 1]`, `q = g · r` (how far the ray points below the horizon).
3. If `q ≤ q_min` (at or above the horizon, or grazing), the ground is not hit: no distance;
   fall back to the image-position estimate and trace it.
4. Ground point `X = (h / q) · r`; horizontal distance `d = |X − (g · X) g|`.
5. Bounds from `h ± Δh` (±0.15 · h) and gravity error (±2°); `DistanceLevel` uses the upper bound
   for NEAR/MEDIUM decisions (errs toward "nearer"), thresholds 1.5 m / 4.0 m as used by
   `DefaultDepthRepository` today.

**Integration**: `DepthRepository.estimateDistance` gains the frame's `CameraGeometry`; the
`DefaultDepthRepository` path chooses ground contact when geometry quality is OK, otherwise the
current `ImagePositionDepthEstimator`. `DetectedObstacle` carries metres + source for trace; events
still speak in NEAR/MEDIUM/FAR (no metric wording to users).

**Known limits**: a contact point hidden behind another object reads too far; sloped ground biases
distance; very low phone height (e.g. phone at waist) is covered by the setting.

**Config**: `GroundContactDistanceConfig(enabled = false, phoneHeightMeters = 1.3f, …)` in
`SailensRuntimeProfile`; the setting is user-editable in the shell.

**Trace**: per obstacle `distanceMeters`, `distanceSource`, bounds; per frame `gravityAgeMs`,
`pitchDegrees`, `geometryQuality`, `timestampSource`, `intrinsicsSource`.

**Tests**: pure maths on synthetic cameras (known pitch/height → known distance); simulator tilt
scenarios (same obstacle, pitch −30°…+10°, class must not flip); fallback when gravity is stale.

## 8. M3b — Ground geometry from depth

**Model contract** (`docs/models.md` gets a section): input RGB, ImageNet normalisation, fixed
shape; output relative disparity (larger = nearer), one channel. Layout NCHW or NHWC resolved from
the tensor shape as for sem/det. No class order, so the "wrong order passes silently" risk of sem
does not apply; the equivalent risk is **inverse output** (depth instead of disparity) — preflight
cannot detect it statically, so the first frames check that disparity grows toward the bottom of
the image on a gravity-consistent ground fit, and the observation is marked invalid otherwise.

**Capability**: depth is an optional sub-capability of Guidance. Absent model → ground geometry
`Unavailable`, Guidance runs as today. Present but failing at runtime → qualification `Degraded`,
never silently "no obstacles".

**Runner** (`sailens-vision.depth`): LiteRT session, GPU fp32 by default (fp16 must be proven on
device first), output read through the zero-copy handle path where available.

**Ground fit** (`sailens-guidance.geometry`, Kotlin first):
1. Sample the lower part of the disparity map on a coarse grid (~6k points).
2. With `q = g · r` per sample, RANSAC `disparity = A·q + t` with a relative inlier threshold (4%).
3. Refit on inliers; ground mask = pixels within threshold and below the horizon (`q > 0`).
4. Above / below masks by signed relative residual (3× threshold).
5. Metric height of a pixel above ground (for steps) = `h − Z · q` with `Z = A·h / (d − t)`.
6. Confidence from inlier ratio, agreement between a free 3-DoF plane fit and the gravity-constrained
   fit, and fit stability over the last few depth frames.

**Scheduling**: 2–5 Hz through `PerceptionScheduler`, sharing the frame's preprocessing where the
cache allows. Stale observation (older than 2 depth periods) is not used.

**GPU contention**: depth is the new GPU tenant next to sem and det; the resource coordinator from
architecture §6.9 is designed here (this replaces the separate P3 GPU-arbitration task). Priority:
sem/det first, depth pre-emptible.

**Trace**: `depthMs`, `depthBackend`, `groundInlierRatio`, `groundConfidence`, `aboveGroundRatio`,
`belowGroundRatio`, `planeAgreesWithGravity`.

**Tests**: JVM tests with synthetic disparity (planes, steps, walls, noise) — ground mask, sign of
steps, step height within tolerance; device test comparing output with the PC reference on stored
frames; binding coverage if any native kernel is added.

## 9. M4 — Local world model

- Ego-centric, gravity-aligned ground grid: 0.1 m cells, 6 m forward × 4 m wide (configurable),
  packed arrays, preallocated.
- Per update, rasterise: ground/free from `GroundObservation` or sem passable (when no depth);
  occupied from det contact points and above-ground regions; drops from below-ground regions.
- Each cell stores value, confidence and last-observed time; decay with τ ≈ 0.7 s; cells older
  than 3τ are unknown, never free.
- Between updates, rotate the grid by the yaw change from `MotionState` (M5). Translation is not
  fused (unknown without VIO), which is why the decay window is short.
- Output: `LocalWorldModel(timestamp, ageMs, grid, confidence)`; `SceneSnapshot` keeps its current
  fields and may later read corridor summaries from it.

## 10. M5 — Motion tracker

- `MotionTracker` interface → `MotionState(timestamp, headingRad, yawRate, isStationary,
  orientationQuality, translation = UNKNOWN)`.
- First implementation: game rotation vector (+ gravity) at `SENSOR_DELAY_GAME`; stationary flag
  from the existing `DeviceMotionDataSource` logic.
- Orientation unavailable or noisy beyond a bound → qualification `Degraded(ORIENTATION)`, and
  anything depending on gravity (M3a, M3b, M4) falls back.
- Step detection: a separate decision record; not implemented without it.

## 11. M6–M10 (outline; detailed in their own reviews)

- **M6** rolling occupancy and corridor clearance over M4; decide the connectivity perspective
  divergence first. Native candidates: rasterisation, distance transform — only with profiling.
- **M7** planner in Kotlin; `GuidanceControlSignal(timestamp, validUntil, mode HOLD|STEER|STOP,
  headingCorrection (normalised), confidence, stopReason)`; debug output only.
- **M8** validation scenarios for oscillation, flip-flop, stale control, recovery.
- **M9** earcons per `guidance-validation-roadmap.md` Phase C (`AudioTrack`, runtime PCM).
- **M10** native kernels: `RegisterNatives` only, coarse-grained calls (one per frame), primitive
  arrays / direct buffers in, numbers out, no product state in C++, explicit failure. A fallback
  that is not proven equivalent degrades Guidance instead of silently changing semantics.

## 12. Cross-cutting rules

- **Freshness**: every observation, world model and control signal carries a timestamp and a
  validity window; consumers check it. Nothing stale produces a new prompt or direction.
- **Fallbacks are explicit and traced**; a fallback that changes navigation meaning must be proven
  equivalent or degrade qualification.
- **Performance**: no per-pixel objects, no `List<Point>` in hot paths; reuse buffers (follow the
  P3 mask/frame pool ownership rules); one JNI call per frame per kernel.
- **Symmetry**: new components with state implement `reset()` wired through
  `StopSceneAnalysisUseCase`.
- **Strings**: any new event key goes through `SceneEventMessageKeys` / `SceneEventStrings` in both
  languages.

## 13. Verification layers

| Layer | What | Where |
|---|---|---|
| A | Pure Kotlin unit tests (maths, state machines, freshness) | JVM, CI |
| B | Native kernel tests + binding coverage | device (androidTest) |
| C | Simulator scenarios with golden outputs | JVM, CI |
| D | Capture replay (M0 recordings) before/after | JVM, local |
| E | Device: latency, thermal, battery, sensors, audio route | SM8450 + SM8850 |
| F | Target-user validation for any output vocabulary | per guidance-validation-roadmap |

## 14. Licensing

No PG code is copied; if that ever changes, keep its copyright/licence headers and update NOTICE.
No model weights, sound assets or datasets enter this repository. The depth model is BYO; the
official distribution decides whether to ship Depth Anything V2 Small (Apache-2.0; its training data
includes datasets with research-use terms — a distribution-level review item, not a platform one).
