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
| Orientation sensors, `MotionTracker` | `sailens-guidance` | `…guidance.motion` (absorbs `sensors/`) | Navigation meaning; no UI |
| Camera geometry maths (rays, ground plane, contact distance) | `sailens-guidance` | `…guidance.geometry` | Guidance-specific use of generic maths |
| Depth model runner | `sailens-vision` | `…vision.depth` | Model output with no navigation meaning, like sem/det |
| Depth `ModelType`, catalog entry, preprocessing | `sailens-runtime` | existing | Model sources and preprocessing live here |
| Ground fit, ground observation | `sailens-guidance` | `…guidance.geometry` | Navigation meaning |
| Local world model | `sailens-guidance` | `…guidance.world` | |
| Qualification (may Guidance guide?) | `sailens-guidance` | `…guidance.safety` | Knows perception/geometry/motion health only |
| Safety state (adds stall + output health) | `sailens-shell` | `…shell.guidance.safety` | Only the shell sees both Guidance and output |
| Phone geometry settings (height, placement, calibration) | `sailens-shell` (store) → injected into Guidance | `…shell.settings` | A user/device setting, not a runtime preset |
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
  `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`. Otherwise the frame is stamped at arrival; the
  resulting alignment error is **unmeasured** until M0 measures it per device, and it is traced
  (`timestampSource`). Geometry consumers treat an unmeasured or too-large error as reduced
  quality.

## 3. Contracts (sketches)

```kotlin
// sailens-camera: carried on the ImageFrame the analyzer emits (per-subscriber copies keep it).
data class FrameCaptureInfo(
    val sensorTimestampNanos: Long,
    val timestampSource: TimestampSource,          // REALTIME | ARRIVAL
    val intrinsics: CameraIntrinsics?,             // in analysis-image pixels; null if unknown
    val intrinsicsSource: IntrinsicsSource,        // CALIBRATION | FOCAL_LENGTH_ESTIMATE | NONE
)

data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float,
                            val width: Int, val height: Int)

// Injected into Guidance from a shell-owned store; the runtime profile only supplies the default.
data class PhoneGeometrySettings(
    val heightMeters: Float,                       // seed 1.3
    val source: HeightSource,                      // DEFAULT_SEED | PRESET(placement) | CALIBRATED
    val placement: Placement?,                     // e.g. CHEST_LANYARD, HANDHELD_CHEST, WAIST
)

// sailens-guidance.geometry
data class CameraGeometry(               // one per frame
    val frameTimestampMs: Long,
    val intrinsics: CameraIntrinsics,
    val gravityInCamera: Vec3,           // unit, down
    val gravityAgeMs: Long,              // distance between frame time and nearest sensor sample
    val phone: PhoneGeometrySettings,
    val quality: GeometryQuality,        // OK | DEGRADED(reason) | UNAVAILABLE(reason)
)

data class GroundContactDistance(
    val validity: GroundContactValidity, // VALID | CLIPPED | OCCLUDED | NEAR_HORIZON | IMPLAUSIBLE_BOX
    val lowerBoundMeters: Float?,        // nearest the obstacle can be
    val upperBoundMeters: Float?,
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

data class MotionState(                  // M3o: rotation only
    val timestampMs: Long,
    val headingRad: Float,               // device heading, NOT walking direction
    val yawRateRadPerS: Float,
    val isStationary: Boolean,
    val orientationQuality: Quality,
)

// M5b: two separate capabilities; neither implies the other.
data class MovementDirectionEstimate(    // where the user walks
    val timestampMs: Long, val headingRad: Float, val confidence: Float, val validUntilMs: Long,
)
data class TranslationEstimate(          // how far the user moved since the previous estimate
    val fromMs: Long, val toMs: Long,
    val deltaForwardM: Float, val deltaRightM: Float, val uncertaintyM: Float, val confidence: Float,
)

// M3b: geometry of analysis image ↔ depth tensor. Part of the model's configured contract.
data class DepthInputTransform(
    val sourceRegion: Rect,              // region of the analysis image fed to the model
    val modelWidth: Int, val modelHeight: Int,
    val policy: ResizePolicy,            // STRETCH | CROP_KEEP_ASPECT | PAD_KEEP_ASPECT
    val scaleX: Float, val scaleY: Float,// analysis px → tensor px, after cropping to sourceRegion
    val padLeft: Int, val padTop: Int,   // tensor px, PAD only
) {
    fun toTensor(u: Float, v: Float): PointF   // analysis image → tensor
    fun toAnalysis(x: Float, y: Float): PointF // tensor → analysis image
    fun covers(u: Float, v: Float): Boolean    // false outside sourceRegion / inside padding
}

// sailens-guidance.safety — capability-scoped (see §5)
data class GuidanceQualification(
    val baseline: BaselineQualification,                 // what today's Guidance depends on
    val capabilities: Map<GuidanceCapability, CapabilityStatus>,
)
sealed interface BaselineQualification {
    data object Qualified : BaselineQualification
    data class Degraded(val reasons: Set<DegradeReason>) : BaselineQualification
    data class Unqualified(val reason: StopReason) : BaselineQualification
}
enum class GuidanceCapability { ORIENTATION, GROUND_CONTACT_DISTANCE, DEPTH_GEOMETRY,
                                MOVEMENT_DIRECTION, TRANSLATION /* later: DROP_WARNING, … */ }
sealed interface CapabilityStatus {
    data object NotConfigured : CapabilityStatus
    data object Available : CapabilityStatus
    data class Unavailable(val reason: String) : CapabilityStatus
}
// sailens-shell: GuidanceQualification + stall detector + output health (mapping in §5)
sealed interface GuidanceSafetyState { /* Initializing, Guiding, Degraded(reasons),
                                          StopRequired(reason), Interrupted(reason) */ }
```

`BinaryMask` stays the representation for per-pixel results (BitSet, no per-pixel objects).

## 4. M0 — Baseline and field capture

**Split of ownership to avoid duplicate work**: the Stage 2 workstream owns the baseline freeze,
the trace "delivered / revoked" fields and the recording handbook; the capture below is owned here.
The delivery fields land first (small), then capture, after the P3 camera-frame-pool PR.

**Trace delivery fields** (implemented in PR #8, `feat/trace-prompt-outcomes`; the PR is the
source of truth). `FrameTrace.messageKeys` stay the candidates after cooldown. A new `prompt_outcome`
record per offered prompt carries `eventId` (key), `sourceSequenceNumber` (joins to the frame),
`messageKey`, `category`, `priority`, exactly one of `deliveredAt` / `revokedAt`, `revokeReason`
(`waiting_for_description` | `output_refused`) and the output settings at the time. Timestamps are
wall-clock ms, the same clock as `pipelineCompletedAt`. Not recorded: a delivered utterance later
cut off by a higher-priority one.

**Clock anchoring.** Trace uses wall-clock ms; sensor samples and (with a `REALTIME` source) frames
use elapsed-realtime nanos. The capture writer records a `(wallMs, elapsedRealtimeNanos)` anchor
pair at session start (and on resume) so both can be placed on one timeline; frames join the trace
by `sequenceNumber`.

**Two capture semantics.**

| | Field evidence capture | Model-regression record |
|---|---|---|
| Purpose | Labelling, #5 threshold calibration, geometry calibration, simulator seeds | Replaying the decision path exactly after a pipeline change |
| Frames | 5 Hz, 640 px long side, JPEG | None continuously; full analysis-resolution frames only in a window around flagged events |
| Also stored | Gravity / game rotation vector / gyro at `SENSOR_DELAY_GAME`, `FrameCaptureInfo`, trace | Per-frame perception outputs (sem class mask or passable mask, detections, frame quality) + `CameraGeometry` |
| Not valid for | Re-running sem/det and expecting identical outputs (scaling + JPEG change model input) | Anything needing continuous video |

**Capture writer (debug builds only).**
- Uses the rate-limited subscription from the camera-frame-pool PR (#10):
  `FrameSource.frames(minIntervalMs = 200)`, so frames it does not need are never delivered to it.
  Stream frames are borrowed (architecture §6.1 as amended by #10): every frame it receives is
  released through `FrameSource.releaseFrame` (idempotent). Each subscriber gets its own
  `ImageFrame.copy()`, so `FrameCaptureInfo` placed on `ImageFrame` travels with it; M3o extends
  `ImageFrameConverter.convert` (which gains a `PlaneAllocator` parameter in #10) to fill it.
- One directory per session under app-internal `files/captures/`, JSONL + image files; exported
  manually; listed and deletable in the debug UI; excluded from release builds. Captures contain
  faces and places (accepted) and never leave the device automatically.
- Retention: a session is deleted 7 days after recording unless it was exported or pinned; total
  size is capped at 2 GB, oldest unpinned sessions removed first. (Field evidence at 5 Hz / 640 px
  is roughly 0.7 GB per hour.)
- Reader in `…guidance.trace.capture` yields a time-ordered stream of frames, sensor samples and
  perception records on the JVM, used by the simulator (M2) and geometry replay tests.

**Capture controls** (debug builds; these fill the placeholders in the field recording manual,
PR #9):

| Need | Design |
|---|---|
| Mode switch | Debug settings: "Field capture" off / field evidence / field evidence + model-regression record |
| Start / stop | While the mode is on, capture follows the Guidance session (starts and stops with it); no separate button to forget |
| Delete, pin | Debug capture list: per session size, duration, pinned flag; delete and pin actions |
| Export | Share sheet with one zip per session; the path under `files/captures/` is also documented for `adb pull` on debug builds |
| Viewing frames around a prompt | On the PC: the reader plus a small script that renders the frames within ±N seconds of a `prompt_outcome` or marker as a contact sheet; no in-app viewer |
| Storage | Estimated ≈ 0.7 GB/hour for field evidence; measured in M0 and written back into the manual |
| **"Missed alert" marker** | Volume-down press while capturing (works eyes-free and with the screen locked to the app; a short vibration confirms), plus a large on-screen button. Writes a `marker` record with wall-clock ms, elapsed-realtime nanos and the latest frame `sequenceNumber`. Volume-down is consumed only while capture is on, so normal volume control is unaffected otherwise |

The manual's sync convention (cover the lens for 3 s at the start and end of each segment) shows up
as an `event_camera_blocked` prompt outcome in the trace and a run of dark frames in the capture;
the reader can split segments on it.

**Measurements M0 must produce per target device**: camera timestamp source; frame-to-sensor
alignment error (e.g. by rotating the phone against a static scene and correlating image motion
with gyro); intrinsics source.

**Operating envelope doc** (`docs/guidance-operating-envelope.md`): phone placement and orientation,
walking only, lighting, weather, indoor/outdoor, stairs, road crossings, crowd density, headphones,
hardware classes, screen-off. Release A lists as unsupported: running, stair/drop guidance,
road-crossing guidance, directional steering, and any placement M3a has not been validated for.

**Tests**: reader round-trip on a synthetic capture; timestamp ordering; the writer releases every
frame it receives.

## 5. M1 — Qualification and safety state (exact reproduction); M1b — stop pre-emption

**Guidance side (`GuidanceQualification`)** is capability-scoped. Computed once per pipeline result:

- **Baseline qualification** — what today's Guidance (sem connectivity, det obstacles, current
  events) depends on: frame age (now − frame timestamp) vs a budget; `FrameQuality` (`OBSTRUCTED` /
  `TOO_DARK`); perception failures (consecutive failures already counted against
  `PipelinePerformanceBudget.maxConsecutiveFrameFailures`); ground-recognition state from #5.
  Unqualified reasons: `FRAME_STALE`, `CAMERA_UNUSABLE`, `PERCEPTION_FAILED`; later `NO_SAFE_PATH`
  once a planner exists.
- **Capability status** — one entry per optional capability (orientation, ground-contact distance,
  depth geometry, movement direction, translation): `NotConfigured` / `Available` / `Unavailable`.

Rules:

1. An optional capability that is missing, failing or stale makes **that capability** `Unavailable`.
   Features built on it fall back to today's behaviour. The baseline qualification does not change:
   **configured-but-broken is never worse than not configured.**
2. Only when a user-facing feature that is **enabled** needs the capability does its loss have wider
   effect, decided per feature in its own review: usually the feature switches off with a trace entry
   (and, if the user relied on it, a low-priority notice); if the capability is a necessary input to
   a control signal currently being delivered (M7+), the control output stops (`StopRequired`).
3. Today no user-facing feature depends on any optional capability (M3a is off by default, M3b is
   not user-visible), so in M1 every capability failure is trace-only.

**Shell side (`GuidanceSafetyState`)** composes qualification with `GuidanceStallDetector` and output
health. Mapping (the rule: output problems degrade *delivery*, not the judgement of the way):

| Condition | Safety state | Note |
|---|---|---|
| Qualified, all outputs OK | `Guiding` | |
| Baseline `Degraded` (e.g. ground not recognised) | `Degraded(perception)` | Today: path prompts paused, obstacles still announced |
| An optional capability `Unavailable`, no enabled feature depends on it | `Guiding` | Trace only |
| TTS unavailable, haptics available | `Degraded(output: speech)` | Guidance keeps judging; haptic vocabulary carries alarms (today's `SPEECH_UNAVAILABLE`) |
| Audio route lost, haptics available | `Degraded(output: audio)` | |
| Baseline `Unqualified` (camera unusable, stale frames, perception failed) | `StopRequired(reason)` | Guidance may not give navigation output |
| No results at all for the stall window (`GuidanceStallDetector`) | `StopRequired(PERCEPTION_STALLED)` | |
| No reliable output channel left (speech and haptics unavailable) | `Interrupted(NO_OUTPUT_CHANNEL)` | The user cannot be told anything; the UI shows it, the session keeps trying to recover |
| Lifecycle interruption (today's `INTERRUPTED`) | `Interrupted(reason)` | |

**M1 behaviour**: none. It reproduces today's alarms and today's `SENSOR_QUALITY` suppression in
`EventConflictResolver` exactly. It does **not** add queue revocation. Proof: unit tests for the
mapping and transitions, and a device alarm timeline identical to the baseline.

**M1b (behaviour change, after M2)**: entering `StopRequired` / `Interrupted` revokes queued normal
events and blocks new ones; leaving needs N consecutive qualified results (hysteresis). Proven with
simulator stop/recovery scenarios and a device run against the baseline.

**Tests**: exhaustive `when` over states; mapping table as a parameterised test; transition tests;
(M1b) "normal event cannot be delivered while StopRequired", "stale result cannot re-qualify".

## 6. M2 — Simulator

- A scenario is a script over a fake clock: synthetic perception results (masks, detections,
  frame quality), sensor timeline (gravity, rotation), failures (dropped frames, stale frames,
  model failure), and expected outputs (qualification and safety states, events, later control).
- Runs the real `AnalyzeSceneUseCase` → `DecideEventsUseCase` path plus new components with fakes
  only at the model boundary. Deterministic: fixed seeds, injected clock (already injectable).
- Golden outputs are checked in; a golden change must be explained in the PR.
- First set: straight clear path, centre / left / right obstacle, narrowing, full blockage,
  segmentation flicker, false detection for one frame, stale frame burst, camera covered, phone
  tilt up / down with a fixed obstacle (for M3a), partially occluded person (for M3a validity),
  recovery after stop (for M1b), walking user with a vanished obstacle (for M4).
- Later: a capture adapter feeds M0 model-regression records through the same harness.

## 7. M3o — Orientation and camera geometry

- `FrameCaptureInfo` from `sailens-camera`: timestamp source; intrinsics from
  `LENS_INTRINSIC_CALIBRATION` when present, else estimated from `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`
  and `SENSOR_INFO_PHYSICAL_SIZE` (source recorded), mapped into analysis-image pixels.
- `MotionTracker` (rotation only): game rotation vector + gravity at `SENSOR_DELAY_GAME`; stationary
  flag from the existing `DeviceMotionDataSource` logic; `headingRad` is device heading and is
  documented as not being walking direction.
- `CameraGeometry` per frame: gravity in the camera frame from the nearest sensor sample (age
  recorded), intrinsics, phone settings, quality. Quality drops when gravity is older than a bound,
  the alignment error is unmeasured/too large, or intrinsics are missing.
- Orientation unavailable → capability `ORIENTATION` is `Unavailable` (§5); every consumer
  (M3a, M3b, M4) falls back to today's behaviour; the baseline qualification is unchanged.
- **Tests**: rotation/axis conventions on synthetic sensor data for each analysis rotation; intrinsics
  mapping through crop and rotation.

## 8. M3a — Ground-contact distance

**Inputs**: `CameraGeometry`; a box in analysis-image coordinates; the frame's other detections and
sem mask (for the validity check).

**Distance interval**
1. Contact pixel `p` = bottom centre of the box.
2. Ray `r = K⁻¹ [u, v, 1]`, `q = g · r` (how far the ray points below the horizon).
3. Ground point `X = (h / q) · r`; horizontal distance `d = |X − (g · X) g|`.
4. Interval `[lower, upper]` from `h ± Δh` (±15% for a seed height; tighter once calibrated) and
   gravity error (±2°).

**Ground-contact validity** — any of these makes the contact invalid, and an invalid contact never
replaces today's estimate:
- `CLIPPED`: the box touches the bottom image edge (true contact below the frame). The obstacle is
  at least as near as the bottom edge; this can only raise risk.
- `OCCLUDED`: the box bottom overlaps another detection that is nearer (lower in the image), or the
  pixels just below the contact are not ground/passable in the sem mask (legs hidden by a railing,
  car, bench).
- `NEAR_HORIZON`: `q ≤ q_min` — distance is ill-conditioned.
- `IMPLAUSIBLE_BOX`: box aspect/size inconsistent with the class at the computed distance (e.g. a
  person box too short for its distance — typical of a detector cutting the box early).

**Risk-conservative fusion (phase 1)**:
`newLevel = level(lowerBound)` using the 1.5 m / 4.0 m thresholds; the level used =
`nearerOf(todayLevel, newLevel)` when valid, `todayLevel` otherwise. The new estimator can only keep
or raise risk, so an obstacle today's code would announce is never pushed into FAR and dropped by
`EventGenerator.shouldAnnounceObstacle`. Replacing today's heuristic outright (allowing the new
estimate to *lower* risk) is a later, separate decision based on field evidence.

**Phone height**: `PhoneGeometrySettings` from a shell-owned store (the runtime profile supplies the
1.3 m seed only). Calibration is by **placement preset** (chest lanyard, handheld at chest, waist),
each with a default height; optionally the user enters body height and the preset's ratio gives the
phone height (source `CALIBRATED`; ratios are proposals to be checked against captures). No guided
distance calibration. Enabling M3a requires a height with source `PRESET` or `CALIBRATED`; the seed
alone never changes distance levels.

**Integration**: `DepthRepository.estimateDistance` gains the frame's `CameraGeometry` and the
validity context; `DetectedObstacle` carries the interval, validity and chosen source for trace;
events still speak in NEAR/MEDIUM/FAR (no metric wording to users).

**Config**: `GroundContactDistanceConfig(enabled = false, …)`; thresholds (`q_min`, occlusion
margins, per-class plausibility) are profile values.

**Trace**: per obstacle `lowerBoundM`, `upperBoundM`, `contactValidity`, `levelToday`, `levelNew`,
`levelUsed`; per frame `gravityAgeMs`, `pitchDegrees`, `geometryQuality`, `timestampSource`,
`intrinsicsSource`, `heightSource`.

**Tests**: pure maths on synthetic cameras (known pitch/height → known distance); fusion table
(new estimate may raise, may not lower); validity cases; simulator tilt scenarios (same obstacle,
pitch −30°…+10°, the used level never becomes less near than today's); occluded-person scenario.

## 9. M3b — Ground geometry from depth

**Model contract** (`docs/models.md` gets a section): input RGB, ImageNet normalisation, fixed
shape; output relative disparity (larger = nearer), one channel. Layout NCHW or NHWC resolved from
the tensor shape as for sem/det. No class order, so the "wrong order passes silently" risk of sem
does not apply; the equivalent risk is **inverse output** (depth instead of disparity) — preflight
cannot detect it statically, so the first frames check that disparity grows toward the bottom of
the image on a gravity-consistent ground fit, and the observation is marked invalid otherwise.

**Capability**: depth is an optional capability (`DEPTH_GEOMETRY`, §5). Absent model →
`NotConfigured`; present but failing, stale or inverse-output → `Unavailable` with a reason. In
either case the baseline qualification is unchanged and Guidance runs as today — a configured but
broken depth model is never worse than none. A failure never turns into "no obstacles": features
built on depth fall back to today's behaviour, and only an enabled user-facing feature that needs
depth is affected (per §5 rule 2).

**Depth input transform (geometry contract).** The mapping between the analysis image and the model
tensor is part of the model's configured contract, not an implementation detail, because every
disparity sample is turned into a ray.
- Each depth model declares a `ResizePolicy`, matching how the export was validated:
  `STRETCH` (e.g. the `litert-community` export, which resizes to 686×518 without keeping aspect),
  `CROP_KEEP_ASPECT` (centre crop to the tensor aspect, then resize — what the PC experiment used), or
  `PAD_KEEP_ASPECT`. It is configured alongside the model (it cannot be read from TFLite metadata)
  and recorded in trace.
- The runner builds a `DepthInputTransform` per analysis size/rotation and returns it with the
  output. `scaleX ≠ scaleY` is allowed (stretch); it only has to be known.
- **All geometry is done in analysis-image pixels.** Ground-fit samples are analysis-image pixels
  `p`; `q = g · K⁻¹ p` uses the analysis-image intrinsics `K`; the disparity for `p` is read from the
  tensor at `toTensor(p)` (bilinear). Nothing works in tensor pixels, so no `K_depth` is needed. (If a
  future kernel does work in tensor space, it must use `K_depth = S · (K − crop offset)` with the
  transform's scale `S`, and prove equality against the analysis-space path.)
- `GroundObservation` masks are in analysis-image coordinates. Pixels where `covers(p)` is false
  (cropped away or padding) are **unknown** — never interpolated or extrapolated into valid ground,
  above or below.
- Tests: round-trip `toAnalysis(toTensor(p)) = p` for each policy and analysis rotation; a synthetic
  plane rendered through each policy yields the same fitted ground in analysis space.

**Runner** (`sailens-vision.depth`): LiteRT session, GPU fp32 by default (fp16 must be proven on
device first), output read through the zero-copy handle path where available; returns the disparity
tensor together with its `DepthInputTransform`.

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

## 10. M4 — Local world model

**V0 (frame-local)**
- Ego-centric, gravity-aligned ground grid: 0.1 m cells, 6 m forward × 4 m wide (configurable),
  packed arrays, preallocated.
- Rebuilt every update from the current observations only: ground/free from `GroundObservation` (or
  sem passable when no depth); occupied from valid det contact points and above-ground regions;
  drops from below-ground regions; everything else unknown.

**V1 (temporal, restricted)**
- **While stationary** (`MotionState.isStationary`): cells fuse over time with decay; yaw changes
  rotate the grid.
- **While moving, translation unknown**: only *risk* evidence (occupied, drop) is carried from past
  frames, dilated by `v_max · age` (v_max ≈ 1.5 m/s walking) and dropped after a short hold
  (≈ 0.5 s). Free/ground is never carried across frames: a cell not observed free in the current
  frame is unknown.
- With a runtime **metric translation** source (M5b translation or M11) the grid may translate and
  fuse free space; that is a separate change with its own review. A movement-direction source alone
  does not permit it.

**Output**: `LocalWorldModel(timestamp, ageMs, grid, fusionMode, confidence)`; `SceneSnapshot` keeps
its current fields and may later read corridor summaries from it.

**Tests**: simulator flicker/dropout scenarios; moving-user scenario proving no cell is free from a
past observation; stationary scenario proving fusion reduces flicker.

## 11. M5a / M5b — Movement sources

Two capabilities, judged and implemented separately (contracts in §3):

| | Movement direction | Metric translation |
|---|---|---|
| Question | Which way is the user walking? | How far did the user move between frames? |
| Consumer | Production steering gate (M7–M9) | Rolling free space / occupancy (M4 V1+, M6) |
| Contract | `MovementDirectionEstimate` (heading, confidence, validity) | `TranslationEstimate` (Δforward, Δright, uncertainty, confidence) |

**M5a — evaluation (offline, decision record).** Candidates assessed on M0 captures: step-based dead
reckoning (needs the `ACTIVITY_RECOGNITION` decision); visual motion from consecutive frames (e.g.
ground-plane flow — M3 gives the plane and scale). Rigid-mount validation is out of scope for now
(decided 2026-09-25). The record gives two verdicts, *direction* and *translation*, each
VALIDATED / NOT_VALIDATED with its error statistics against a reference (e.g. walked routes of known
length and shape).

**M5b — runtime implementation.** Only for a VALIDATED verdict: implement `MovementDirectionSource`
and/or `TranslationSource` in `…guidance.motion`, with freshness, confidence and explicit failure
(capability status per §5), and validate on device against the M5a evidence. An offline verdict
without M5b does not satisfy any gate.

## 12. M6–M11 (outline; detailed in their own reviews)

- **M6** occupancy and corridor clearance over M4 (frame-local / stationary unless a runtime metric
  translation source from M5b or M11 exists); decide the connectivity perspective divergence first. Native candidates:
  rasterisation, distance transform — only with profiling.
- **M7** planner in Kotlin; `GuidanceControlSignal(timestamp, validUntil, mode HOLD|STEER|STOP,
  headingCorrection (normalised), confidence, stopReason)`; debug output only.
- **M8** validation scenarios for oscillation, flip-flop, stale control, recovery.
- **M9** earcons per `guidance-validation-roadmap.md` Phase C (`AudioTrack`, runtime PCM), as an
  experiment; production steering only through the roadmap's gate.
- **M10** native kernels: `RegisterNatives` only, coarse-grained calls (one per frame), primitive
  arrays / direct buffers in, numbers out, no product state in C++, explicit failure. A fallback
  that is not proven equivalent degrades Guidance instead of silently changing semantics.
- **M11** ARCore evaluation as a camera-architecture proposal.

## 13. Cross-cutting rules

- **Freshness**: every observation, world model and control signal carries a timestamp and a
  validity window; consumers check it. Nothing stale produces a new prompt or direction.
- **Raise before lower**: a new estimator may keep or raise risk relative to today's behaviour;
  letting it lower risk is a separate evidence-backed decision.
- **Fallbacks are explicit and traced**; a fallback that changes navigation meaning must be proven
  equivalent or degrade qualification.
- **Performance**: no per-pixel objects, no `List<Point>` in hot paths; reuse buffers (follow the
  P3 mask/frame pool ownership rules); one JNI call per frame per kernel.
- **Symmetry**: new components with state implement `reset()` wired through
  `StopSceneAnalysisUseCase`.
- **Strings**: any new event key goes through `SceneEventMessageKeys` / `SceneEventStrings` in both
  languages.

## 14. Verification layers

| Layer | What | Where |
|---|---|---|
| A | Pure Kotlin unit tests (maths, state machines, freshness, fusion tables) | JVM, CI |
| B | Native kernel tests + binding coverage | device (androidTest) |
| C | Simulator scenarios with golden outputs | JVM, CI |
| D | Capture replay (M0 records) before/after | JVM, local |
| E | Device: latency, thermal, battery, sensors, timestamp alignment, audio route | SM8450 + SM8850 |
| F | Target-user validation for any output vocabulary | per guidance-validation-roadmap |

## 15. Licensing

No PG code is copied; if that ever changes, keep its copyright/licence headers and update NOTICE.
No model weights, sound assets or datasets enter this repository; the experiment scripts under
`scripts/experiments/geometry_probe/` are Sailens code. The depth model is BYO; the official
distribution decides whether to ship Depth Anything V2 Small (Apache-2.0; its training data includes
datasets with research-use terms — a distribution-level review item, not a platform one).
