**English** | [简体中文](detfirst-thresholds-2026-10.zh-CN.md)

# Threshold proposal: det-first, what to freeze before the sealed acceptance set opens (2026-10-28)

det-first plan v4.1 requires two kinds of threshold to be frozen before the sealed acceptance set (S)
is opened: the `SpeakerModel` calibration thresholds (D1.4-2) and the D2–D4 success thresholds (Q6).
This note proposes both, plus the questions the user has to decide. **It uses the calibration/baseline
set (C) only**; no tool that shows results has opened S.

> **Status: proposal, not frozen.** Freezing takes all three: the user decides every item of
> section 5 (done 2026-10-28, every recommendation accepted); the GPT review has no findings;
> the freezing commit is written into the "threshold
> freeze" line of the local assignment file `detfirst-d18\assignment.csv`. Until that line is filled
> in, S may not be used for any metric.

## 1. Data and conventions

- **C**: 14 sessions, 55.7 min (by stratum: 9 outdoor, including pilot C-P-01; 5 indoor, including
  pilot C-P-02, the two I2 sessions walking in from outside), all recorded on `0bd1f50` (tag
  `baseline/detfirst-pr0`) with the weights of the 2026-10 captures. Every session is complete,
  replays exactly frame by frame, gives birth to the same tracks in both runs, and has a full speech
  lifecycle (0 dropped, 0 unreconciled, 0 evidence conflicts).
- **S**: 13 sessions, 48.9 min, sealed; only integrity checks and exact replay have run on it.
- **The ground-contact switch was on for the whole batch** (preset 1.2 m, handheld at the chest);
  the plan said off. C and S agree. The "baseline" here is therefore **today's policy with the
  ground-contact switch on**, not "every new switch off, ground contact off". Every baseline number
  below means that.
- **Two C sessions are corrected copies** (the stop race, fixed in `dee6505`): C-O3-01 without the
  last frame, decided after the stop; C-O4-02 with every revoke position minus one. The originals are
  in `detfirst-d18\raw-replaced\`. Both replay exactly after the correction.
- **Distances use a phone height of 1.2 m, not 1.15 m (new finding).** These captures record
  geometry per frame (schema 1.4), and every run of the det-first baseline uses the recorded geometry,
  i.e. the device's 1.2 m preset; `-Pm3aPhoneHeight=1.15` applies only to older captures without
  per-frame geometry. The PR-0 report's header "phone height preset 1.15 m" was misleading; this PR
  makes each capture state the height its distances use (section 6). A ground-contact distance is
  proportional to the height, so the C report's "≤ 2.0 m" is about 1.92 m for the user's real
  1.15 m carry. Section 4.1 fixes which height later counterfactual runs use.
- **This PR's changes to the evaluation code** (test source set) change no production behaviour and
  no number of the C report outside the speaker model (compared line by line):
  1. the speaker model takes a sentence's duration by **wording** (message key plus whether it
     carries the NEAR prefix), no longer by message key alone;
  2. a wording this capture never spoke to the end falls back to the D1.7 duration table (zh-CN,
     rate 1.30), and only one missing from the table to the 1.4 s default;
  3. the calibration report adds agreement on being spoken to the end, CRITICAL errors by direction,
     disagreements by the refusing side's reason, where durations came from, and a line pooled over
     all of C;
  4. the report states the phone height its distances use.

## 2. C baseline (the reference for the success thresholds)

C in all: 2 372 prompts offered, 625 received (26.3%), 507 spoken to the end (21.4%); 9.1 spoken to
the end per minute.

| Hazard / stage | n | Spoken to the end | Share |
|---|---|---|---|
| Front person, first | 104 | 62 | 59.6% |
| Front person, CRITICAL | 91 | 55 | 60.4% |
| Front person, NEAR | 57 | 19 | 33.3% |
| Front vehicle, first | 95 | 49 | 51.6% |
| Front vehicle, CRITICAL | 93 | 49 | 52.7% |
| Front vehicle, NEAR | 63 | 22 | 34.9% |
| All classes, NEAR | 494 | 198 | 40.1% |
| Blocked, first (CRITICAL only) | 134 | 94 | 70.1% |
| Path complex, first | 146 | 37 | 25.3% |

**Lead time** (first prompt spoken to the end vs. crossing, point estimate, 1.2 m height):

| Hazard / threshold | Bracketed | Before / after / clearly missed / order undetermined | "Before" of bracketed |
|---|---|---|---|
| Front person, first ≤ 2.0 m | 41 / 104 | 2 / 32 / 7 / 0 | 4.9% |
| Front person, first ≤ 3.0 m | 46 / 104 | 0 / 39 / 7 / 0 | 0% |
| Front vehicle, first ≤ 2.0 m | 41 / 95 | 2 / 28 / 11 / 0 | 4.9% |
| Front vehicle, first ≤ 3.0 m | 29 / 95 | 1 / 24 / 4 / 0 | 3.4% |
| Front person, NEAR ≤ 1.5 m | 36 / 104 | 1 / 10 / 25 / 0 | 2.8% |
| Front vehicle, NEAR ≤ 1.5 m | 37 / 95 | 0 / 18 / 19 / 0 | 0% |

**NEAR held by a same-priority cooldown (D1.6)**: of 494 hazards entering NEAR for the first time,
249 were held by a same-priority record in their zone's cooldown, and 184 of those never had the
NEAR wording spoken to the end afterwards.

Static obstacles (4 front, 15 side) bracket a crossing 0–25% of the time; distance metrics are
undeterminable.

## 3. (a) `SpeakerModel` calibration thresholds (D1.4-2)

### 3.1 Results on C

The speaker model takes every prompt the device actually offered (open loop: the offer stream is the
device's), predicts accept/refuse and the start and end times, and is compared with the lifecycle.

| | Before (PR-0 report) | After (this PR) |
|---|---|---|
| Accept: precision / recall | 0.946 / 0.931 | 0.965 / 0.957 |
| Refuse: precision / recall | 0.976 / 0.981 | 0.985 / 0.987 |
| Spoken to the end: precision / recall | (not counted) | 0.956 / 0.951 |
| CRITICAL misjudged (both directions) | 33 | 21 (1.7% of 1 244 CRITICAL offers) |
| CRITICAL: model accepts, device refused / model completes, device did not | (not counted) | 10 (0.8%) / 12 (1.0%) |
| \|start\| error p50 / p95 (pooled) | per capture 6–9 / 10–103 ms | 7 / 62 ms (per capture p95 10–103) |
| \|done\| error p50 / p95 (pooled) | per capture 7–46 / 11–734 ms | 12 / 69 ms (per capture p95 11–81) |
| Worst capture: accept precision / completion precision | 0.867 / — | 0.905 / 0.886 (both C-P-02) |
| Durations from: this capture / D1.7 table / default | — / — / all unmeasured | 2 068 / 304 / 0 |

- Before, seven captures had a done-error p95 of 658–734 ms, because a sentence with the "注意，"
  (NEAR) prefix -- about +0.6 s in Chinese, D1.7 -- shared one duration with the sentence without it.
  Taken by wording, the done error is back to tens of milliseconds, and accept/refuse disagreements
  fall from 76 to 49. 304 offers have a wording this capture never spoke to the end and now take
  the D1.7 duration; on C that predicts exactly what the 1.4 s default did (no number changed). It is
  there for later counterfactual runs, which will offer wordings the baseline never finished (more
  NEAR sentences, for one): the default would make a ~2 s NEAR sentence too short, which errs
  towards "it was finished", the dangerous direction.
- C has no description, no system notice and no engine restart (every capture has only engine
  generation 1); every device refusal is "equal or higher is speaking" (`outranked`). The remaining
  disagreements are therefore all **boundary moments**: a new prompt arrives in the tens of
  milliseconds around the end of a sentence, and the two sides order them differently.
- The worst capture is C-P-02 (hospital corridor, about 4.4 min and 341 offers, the most offers per
  minute): accept precision 0.905, completion precision 0.886, all 17 disagreements of the boundary
  kind above. It is not excluded; A11's per-capture floor leaves room for it.

### 3.2 Thresholds

Computed on S, **pooled over all S sessions** with a fully recorded lifecycle; C is reported as well
and has to pass too (it does now). Three principles:

- **Stricter in the dangerous direction.** In a counterfactual evaluation, "the model thinks it was
  spoken to the end and it was not" hides a regression (unsafe); "the model thinks it was not and it
  was" only makes the experiment look worse (conservative). So completion precision and CRITICAL
  "the model accepts / completes and the device did not" get thresholds of their own.
- **Below C's values, leaving room for sampling error.** S should have about 2 000 offers and about
  550 receipts; the standard error of accept precision is about 0.8 points.
- **Timing limits well inside M5's 0.5 s tolerance**, or M5's "no later than the baseline by more
  than 0.5 s" means nothing.

| # | Metric | Threshold (S pooled) | C pooled |
|---|---|---|---|
| A1 | Accept precision | ≥ 0.93 | 0.965 |
| A2 | Accept recall | ≥ 0.92 | 0.957 |
| A3 | Refuse precision / recall | each ≥ 0.97 | 0.985 / 0.987 |
| A4 | Completion precision (model completes → device completed) | ≥ 0.93 | 0.956 |
| A5 | Completion recall | ≥ 0.90 | 0.951 |
| A6 | CRITICAL: model accepts, device refused | ≤ 1.5% of CRITICAL offers | 10 / 1 244 (0.8%) |
| A7 | CRITICAL: model completes, device did not | ≤ 1.5% of CRITICAL offers | 12 / 1 244 (1.0%) |
| A8 | CRITICAL, both directions | ≤ 3% of CRITICAL offers | 21 / 1 244 (1.7%) |
| A9 | \|start\| error p95 | ≤ 150 ms | 62 ms |
| A10 | \|done\| error p95 | ≤ 150 ms | 69 ms |
| A11 | Per-capture floor: captures with ≥ 50 offers, accept precision and completion precision | each ≥ 0.85 | lowest 0.905 / 0.886 (C-P-02) |
| A12 | Closed-loop check (below) | three parts, below | to be measured on C once PR-1b implements it |

**A12 closed-loop check.** Section 3.1 is open loop: the offers come from the device. A
counterfactual run is closed loop: a refusal by the model revokes the cooldown and changes later
offers. Agreement in the open loop is not agreement in the closed loop. So one more check: rerun
today's policy with `SPEAKER_MODEL` delivery, **the recorded phone height (1.2 m)** and the recorded
configuration, and compare with the device, hazard stage by hazard stage, whether each CRITICAL and
NEAR stage was spoken to the end -- of the stages the device completed, the model completes ≥ 0.90;
of the stages the model completes, the device completed ≥ 0.90; and the total count spoken to the end
differs by at most 10%. `SPEAKER_MODEL` delivery does not exist yet (it is part of PR-1b / PR-2); once
it does, it is **measured on C first, and if C fails, S is not opened** (the model or the threshold is
revised under rule R6 of section 4.1).

### 3.3 Exclusions named in advance

These are the only exclusions. Where one applies, the model **may not be used**: the stages
concerned are judged on device evidence only in an acceptance, and are "undeterminable" in a
counterfactual.

| # | Exclusion | Rule | On C |
|---|---|---|---|
| X1 | Captures without a full lifecycle | Evidence other than RECORDED (none, dropped or unreconciled events, a screen-reader session): the whole capture stays out of calibration and the model is not used on it | 0 captures |
| X2 | Logical utterances with an evidence conflict | Out of calibration and out of acceptance; more than 1% of S's offers fails calibration | 0 |
| X3 | Engine failure and restart windows | The record gives the model no engine readiness. Every offer from an engine generation's first `error`, `unknown_terminal` or "engine not ready / speak error" refusal until the next generation's first `started` (or the end of the session if there is none) is excluded; more than 2% of S's offers fails calibration | 0 (all generation 1) |
| X4 | Prompts with no outcome when the record ends | Out of the timing errors only; accept/refuse counted as usual | counted separately in the S report |

Descriptions and system notices are **not** excluded: the model queues them. But C contains none, so
that path is uncalibrated (3.5).

### 3.4 What is frozen; what happens if it fails

- **Frozen are the thresholds and the model itself**: `SpeakerModel.kt`,
  `PromptPolicyCaptureEvaluation.calibrate` and `TtsDurations.kt` as of the freezing commit. Any later
  change to one of them needs a new calibration; if S is open by then, it can no longer calibrate the
  changed model, and a new sealed set has to be recorded.
- **Failing on S** (any of A1–A12, or X2/X3 over their limit): per plan D1.4-3, D3 (PR-1b) is judged
  on device data only; M5 for D2 and D4 can only be reported at the receipt level, labelled "not
  completion-verified", and **no switch may be flipped on it** (§4 M5). PR-4 then waits for a new batch
  of device recordings.
- Errors cannot be explained away one by one.

### 3.5 Known limits (they do not change the thresholds, but limit what the model is for)

- Valid only for Chinese, Google TTS, speech rate 1.30, SM8850 (the D1.8 settings).
- Calibration covers today's offer stream only. D3b's rank changes the arbitration order; the model
  computes it on the integer scale just the same, but the new arbitration itself is uncalibrated, so
  D3b must be verified on the device (plan D3b-6), own TTS and TalkBack separately.
- Queueing of descriptions and system notices is uncalibrated. If D4a's mode notice or D5's notices
  enter a counterfactual run, their durations and queueing are measured the D1.7 way and verified on
  the device.
- Durations are medians per wording; one wording varies by a dozen or so milliseconds between plays.
  That is where the remaining boundary disagreements come from, and it cannot be removed.

## 4. (b) D2–D4 success thresholds (Q6)

### 4.1 General rules

- **R1 Where it is judged.** Every threshold is judged on S, pooled over all its sessions; per-session
  numbers are reported too. C numbers are reference only.
- **R2 Which combination.** What is judged is **the combination of switches to be shipped** (every
  switch PR-4 is to flip, all on). Runs with one switch at a time are also made, as diagnostics, with
  no thresholds of their own (no multiple comparisons).
- **R3 How the two arms run.** Baseline arm = today's policy (the new det-first switches all off);
  experiment arm = the shipped combination. Both use `SPEAKER_MODEL` delivery, the recorded
  configuration (**the ground-contact switch stays on**, handheld at the chest), and replay-injected
  track ids; the two arms must give birth to the same tracks, or no paired metric is given.
- **R4 Phone height.** Both arms' policy and distance measurement use a **1.15 m preset** (the user's
  real carry, plan §4); M4 and M5 must also pass at 1.05 m and 1.25 m; M1 is judged at 1.15 m only.
  (Section 1: today's report uses 1.2 m; the A12 closed-loop check uses 1.2 m because it compares
  with the device.)
- **R5 Minimum sample.** A primary metric whose denominator (hazards with a bracketed crossing, or
  hazards entering the stage) is < 20 is "undeterminable", and **undeterminable is not passed**: the
  switch concerned is not flipped. Estimated from C (S is about 88% of C's duration), every primary
  metric below should have a denominator ≥ 30 on S.
- **R6 Revision.** Before S opens, a threshold can be revised only by a new commit that states the
  reason and the C evidence, passes the GPT review and the user, and updates that line of the
  assignment file. After S opens it cannot be revised; S's results may not be fed back into
  parameters, and if they are, the acceptance concerned is void and a new sealed set has to be
  recorded.
- **R7 S is opened once**: after the `SpeakerModel` version, PR-1b / PR-2 / PR-3's code and all their
  parameters are frozen. The parameter freeze gets a line of its own (Q14).
- **R8 Intervals reported.** Ratio metrics are judged on the point estimate, with a Wilson 95%
  interval reported (not used for the decision).

### 4.2 M5, no regression (for the shipped combination)

- **Scope**: every CRITICAL and NEAR stage the baseline arm spoke to the end (CRITICAL includes the
  single stage of "blocked" and of a covered camera), plus front persons' first stage (a front
  person's first prompt is at least HIGH but not necessarily CRITICAL).
- **A regression** = in the experiment arm, the same stage of the same hazard is not spoken to the
  end, or is spoken to the end more than 0.5 s later than in the baseline arm. A stage that was only
  received does not count; neither does another hazard or an earlier ordinary prompt (plan §4).
- **Threshold M5**:
  - front vehicles' and front persons' CRITICAL stages and "blocked": **0 regressions**;
  - over the whole scope: regressions ≤ max(2, 1% of the baseline arm's completed stages);
  - every regression is reviewed by hand on a contact sheet and written into the acceptance record;
    the review is for understanding the cause and **exempts nothing**.
- The same comparison of "device baseline vs experiment arm" (the device's real completion vs the
  model) is reported, not judged.

### 4.3 PR-1b (D3a-2 approach escalation, D3b speaker ranking, D3c remove neither)

| # | Metric | Threshold (S, shipped combination) | C baseline |
|---|---|---|---|
| B1 | M2: of front persons entering NEAR (today's definition: box bottom y > 0.75 or ground-contact fusion NEAR), the share with the NEAR wording spoken to the end afterwards | ≥ 60% | 33.3% (19/57) |
| B2 | M2: front vehicles, likewise | ≥ 60% | 34.9% (22/63) |
| B3 | M2: all classes, likewise | ≥ 55% | 40.1% (198/494) |
| B4 | M3: NEAR / CRITICAL removed by conflict resolution (structurally 0 after D3c) | = 0 | NEAR removed 6 |
| B5 | M3: share of front vehicles' and front persons' CRITICAL stages spoken to the end | each not below the baseline arm | 52.7% / 60.4% |
| B6 | M4: prompts spoken to the end per minute | ≤ baseline arm × 1.25 | 9.1 / min |
| B7 | M4: D3b-4 re-offers (offered again after an interruption) | ≤ 1 per hazard stage; in all ≤ 15% of those spoken to the end | — |
| B8 | Device: own TTS and TalkBack each run the scripted cases listed in PR-1b's detailed design | all pass | — |

- M2 counts its denominator with **today's NEAR definition**, not the experiment field (Q4's 2.0 m):
  otherwise widening NEAR would change the denominator by itself. A NEAR wording spoken earlier in the
  experiment counts as success as long as it is the same hazard.
- D1.6 (held by a same-priority cooldown) goes to about 0 by definition after D3a-2; it is reported,
  not a threshold. The real thresholds are B1–B3.

### 4.4 PR-2 (D2 metric thresholds)

| # | Metric | Threshold (S, shipped combination, 1.15 m) | C baseline |
|---|---|---|---|
| C1 | M1: share of front persons' first prompts spoken to the end **before** the 2.0 m crossing (denominator = before + after + clearly missed + order undetermined) | ≥ 50% | 4.9% (2/41) |
| C2 | M1: front vehicles, likewise | ≥ 50% | 4.9% (2/41) |
| C3 | M1: "clearly missed" as a share of bracketed, front persons and front vehicles | each not above the baseline arm | 17% / 27% |
| C4 | M4: prompts admitted by the metric threshold only, on hazards that leave without ever coming within 2.0 m (fresh point estimate) | ≤ 1.0 per minute | — |
| C5 | M4: prompts admitted by the metric threshold only, on tracks living < 1 s | ≤ 5% | — |
| C6 | M4: the same stage spoken again when the same hazard turns from "metric only" into today's admission (D2-4) | = 0 | — |
| C7 | M4: prompts spoken to the end per minute; prompts per minute while stationary | each ≤ baseline arm × 1.25 | 9.1 / min; "stationary" is defined in PR-2's detailed design and frozen with the parameters |
| C8 | Labels: prompts admitted by the metric threshold only and spoken to the end, labelled real or not by the user (all of them up to 60; above that, 60 drawn with a random seed fixed in advance) | real hazards ≥ 80% | — |

- The 3.0 m crossing is reported, not judged: only 30.5% of C's front vehicles bracket it, and on S it
  will likely fall below the 30% usability limit.
- **There is circularity here**: the metric threshold uses M3a's ground-contact estimate, and M1's
  crossing is measured by the same estimator. Part of C1/C2's improvement is therefore by
  construction. The independent checks are C8 (user labels), C4/C5 (false-alarm proxies) and R4's
  height sensitivity.
- Static obstacles get no distance threshold (coverage 0–25% on C, undeterminable); only M4 and M5
  bind them.

### 4.5 PR-3 (D4a: PATH_COMPLEX off by default under the det-first switch)

| # | Metric | Threshold (S, shipped combination) |
|---|---|---|
| D1 | PATH_COMPLEX offers with the switch on | = 0 |
| D2 | Regressions of "blocked" within M5's scope | = 0 (already in 4.2) |
| D3 | The mode notice at every start of guidance | device: spoken to the end once at every start; not judged in counterfactuals (3.5) |
| D4 | Indoor sem passage prompts spoken to the end per minute | reported only (C: path complex spoken to the end 37 times) |

### 4.6 PR-4 (enabling)

Switches are flipped only when all of these hold: A1–A12 of section 3 pass on S; the thresholds of
4.2–4.5 pass on S (M4 and M5 at all three heights); PR-1b's and PR-3's device cases pass; M3a's
placement is in the user settings (Q9). Then the user walks once outdoors and once indoors: no crash,
exact replay, and the user's own judgement (the user's decision, not a threshold). PR-D5 is accepted
on the device under its own detailed design, outside Q6.

## 5. (c) Decisions

**On 2026-10-28 the user decided every item below as recommended**, Q14 (open S once) and Q6 (the
numbers of sections 3 and 4 as written) included. Any later change to an item is recorded per R6.

| # | Question | Recommendation | Why |
|---|---|---|---|
| Q1 | Target distance of the first prompt | front vehicles and bicycles **5.0 m**, front persons **4.0 m** (lower bound, per Q2); success judged by C1/C2, "spoken to the end before the 2.0 m crossing ≥ 50%" | today only 2/41 front first prompts finish before 2 m (persons and vehicles alike); finishing takes about 1.2 s (D1.7), and an oncoming person closes at up to 2.4 m/s (1.2 m/s each), so speech has to start beyond 4 m |
| Q2 | Lower bound or point estimate for the metric threshold | **lower bound**; the threshold values are meant as point estimates | when the estimate is uncertain (pitch jitter, box-bottom error) the lower bound admits earlier, the safe direction; the cost is more prompts, bound by C4, C5, C8 |
| Q4 | NEAR at 2.0 m in the det-first experiment (experiment field only) | **yes, 2.0 m** | a Chinese NEAR sentence takes about 1.9–2.1 s, 2.3–2.5 m of walking; today front hazards at NEAR ≤ 1.5 m finish before the crossing 1/36 and 0/37. 2.0 m is a compromise too, so M2 is judged on completion, not lead time |
| Q5a | PATH_COMPLEX off by default under the det-first switch | **off**, with the mode notice at the start of guidance | of C's 146 path-complex episodes only 37 (25%) were spoken to the end; no direction, hard to act on; it is MEDIUM, so under today's arbitration a same-priority side-obstacle prompt is refused while it speaks |
| Q8 | Metric threshold for roadside static obstacles | **2.0 m** (no farther), with no distance success threshold | C has only 19 static obstacles at 0–25% coverage, no evidence for farther; det's COCO classes have no pole or tree, the static ones are benches, fire hydrants, potted plants and the like, and farther mostly adds false alarms |
| Q11a | User turns off both speech and fault-alert vibration: is the failure notice still forced to vibrate? | **forced**: with speech off, fault-alert vibration cannot be turned off (shown locked in settings, with the reason) | for the target user, "failure + silence" is the worst case; today's `SPEECH_UNAVAILABLE` alert already ignores the switch (plan D5-3) |
| Q11b | "Assistance interrupted" under fault alerts, or never switchable at all | **never switchable** (as today) | the user may be walking when it happens; it is the last channel |
| Q11c | D5 on its own, before PR-1b? | **yes**, order PR-1a → PR-D5 → PR-1b → PR-2 → PR-3 → PR-4 | D5 fixes an existing invariant-3 gap, independent of det-first; PR-1b's rank table puts SENSOR_QUALITY highest, which is easier to verify with D5's notices in place |
| Q12 | D3b-4 offers an interrupted prompt again; one sentence may be heard twice in a short time | **accept**, at most one re-offer per hazard stage (B7) | C: 625 received, 507 spoken to the end: 118 (19%) received and not finished (interrupted, flushed, …), each a possible re-offer under D3b-4; B7 limits re-offers to one per hazard stage and 15% in all |
| Q13 | PR-1a right after PR-0, or with PR-1b? | **right after** (next) | it is a defect fix with a bit-for-bit equivalence proof and needs no S |
| Q14 (new) | How S is opened | **once**, after PR-1b / PR-2 / PR-3's code and parameters are all frozen; the assignment file gets a line "parameter freeze: (commit)", and S opens only when both lines are filled | every opening leaks; S has only 13 sessions, and a void one means recording again |
| Q15 (new) | Phone height and ground-contact switch of the counterfactual arms | both arms **1.15 m preset, ground-contact switch kept on** (as recorded); sensitivity 1.05 / 1.25 m | only then is det-first the one difference between the arms; the switch was on by mistake, but for the whole batch, and turning it off would depart from what the device spoke |
| Q6 | The values of sections 3 and 4 | as written | the user can change any of them; changes are recorded under R6 |

## 6. Reproduce

```
gradlew :sailens-guidance:testDebugUnitTest --tests "*PromptPolicyBaselineTest*" -PreplayCapture=<...\detfirst-d18\calibration> -Pm3aPhoneHeight=1.15
```

The report is at `sailens-guidance/build/replay/detfirst-baseline.txt`. On this PR it differs from
`detfirst-d18\reports\detfirst-baseline-C-2026-10-28.txt` only in the header, the speaker-model lines
(including the new pooled block) and each capture's geometry line; every other line is identical.
Do not run it on `sealed`; until both the threshold-freeze and parameter-freeze lines are filled in,
`-PunsealCapture` is not used.
