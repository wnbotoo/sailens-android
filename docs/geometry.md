**English** | [简体中文](geometry.zh-CN.md)

# Camera geometry, ground contact and depth

How Guidance knows where the camera points, how far an obstacle's ground contact is, and how a
depth model's output is turned into a ground estimate. This document describes the code as it is;
where the two disagree, the code is the fact and this document gets corrected.

The code lives in `sailens-guidance` (`…guidance.geometry`, `…guidance.motion`, `…guidance.depth`),
`sailens-vision` (`…vision.depth`), `sailens-camera` (`CameraCharacteristicsSnapshot`) and the
shell's `CameraFactsAdapter` and `PhoneGeometryStore`.

## 1. Coordinate frames and time

- **Analysis image frame**: the upright analysis image, in which the semantic mask and detection
  boxes already share one coordinate space (letterbox cropped by `SemanticContentRegion`). All
  geometry works in this frame. Intrinsics are mapped from the sensor through CameraX's
  sensor-to-buffer transform and the analysis rotation into it.
- **Pixel coordinates are continuous**: pixel i spans [i, i+1), so the centre of a W-wide image is
  W/2, and a 90° rotation maps buffer y to upright x as `H − y`.
- **Camera frame**: x right, y down, z forward, attached to the analysis image frame.
- **Gravity**: a unit vector `g` in the camera frame pointing down, derived from the device-frame
  gravity sensor by the back camera's `SENSOR_ORIENTATION` and the frame's rotation.
- **Heading and yaw sign**: the heading is where the **phone** points, not where the user walks. It
  increases when the phone turns right. Android's rotation angles follow the right-hand rule about
  their own axes and are converted to this sign before use.
- **Degenerate view**: when the camera points nearly straight down or up, the projected optical axis
  is noise; the heading is carried over from the previous sample and no rate is claimed.
- **Time**: Guidance time is `SystemClock.elapsedRealtime()` end to end. `SensorEvent.timestamp` is
  in that base. A camera timestamp is in that base only when Camera2 reports
  `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`; otherwise the frame's source-side receipt time
  (`receivedElapsedRealtimeNanos`, a Sailens fallback, not a Camera2 source) is used and the geometry
  is marked degraded. The time basis is traced. How well frame time and sensor time align on real
  devices is measured with the timing-sync burst ([field-capture.md](field-capture.md) §3).

## 2. Camera facts and intrinsics

Camera facts are recorded raw and resolved per frame, by the same code for a live camera and for a
recorded capture, so old captures stay usable.

- **Raw facts**: per binding, `sailens-camera`'s `CameraCharacteristicsSnapshot` (intrinsic
  calibration, distortion, lens pose, active and pre-correction arrays, pixel array, physical size,
  focal lengths, sensor orientation, timestamp source), read on every bind and never cached across
  bindings. Per frame, `ImageFrame` carries the camera timestamp, the source-side receipt time, the
  rotation and `FrameSourceGeometry` (CameraX's sensor-to-buffer transform and crop rect). The shell's
  `CameraFactsAdapter` turns the snapshot into Guidance's `CameraFacts`; a capture gives the same
  through `CaptureCameraRecord.toCameraFacts()` and `FrameFacts.of(FrameRecord)`.
- **`CameraIntrinsicsResolver`**: `LENS_INTRINSIC_CALIBRATION` when reported, else an estimate from
  `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` and `SENSOR_INFO_PHYSICAL_SIZE` (the source is recorded);
  mapped through the frame's sensor-to-buffer transform, then rotated upright.
  - The transform takes **sensor coordinates**, the system in which the active array's rect is
    given, origin offset included. Calibration is relative to the pre-correction array's top-left,
    so it is moved by that array's origin first. The estimate's centre is the active array's centre
    in the same coordinates.
  - The **crop rectangle is not applied**: the models see the whole buffer.
  - With no transform, the buffer is assumed to be a centred fill of the active array, and the
    result is flagged.
  - A transform that is not an axis-aligned scale and offset (possibly with swapped or flipped axes)
    is refused.
- The simulator's camera resolves to 449.625 / 180 / 320 px (fx = fy, cx, cy) in the 360×640 class
  map, from the SM8850 main camera as recorded (4096×3072 active array, f = 2877.6 px, 1280×720
  buffer, sensor orientation 90°).

## 3. Gravity, camera geometry and motion

- **`GravityInCamera`**: device `TYPE_GRAVITY` (the reaction, pointing up) → unit down vector in the
  upright camera frame, for the back camera.
- **`CameraGeometryResolver`**: one `CameraGeometry` per frame, with its time basis, intrinsics,
  nearest gravity sample and its age, and the phone settings (§4).
  - **Unavailable** when there is no frame time, no intrinsics, no sensor orientation or no gravity.
  - **Degraded** for receipt-time alignment, estimated intrinsics or an assumed mapping, or gravity
    more than 100 ms from the frame.
- **`MotionTracker`** (pure, fed game-rotation-vector samples): the camera heading and the yaw rate.
  Within about 10° of vertical the heading is carried over (degraded) and no rate is claimed across
  that stretch. It keeps the last 128 samples and answers for the frame's time, interpolating between
  the samples either side; no sample within 200 ms on either side makes the state unavailable.
  **Rotation only**: translation is unknown (§8).
- **`AndroidOrientationSource`**: `TYPE_GRAVITY` and `TYPE_GAME_ROTATION_VECTOR` at
  `SENSOR_DELAY_GAME`, on a thread of their own. Started and stopped by the Guidance session itself
  (`StartSceneAnalysisUseCase`, in its `finally`), so the higher sensor rate costs nothing outside
  Guidance; reference-counted, so a session that ends late cannot switch the sensors off under the
  next one.
- **Wiring**: `StartSceneAnalysisUseCase` resolves the frame's geometry *before* processing it,
  because the ground-contact distance (§5) reads it. The motion state is read *after* the decision,
  for the record, so it cannot delay a prompt. A host without a `CameraFactsSource` binding runs
  Guidance with geometry unavailable, which is legal: every distance then stays at today's level.
- **Trace fields**: `geometryQuality`, `geometryIssues`, `geometryTimeBasis`, `gravityAgeMs`,
  `pitchDownDeg`, `rollDeg`, `intrinsicsSource`, `focalPx`, `headingDeg`, `yawRateDegPerS`,
  `orientationQuality`. Older traces parse as `none`.

## 4. Phone height

`PhoneGeometrySettings` comes from a shell-owned store (`PhoneGeometryStore`); the runtime profile
supplies only the seed, 1.3 m (chest height). Guidance reads `current()` once per frame.

| Placement | Preset height | Body-height ratio |
|---|---|---|
| chest lanyard | 1.25 m | 0.73 |
| handheld at chest | 1.20 m | 0.70 |
| waist | 1.00 m | 0.58 |

The heights and ratios are proposals, not calibrated. `PhoneGeometrySettings.of(placement,
bodyHeightCm)` gives: nothing → the seed (`DEFAULT_SEED`); a placement → `PRESET`; a placement and a
body height of 100–220 cm → `CALIBRATED`, rounded to the centimetre; a body height alone → the seed.

- **The ground-contact switch reaches Guidance at a session boundary.**
  `PerceptionProfileManager.selectGroundContact` takes effect at the next `activateSelected`,
  together with the profile, so a running session, and the config its capture records, never changes
  midway.
- **Release keeps it off.** The app sets the switch from the store only in a debug build. The only
  UI is the debug screen Settings → Diagnostics → "Ground-contact distance (experimental)": the
  switch, the placement, an optional body height, and the height in use with its source. In release
  nothing writes the store, so traces record the seed.

## 5. Ground-contact distance

Today's distance level comes from the box bottom's image position (`DistanceLevel.fromNormalizedY`).
The ground-contact estimate is computed beside it on every frame and traced; it may change a level
only when `PerceptionConfig.groundContact.enabled` and the phone height is a preset or a calibration.
Then it can only make a level **nearer**, never farther.

**Estimate** (`GroundContactDistanceEstimator`). The contact pixel is the bottom centre of the box.
β is the ray's angle below the horizon, from gravity, and `d = h / tan β` for phone height `h`. The
interval is `h(1 − Δh) / tan(β + δ)` … `h(1 + Δh) / tan(β − δ)`, with Δh = 15% for the seed or a
preset and 10% when calibrated, and δ = 2°.

**Validity**, checked in this order; an invalid contact never replaces today's level:

- `NO_GEOMETRY`: no geometry, or geometry that is unavailable or whose gravity is stale. Estimated
  intrinsics and receipt-time alignment are accepted.
- `NEAR_HORIZON`: β < 3°; the distance is ill-conditioned.
- `CLIPPED`: the box bottom is within 1% of the bottom edge; the true contact is below the frame.
- `OCCLUDED`: another detection covers the contact row across the box's central half. It must pass
  the extractor's confidence floor and its bottom must be more than 2% of the frame lower.
- `GROUND_UNSEEN`: under 30% of the strip below the contact is ground (passable or a ground type) in
  the semantic class map. The strip is 2% of the frame high and spans the box's central half. It is
  kept apart from `OCCLUDED` because it is common indoors, where reflective floors read as building.
- `IMPLAUSIBLE_BOX`: the box's implied height `H = h (1 − tan β_top / tan β)` falls outside its
  class's range under every allowed error (the height tolerance and the same ±2° gravity error the
  interval allows). Ranges (proposals, not calibrated): person 0.7–2.4 m, bicycle 0.6–2.2 m, vehicle
  0.9–4.5 m; static classes have none; a box whose top touches the frame is not judged.
  - The bound is proved, not sampled. A gravity within δ of the estimate moves the angle between
    gravity and any ray by at most δ (the triangle inequality on the sphere), so each β lies within δ
    of its nominal value, clipped to ±90°.
  - `H` is monotonic in `tan β_top` and `1 / tan β_bottom`, and linear in the phone height, so the
    extremes over those intervals enclose every allowed combination, the interior of the error cone
    included. Where the bottom ray may point straight down, `1 / tan` reaches its limit, exactly 0.
  - Treating the two rays' errors independently only widens the enclosure, so it rejects less.

**What validity does not catch.** A box cut short at the bottom (legs behind a wall) reads farther
and *taller*, not shorter, so within the tolerances it often passes. Its point estimate reads farther
than the truth; its lower bound, which carries the tolerances and is what the fusion uses, can still
be nearer than the truth (camera at 1.2 m, 25° down, a 0.9 m person truly 1.6 m away with the bottom
5 cm hidden: 1.67 m, lower bound 1.32 m, MEDIUM → NEAR). What is guaranteed is only the fusion's: the
level used is never farther than today's. The check bites on gross cases, such as a person box that
ends at the knee.

**Fusion** (`DistanceFusion`): the new level is `level(lowerBound)` with the 1.5 m / 4.0 m
thresholds; the level used is the nearer of today's and the new one when the contact is valid, the
switch is on and the height is not the seed, and today's otherwise. `DistanceEstimate` refuses to
hold a used level farther than today's. So an obstacle today's code would announce is never pushed
into FAR and dropped. Letting the estimate *lower* risk would be a separate decision on field
evidence. Events still speak in NEAR / MEDIUM / FAR, never metres.

**Config**: `PerceptionConfig.groundContact = GroundContactDistanceConfig(enabled = false, …)`. As
part of `PerceptionConfig`, a model-regression capture records it.

**Pipeline**: `ProcessFrameUseCase(frame, geometry)`. Each frame's distance context holds the
geometry, that frame's detection boxes, its class map and the config. `DefaultDepthRepository`
computes today's level and the contact on every call. Tracker-predicted frames keep the last
estimate.

**Trace**: per frame `phoneHeightM`, `heightSource`, `groundContactEnabled`; `obstacleDistances[]`,
one entry per tracked obstacle: `category`, `zone`, `contactValidity`, `lowerBoundM`, `pointM`,
`upperBoundM`, `levelToday`, `levelNew`, `levelUsed`.

**Recorded geometry and counterfactual replay.** Schema 1.4 of the model-regression record adds
`RegressionFrameRecord.geometry`, the geometry the frame was processed with: it is recorded, not
re-resolved from the sensor log, because the live pipeline uses the nearest sample *it has*, which a
replay cannot know. Replay uses it by default; a caller can supply geometry for older records. A
counterfactual replay runs a config the device did not run. The device's revokes say only which of
*its* prompts went undelivered, and a changed prompt may have been delivered after all (an upgrade
can interrupt the speech that blocked the original), so a counterfactual run states its assumption:
`UNCHANGED_AS_RECORDED` (a revoke reaches only an identical event; on the recorded config this
reproduces the device exactly, which a test checks) or `ALL_DELIVERED` (no revoke applies).

**Evaluation** (`GroundContactEvaluation`; on local captures through
`GroundContactCaptureEvaluationTest`, on synthetic captures in CI):

- Three runs: **exact** (the recorded config and geometry, untouched; it must reproduce the device),
  **baseline** (the distance explicitly off) and **treatment** (explicitly on). Baseline and treatment
  keep the recorded thresholds, use the evaluation's phone height (`-Pm3aPhoneHeight`), and run once
  under each delivery assumption; the effect is treatment against baseline *under the same
  assumption*. Only a recording made with the distance off must have its `UNCHANGED_AS_RECORDED`
  baseline equal the device.
- Geometry is the recorded one; an older capture is resolved from its sensor log, which needs the
  camera's realtime clock; a capture with neither is reported as not evaluable.
- A capture is complete only if its manifest says so, it lost no frames, the reader warned of
  nothing, and the records read match the manifest's count. Anything else is evaluated as far as it
  goes, labelled, and kept out of the pass. An empty evaluation fails.
- It counts prompts the user would have **heard**: the device made the identical offer and did not
  revoke it. Other prompts have unknown delivery and are counted apart. A prompt changes only if what
  is spoken changes: the key, the priority, the NEAR prefix or the direction suffix
  (`SceneEventStringsTest` checks that no event string has a placeholder).

## 6. Depth input transform and ground fit

**Model contract.** Input RGB, ImageNet normalisation, fixed shape; output relative disparity (larger
= nearer), one channel; layout resolved from the tensor shape as for sem/det. There is no class order
to get wrong; the equivalent risk is **inverse output** (depth instead of disparity), which static
preflight cannot see, so the ground fit checks the slope's sign on every frame and refuses an inverted
model. The rule for whatever consumes depth: a configured but broken depth model is never worse than
none. A failing, stale or inverse-output model leaves Guidance running as without it, and a failure
never turns into "no obstacles".

**Depth input transform** (`sailens-vision` `…vision.depth`). The mapping between the analysis image
and the model tensor is part of the model's configured contract, because every disparity sample is
turned into a ray.

- Each depth model declares a `ResizePolicy`, matching how its export was validated: `STRETCH`
  (resize without keeping aspect), `CROP_KEEP_ASPECT` (centre crop to the tensor aspect, then resize)
  or `PAD_KEEP_ASPECT`. It cannot be read from TFLite metadata, so it is configured with the model.
- `DepthInputTransform.create(analysis size, tensor size, policy)` gives `toTensor*` / `toAnalysis*`
  and `covers`. `scaleX ≠ scaleY` is allowed (stretch); it only has to be known.
- **All geometry is done in analysis-image pixels.** Ground-fit samples are analysis-image pixels
  `p`; `q = g · K⁻¹ p` uses the analysis-image intrinsics `K`; the disparity for `p` is read from the
  tensor at `toTensor(p)`. Nothing works in tensor pixels, so no tensor-space intrinsics are needed.
- Pixels where `covers(p)` is false (cropped away or padding) are **unknown**: never interpolated or
  extrapolated into ground, above or below. `DisparityMap.sampleAt(u, v)` is bilinear, returns null
  where the model did not see the point, and is clamped to the pixels whose centres lie in the
  content, so padding never blends in, even when it is fractional (960×1280 → 518×518 pads 64.75 px)
  or a half pixel (640×1280 → 518×518 pads 129.5 px; a centre exactly on the open right end is
  padding).

**Ground fit** (`…guidance.geometry` `GroundFitter`, Kotlin):

1. Sample the image below 40% of its height on a coarse grid. Far ground is where a small step or a
   gentle slope stays within the inlier threshold, and sampling it would bend the fit (a 15 cm curb's
   far road pulled `t` from 0.20 to 0.10).
2. With `q = g · r` per sample, RANSAC `disparity = A·q + t` with a 4% threshold, over both slopes. A
   better negative slope means inverse output and makes the observation invalid.
3. **Every decision uses the depth ratio `(d − t)/(A·q) = Z_ground/Z`**: the inlier test, the above /
   below labels and the recession check. Relative-depth models output `a/Z + b` with a and b free per
   frame; a residual relative to `d` itself lets a large b hide a 15 cm curb or a 1.5 m drop, while
   the depth ratio is unchanged by any a and b.
4. **The ground is the plane under the user.** Samples are ordered nearest first and grouped into 20
   bins; a candidate is scored by the unbroken run of bins it explains from the nearest outward,
   stopping at the first bin it explains less than half of; a tie goes to the candidate matching less
   outside the run. The refit uses only the run's inliers, and the run must span at least 25% in
   depth, else the result is `NO_SUPPORT`. Ranked by total inliers instead, the floor of a drop being
   approached becomes "the ground" once it fills more of the view. Selection and refit are
   deliberately redundant: each alone handles the approach.
5. **Support-switch guard.** When more than half of the cells the last valid fit saw below ground now
   read as ground (on a grid where at least 2% was below), the result is `SUPPORT_SWITCH`, which is
   unknown: relative depth cannot tell "stepped down" from "looking over the edge". It is released
   only when a fit sees a level below its ground again (two levels together, the near one under the
   user) or a new session starts — not after some number of fits, because a user standing at the edge
   gives any number of identical frames. Only a translation estimate could release it otherwise.
6. **A flat line is no ground**: a wall filling the view fits one too, and the run-span check rejects
   it.
7. **Labels** above / below ground by signed depth ratio (3× the threshold); metric height of a pixel
   above ground `h − Z · q` with `Z = A·h / (d − t)` (`GroundObservation.heightAboveGroundM`).
8. **Gravity agreement**: a free 3-parameter plane on the inliers must lean within 10° and ±33% of
   what gravity predicts. **Confidence** is the product of inlier ratio (0.2 → 0.6), agreement (×0.5
   when it fails) and the stability of ground coverage over the last 3 fits.

**Validity**: `VALID`, `TOO_FEW_SAMPLES`, `DEGENERATE_VIEW` (camera pointing straight down),
`NO_GROUND`, `NO_SUPPORT`, `SUPPORT_SWITCH` or `INVERSE_OUTPUT`.

There is no depth runner, depth `ModelType` or scheduling yet; the transform and the fit are pure JVM
code exercised by the simulator's synthetic disparity (`renderDisparity`, `a/Z + b`, through any
transform).

## 7. Simulator

`sailens-guidance/src/test/java/com/sailens/guidance/sim/` runs scripted walks through a synthetic
world on the JVM, deterministically.

- **`SyntheticScene`** is the physical truth: the camera of §2, a pose (camera height, pitch below
  horizontal, distance walked), and a world of ground patches at given heights, upright boxes and a
  far background. A ray-cast renderer turns it into what the models would report: the class map and
  detection boxes (a box can be detected only above a height, for a person behind a wall).
  `Scenario.facts(i)` gives the raw facts a capture of the same frame would hold (camera record,
  frame record with sensor-to-buffer transform, gravity samples), so the geometry resolves a scenario
  exactly as it resolves a recording. `SyntheticSceneTest` checks that the two describe one camera.
- **`GuidanceSimulator`** runs the whole `StartSceneAnalysisUseCase` path, wired as
  `DomainBindingsModule` wires the app, with fakes only for the two models, the clock and the
  stationary flag. Two substitutions are forced by the JVM, because the native libraries are
  arm64-only: connectivity runs on the Kotlin fallback, not the native kernel (the two differ on a
  narrowing corridor; see the connectivity note in [architecture.md](architecture.md)), and semantic
  stats come from the Kotlin extractor. The goldens' blockage, width and flood figures are the
  fallback's.
- **Goldens**: `sailens-guidance/src/test/resources/scenarios/<name>.golden.txt`, headed by the
  scenario's description, then one line per frame: decided events (key, priority, parameters),
  tracked obstacles (category / zone / distance), ground recognition, the connectivity figures and
  `geometry=<quality>/<pitch>`, rounded to 4 decimals. `ScenarioGoldenTest` fails on the first
  differing line. `<name>.m3a.golden.txt` is the same scenario with the ground-contact distance on (a
  1.3 m chest preset), with each obstacle's distance evidence (`GroundContactScenarioTest`); with the
  distance off the plain goldens are unchanged. After an intended change, regenerate and commit the
  diff with the change:
  `gradlew :sailens-guidance:testDebugUnitTest --tests "*ScenarioGoldenTest*" -PupdateGoldens`.
- **The goldens pin behaviour; they do not endorse it.** A person standing still 3 m away is MEDIUM
  or FAR depending only on pitch and phone height; an indoor floor read as building gives a CRITICAL
  "blocked" before "ground unrecognized"; a 15 cm curb down to the road is invisible; walking to a
  1.5 m drop drives blockage to 1.0 without a prompt; a person whose lower body is hidden by a wall
  reads FAR for the whole approach. With the ground contact on, no scenario's decided events change;
  levels do (a person 3 m away at about 29° down stays MEDIUM where today's level says FAR, and the
  parked car becomes NEAR about 0.25 m sooner).

## 8. Rules for geometry and why it is built this way

**Rules for every geometry consumer**

- **Raise before lower**: a new estimator may keep or raise risk relative to today's behaviour;
  letting it lower risk is a separate, evidence-backed decision.
- **Freshness**: every observation carries a timestamp and a validity window, and consumers check
  it. Nothing stale produces a new prompt or direction.
- **Fallbacks are explicit and traced**; a fallback that would change navigation meaning must be
  proven equivalent or degrade the result.
- **Performance**: no per-pixel objects, no `List<Point>` in hot paths; reuse buffers under the
  frame- and mask-pool ownership rules; one JNI call per frame per kernel.
- **Symmetry**: components with state implement `reset()` wired through `StopSceneAnalysisUseCase`.

**Why ground contact and gravity.** A PC experiment on DIODE and Cityscapes
([experiments/geometry-probe-2026-09.md](experiments/geometry-probe-2026-09.md)) found:

- Relative disparity alone cannot tell a floor from a wall (every plane is linear in disparity); on
  frames without a floor it still labelled about half the lower image as ground. With gravity, that
  dropped to about 20%.
- Phone-height error propagates linearly (±15% height → ±15% distance), and 2° of gravity error adds
  about 5 points: phone height is a calibration problem, not a constant.
- Obstacle distance extrapolated from depth is unreliable and biased *far*, the dangerous direction;
  distance has to come from the ground contact, and an occluded or clipped contact fails the same way,
  hence its own validity checks.
- Geometric indoor floor segmentation is weak (IoU about 0.33): it supplements the semantic model
  rather than replacing it.

**What a phone without visual-inertial odometry can know.** Gravity and rotation over a few seconds
are reliable. Translation and velocity are not (double-integrated acceleration drifts, and the step
detector needs a permission Sailens does not request), and device yaw is not walking direction for a
handheld phone. Hence `MotionTracker` is rotation only, and nothing fuses metric evidence across
frames while the user moves.

**Licensing.** No Project Guideline code is copied; if that ever changes, its copyright and licence
headers stay and `NOTICE` is updated. No weights, sound assets or datasets enter this repository; the
scripts under `scripts/experiments/geometry_probe/` are Sailens code. The depth model is
bring-your-own; a distribution that ships one reviews its licence and training-data terms (Depth
Anything V2 Small is Apache-2.0, and its training data includes datasets with research-use terms).
