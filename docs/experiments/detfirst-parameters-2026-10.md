**English** | [简体中文](detfirst-parameters-2026-10.zh-CN.md)

# Parameter freeze: the shipped det-first combination, before the sealed acceptance set opens

This records the parameters and the code that are frozen before the sealed acceptance set (S) opens. The thresholds are in [detfirst-thresholds-2026-10.md](detfirst-thresholds-2026-10.md) (revision 8, §4.7) and are not repeated here. S opens once (R7, Q14), and not before this document's commit id is in the "parameter freeze" line of the local assignment file `detfirst-d18\assignment.csv`.

**It uses the calibration/baseline set (C) only.** No tool that shows results has opened S. Parameters were chosen on C, so C's readings are in-sample.

## 1. The shipped combination

`DetFirstConfig.LAUNCH`:

| Field | Value |
|---|---|
| `proximityEscalation` | on |
| `speechRank` | on |
| `retryInterrupted` | on |
| `keepBlockedWithObstacle` | on |
| `nearMeters` | 2.0 m |
| `pathComplexOff` | on |
| `metricGate.vehicleAheadM` / `vehicleSideM` | 5.0 m / 2.5 m |
| `metricGate.personAheadM` / `personSideM` | 4.0 m / 3.0 m |
| `metricGate.staticM` / `roadsideStaticM` | 2.5 m / 2.0 m |
| `metricGate.metricOnlyRepeatMs` | 6 s (at most 8 s once scaled by the cooldown) |
| `metricGate.minStableFrames` | 20 |

`minStableFrames` counts track matches, not time. C (SM8850, the recorded frame rate) is 69546 frames in 55.7 minutes, 20.8 frames a second, so at least about 0.96 s; a slower device waits longer.

All switches are off by default; only a debug APK built with `-PdetFirst=launch` turns them on. Off is byte-identical to the policy before them (`ScenarioGoldenTest`).

## 2. Constants

| Constant | Value | Where |
|---|---|---|
| Escalation window | 8 s | `CooldownManager.ESCALATION_WINDOW_MS` |
| Outcome window | 8 s | `UtteranceSettlement.DEFAULT_WINDOW_MS` |
| Regives per stage | 1 | `CooldownManager` |
| NEAR in a frame | first within its frame only, cuts no sentence of its band | `SpeechRanks.saysNearOfADetectedHazard` |

Standing on the speaker (priority × 8 + rank, `SpeechScale`):

| Sentence | Rank |
|---|---|
| Covered lens, low light | 7 |
| Fault rhythms, except the unrecognised floor | 7 |
| Guidance stalled | 6 |
| Obstacle, blocked | 4 |
| Metric-only obstacle (always the MEDIUM band) | 3 |
| Others | 0 |
| Mode notice (MEDIUM band, whatever `speechRank` says) | 7 |

The mode notice: offered again 500 ms after a refusal; after the k-th cut, waits min(2^k, 8) s; no limit on attempts; a screen reader taking it once counts as told.

## 3. Definitions

- **Stationary** (C7): a frame's decision is stationary ⇔ the recorded `stationary` is true. Prompts per stationary minute = those decided on stationary frames and later spoken to the end ÷ stationary minutes.
- **C4's "never within 2.0 m"**: no fresh point estimate ≤ 2.0 m in the track's whole life (the 1.15 m samples both arms share).
- **C8's sample**: all of them up to 60; above that, the first 60 after a shuffle with seed `20261103`. The list is `detfirst-c8-list.csv`.
- **P_live, serious loss, `heard_ahead`, unmodeled**: threshold document §4.7.

## 4. Durations

Recorded wordings: as measured on C. C uses the pooled table of its other captures (leave one out); S uses the frozen C table (`SpeakerDurations.FROZEN_C`). Wordings newer than the recordings (ZH-CN, rate 1.30, measured on the device with the D1.7 tool):

| Key | ms |
|---|---|
| `CAMERA_RESTORED` | 1450 |
| `LIGHT_RESTORED` | 1437 |
| `GROUND_RECOGNIZED` | 2649 |
| `MODE_OBSTACLES_AND_BLOCKED` | 2695 |

## 5. Time in the closed loop

The run with no error decides acceptance; it has one branch and takes each sentence at the durations above. The 4 fixed error traces of line e give each sentence one fixed error, set by its id and the trace number, within ±ε, shared by both arms; ε = 150 ms, doubled for wordings the recordings do not hold.

The joint run, reported only: calls of one frame see one occupancy and one expiry, and compare once per millisecond; the two branches of `heldAfterDone`; `frameSkewMs = 50`; limits of 1 million branches and 500 million branch-frames.

Frames in X3 or X5 windows or past δ are not recovered from: later stages read as unknown. C has none of them.

## 6. Results on C

14 captures, 55.7 minutes, |P| = 609. The line-by-line readings are in threshold document §4.7; run with `-PreplayLabels`, **every line passes at all three heights**. C through the sealed set's frozen duration table (1.15 m) also passes every line; the largest change is B1, 34/57 against 35/57 leave-one-out.

Each switch alone and the combination without each switch are diagnostics, not thresholds. C6's three classes and the earlier count are at the end of threshold document §4.4.

## 7. Device (CPH2747, SM8850; the final build)

Run by the user, on two builds; no video or places here.

| Case | Result |
|---|---|
| Approaching person, person before a wall, vibration with speech off, mode notice 5 times (D3) | pass |
| Regive after an interruption | the user reported the group as passing, without confirming the scene really happened; still to be confirmed |
| TalkBack on: no crash, no flood | pass |
| Plain build, switches off: approaching person, person before a wall | as today |
| PR-D5 B1, B2 | pass |
| PR-D5 A1–A3 | prompts come, mixed with "blocked" or obstacle prompts, and quick covering gives inaccurate recovery notices. The user judges the whole as acceptable; the cause was read from code, not logs, and is a guess. |
| PR-D5 C (optional) | not run |

## 8. Opening S

Only after both lines of the assignment file are filled in. The first two commands run once each, the third at each of the three heights, with `<sealed>` as `sailens-captures\detfirst-d18\sealed`:

```
gradlew :sailens-guidance:testDebugUnitTest --tests "*PromptPolicyBaselineTest*" -PreplayCapture=<sealed> -PunsealCapture -Pm3aPhoneHeight=1.15
gradlew :sailens-guidance:testDebugUnitTest --tests "*DetFirstRehearsalTest*" -PreplayCapture=<sealed> -PunsealCapture --rerun
gradlew :sailens-guidance:testDebugUnitTest --tests "*DetFirstPairedRehearsalTest*" -PreplayCapture=<sealed> -PunsealCapture [-Pm3aPhoneHeight=1.05|1.25] -PreplaySingleSwitches=launch --rerun
```

- The first is the open-loop model calibration (the lines of section 3 other than A12), the second A12 (at the recorded 1.2 m), both on the frozen C table.
- Before computing any metric, each of the three entries checks that it reads exactly the whole of S: all 13 captures, none twice, no other capture, each complete and replaying with no problem. Otherwise nothing counts and nothing is reported.
- The third writes `detfirst-paired.txt`, `detfirst-label-list.csv`, `detfirst-label-frames.csv` and `detfirst-c8-list.csv`. After the user labels the clips, it runs once more at the same commit with `-PreplayLabels=<the exported file>` so a1 and a2 have a verdict. That rerun is part of the same acceptance, not another opening of S.
- The labels cover the whole list, each one of need / no need / false detection / cannot tell; with a blank or any other value, a1 and a2 do not pass.
- A share over a denominator below 20 is undeterminable under R5 and does not pass.
- The reports, lists, labels and C8 results of each height are kept apart.

**What the results mean:**

- If any substantive line or standing check fails, the switch is not flipped. This S cannot be used to pass after a change to the code or the thresholds.
- B8's second case (regive after an interruption) is no precondition for opening S, but it is still a hard gate before the switch is flipped. If it fails later and frozen code has to change, this S cannot vouch for the changed version.

## 9. What is frozen

The 6 files of threshold document section 3.4 stay byte-identical to `5418a35` (`FrozenEvaluationFilesTest`).

**Also frozen is the commit the assignment file names, as a whole**: everything in the repository at that commit, production code, evaluation code, the acceptance entries, build scripts and Gradle wiring alike, with no list of files. S is opened on a build of that commit with a clean working tree. Any change means rehearsing on C again; once S is open nothing can change.

## 10. Known limits

The costs and residual risks of threshold document §4.7 all apply. A known evaluation issue: the `sameInputs` exemption in `lossesIn` keeps an older deadline, and under another configuration it could miss a divergence that comes later; in the shipped combination the mode notice keeps the two arms apart, so it is not reached.
