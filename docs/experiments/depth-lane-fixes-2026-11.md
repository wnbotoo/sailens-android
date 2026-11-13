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

**On the phone.** The same phone, a build of this change (`919c657`), one field-evidence capture with depth evidence on
an indoor floor, held at the chest and walked from the start of the session: 464 runs, none dropped, recorded under rule
2 (the manifest names it). Every run replays exactly under rule 2 (464 of 464, scale and shift differences 0). Rule 2
gave no `SUPPORT_SWITCH`; the same outputs fitted under rule 1 give 145, from a single hold that lasts 162 fits.
Against the PC (the same model in float32 on the CPU, `depth_evidence.py`): output error median 0.42 %, fit agreement
98.9 % (5 runs), labels 98.9 % over 439 runs, session order 99.4 %: a pass.

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
replay all seven sessions pass with the numbers of the device record's §4.

On the phone, the device record's procedure for the budget pairs: the phone on a stand facing one fixed indoor scene, not
charging, other apps closed, the lane on, three sessions of about 90 s per build. First the build that only adds the fit's
own CPU time to the records (`6b96f0e`), then, after the phone had cooled back to the first build's starting temperature
(31.9 °C against 30.4 °C), the build with the rewrite (`814fa77`); both fit under rule 1.

| | Before the rewrite | After |
|---|---|---|
| Published runs | 848 | 845 |
| Fit wall time p50 / p95 | 90 / 110 ms | 44 / 57 ms |
| Fit CPU time (`fitCpuUs`) p50 / p95 | 84 / 101 ms | 41 / 52 ms |
| Wall − CPU, per run, p50 / p95 | 5.0 / 11.1 ms | 2.6 / 6.5 ms |
| Publishes per second | 2.76–2.81 | 2.72–2.80 |
| Observation age p50 / p95 | 266 / 433 ms | 199 / 366 ms |
| sem p95 / det p95 | 48 / 29 ms | 51 / 30 ms |
| Dropped frames | 19.0 % | 25.2 % |

- **The fit is computing, not waiting**: its own CPU time is over 90 % of its wall time, before the rewrite and after. The
  same code takes 84 ms of CPU on the phone and under 2 ms on the desktop JVM: the phone's debuggable build and its cores,
  not the dispatcher. The rewrite halves it, and the lane's fit uses about 0.11 of a core where it used 0.23.
- This scene's fits were faster than the device record's (146 ms p50 there, other scenes and a warmer phone), so only the
  two columns here compare.
- With the rewrite sem and det were slightly slower and more frames were dropped. The device record saw sem and det run
  faster with the lane on than off, perhaps because the extra load kept the clocks up; a lighter lane would then hold them
  up less. The clocks were not read, so that stays a guess.

## 5. Stopping with a real inference in flight

`DepthLaneStopDuringInferenceTest` on the phone (build `919c657`, the reference model in the test app's files), run twice:
both pass, each in about 40 s. Each run compiles the model six times on the GPU (715 of 715 nodes delegated to
`LITERT_CL`): five sessions stopped with their inference held at the gate until the stop had returned, then run to the
end, and one normal session that publishes. Thermal status 0. This proves the order -- sealed while the call is in
flight, the real inference finished and its output read, the runner released after it, the next session initialized
only after that, also when it was started before the stopped one was done -- not that the GPU was executing at the
instant of a stop. The device record's four stops that landed in inference by timing stand.

## 6. Watched, not changed

During §4's sessions (`sampler.ps1`, every 10 s): the GL mtrack figure went from 384 KB at the app's start to 1.7–2.3 MB
over three depth sessions, for both builds, the same range as in the device record (1.5–2.3 MB), with no further rise;
threads 52–89; thermal status 0 throughout, battery 29.5–36.4 °C. The LOW queue priority was not looked at again: the lane
still runs only beside sem and det. Nothing is changed for either.

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
adb shell am instrument -w -e class com.sailens.guidance.depth.geometry.DepthLaneStopDuringInferenceTest com.sailens.guidance.test/androidx.test.runner.AndroidJUnitRunner
```

Without `-PdepthGuardRule` the replay evaluates the recorded rule (rule 1 for these captures), which gives §1's table.
