**English** | [简体中文](local-navigation-roadmap.zh-CN.md)

# Local Navigation Roadmap (absorbing Project Guideline)

> Status: **proposal, review-first**. Nothing here is implemented. Each milestone is reviewed as a
> plan before code is written, lands as its own PR series, and keeps today's guidance behaviour
> unless the milestone says otherwise. There is no release deadline; releases are cut when a
> coherent set of milestones is done. Implementation detail lives in
> [`local-navigation-implementation.md`](local-navigation-implementation.md).

## 1. Why

Sailens Guidance today answers "what do I see in this frame, and is it worth a prompt?"
(`SceneSnapshot` → `SceneEvent` → speech/haptics). That is the right shape for "person ahead",
"camera covered" or "path blocked", but it cannot honestly answer:

- how far away is that obstacle, independent of how the phone is tilted;
- is the ground ahead flat, and is there a step or drop;
- is Guidance still qualified to guide at all;
- later: which way should the user move.

Google's [Project Guideline](https://github.com/google-research/project-guideline) (PG) built the
missing middle layer — spatial representation, motion, control, explicit STOP — and its 2026
successor, the [Running Guide agent](https://blog.google/innovation-and-ai/models-and-research/google-deepmind/running-guide-agent/),
kept a fast on-device safety path separate from a slow VLM path. This roadmap absorbs those ideas
into Sailens' own architecture. It is not a port: no PG code is copied, and PG is a reference, not
a template.

The target, in one line:

```
Perception → ground geometry → local world model → (later) planner → control → accessible output
                    SceneEvent continues to exist alongside, for "what happened"
```

## 2. What PG actually does, and what it depended on

Read from PG `main` (2026-09). The premises matter more than the techniques, because Sailens does
not share most of them.

| PG technique | Where | Premise it relies on | Sailens stance |
|---|---|---|---|
| Relative depth aligned to metric with a scale + shift fitted in inverse-depth space by RANSAC, ≥10 inliers | `depth/depth_align_ransac.cc` | ARCore VIO feature points with metric depth | **Borrow the maths**, anchor it on the ground plane (gravity + assumed phone height) instead of VIO |
| Hit-test image points against a synthetic ground plane at a fixed camera height | `util/hit_test_util.cc`, `camera_height_meters` (default 1 m, "TODO calibrate") | Phone fixed on a waist harness; pose from ARCore | **Borrow**: this is how obstacle distance should be computed (§5, M3a) |
| Point cloud → occupancy in a gravity-aligned clearance zone (4 m × 5 m, 1 m cells, height band camera −0.5…+1.2 m) | `environment/occupancy_map.cc`, `obstacle_utils.cc` | Metric point cloud in world frame | **Borrow the idea**, but PG's band ignores everything below ~0.5 m above ground: **PG never detects curbs, steps or drops**. Sailens must add that |
| Occupancy is per frame, stateless | `occupancy_map.h` comment | — | **Improve**: rolling occupancy with freshness and decay |
| Temporal latch before announcing "obstacle ahead" (enter needs 15 of ~30 frames) | `util/windowed_value_latch.h` | — | Sailens already has tracking + cooldown; reuse the idea for new signals only |
| Heading from VIO velocity; camera forward only below a minimum speed | `environment/path_planning.cc:53-60` | Metric 6-DoF pose (ARCore) | **Cannot borrow as is** without VIO: phone IMU gives reliable rotation, not translation (§6) |
| Tracking lost → reset + STOP signal | `guidance_system.cc` `OnTrackingStateChanged` | — | **Borrow**: loss of qualification is a state, not a prompt (M1) |
| Low-latency spatial audio | `audio/` | Headphones, fixed sound packs | Already planned in [`guidance-validation-roadmap.md`](guidance-validation-roadmap.md) Phase C; do not port PG sound packs or AAudio |
| Unreal simulator | `unreal/` | — | **Borrow the purpose**, not the tool: a deterministic JVM simulator (M2) |

PG defects worth not repeating: when depth alignment fails the point cloud is not refreshed, so a
stale cloud is paired with a new pose; `auto obstacle = obstacles.emplace_back()` copies, so
obstacle positions are never set (harmless only because the caller checks `empty()`).

Models: PG's `depth.tflite` model card says Apache-2.0, trained on OpenImages with DPT pseudo-labels
plus SANPO (CC-BY-4.0), and explicitly lists indoor scenes as a failure mode and "not appropriate for
safety-critical applications". Sailens chose **Depth Anything V2 Small** (Apache-2.0; its larger
siblings are CC-BY-NC) after a PC experiment — see §5.

## 3. Principles

1. **Kotlin owns meaning, state and safety; C++ owns expensive computation.** A kernel goes native
   only with profiling evidence, a correctness oracle and binding coverage (architecture §6.10).
2. **VLM is never on the safety path.** Describe failing cannot remove STOP or obstacle warnings.
3. **Learned depth is a measurement, not truth.** It may say "ground here", "something stands up
   there", "step down ahead". Metric obstacle distance comes from the ground plane, not from depth
   extrapolation (measured error: §5).
4. **No direction from unsupported evidence** (unchanged from the existing invariant: `suggestedBias`
   is a centroid, not a route). Directional output only after M8.
5. **Fail closed, visibly.** A missing or stale input degrades Guidance on a non-visual channel;
   it never reads as "nothing ahead".
6. **New behaviour ships off by default** and is switched on only by evidence from the simulator,
   trace comparison against a frozen baseline, and device runs.
7. **Model-neutral platform.** The depth model is bring-your-own like `sem`/`det`; no weights in this
   repository. Guidance without a depth model is a legal configuration.
8. **Capability boundaries stay.** New logic goes into existing modules as packages; no new Gradle
   modules unless a real dependency/API/native boundary appears.

## 4. Where the code is today (facts the plan builds on)

- **Obstacle distance ignores phone tilt.** `ProcessFrameUseCase` → `DefaultDepthRepository` →
  `ImagePositionDepthEstimator` → `DistanceLevel.fromNormalizedY` classifies NEAR/MEDIUM/FAR purely
  from the box bottom (`> 0.75`, `> 0.45`). Tilt the phone up and a near person becomes FAR, and
  `EventGenerator` drops FAR non-critical obstacles. This is the first thing M3a fixes.
- **No gravity, no intrinsics.** Guidance uses the accelerometer only for "is the user standing
  still" (`DeviceMotionDataSource`). Camera intrinsics are not read anywhere.
- **Safety pieces are spread across modules.** `FrameQuality` is in `sailens-guidance`;
  `GuidanceStallDetector`, the `SPEECH_UNAVAILABLE`/`INTERRUPTED`/`VISION_UNRELIABLE` haptics and the
  failure alarms are in `sailens-shell`. `sailens-guidance` may not learn about output health and
  `sailens-output` may not learn about Guidance, so a unified safety state must be layered (M1).
- **Trace is metrics only.** Frame traces carry timings, flags and `messageKeys` (candidates after
  cooldown), not images, sensor data or what was actually delivered. Nothing recorded today can be
  replayed through new geometry code.
- **Native binding coverage tests exist but only on device** (`Native*BindingCoverageTest` in
  androidTest); CI does not run them.

## 5. Evidence behind the geometry choice

PC experiment, 2026-09-24, `ai-edge-litert` on DIODE val (771 frames with laser depth) and
Cityscapes curbs. Medians over frames where the floor is visible.

| | DA V2 Small | PG depth |
|---|---|---|
| True ground is a plane in the model's disparity (median relative residual, outdoor / indoor) | **0.5% / 3.8%** | 1.8% / 5.1% |
| Ground depth error with gravity + correct height (outdoor / indoor) | **0.9% / 2.3%** | 1.8% / 7.3% |
| Precision of "more than 0.25 m above ground" (outdoor / indoor) | **1.00 / 0.98** | 1.00 / 0.72 |
| Curb: sidewalk judged above road; estimated height (true ≈ 10–15 cm) | **93–100%; 7–22 cm** | road plane fit covers only 22–60% of road |

Findings that shape the plan:

- **Relative disparity alone is not enough**: every plane is linear in disparity, so it cannot tell
  the floor from a wall; on floor-less frames it still labels ~50% of the lower image as ground.
  **Gravity is required**; with it the false ground drops to ~20%.
- **Height error propagates linearly** (±15% height → ±15% distance); IMU error of 2° adds 4–5
  points, 5° ~10 points.
- **Obstacle distance extrapolated from depth is unreliable** (outdoor median 40%, biased *far*,
  the dangerous direction). Distance must come from the ground contact point.
- Indoor floor segmentation by geometry reached only IoU ≈ 0.33 — indoor stays a supplement.
- 266×350 input (29 GOPs) performs close to 518×686 (122 GOPs).

## 6. Motion without VIO — the honest limit

PG steers by the direction the user moves, derived from ARCore's metric pose. A handheld Android
phone without VIO gives:

- **reliable**: gravity direction and rotation over a few seconds (gyro / game rotation vector);
- **unreliable**: translation and velocity (double-integrated acceleration drifts; the step
  detector needs the `ACTIVITY_RECOGNITION` permission, which Sailens deliberately avoided);
- **not equivalent**: device yaw ≠ walking direction for a handheld phone.

So the first `MotionTracker` is rotation-only and reports translation as unknown. The local world
model compensates rotation and keeps a short decay window instead of pretending to fuse
translation. ARCore would give metric pose but takes over the camera (it cannot share with
CameraX; shared-camera mode is Camera2 only) and adds a Play Services for AR dependency; it is the
**last** milestone (M11), evaluated as a camera-architecture change.

## 7. Milestones

Each milestone: goal → deliverables → exit criteria. "User-visible" says whether behaviour changes.

### M0 — Baseline and field capture (shared with Stage 2)
- **Goal**: one recording effort that serves false-positive labelling (Stage 2), #5 threshold
  calibration, geometry calibration and simulator/replay regression.
- **Deliverables**: frozen baseline commit; trace records what was *delivered* or *revoked*, not
  just candidates; a debug-only **field capture** (frames at reduced rate + gravity/rotation/gyro
  samples + camera intrinsics + trace) with a JVM reader; the recording handbook updated to use it;
  `docs/guidance-operating-envelope.md` (what is and is not supported).
- **Exit**: a capture made on the target device replays through the JVM reader with frames, sensor
  samples and intrinsics aligned; baseline metrics computed.
- **User-visible**: no (debug builds only).

### M1 — Guidance qualification and safety state (contracts)
- **Goal**: "may Guidance still guide?" becomes a first-class state instead of scattered alarms.
- **Deliverables**: `GuidanceQualification` in `sailens-guidance` (frame freshness, perception
  health, geometry/motion quality); `GuidanceSafetyState` composed in `sailens-shell` with stall
  detection and output health; every existing alarm mapped onto it; trace fields.
- **Exit**: unit tests for every transition; STOP-class states pre-empt normal events in tests;
  device run shows identical alarms to baseline.
- **User-visible**: no (same alarms, now explained by state).

### M2 — Deterministic simulator
- **Goal**: synthesize edge cases (dropouts, flicker, tilt, stale frames, blockage, recovery) and
  run the real decision path deterministically.
- **Deliverables**: JVM scenario harness in `sailens-guidance` tests; first scenario set; later
  able to drive M0 captures.
- **Exit**: scenarios run in CI and reproduce today's behaviour as golden output.
- **User-visible**: no.

### M3a — Ground-contact distance (no model)
- **Goal**: obstacle distance from gravity + intrinsics + assumed phone height (default 1.3 m).
- **Deliverables**: per-frame camera geometry (gravity in camera frame, intrinsics, timestamp
  alignment); contact-point distance with uncertainty; `DistanceLevel` from metres; fallback to the
  current estimator with the source traced; phone-height setting.
- **Exit**: simulator tilt scenarios pass; capture replay shows NEAR/MEDIUM/FAR stable under tilt;
  trace comparison against the baseline reviewed before enabling.
- **User-visible**: yes, once enabled — distance classes change. Ships behind a flag first.

### M3b — Ground geometry from learned depth
- **Goal**: class-agnostic ground, above-ground structure and steps/drops, indoors and outdoors.
- **Deliverables**: `ModelType` for depth (BYO, optional); depth runner; gravity-constrained ground
  fit; observation with ground / above / below masks and confidence; 2–5 Hz scheduling; device
  latency budget and GPU contention measured (this absorbs the pending P3 GPU-arbitration work,
  since depth is the new GPU tenant); debug overlay and trace.
- **Exit**: device budget met (§8); capture replay agrees with the PC experiment on the same
  scenes; no regression of sem/det p95 beyond the budget.
- **User-visible**: no. Using geometry to change prompts (e.g. letting a confirmed floor lift the
  #5 "ground not recognised" gate, or announcing steps) is a separate, evidence-gated decision.

### M4 — Local world model V0/V1
- **Goal**: separate "this frame's observation" from "short-term belief about the space ahead".
- **Deliverables**: ego-centric, gravity-aligned ground grid built from M3 observations, det
  contact points and sem passable area; rotation compensation; freshness and decay; trace fields.
- **Exit**: simulator scenarios for flicker and dropout show the model holding and then expiring
  correctly; no stale cell outlives its decay.
- **User-visible**: no.

### M5 — Motion tracker (rotation only)
- **Goal**: a `MotionState` with heading, rotation rate, stationary flag and honest tracking quality.
- **Deliverables**: interface + rotation-only implementation; qualification drops when orientation
  is unavailable; decision record on whether step detection is worth a permission prompt.
- **User-visible**: no.

### M6 — Occupancy and clearance
- **Goal**: "can I pass here?" separated from "what is this?".
- **Deliverables**: rolling occupancy over the world model; corridor clearance; first place where a
  native kernel is likely (only with profiling evidence).
- **Prerequisite**: decide the connectivity perspective divergence between the native kernel and
  the Kotlin fallback (open since the refactor) before anything consumes connectivity geometrically.
- **User-visible**: no.

### M7 — Planner and control signal (debug only)
- Short-horizon planner over M6; `GuidanceControlSignal` (HOLD / STEER / STOP, confidence,
  `validUntil`); visible only in overlay, trace and simulator.

### M8 — Control validation
- Oscillation, left/right flip-flop, stale control, unsafe recovery, false continuation after
  qualification loss; simulator + capture + device.

### M9 — Spatial earcon experiment
- Continues [`guidance-validation-roadmap.md`](guidance-validation-roadmap.md) Phase C, with
  `GuidanceControlSignal` as an input. Target-user evidence decides defaults.

### M10 — Native optimisation
- Projection, BEV rasterisation, occupancy, distance transform, depth alignment — each only with
  profiling evidence, an oracle, binding coverage and a device comparison.

### M11 — ARCore evaluation (last)
- A camera-architecture proposal: ARCore as an alternative `FrameSource` + pose source, its effect
  on `sailens-camera`, Describe snapshots, device coverage (incl. devices without Play Services),
  privacy disclosure, and whether metric pose beats the assumed-height model in field data.

### Suggested release grouping (no deadlines)
- **Release A**: M0–M3a (qualification state, simulator, tilt-correct distance).
- **Release B**: M3b–M6 (ground geometry, world model, clearance — still no steering).
- **Release C**: M7–M9 (control, validated earcons), subject to target-user evidence.

## 8. Device budget for the geometry work

Measured on SM8450 and SM8850:

1. `benchmark_model` per candidate (266×350, 392×518): GPU fp32 vs fp16 (the model card reports
   wrong fp16 GPU output — verify against PC fp32 on 20 frames), op profile to prove no CPU
   fallback; NPU attempt on SM8850.
2. In-app contention: depth at 3 Hz next to sem + det; sem/det p95 and dropped frames against the
   baseline.
3. 15-minute sustained run: thermal status, latency drift, battery.

Proposed gates: depth p95 ≤ 35 ms (SM8850) / ≤ 60 ms (SM8450); sem/det p95 regression ≤ 10%;
no thermal status ≥ SEVERE within 15 minutes.

## 9. Relationship to other open work

| Work | Relation | Order |
|---|---|---|
| P3 perf: sem mask ownership (mask pool) | Independent | Proceed now |
| P3 perf: camera frame pool | Touches `FrameSource`/`ImageFrameAnalyzer`, the same area M0/M3a extend with per-frame metadata | Land **before** M0 capture work |
| Stage 2+3: baseline, recording handbook, false-positive metrics | Is M0's baseline half; recordings must include the M0 capture or they will have to be redone | Freeze baseline + trace delivery fields now; **record after capture exists** |
| P3 GPU arbitration | VLM integration is postponed; the depth model is the next GPU tenant | Folded into M3b |
| P3 sem downsampling | Changes behaviour | After baseline, as already planned |
| #5 ground-recognition threshold calibration | Needs field data | Uses M0 captures; no separate calibration run |
| Connectivity perspective divergence | Affects anything consuming connectivity geometry | Decide before M6 |

## 10. Non-goals

Global navigation or map routing; porting PG code, Bazel, MediaPipe or Unreal; PG sound packs;
Pixel-only assumptions; moving policy or safety state into C++; ARCore before M11; directional
steering before M8; committing model weights.

## 11. Open questions for review

1. M0 capture: frame rate and resolution stored (proposal: 5 Hz, 640 px long side, JPEG), and
   whether captures may contain faces (they stay on device; export is manual).
2. Phone-height setting: default 1.3 m, user-editable in settings — or also a guided calibration?
3. Whether M3a ships as Release A's only user-visible change, or waits for M3b.
4. Operating envelope: which environments are declared unsupported in Release A (stairs? road
   crossings? running?).
