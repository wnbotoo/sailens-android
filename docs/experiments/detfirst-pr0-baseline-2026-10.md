**English** | [简体中文](detfirst-pr0-baseline-2026-10.zh-CN.md)

# Experiment note: det-first PR-0 baseline (2026-10-21)

The first measurement of the det-first prompt strategy's PR-0 (plan D1): how the **current** prompt
policy treats each hazard, stage by stage, on the seven M0b captures of 2026-10 (SM8850, four
outdoor, three indoor). PC only; the captures stay on the developer's machine. Numbers only.

Reproduce:

```
gradlew :sailens-guidance:testDebugUnitTest --tests "*PromptPolicyBaselineTest*" -PreplayCapture=<dir> -Pm3aPhoneHeight=1.15
```

## What this baseline can and cannot say

- **No completion evidence.** These captures predate the speech lifecycle (schema 1.5), so whether
  a prompt was *spoken to the end* is undeterminable -- not "no failures". "Received" below means the
  output side accepted the prompt (it was not revoked), which says nothing about being heard to the end.
- **Lead times use a receipt proxy** (when the prompt was accepted), labelled as such. With the
  lifecycle they will use completion.
- **Descriptive only.** The 1 s bracket and 30% coverage limits are usability gates, not proof of
  accuracy; no success threshold is proposed here (plan Q6) and no `SpeakerModel` threshold is frozen
  (D1.4). This set is the development set (D1.8); calibration and sealed acceptance sets are still to
  be recorded.
- Phone height 1.15 m (the user's real carry); geometry resolved from each capture's sensor log.

## Integrity

- All 7 captures complete; exact replay of every frame (52 870 frames); 0 problems.
- Track births identical between the exact run and an `ALL_DELIVERED` run in every capture
  (8 566 births), so track ids pair across runs.
- Replay and M3a reports byte-identical to `683ba08` (before PR-0).

## Results (all seven captures)

Offered prompts 957, received 287 (30.0%). Tracks 3 261, of which 3 053 never passed the
announcement gates; 1 080 of 8 566 births look like a track rebuilt after missed detections (each
is a new hazard, D1.1-3).

**Stages** (n = hazards whose stage appeared before the cooldown; latency from that appearance to
the first receipt, a stage never received counting as "never"):

| hazard | stage | n | received | p50 / p90 ms |
|---|---|---|---|---|
| vehicle / front | first | 19 | 11 | 0 / never |
| vehicle / front | CRITICAL | 17 | 9 | 1 212 / never |
| vehicle / front | NEAR | 6 | 2 | never / never |
| vehicle / side | first | 25 | 12 | never / never |
| person / front | first | 19 | 17 | 0 / 1 161 |
| person / front | CRITICAL | 18 | 15 | 0 / never |
| person / front | NEAR | 8 | 3 | never / never |
| person / side | first | 136 | 84 | 432 / never |
| person / side | NEAR | 35 | 19 | 148 / never |
| static (all) | first | 9 | 5 | 0 / never |
| blocked | first | 84 | 65 | 0 / never |
| path complex | first | 90 | 45 | 5 120 / never |
| ground unrecognized | first | 14 | 10 | 1 114 / never |

No hazard was credited only ambiguously in this set.

**Lead time** (receipt proxy, point estimate; distance metrics withheld below 30% bracketed):

| hazard | stage, threshold | n | bracketed | before / after / missed / order? / unknowable | median lead ms |
|---|---|---|---|---|---|
| person / front | first, 2 m | 19 | 36.8% | 5 / 1 / 0 / 1 / 12 | +618 … +685 |
| person / front | first, 3 m | 19 | 68.4% | 4 / 6 / 1 / 2 / 6 | −73 … −6 |
| person / front | first, 4 m | 19 | 57.9% | 0 / 9 / 2 / 0 / 8 | −773 … −706 |
| person / side | first, 3 m | 136 | 34.6% | 11 / 22 / 9 / 5 / 89 | −322 … −255 |
| vehicle / front | first, 2 m | 19 | 31.6% | 5 / 0 / 1 / 0 / 13 | +1 453 … +1 519 |
| vehicle / front | first, 3–4 m | 19 | 26.3% | -- | undeterminable |
| vehicle / side | NEAR, 1.5 m | 25 | 52.0% | 1 / 7 / 5 / 0 / 12 | −252 … −216 |
| static (all) | any | 9 | 0% | -- | undeterminable |

Most unknowable cases are "the first valid contact was already within the threshold" (the hazard
was first measured close) or "never within it".

**NEAR entries held by a same-priority cooldown (D1.6).** Of 78 hazards that turned NEAR: the
offered prompt said it for 33; 37 were held because their own zone's cooldown key recorded the
same priority, and for 33 of those the NEAR wording was **never received** afterwards; 6 were held
by a higher recorded priority; 2 were suppressed by BLOCKED; none was left unexplained. (Holds are
read from a copy of the cooldown taken just before each decision, not approximated.)

**Speaker model** (descriptive; default 1.4 s sentences; compared with revokes, which also cover
waiting for a description): of 957 offers, both accept 245, both refuse 657, model accepts while
the device refused 13, model refuses while the device accepted 42; CRITICAL misjudged 15.

## Next

- Device (SM8850): the D1.7 speech-duration table (Settings → Diagnostics → Speech durations, Chinese
  and English); then the D1.8 recordings with the lifecycle, split before recording into a
  calibration/baseline set and a sealed acceptance set (assignment kept in a local file: session id +
  date).
- With lifecycle evidence, re-run this baseline: completion, lead time against completion, and the
  speaker model's timing errors.
