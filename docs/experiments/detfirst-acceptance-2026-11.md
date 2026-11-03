**English** | [简体中文](detfirst-acceptance-2026-11.zh-CN.md)

# Acceptance on the sealed set: the det-first combination did not pass, and the switches stay off

The sealed acceptance set (S) was opened once, on 2026-11-03, on the parameter freeze commit `08f47a6`. The thresholds are in [detfirst-thresholds-2026-10.md](detfirst-thresholds-2026-10.md) §4.7; the commands and what a result means are in [detfirst-parameters-2026-10.md](detfirst-parameters-2026-10.md) §8.

**Outcome: the switches are not turned on.** One line failed: C1 at 1.15 m (a first prompt about a person ahead, said to the end before the 2.0 m crossing) is 3/33 = 9.1%, against at least 20%. Every other line judged and every standing check held at all three heights. By section 8 of the parameter document, one failure means the switches stay off, and no change to the code or the thresholds may then earn a pass from this S: **this S is spent.** `DetFirstConfig.LAUNCH` stays in the code, off by default; only a debug APK built with `-PdetFirst=launch` turns it on.

Only aggregate numbers are here. The captures hold faces and places and stay on the machine; the reports, lists and logs for the three heights are in the local `detfirst-d18\reports\S-08f47a6\`.

## 1. How it was opened

- A new worktree, detached at `08f47a6`, with no build directory and no change (only the git-ignored `local.properties` added) and no weights. The script checked the commit and the worktree before running.
- The three commands of section 8 of the parameter document, the third at 1.05, 1.15 and 1.25 m, each with `--no-daemon`. All five ran 1 test, 0 skipped, 0 failed.
- All three entry points found exactly the whole S: 13 captures, no problems, none skipped. 49.0 minutes, 60 578 frames, 20.6 frames a second, so 20 stable frames are at least 0.97 s.
- C8's counting rule, not written in the frozen documents, was fixed with the user before opening: an item labelled "cannot tell" stays in the denominator and does not count as a real hazard; a denominator under 20 does not pass.

## 2. First command: open-loop calibration (1.15 m, the frozen C table, S pooled)

| # | Threshold | S | Verdict |
|---|---|---|---|
| A1 accept precision | ≥ 0.93 | 0.971 | pass |
| A2 accept recall | ≥ 0.90 | 0.945 | pass |
| A3 refuse precision / recall | each ≥ 0.965 | 0.982 / 0.991 | pass |
| A4 completion precision | ≥ 0.92 | 0.963 | pass |
| A5 completion recall | ≥ 0.90 | 0.939 | pass |
| A6 CRITICAL: model accepts, device refused | ≤ 2.5% | 11 / 1 205 = 0.9% | pass |
| A7 CRITICAL: model completes, device did not | ≤ 2.5% | 15 / 1 205 = 1.2% | pass |
| A8 CRITICAL misjudged, both directions | ≤ 4.5% | 31 / 1 205 = 2.6% | pass |
| A9 \|start\| p95 | ≤ 150 ms | 53 ms | pass |
| A10 \|done\| p95 | ≤ 150 ms | 67 ms | pass |
| A11 per-capture floor (the 12 captures with ≥ 50 offers) | each ≥ 0.80 | lowest 0.931 / 0.902 | pass |
| A13 end error beyond 150 ms | ≤ 2% | 2 / 387 = 0.5% | pass |
| X2 evidence conflicts | ≤ 1% | 0 | pass |
| X3, X5 | each ≤ 2% | 0: all 2 016 offers scored, and the paired runs found no X3 or X5 window | pass |

The "DESCRIPTIVE ONLY" on each speaker-model paragraph of the report is an old label left in the frozen code; the verdicts follow the numbers of §3.2 of the threshold document.

## 3. Second command: A12, closed loop (recorded height 1.2 m)

| # | Threshold | S |
|---|---|---|
| A12a | ahead CRITICAL and blocked ≥ 0.95; all of P ≥ 0.90 | 186/186 = 100%; 467/469 = 99.6% |
| A12b | ≥ 0.90 | 467/476 = 98.1% |
| A12c | p95 ≤ 250 ms | 71 ms |
| A12d | within 10% | device 412, model 415, 0.7% |

**A12 passes.**

## 4. Third command: the launch combination with no error (§4.7 of the threshold document)

|P| = 469; P_live = 143, of them 67 ahead. In brackets, the baseline arm (today's policy) of the same run.

| Line | Threshold | 1.05 m | 1.15 m | 1.25 m |
|---|---|---|---|---|
| a1, a2 | judged after labelling | not labelled (section 7) | not labelled | not labelled |
| b bounded delay | all ≤ 30%; ahead ≤ 35% | 13.3%; 14.9% | 9.1%; 9.0% | 9.1%; 7.5% |
| c serious loss | all ≤ 5%; ahead ≤ 7% | 4.9%; 4.5% | 4.2%; 3.0% | 4.2%; 3.0% |
| B1 | ≥ 55% | 31/48 = 64.6% (20/48) | 30/48 = 62.5% (19/48) | 28/48 = 58.3% (19/48) |
| B2 | ≥ 55% | 43/60 = 71.7% (32/60) | 44/60 = 73.3% (32/60) | 44/60 = 73.3% (28/60) |
| B3 | ≥ 55% | 200/318 = 62.9% (152) | 196/318 = 61.6% (152) | 196/318 = 61.6% (142) |
| B5 person | not below the baseline | 65.8% (53.9%) | 65.8% (55.3%) | 64.5% (53.9%) |
| B5 vehicle | not below the baseline | 79.8% (62.6%) | 80.8% (63.6%) | 78.8% (61.6%) |
| C3 person | not above the baseline | 14.3% (31.4%) | 18.2% (27.3%) | 13.8% (13.8%) |
| C3 vehicle | not above the baseline | 2.7% (16.2%) | 2.6% (13.2%) | 2.6% (12.8%) |
| **C1** | ≥ 20%, judged at 1.15 m only | 5/35 = 14.3% (reported) | **3/33 = 9.1% (1/33), fails** | 3/29 = 10.3% (reported) |
| C2 | ≥ 15%, judged at 1.15 m only | 13/37 = 35.1% (reported) | 13/38 = 34.2% (6/38) | 13/39 = 33.3% (reported) |
| B6 | ≤ 1.40 times | 1.21 | 1.20 | 1.17 |
| C7 stationary | ≤ 1.40 times | 1.33 | 1.27 | 1.28 |
| B7 regiven sentences | ≤ 30% | 16.5% | 15.7% | 16.1% |
| B7 one stage | ≤ 1 | 1 | 1 | 1 |
| B4 | = 0 | 0 | 0 | 0 |
| C4 | ≤ 1.5 a minute | 0.82 | 0.86 | 0.78 |
| C5 | ≤ 5% | 0/82 | 0/72 | 0/62 |
| C6 | = 0 | 0 | 0 | 0 |
| D1 | = 0 | 0 | 0 | 0 |
| C8 | real hazards ≥ 80% | list of 60, not labelled | 60, not labelled | 60, not labelled |

Apart from C1 at 1.15 m, every line with a threshold in the table passes. The same line on C (§4.7) is 26.8%.

## 5. Standing checks

They held at all three heights, so the result counts:

- The nominal path agrees with the frozen nominal model: 0 stages judged differently, 0 completions more than 1 ms apart.
- The arm that changes nothing scored 0 and both arms' completion records are the same; with no error each arm has one branch; no sentence was credited to a stage after its watch closed.
- Both control arms lose more serious stages ahead than a2's 8: vehicles withheld 56 / 56 / 56, a top-priority sentence every 10 s 45 / 44 / 41 (1.05 / 1.15 / 1.25 m).
- Frames later than 50 ms: 0.

## 6. Why C1 failed

What follows is the report's attribution (1.15 m, no error, a sentence taken as 1.25 s). It explains; it waives nothing. The 33 people ahead:

| Reason | Count |
|---|---|
| tracked too late to say a sentence | 15 |
| admitted too late: still held at the last moment as not stable | 5 |
| admitted too late: still held at the last moment as farther than the gate | 2 |
| offered in time, refused | 3 |
| offered in time, then cut off | 5 |
| over before the crossing | 3 |

Time tracked before the crossing: people p25 0.6 s, median 1.3 s, p75 2.6 s; vehicles p25 1.4 s, median 2.9 s, p75 5.1 s. For nearly half the people, the time from being tracked to the crossing is shorter than one sentence; that part lies in perception and tracking, and no prompt policy makes it up. For vehicles, C2 went from 6/38 to 13/38 and passes.

## 7. What was not judged

The C1 line already decided the outcome, so the user decided not to label. The a1, a2 lists hold 21 items over the three heights (19 / 16 / 18 at each), and C8's 127 (60 at each); the labelling pages were made and not used. So on this S:

- a1 and a2 have no verdict;
- C8 has no verdict: S does not say how many of the prompts the metric gate let in were real hazards;
- the device case "regive after an interruption" in section 7 of the parameter document is still not confirmed;
- the placement is not yet in the user settings (Q9, §4.6 of the threshold document).

## 8. What reopening needs

This S cannot be used again. For the launch combination, or part of it, to take effect in a release build, at least:

1. **A new sealed acceptance set**, recorded as in [detfirst-d18-recording-plan-2026-10.md](detfirst-d18-recording-plan-2026-10.md), with integrity checks only until it is opened.
2. **Before it is opened, a decision on how C1 is judged**, and on whether the whole combination ships or a part of it. Any such revision follows R6, with its reason and its C evidence, and **says that it was made after this S's results were seen**.
3. **The placement in the release settings.** The metric gate takes only a height the user has given (a preset or a calibration). Today only the debug "ground-contact distance" screen sets it, so a release build reads the seed and the gate lets nothing in.
4. **A decision on the ground-contact switch's default in a release build.** C and S were recorded with it on, and both arms ran with it on; a release build has it off by default.
5. A device confirmation that "regive after an interruption" really occurred; C8 labelled by the rule of section 1.

## 9. Reproducing

The three commands of section 8 of the parameter document, on a clean build of `08f47a6`. The reports are in `sailens-guidance/build/replay/`: `detfirst-baseline.txt` for the first, `detfirst-rehearsal.txt` for the second, `detfirst-paired.txt` and the three lists for the third. Only the machine that holds this S can run them.
