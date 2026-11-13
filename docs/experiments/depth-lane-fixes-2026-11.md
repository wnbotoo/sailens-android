**English** | [简体中文](depth-lane-fixes-2026-11.zh-CN.md)

# Experiment record: the support-switch guard and the cost of the ground fit (2026-11-13)

Two findings of the depth lane's device record ([depth-lane-device-2026-11.md](depth-lane-device-2026-11.md) §6),
measured on its own recordings: the support-switch guard held on flat ground, and the ground fit was the largest part
of a run. The guard now counts only a level a quarter of the camera height down ([`../geometry.md`](../geometry.md)
§6, step 5); the fit's inner loops were rewritten without changing a bit of its results. A device test stops a depth
session with its real inference in flight. Nothing reads the depth lane to decide anything, so none of this changes a
prompt. Captures and traces stay on the machine that recorded them.

## Environment

- The seven captures of the device record's §4 (OnePlus CPH2747, `qti_sm8850`, app code as `bd66f7b`, Depth Anything
  V2 Small 392×224 `4927dbc0…` on the GPU in FP16), 3016 acceptable runs, and their PC outputs from the same record.
  They name no guard rule, so they were recorded under rule 1, the label threshold.
- JVM: the module's unit tests on a Windows 11 desktop, the Gradle test JVM.
- Simulator: `GroundFitterTest`, noise-free disparity `a/Z + b`, a camera 1.3 m up and 25° down.

## 1. Why the guard held on flat ground

Under rule 1 a cell below the label threshold (depth ratio below −0.12, about 0.136 of the camera height down) counted
as another level. The guard armed on any such region of 2 % of the grid, switched when half of it later read as ground,
and was released only by another such region. Over the seven sessions (the exact replay, as recorded):

| Session | Runs | `SUPPORT_SWITCH` | Holds begun | Longest hold (fits) |
|---|---|---|---|---|
| Indoor floor `d0dfcfba` | 470 | 330 | 2 | 295 |
| Stair top `8d2f0011` | 446 | 239 | 3 | 152 |
| Granite sidewalk `9318627a` | 455 | 203 | 4 | 192 |
| Granite sidewalk, second `13ceb0a6` | 442 | 23 | 3 | 15 |
| Curb `e23b1ba6` | 370 | 74 | 5 | 22 |
| Curb, second `ba84831f` | 383 | 1 | 1 | 1 |
| Curb, third `782b2d9c` | 450 | 90 | 4 | 48 |

"Longest hold" counts the fits the hold stayed on, those that returned early while it was on included; it is not a run
of `SUPPORT_SWITCH` results. A local look at the fit before each of the 22 holds (refitting it without history, not part
of this repository) found shallow regions every time: median heights −0.18 to −0.38 m at the preset 1.2 m, just past
the label threshold. Outdoors they lay near the horizon, 20–50 camera heights out, where a small error in the fitted
shift becomes a large depth ratio; indoors at the far end of the visible floor, between furniture. Releasing needed such
a region again, which flat ground rarely gave (5 of 427 valid fits on the indoor floor), and fits that return early keep
the hold, so one trigger could hold to the end of a session. Those heights come from the same model and fitter: they say
the regions were shallow, not that the ground there was flat.

The fitted scale `A` between consecutive valid fits of the same scene moved by a median 6 %, p90 16–18 %, p99 33–42 %,
which is why the guard does not compare scales across frames: a single step changes it by 12–15 %.

## 2. The quarter-height rule on the same recordings

Rule 2 (`QUARTER_HEIGHT`): another level is a region with depth ratio −0.2 or less, a quarter of the camera height down
at any height, for arming and release alike. Each session's exact replay still fits with rule 1 and replays every
recorded fit; the phone's outputs are then fitted again under rule 2 from the session's first fit, and the PC's outputs
from that history (`-PdepthGuardRule=2`, [`../field-capture.md`](../field-capture.md) §11):

| Session | `SUPPORT_SWITCH` rule 1 → 2 | Holds begun | Longest hold | Fit agreement with the PC, rule 1 → 2 | Labels (runs) | Session order, rule 1 → 2 | Verdict |
|---|---|---|---|---|---|---|---|
| Indoor floor | 330 → **0** | 2 → 0 | 295 → 0 | 97.9 % → 98.7 % | 99.2 % (423) | 75.1 % → 98.7 % | pass |
| Stair top | 239 → 206 | 3 → 2 | 152 → 152 | 98.0 % → 98.7 % | 99.1 % (212) | 77.4 % → 98.7 % | pass |
| Granite sidewalk | 203 → **0** | 4 → 0 | 192 → 0 | 96.5 % → 98.7 % | 98.0 % (455) | 56.0 % → 99.8 % | pass |
| Granite sidewalk, second | 23 → **0** | 3 → 0 | 15 → 0 | 96.2 % → 98.0 % | 97.6 % (442) | 95.2 % → 100 % | pass |
| Curb | 74 → 19 | 5 → 1 | 22 → 19 | 98.4 % → 99.2 % | 98.2 % (348) | 91.4 % → 99.7 % | pass |
| Curb, second | 1 → 0 | 1 → 0 | 1 → 0 | 96.9 % → 95.6 % | 96.3 % (377) | 96.6 % → 99.7 % | pass |
| Curb, third | 90 → 140 | 4 → 3 | 48 → 118 | 99.3 % → 100 % | 98.3 % (307) | 99.6 % → 100 % | pass |

The output error is the same under both rules (median 0.49–0.79 %), since the rule changes no output. On flat ground the
guard no longer holds. Of the two holds left at the stair top, one followed a region reaching −1.33 m: likely the stairs.
On the third curb session rule 2 holds longer: before each hold 42–65 % of the grid read below ground, likely the road
seen from the curb, and a hold ends only when a level a quarter of the camera height deep is seen below the ground again.
Rule 2 does not make the fit agreement higher everywhere: on the second curb session it fell to 95.6 %, still within the
band.

## 3. What the guard still catches

`GroundFitterTest`, the same under rule 1 and rule 2 unless said:

- Drops of 0.6 m and more, approached 8 cm a fit, switch once only their floor is in view; so does a 1.5 m drop at
  0.4 m a fit (about 1.2 m/s at 3 Hz), from every one of four starting offsets. Pitching the phone up at the edge of a
  1.5 m drop, until only the floor is in view, switches too.
- Drops of 0.2–0.4 m approached 8 cm a fit are not caught, under either rule: one fit just before the near ground runs out
  takes a line across the last of it and the level below (twice the ground's scale), sees nothing below, and clears the
  history; the level below is then the ground.
- A 1.5 m drop whose disparity difference from the ground is pulled to half is still caught; to 45 % it is not.
- Holding at a 1.5 m drop's edge, a band 15 % below the floor does not end the hold; 25 % does. The same release ends it
  when a further drop lies beyond the floor, with the user still at the top; and a floor that ends in a further drop
  1 m down is not caught at all: the second drop takes most of the cells the guard remembered.

So the guard catches a level a quarter of the camera height and more taking over the near view, when the model reads
it that deep and no passing fit clears the history first; a `VALID` observation is not a confirmation that the fitted
plane is under the user.

## 4. The cost of the ground fit

| | p50 | p95 |
|---|---|---|
| JVM, the same 3016 outputs, before the rewrite (`GroundFitterBaseline`) | 1.87 ms | 2.30 ms |
| JVM, after | 1.09 ms | 1.51 ms |

Every fit of the 3016 is bit for bit the same before and after (`GroundFitterEquivalenceTest`); in the depth evidence
replay all seven sessions pass with the numbers of the device record's §4. The phone measured 146 ms p50 and 196 ms p95
for the fit's wall time in the same build of the code that takes about 2 ms here. Not measured yet: the phone, with the
build that only adds the fit's own CPU time to the records (`fitCpuUs`) and the build with the rewrite, three 90 s
sessions each with the lane on (`fit_cost.py`), to tell the fit's own work from waiting and core speed.

## 5. Stopping with a real inference in flight

Not run yet. `DepthLaneStopDuringInferenceTest` stops five sessions with the real runner's inference held at a gate
until the stop has returned, then lets it run: each must close with its attempt in the `infer` stage, read a whole
output, release the runner after it, and leave the next session to initialize only after that. It proves that order,
not that the GPU was executing at the instant of a stop. The device record's four stops that landed in inference by
timing stand.

## 6. Watched, not changed

The LOW queue priority leaning on sem and det to keep the GPU clocked, and the small rise of the GL mtrack figure
over many depth sessions (device record §1, §2): to be sampled again during §4's sessions; nothing is changed for them.

## 7. Older captures under a changed fitter

Depth evidence records each run's planes, the geometry the fit used, the input transform and the model's whole output
(float32), with the fit's results kept only as a summary to check against. So a capture recorded before the guard
changed can be fitted again under the new rule, as §2 does. What it cannot give:

1. Its recorded fits check only the rule it was recorded with (`depthEvidence.groundFitRule`; none named is rule 1).
2. Its timing is the recording build's: when each observation was published, its age, which frames were picked for a
   run. A replay that needs the new fitter's timing cannot take it from an older capture; the frame times still say
   when each frame was taken.
3. Acceptance still needs every fit from the session's first, under either rule: the guard's history has no expiry.
4. Its outputs are the recording model's on the recording runtime. Another model can be run on its planes on a PC,
   which gives the PC's outputs, not the phone's.

Such captures serve developing a reader of depth; accepting one needs recordings on the build that ships.

## Reproduce

```
gradlew :sailens-guidance:testDebugUnitTest --tests "*DepthEvidenceCaptureReplayTest*" -PreplayDepth=<captures> -PdepthPcOutputs=<pc dir> -PdepthGuardRule=2
gradlew :sailens-guidance:testDebugUnitTest --tests "*GroundFitterEquivalenceTest*" -PreplayDepth=<captures>
gradlew :sailens-guidance:testDebugUnitTest --tests "*GroundFitterTest*"
python scripts/experiments/depth_lane_budget/fit_cost.py <traces before> <traces after>
```

Without `-PdepthGuardRule` the replay evaluates the recorded rule (rule 1 for these captures), which gives §1's table.
