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
| Relative depth aligned to metric with a scale + shift fitted in inverse-depth space by RANSAC, ≥10 inliers | `depth/depth_align_ransac.cc` | ARCore VIO feature points with metric depth | **Borrow the maths**, anchor it on the ground plane (gravity + phone height) instead of VIO |
| Hit-test image points against a synthetic ground plane at a fixed camera height | `util/hit_test_util.cc`, `camera_height_meters` (default 1 m, "TODO calibrate") | Phone fixed on a waist harness; pose from ARCore | **Borrow, with a validity gate**: the basis for obstacle distance (M3a) |
| Point cloud → occupancy in a gravity-aligned clearance zone (4 m × 5 m, 1 m cells, height band camera −0.5…+1.2 m) | `environment/occupancy_map.cc`, `obstacle_utils.cc` | Metric point cloud in world frame | **Borrow the idea**, but PG's band ignores everything below ~0.5 m above ground: **PG never detects curbs, steps or drops**. Sailens must add that |
| Occupancy is per frame, stateless | `occupancy_map.h` comment | — | Frame-local first. Rolling occupancy only where translation is known (§6) |
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
   extrapolation (measured error: §5), and only where the ground contact is valid.
4. **No direction from unsupported evidence** (unchanged from the existing invariant: `suggestedBias`
   is a centroid, not a route). Production steering has its own hard gate (§7, "Production steering
   gate").
5. **Fail closed, visibly.** A missing or stale input degrades Guidance on a non-visual channel;
   it never reads as "nothing ahead".
6. **New behaviour ships off by default** and is switched on only by evidence from the simulator,
   trace comparison against a frozen baseline, and device runs.
7. **A new estimate may raise risk before it may lower it.** Until field evidence says otherwise, a
   new estimator can only keep or increase what today's code would announce; it cannot suppress it.
8. **Stale "occupied" is a nuisance; stale "free" is a hazard.** Anything carried across frames
   without a known translation may hold risk, never clear it.
9. **Model-neutral platform.** The depth model is bring-your-own like `sem`/`det`; no weights in this
   repository. Guidance without a depth model is a legal configuration.
10. **Capability boundaries stay.** New logic goes into existing modules as packages; no new Gradle
    modules unless a real dependency/API/native boundary appears.

## 4. Where the code is today (facts the plan builds on)

- **Obstacle distance ignores phone tilt.** `ProcessFrameUseCase` → `DefaultDepthRepository` →
  `ImagePositionDepthEstimator` → `DistanceLevel.fromNormalizedY` classifies NEAR/MEDIUM/FAR purely
  from the box bottom (`> 0.75`, `> 0.45`). Tilt the phone up and a near person becomes FAR, and
  `EventGenerator.shouldAnnounceObstacle` drops FAR non-critical obstacles.
- **No gravity, no intrinsics.** Guidance uses the accelerometer only for "is the user standing
  still" (`DeviceMotionDataSource`). Camera intrinsics are not read anywhere.
- **Safety pieces are spread across modules.** `FrameQuality` (`OK` / `OBSTRUCTED` / `TOO_DARK`) is in
  `sailens-guidance` and, through a `SENSOR_QUALITY` event, suppresses same-frame events in
  `EventConflictResolver`. `GuidanceStallDetector`, the `SPEECH_UNAVAILABLE` / `INTERRUPTED` /
  `VISION_UNRELIABLE` haptics and the failure alarms are shell-level system notices. There is no
  single state that revokes already-queued normal events. `sailens-guidance` may not learn about
  output health and `sailens-output` may not learn about Guidance, so a unified safety state must be
  layered (M1).
- **Trace is metrics only.** Frame traces carry timings, flags and `messageKeys` (candidates after
  cooldown), not images, sensor data or what was actually delivered.
- **Native binding coverage tests exist but only on device** (`Native*BindingCoverageTest` in
  androidTest); CI does not run them.

## 5. Evidence behind the geometry choice

PC experiment, 2026-09-24, on DIODE val (771 frames with laser depth) and Cityscapes curbs. Method,
model hashes, commands and the raw summary are in
[`experiments/geometry-probe-2026-09.md`](experiments/geometry-probe-2026-09.md). Medians over
frames where the floor is visible.

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
- **Height error propagates linearly** (±15% height → ±15% distance); gravity error of 2° adds ~5
  points, 5° ~11 points. Phone height is therefore a calibration problem, not a constant.
- **Obstacle distance extrapolated from depth is unreliable** (outdoor median 40%, biased *far*,
  the dangerous direction). Distance must come from the ground contact point — and a contact point
  that is occluded or clipped has the same "too far" failure, so it needs its own validity gate.
- Indoor floor segmentation by geometry reached only IoU ≈ 0.33 — indoor stays a supplement.
- 266×350 input (29 GOPs) performs close to 518×686 (122 GOPs).

## 6. Motion without VIO — the honest limit

PG steers by the direction the user moves, derived from ARCore's metric pose. A handheld Android
phone without VIO gives:

- **reliable**: gravity direction and rotation over a few seconds (gyro / game rotation vector);
- **unreliable**: translation and velocity (double-integrated acceleration drifts; the step
  detector needs the `ACTIVITY_RECOGNITION` permission, which Sailens deliberately avoided);
- **not equivalent**: device yaw ≠ walking direction for a handheld phone.

Consequences written into the milestones:

1. **No metric cross-frame fusion while moving.** At 1–1.5 m/s, 0.7 s of unknown translation is
   0.7–1 m of position error against 0.1 m cells. Without a translation source the world model is
   frame-local; temporal fusion is allowed only while stationary, and risk evidence carried across
   frames is spread by the worst-case walked distance and can never mark a cell free (M4).
2. **No production steering without a movement-direction source.** A planner that knows where the
   phone points but not where the user walks must not steer. See the gate in §7.
3. ARCore would give metric pose but takes over the camera (it cannot share with CameraX;
   shared-camera mode is Camera2 only) and adds a Play Services for AR dependency. It stays the
   **last** milestone (M11). The consequence: production steering waits for M11 unless another
   movement-direction source (M5) or a rigid-mount operating envelope is validated first.

## 7. Milestones

Each milestone: goal → depends on → deliverables → exit criteria. Dependencies only point backwards.

```
M0 ─┬─ M1 ── M1b
    ├─ M2 ───┘
    └─ M3o ── M3a
          └── M3b ── M4 ── M6 ── M7 ── M8 ── M9
    M5 (movement direction) ─────────┘   (production steering gate needs M5 or M11)
    M10 after M6–M8 stabilise;  M11 last
```

### M0 — Baseline and field capture (shared with Stage 2)
- **Depends on**: the P3 camera-frame-pool PR (same code area).
- **Goal**: one recording effort that serves false-positive labelling (Stage 2), #5 threshold
  calibration, geometry calibration and simulator scenarios.
- **Deliverables**: frozen baseline commit; trace records what was *delivered* or *revoked*, not
  just candidates; two capture semantics (implementation §4):
  - **field evidence capture** — frames at reduced rate and size (5 Hz, 640 px JPEG) + gravity /
    rotation / gyro samples + intrinsics + trace, for labelling and geometry; it is **not** an exact
    perception replay (scaling and JPEG change model input);
  - **model-regression record** — per-frame perception outputs (and, only around flagged events,
    full analysis-resolution frames), for replaying the decision path exactly;
  a JVM reader; the recording handbook updated to use it; `docs/guidance-operating-envelope.md`.
- **Exit**: a capture from the target device replays through the JVM reader with frames, sensor
  samples and intrinsics aligned; the camera timestamp source and the frame/sensor alignment error
  are measured on each target device; baseline metrics computed.
- **User-visible**: no (debug builds only).

### M1 — Guidance qualification and safety state (contracts, exact reproduction)
- **Depends on**: M0 baseline.
- **Goal**: "may Guidance still guide?" becomes a first-class state instead of scattered alarms.
- **Deliverables**: `GuidanceQualification` in `sailens-guidance`; `GuidanceSafetyState` composed in
  `sailens-shell` with stall detection and output health, with an explicit mapping table
  (implementation §5) that keeps "I cannot speak" (output degraded) apart from "I cannot judge the
  way" (qualification lost); every existing alarm mapped onto it; trace fields.
- **Behaviour**: none. M1 reproduces today's alarms and today's `SENSOR_QUALITY` suppression exactly;
  it does not add queue revocation.
- **Exit**: unit tests for every transition; device alarm timeline identical to the baseline.

### M1b — Stop pre-empts queued events (behaviour change)
- **Depends on**: M1, M2.
- **Goal**: the new invariant — entering a stop-class state revokes queued normal events and blocks
  new ones until re-qualified with hysteresis.
- **Exit**: simulator scenarios for stop/recovery; device run reviewed against the baseline.
- **User-visible**: yes (fewer stale prompts after a stop).

### M2 — Deterministic simulator
- **Depends on**: M0 (reader, for later capture-driven scenarios); M1 states when present.
- **Goal**: synthesize edge cases (dropouts, flicker, tilt, stale frames, blockage, recovery) and
  run the real decision path deterministically.
- **Exit**: scenarios run in CI and reproduce today's behaviour as golden output (including the M1
  state timeline).

### M3o — Orientation and camera geometry (foundation)
- **Depends on**: M0 (timestamp source measured), P3 frame pool.
- **Goal**: per-frame gravity in the camera frame, intrinsics in analysis-image pixels, and a
  rotation-only `MotionState` (heading, yaw rate, stationary, orientation quality; translation
  unknown). This is what M3a, M3b and M4 consume; nothing here depends on a later milestone.
- **User-visible**: no.

### M3a — Ground-contact distance (no model)
- **Depends on**: M3o, M2.
- **Goal**: obstacle distance from gravity + intrinsics + phone height.
- **Deliverables**: contact-point distance as an interval; a separate **ground-contact validity**
  check (clipped box, contact occluded by another detection or by the sem mask, contact near the
  horizon, implausible box geometry) — invalid contacts never replace today's estimate;
  **risk-conservative fusion** — the level used is the nearer of today's level and the new level
  computed from the interval's *lower* bound, so the new estimator can raise or keep risk but never
  push an obstacle into FAR where `EventGenerator` would drop it; phone height from a user/device
  geometry setting (with a guided calibration or placement preset), not from the runtime profile.
- **Enable gate** (the code can land earlier, default off): calibration available; simulator tilt
  scenarios pass; capture replay and device evidence reviewed against the baseline.
- **User-visible**: only after the enable gate.

### M3b — Ground geometry from learned depth
- **Depends on**: M3o, M2.
- **Goal**: class-agnostic ground, above-ground structure and steps/drops, indoors and outdoors.
- **Deliverables**: `ModelType` for depth (BYO, optional); depth runner; gravity-constrained ground
  fit; observation with ground / above / below masks and confidence; 2–5 Hz scheduling; device
  latency budget and GPU contention measured (this absorbs the pending P3 GPU-arbitration work);
  debug overlay and trace.
- **Exit**: device budget met (§8); capture replay agrees with the PC experiment on the same scenes;
  no regression of sem/det p95 beyond the budget.
- **User-visible**: no. Using geometry to change prompts (e.g. letting a confirmed floor lift the
  #5 gate, or announcing steps) is a separate, evidence-gated decision.

### M4 — Local world model V0 (frame-local) and V1 (stationary / risk-hold)
- **Depends on**: M3o, M3a, M3b.
- **V0**: an ego-centric, gravity-aligned ground grid rebuilt every frame from the current
  observations only (M3b ground/above/below, det contact points, sem passable). No cross-frame
  memory.
- **V1**: temporal fusion only (a) while `MotionState.isStationary`, or (b) for *risk* evidence
  (occupied, drop), spread by `v_max · age` and expiring quickly. Free space is never carried across
  frames while moving.
- **Exit**: simulator flicker/dropout scenarios; a moving-user scenario proves no cell is marked free
  from past observations.
- **User-visible**: no.

### M5 — Movement-direction source (evaluation)
- **Depends on**: M3o.
- **Goal**: decide whether any non-ARCore source can give a validated walking direction: step-based
  dead reckoning (needs the permission decision) or visual motion from consecutive frames. Rigid-mount
  validation is out of scope for now (decided 2026-09-25). Output: a decision record with field
  evidence, or "none validated".
- **User-visible**: no.

### M6 — Occupancy and clearance
- **Depends on**: M4; rolling (translation-compensated) occupancy additionally needs a validated
  source from M5 or M11 — otherwise frame-local / stationary only.
- **Prerequisite**: decide the connectivity perspective divergence between the native kernel and the
  Kotlin fallback before anything consumes connectivity geometrically.
- First place where a native kernel is likely (only with profiling evidence).

### M7 — Planner and control signal (debug only)
- **Depends on**: M6. Short-horizon planner; `GuidanceControlSignal` (HOLD / STEER / STOP,
  confidence, `validUntil`); visible only in overlay, trace and simulator.

### M8 — Control validation
- **Depends on**: M7. Oscillation, left/right flip-flop, stale control, unsafe recovery, false
  continuation after qualification loss; simulator + capture + device.

### M9 — Spatial earcon experiment
- **Depends on**: M8. Continues [`guidance-validation-roadmap.md`](guidance-validation-roadmap.md)
  Phase C as an experiment, with `GuidanceControlSignal` as an input.

### Production steering gate (not a milestone; a condition)
`GuidanceControlSignal.STEER` reaches users only when **all** hold: M8 passed; M9 target-user
evidence; and **either** a validated movement-direction source (M5 or M11) **or** an operating
envelope that requires a rigid mount whose forward axis is the walking direction, validated in the
field. Otherwise STEER stays debug-only. HOLD/STOP semantics can ship earlier through M1b.

### M10 — Native optimisation
- After M6–M8 stabilise. Projection, BEV rasterisation, occupancy, distance transform, depth
  alignment — each only with profiling evidence, an oracle, binding coverage and a device comparison.

### M11 — ARCore evaluation (last)
- A camera-architecture proposal: ARCore as an alternative `FrameSource` + pose source, its effect
  on `sailens-camera`, Describe snapshots, device coverage (incl. devices without Play Services),
  privacy disclosure, and whether metric pose beats the calibrated-height model in field data.

### Suggested release grouping (no deadlines)
- **Release A**: M0, M1, M2, M3o, M3a code (M3a default off unless its enable gate is met), M1b if
  ready.
- **Release B**: M3b, M4, M6 (frame-local / stationary), M5 decision — still no steering.
- **Release C**: M7–M9 as experiments. Production steering only through its gate.

## 8. Device budget for the geometry work (to be measured)

Plan, on SM8450 and SM8850:

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
| P3 perf: camera frame pool | Touches `FrameSource`/`ImageFrameAnalyzer`, the same area M0/M3o extend | Land **before** M0 capture; M0 uses its rate-limited subscription |
| Stage 2+3: baseline, recording handbook, false-positive metrics | Is M0's baseline half; recordings must use the M0 capture or they will have to be redone | Freeze baseline + trace delivery fields now; **record after capture exists** |
| P3 GPU arbitration | VLM integration is postponed; the depth model is the next GPU tenant | Folded into M3b |
| P3 sem downsampling | Changes behaviour | After baseline, as already planned |
| #5 ground-recognition threshold calibration | Needs field data | Uses M0 captures; no separate calibration run |
| Connectivity perspective divergence | Affects anything consuming connectivity geometry | Decide before M6 |

## 10. Non-goals

Global navigation or map routing; porting PG code, Bazel, MediaPipe or Unreal; PG sound packs;
Pixel-only assumptions; moving policy or safety state into C++; ARCore before M11; production
steering outside its gate; committing model weights.

## 11. Decisions from review and remaining questions

Adopted (PR #7 review):

- M0 capture at 5 Hz / 640 px JPEG is field evidence, not exact replay; exact replay uses recorded
  perception outputs plus event-window full frames.
- Phone height: 1.3 m is only a seed; enabling M3a requires a guided calibration or explicit
  placement presets.
- M3a code may land in Release A but stays off by default until its enable gate is met.
- Release A operating envelope declares unsupported: running, stair/drop guidance, road-crossing
  guidance, directional steering, and any phone placement M3a has not been validated for.

Decided afterwards (2026-09-25):

- **Calibration = placement presets** (chest lanyard / handheld at chest / waist), each with a
  default height. Optional low-effort refinement: the user enters body height and the preset's ratio
  gives phone height. A guided distance calibration is not planned — it asks a blind user to know a
  distance to a wall, which is the thing we are trying to measure.
- **Captures may contain faces.** Retention: deleted automatically 7 days after recording unless
  exported or pinned in the debug UI; total cap 2 GB, oldest removed first.
- **No rigid-mount validation for now.** M5 evaluates step-based and visual movement direction only;
  production steering therefore waits for M5 to validate a source or for M11.
