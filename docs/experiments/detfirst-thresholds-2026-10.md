**English** | [简体中文](detfirst-thresholds-2026-10.zh-CN.md)

# Threshold proposal: det-first, what to freeze before the sealed acceptance set opens (2026-10-28)

det-first plan v4.1 requires two kinds of threshold to be frozen before the sealed acceptance set (S)
is opened: the `SpeakerModel` calibration thresholds (D1.4-2) and the D2–D4 success thresholds (Q6).
This note proposes both, plus the questions the user has to decide. **It uses the calibration/baseline
set (C) only**; no tool that shows results has opened S.

> **Status: proposal, revision 4 (after review round 3), not frozen.** Freezing takes all three: the user decides every item of
> section 5 (done 2026-10-28, every recommendation accepted); the GPT review has no findings;
> the freezing commit is written into the "threshold freeze" line of the local assignment file
> `detfirst-d18\assignment.csv`. Until that line is filled in, S may not be used for any metric.

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
  2. durations **never come from the capture being scored** (revision 2, review round 1 P1-1): S takes the
     frozen C table; each C capture takes the median over the other 13 (leave one out); a wording
     missing there falls back to the D1.7 duration table (zh-CN, rate 1.30), and only then to the
     1.4 s default;
  3. the calibration report adds agreement on being spoken to the end, CRITICAL errors by direction,
     disagreements by the refusing side's reason, where durations came from, exclusions by reason
     (X2, X3, X5), a block pooled over all of C, and a reference block with the D1.7 table alone;
  4. the report states the phone height its distances use, counted per frame (revision 2: no longer
     the first frame's only);
  5. revision 3: which capture is C or S comes from a frozen session list, not the `SEALED` marker; the
     X3 and X5 windows run on until both speakers are idle; X3 also opens on `speak_error` and
     `speak_failed`;
  6. revision 4: `SpeakerRobustness` (R10's per-sentence independent error branches, open-loop core);
     the calibration report adds the |done| p99, the maximum and the count beyond ε (A13).

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
The columns differ only in where a sentence's duration comes from:

- **PR-0**: by message key alone, from the capture's own lifecycle;
- **revision 1**: by wording, from the capture's own lifecycle -- **not independent** (review round 1 P1-1),
  kept for comparison only;
- **revision 2 (what the thresholds rest on)**: by wording, **leave one out** (each capture's
  durations from the other 13), falling back to the D1.7 table plus the offset below.

| | PR-0 | Revision 1 (not independent) | Revision 2: leave one out | Reference: D1.7 table alone |
|---|---|---|---|---|
| Accept: precision / recall | 0.946 / 0.931 | 0.965 / 0.957 | **0.954 / 0.934** | 0.911 / 0.912 |
| Refuse: precision / recall | 0.976 / 0.981 | 0.985 / 0.987 | **0.977 / 0.984** | 0.968 / 0.968 |
| Spoken to the end: precision / recall | (not counted) | 0.956 / 0.951 | **0.950 / 0.931** | 0.895 / 0.903 |
| CRITICAL accept/refuse misjudged (both directions, of 1 244) | 33 | 21 | **36 (2.9%)** | 54 |
| CRITICAL: model accepts, device refused / model completes, device did not | (not counted) | 10 / 12 | **16 (1.3%) / 17 (1.4%)** | 28 / 30 |
| \|start\| error p50 / p95 (pooled) | per capture 6–9 / 10–103 ms | 7 / 62 ms | **7 / 61 ms** | 7 / 61 ms |
| \|done\| error p50 / p95 (pooled) | per capture 7–46 / 11–734 ms | 12 / 69 ms | **20 / 70 ms** | 54 / 106 ms |
| Worst capture: accept precision / completion precision | 0.867 / — | 0.905 / 0.886 | **0.880 / 0.860** (C-O2-02) | — |
| Duration sources | own capture | own 2 068 / D1.7 304 | **other captures 2 333 / D1.7 39** / default 0 | D1.7 2 372 |

- In PR-0, seven captures had a done-error p95 of 658–734 ms, because sentences with the "注意，"
  prefix (about +0.6 s in Chinese, D1.7) shared one duration with those without. Keying by wording is
  right and stays in revision 2.
- Revision 1's numbers flatter: a wording finished once predicts itself, with zero end-time error.
  Leave one out costs about 1–2 points on accept and on completion, and CRITICAL misjudgements rise
  from 21 to 36. **Revision 2's thresholds rest on the leave-one-out numbers** (section 3.2).
- **The D1.7 table alone is clearly worse** (completion precision 0.895): on the device a sentence
  runs from `started` to `done` some 40–50 ms longer than D1.7 timed it alone (for example "前方有车辆"
  1 237 vs 1 193 ms, with the prefix 2 000 vs 1 952 ms), which flips the call at the end-of-sentence
  boundary. So what is frozen is the **C table**: per wording, the median over all 14 C captures, 28
  wordings (`SpeakerDurations.FROZEN_C`). A wording missing from it falls back to the D1.7 table plus
  the median difference over the 28 wordings both have (45 ms, range −10 to +66 ms): a too-short
  duration leans toward "spoken to the end", the direction that hides a regression.
- C contains no description, no system notice and no engine restart (all 14 captures ran on engine
  generation 1), so X2, X3 and X5 exclude nothing; the device's refusals are all "equal or higher
  priority speaking" (`outranked`). The remaining 69 disagreements are all **boundary moments**: a
  new prompt arrives in the few tens of milliseconds in which a sentence ends, and the two sides
  order the events differently.
- The worst capture is C-O2-02 (outdoor, about 5.8 min and 254 offers; its manifest scene name
  repeats the discarded C-O2-01): accept precision 0.880, completion precision 0.860, and of its 165
  CRITICAL offers the model accepts 6 the device refused. It is not excluded; A11's per-capture floor
  leaves room for it.

### 3.2 Thresholds

Computed on S, **pooled over all S sessions** with a fully recorded lifecycle; C is reported as well
and has to pass too (it does now). Four principles:

- **Durations do not come from the capture being scored.** Revision 1 took durations by wording from
  the capture's own lifecycle and then predicted that same capture's end times; a wording finished
  once was "predicted" to the millisecond (review round 1 P1-1), so the 69 ms done error was not independent.
  Now S uses only the **frozen C duration table** (`SpeakerDurations.FROZEN_C`: per wording, the median
  over all 14 C captures, then the D1.7 table plus a 45 ms offset, then the 1.4 s default), and S's own lifecycle is used for
  scoring only; C's numbers are **leave one out** (each capture's durations from the other 13), as
  independent as S's. Section 3.1's numbers are computed that way.
  Whether a capture belongs to C or S is decided by the frozen session list (`D18Sets`: the 14 + 13
  session ids recorded in the assignment file), **not by the `SEALED` marker**: the marker only governs
  whether a capture may be looked at (the recording plan lets it be removed after the freeze), and
  removing it must not make S a source of the duration table (review round 2 P1-1). A capture on
  neither list is not scored by the speaker model. With all of C present, C's pooled table must equal
  the frozen one, or the test fails.
- **Stricter on the experiment arm's "spoken to the end".** In a counterfactual evaluation, the
  experiment arm's "the model thinks it was spoken to the end and it was not" hides a regression
  (unsafe), so completion precision and CRITICAL "the model accepts / completes and the device did not"
  get thresholds of their own. Revision 1 took the other direction (the baseline arm's "not spoken to
  the end") to be merely conservative; the review showed it removes protected stages from M5. Revision
  2's M5 takes the device's real completions as the protected set (§4.2), which makes that direction
  conservative.
- **Below C's values, leaving room for sampling error.** S should have about 2 000 offers, about 550
  receipts and about 1 100 CRITICAL offers; each threshold sits about 2.5–3.5 standard errors (at S's
  expected counts) below C's leave-one-out value. Revision 1's thresholds were set on numbers that
  were not independent; revision 2 resets them on the leave-one-out numbers, which loosens A2, A3, A4,
  A6–A8 and A11 (a revision before S opens, under R6: the reasons and the C evidence are this section
  and section 3.1).
- **Timing limits well inside M5's 0.5 s tolerance**, or M5's "no later than the baseline by more
  than 0.5 s" means nothing.

| # | Metric | Threshold (S pooled) | Revision 1 | C pooled (leave one out) |
|---|---|---|---|---|
| A1 | Accept precision | ≥ 0.93 | 0.93 | 0.954 |
| A2 | Accept recall | ≥ 0.90 | 0.92 | 0.934 |
| A3 | Refuse precision / recall | each ≥ 0.965 | 0.97 | 0.977 / 0.984 |
| A4 | Completion precision (model completes → device completed) | ≥ 0.92 | 0.93 | 0.950 |
| A5 | Completion recall | ≥ 0.90 | 0.90 | 0.931 |
| A6 | CRITICAL: model accepts, device refused | ≤ 2.5% of CRITICAL offers | 1.5% | 16 / 1 244 (1.3%) |
| A7 | CRITICAL: model completes, device did not | ≤ 2.5% of CRITICAL offers | 1.5% | 17 / 1 244 (1.4%) |
| A8 | CRITICAL accept/refuse misjudged, both directions | ≤ 4.5% of CRITICAL offers | 3% | 36 / 1 244 (2.9%) |
| A9 | \|start\| error p95 | ≤ 150 ms | 150 ms | 61 ms |
| A10 | \|done\| error p95 | ≤ 150 ms | 150 ms | 70 ms |
| A11 | Per-capture floor: captures with ≥ 50 offers, accept precision and completion precision | each ≥ 0.80 | 0.85 | lowest 0.880 / 0.860 (C-O2-02) |
| A12 | Closed-loop check (below) | four parts, below | three parts | to be measured on C once PR-1b implements it |
| A13 | Share of sentences (spoken to the end by both) whose end misses the device's by more than ε = 150 ms (R10) | ≤ 2% | (new in revision 4) | 4 / 472 (0.8%) |

The model stays usable for M5 after the loosening because revision 2's M5 no longer needs the model's
errors to cancel between the arms: errors in the baseline arm only add undeterminable stages
(conservative), and the experiment arm's "spoken to the end" is bound by A4 and A7.

**A12 closed-loop check.** Section 3.1 is open loop: the offers come from the device. A
counterfactual run is closed loop: a refusal by the model revokes the cooldown and changes later
offers. Agreement in the open loop is not agreement in the closed loop. So one more check: rerun
today's policy with `SPEAKER_MODEL` delivery, **the recorded phone height (1.2 m)**, the recorded
configuration and section 3.2's duration rule (S: the frozen C table; C: leave one out), and compare
with the device stage by stage over §4.2's protected set P (R9's reference cohort):

| # | Metric | Threshold |
|---|---|---|
| A12a | Stage recall: of P's stages, the share the model also speaks to the end with its first completion within 0.5 s of the device's (§4.2's "baseline arm reproduces") | front vehicles' and front persons' CRITICAL and "blocked" ≥ 0.95; all of P ≥ 0.90 |
| A12b | Stage precision: of the protected-kind stages the model speaks to the end, the share the device did too | ≥ 0.90 |
| A12c | First completion time: stages both spoke to the end, p95 of \|model − device\| | ≤ 250 ms (M5's tolerance is 0.5 s) |
| A12d | Total spoken to the end | differs by ≤ 10% |

A stage A12a does not reproduce becomes undeterminable in §4.2 and most likely counts as a
regression, hence the stricter front CRITICAL limit. `SPEAKER_MODEL` delivery does not exist yet (it
is part of PR-1b / PR-2); once it does, it is **measured on C first, and if C fails, S is not
opened** (the model or the threshold is revised under rule R6 of section 4.1).

### 3.3 Exclusions named in advance

These are the only exclusions. Where one applies, the model **may not be used**: the stages
concerned are judged on device evidence only in an acceptance, and are "undeterminable" in a
counterfactual (§4.2: unless resolved by its one rule, they count as regressions).

| # | Exclusion | Rule | On C |
|---|---|---|---|
| X1 | Captures without a full lifecycle | Evidence other than RECORDED (none, dropped or unreconciled events, a screen-reader session): the whole capture stays out of calibration and the model is not used on it | 0 captures |
| X2 | Logical utterances with an evidence conflict | Out of calibration and out of acceptance; more than 1% of S's offers fails calibration | 0 |
| X3 | Engine failure and restart windows | The record gives the model no engine readiness. Every offer from an engine generation's first `error`, `unknown_terminal`, refusal for "engine not ready" (`engine_not_ready`) or "speak error" (`speak_error`), or queue forgotten for `speak_failed`, until the next generation's first `started` (or the end of the session if there is none), extended until **both** the device's and the model's speaker are idle, is excluded; more than 2% of S's offers fails calibration | 0 (all generation 1) |
| X4 | Prompts with no outcome when the record ends | Out of the timing errors only; accept/refuse counted as usual | counted separately in the S report |
| X5 | Offers while a system notice or a description holds the speaker | Offers from its submission to its last lifecycle record (or submission + duration, whichever is later), extended until **both** the device's and the model's speaker are idle, are not scored; more than 2% of S's offers fails calibration | 0 (C has no description or notice) |

X5 is new in revision 2. The model still queues notices and descriptions, but their durations can
only come from this capture's own lifecycle (their texts vary; there is no table). Per section 3.2 a
scored prediction may not use the scored capture's lifecycle, so offers in that time are not scored;
the lifecycle only frees the speaker at the right moment. Revision 3: the X3 and X5 windows both run
on until **both the device's and the model's speaker are idle** (review round 2 P1-2's
counterexample: the device's notice ends at 1 990 ms, the model's at 2 000 ms, and an offer at
1 995 ms still rests on the notice's duration). Until then the model's predictions can still depend
on the excluded stretch indirectly. In the open-loop calibration the offers are fixed, so both speakers idle is enough. In the **closed
loop** (A12, the counterfactual arms) it is not: accepts and refusals inside the window leave cooldown
records, revokes and re-offers that shape later offers after the speakers are idle (review round 3
P2). So in the closed loop the stages after an X3 or X5 window stay undeterminable until the cooldown
state can be shown independent of what happened in the window: most simply, no cooldown record is
still in force (the last record is older than the 8 s cooldown ceiling) and no prompt was emitted in
the 20 s storm window; failing that, they count under §4.2. PR-1b needs a test with "both speakers
idle, cooldowns still different". X2, X3 and X5 are implemented in code
(`PromptPolicyCaptureEvaluation.speakerInputs`), and the calibration report counts exclusions by
reason.

### 3.4 What is frozen; what happens if it fails

- **Frozen are the thresholds and the model itself**: `SpeakerModel.kt`,
  `PromptPolicyCaptureEvaluation.speakerInputs`, `SpeakerDurations.kt` (with the frozen C duration
  table), `D18Sets.kt` (which capture is C and which is S), `SpeakerRobustness.kt` and `TtsDurations.kt` as of the freezing
  commit. Any later
  change to one of them needs a new calibration; if S is open by then, it can no longer calibrate the
  changed model, and a new sealed set has to be recorded.
- **Failing on S** (any of A1–A13, or X2/X3/X5 over their limit): per plan D1.4-3, D3 (PR-1b) is judged
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
- Durations are C's medians per wording; one wording varies by a few tens of milliseconds between
  captures and plays (leave-one-out done error median 20 ms). That is where the remaining boundary
  disagreements come from, and it cannot be removed.
- C spoke only 28 wordings to the end. Counterfactual runs will offer more wordings never finished
  (NEAR sentences for other zone combinations, for example); those use the D1.7 table plus 45 ms,
  coarser than a measured wording. Each run reports the fallback offers per arm; the experiment arm
  gives them twice the ε (R10).
- R10 covers independent errors up to ε per sentence; sentences erring more (0.8% on C) are not
  covered.

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
- **R9 Reference hazard cohort: both arms are scored on one set.** Revision 1 fixed only the NEAR
  definition. The review pointed out that today's evaluator classes a hazard by its category and
  bearing **when its first stage appears**: a metric threshold makes a hazard appear earlier, possibly
  at the side first, so it is classed `person/side` and leaves the front denominator. Therefore:
  - hazard identity = the track (birth-order ids injected by the replay); both arms and the device's
    exact replay must give birth to the same tracks (R3).
  - **The reference cohort = the hazards appearing in the device's exact replay** (today's policy, the
    run behind the C report), with category and bearing by that run's rule, frozen. An experiment
    that changes when a hazard appears does not change which class it belongs to.
  - Denominators follow the reference cohort: M1's "front person first" = hazards whose reference
    class is `person/front`; M2's NEAR denominator = hazards whose NEAR stage appeared in the reference
    run by today's definition; M5's P uses the same cohort. The experiment arm speaking the matching
    wording to the end for the same hazard (first: any prompt that speaks of it; NEAR: the NEAR
    wording) counts, whatever bearing the experiment arm would class it under.
  - Whether a crossing can be bracketed, an undetermined order and an unknowable end are decided by the
    track's own fresh distance samples alone (`LeadTime`), independent of the policy; both arms and
    the reference run use the same samples.
  - Hazards new in the experiment arm, absent from the reference cohort, stay out of M1, M2 and M5's
    denominators and are reported apart; M4 (C4, C5, C8) binds them.
  - The implementation comes with the first counterfactual PR (PR-1b), and must turn the review's
    counterexample into a test: a track the experiment arm admits at the side first, classed
    `person/front` by the reference run, still counts as `person/front`.
  - R9 only answers "for the hazards in the reference cohort, did the experiment speak better". Hazards
    new in the experiment arm, reported apart and bound by M4, are checked only for part of their
    false-alarm behaviour; it **does not show their recall or lead time**, and the acceptance record
    says so rather than counting it as evidence of a pass.
- **R10 The experiment arm's completions are judged robustly (revision 4).** Every sentence's
  predicted end carries an error (C, leave one out: |done| p95 70 ms, p99 137 ms, max 752 ms). A
  sentence that ends a few tens of milliseconds before the next prompt arrives is "spoken to the end"
  for the model and may be cut off for real -- or, the other way round, may block the sentence after
  it. A4 and A7 are overall rates and cannot rule out such a false completion of one stage. Revision 3
  used four uniform perturbations; review round 3 gave a counterexample: A, B and P a dozen or so
  milliseconds apart, where making everything longer refuses B and clears the way for P, but B alone
  40 ms longer blocks P -- all four uniform runs keep P. So errors are now **independent per
  sentence**:
  - each sentence's end is an interval [nominal − ε, nominal + ε], ε = 150 ms, independent of every
    other sentence's; for a wording on the D1.7 fallback, ε = 300 ms.
  - The speaker model's arbitration is run forward. Wherever a step compares a time with a sentence's
    end and the interval straddles that time ("already ended" and "still speaking" both possible),
    the run splits in two, each carrying the narrowed interval on. Every outcome any independent
    error within ε can reach is reached that way (`SpeakerRobustness`).
  - A stage of the experiment arm counts as spoken to the end (M5's kept, M1 and M2's successes) only
    if it is spoken to the end before its deadline in **every branch**: M5's deadline is
    t_dev + 0.5 s, M1 and M2's the crossing. Refused, cut off or late in one branch, it does not
    count: undeterminable in M5 (§4.2, counted as a regression), no success in M1 or M2.
  - Branches with equal state merge. More than 64 branches at once, and every stage involved is
    undeterminable.
  - Closed loop (the counterfactual arms) re-runs the decisions in every branch (cooldown records,
    revokes and the offers after them), and branches merge only when the speaker state **and** the
    cooldown state are equal. That part comes with PR-1b, with the A/B/P counterexample as a
    closed-loop test (P must not be kept). The open-loop core and the A/B/P open-loop test are in this
    PR (`SpeakerRobustness`, `SpeakerRobustnessTest`).
  - **ε is a coverage, not an upper bound on the error.** Of the 472 sentences both sides spoke to the
    end on C, 4 (0.8%) missed the device's end by more than 150 ms; A13 checks the same on S. A sentence
    erring beyond ε can still make a stage look kept when it was not; that remaining risk is written
    into the acceptance record.
  - The baseline arm does not branch: it only decides whether the model reproduces the device, and a
    stage it cannot reproduce is undeterminable anyway. This can only make the experiment look worse;
    the thresholds stay.

### 4.2 M5, no regression (for the shipped combination)

Revision 1 ran both arms on the model and took the protected set from the model's baseline arm. The
review pointed out that a stage the device did speak to the end but the model misses in the baseline
arm never enters the protected set, so the experiment arm dropping it is no regression; one model in
both arms does not guarantee that the errors cancel. Hence the rules below.

- **The protected set P comes from the device, not the model.** P = every CRITICAL and NEAR stage the
  device spoke to the end on S (CRITICAL includes the single stage of "blocked" and of a covered
  camera), plus front persons' first stage (a front person's first prompt is at least HIGH but not
  necessarily CRITICAL). Hazards and stages follow R9's reference cohort; a stage's completion time
  t_dev = the device's first completion. Whatever the model judges, no stage leaves P.
- For each stage of P, both arms run closed loop on the model (R3):
  - **the baseline arm reproduces** it: the baseline arm speaks it to the end, first completion within
    0.5 s of t_dev;
  - **the experiment arm keeps** it: the experiment arm speaks it to the end **robustly** (R10), that is,
    with its first completion ≤ t_dev + 0.5 s in every branch.
  - Experiment arm keeps it → **kept**.
  - Experiment arm does not keep it, baseline arm reproduces it → **regression** (the model
    reproduces the device here, so the difference can only come from the experiment).
  - Experiment arm does not keep it, baseline arm does not reproduce it either → **undeterminable**:
    the model cannot be trusted here. The only way to resolve it: there is a time t0 (no later than
    the stage's first offer in the device record) at which the device, the baseline arm and the
    experiment arm all have an idle speaker, and from t0 to t_dev + 0.5 s **every input** all three
    give the speaker is the same one for one: each prompt's time, wording, priority, expiry and speaker
    rank (after PR-1b), and every notice, description, cancellation, requeue and engine readiness
    change (on the device's side, as its lifecycle records them). Then the experiment changed nothing
    on the speaker in that time, the device's outcome is the experiment arm's, and it counts as kept.
    If any of these cannot be shown equal from the records, it is not resolved (review round 2 P2-1:
    comparing only time, wording and priority, a different expiry can reverse the outcome). **Every other
    undeterminable stage counts as a regression** (blocking); a manual review cannot resolve it.
- A stage that was only received does not count; neither does another hazard or an earlier ordinary
  prompt (plan §4).
- **Threshold M5**:
  - front vehicles' and front persons' CRITICAL stages and "blocked": **0 regressions** (counting the
    undeterminable ones that count as regressions);
  - over the whole scope: regressions ≤ max(2, 1% of |P|);
  - every regression and every undeterminable stage is reviewed by hand on a contact sheet and written
    into the acceptance record; the review is for understanding the cause and **exempts nothing**.
- All counts are reported: kept, regressed, undeterminable (resolved / counted as regressions). The
  same comparison of "the model's baseline arm vs the experiment arm" is reported, not judged.
- Directions: the model thinking "not spoken to the end" in the experiment arm only adds regressions
  (conservative); thinking "spoken to the end" hides one, which A4 and A7 bound; a miss in the
  baseline arm only adds undeterminable stages (conservative).

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
  otherwise widening NEAR would change the denominator by itself. Category and bearing follow R9's
  reference cohort, so the experiment cannot change whether a hazard counts as "front" either. A NEAR
  wording spoken earlier in the experiment counts as success as long as it is the same hazard.
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
  height sensitivity. They bound false alarms; they **do not independently prove lead time at true
  distances**, so Q1, Q4 and Q8's values are candidates under test, and passing on S shows they hold
  under this estimator only. Denominators follow R9's reference cohort.
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

Switches are flipped only when all of these hold: A1–A13 of section 3 pass on S; the thresholds of
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
(including the pooled block, the D1.7-only reference block and the duration-table line) and each
capture's geometry line; every other line is identical.
Do not run it on `sealed`; until both the threshold-freeze and parameter-freeze lines are filled in,
`-PunsealCapture` is not used.
