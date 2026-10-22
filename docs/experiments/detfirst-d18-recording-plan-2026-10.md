**English** | [简体中文](detfirst-d18-recording-plan-2026-10.zh-CN.md)

# Recording plan: det-first D1.8 (calibration/baseline set and sealed acceptance set)

det-first plan D1.8 calls for new recordings with the speech lifecycle, split **before recording**,
by whole session, into two sets:

- **Calibration/baseline set (C)**: calibrates the `SpeakerModel` (D1.4) and gives the new baseline
  (spoken to the end, lead time against completion).
- **Sealed acceptance set (S)**: kept for the acceptance of D2–D4 and the M5 no-regression check. It
  is **not looked at** until every parameter and threshold is frozen.

This plan keeps the safety, privacy and carrying rules of the
[field recording manual](../field-recording-manual.md) and only states what differs for D1.8. The
recordings contain faces and places: they stay on the phone and your own computer, never in a
repository or online.

## 1. Principles

1. **Sets are fixed before recording, by whole session.** Every planned recording slot is assigned
   to C or S before the first outing and never moves. A session is never placed by how it turned out.
2. **Both sets cover the same strata.** Within each stratum, C and S have as many slots and
   alternate. So the C counts estimate how much S holds, without looking at S.
3. **Sample size is counted in independent hazard stages, not sessions.** A pilot first proves the
   pipeline; further slots are added where the C counts fall short, always one C and one S at a time.
4. **The sealed set gets integrity checks only.** Completeness and exact replay may be checked; no
   metric is computed (the baseline test refuses, section 5) until the thresholds are frozen in
   `docs/experiments/`.

## 2. Before each outing

On top of manual section 3:

1. **Build**: the commit PR #30 merges as, with the weights of the 2026-10 recordings
   (`det.tflite` sha256 `d9cef07c…`, `sem.tflite` sha256 `d29dee02…`). After the merge, create an
   annotated tag `baseline/detfirst-pr0` naming both hashes and check every day with
   `capture_stats.py --baseline-tag baseline/detfirst-pr0`. A session from another build is unusable.
2. **Settings** (the same for the whole batch):
   - Speech on, **speech rate 1.30** (the D1.7 duration table was measured at it); vibration on;
     **TalkBack off**: a screen-reader session cannot be modelled and counts in neither set.
   - Perception profile: **standard** (sem + det).
   - Ground-contact distance: **switch off** (the baseline is today's policy); placement "In the hand at chest
     height", body height 170.
   - Field capture: "Capture during Guidance" and "Also record the model-regression record" on;
     group outdoor or indoor; **scene name = the slot id** (for example `C-O2-03`).
3. Battery > 60%, free storage > 10 GB (the model-regression record is about 3.5 GB an hour
   outdoors). Rest every 20 minutes or so: thermal throttling makes data unrepresentative.

## 3. Strata and slots

A slot is one "start guidance → stop guidance", 5–8 minutes, one kind of scene. Slot ids:
`<set>-<stratum>-<number>`.

| Stratum | What | What it tests |
|---|---|---|
| O1 parked vehicles | Walk along parked cars; walk straight at one, stop about 1 m away, then go round | Vehicle ahead: first prompt, NEAR, CRITICAL |
| O2 oncoming people | A moderately busy pavement or shopping street, people coming towards you or crossing | Person ahead/at the side; same-priority non-escalation (D1.6) |
| O3 pavement obstacles | Poles, trees, parked bicycles, benches: approach head-on then go round, and pass alongside | Static obstacles, NEAR |
| O4 waiting at a crossing | Approach a crossing, stand at least 30 s, then cross | Cooldown while still, repeated prompts |
| I1 mall corridor | Atrium and corridors with people and shop-front obstacles | "Path blocked" competing with obstacles for the speaker (best effort) |
| I2 mall entrance | Start outside, walk in, walk out | Ground unrecognized, prompt switching (best effort) |

**First round** (4 slots per stratum: 2 C and 2 S, alternating): a coin toss per stratum decides
whether C or S comes first, for example `C-O1-01`, `S-O1-01`, `C-O1-02`, `S-O1-02`. The tosses are
written in the assignment file's header before the first outing (section 5) and never changed. Record
a stratum's C and S slots on the same day at similar places, so the sets do not differ by weather or
time of day.

## 4. What to record first

1. **Pilot (two sessions, both in C)**: one outdoor O1 (`C-P-01`) and one indoor I1 (`C-P-02`). Check
   the same day:
   - the manifest has `schemaMinor = 5` and `regression.utteranceLifecycle = true`;
   - `regression.stats` has `framesLost = 0`, `utteranceEventsDropped = 0`, `utteranceEventsUnreconciled = 0`;
   - the baseline test on the C folder shows lifecycle "recorded" and a "spoken to the end" figure.

   If anything is off, stop and fix the capture pipeline before recording more.
2. **First round**: the table in section 3, outdoor strata first (O1 and O2 matter most), indoor as
   best effort.
3. **Fill the gaps**: after each outing, run the baseline on **C only** and read the hazard-stage
   counts. First-round targets (initial; revisit after the pilot), per set at least:
   - 20 first prompts for vehicles ahead, 20 for people ahead;
   - 20 NEAR stages and 20 CRITICAL stages (all classes);
   - 10 NEAR entries held by a same-priority cooldown.

   Where one falls short, add **both** a C and an S slot to the stratum that feeds it, continuing the
   alternation. For scale: the seven 2026-10 captures (about 55 minutes) held 19 vehicles ahead and 19
   people ahead, so each set needs about an hour of outdoor recording rich in vehicles and people.
   One outdoor and one indoor session are **not presumed enough**.

## 5. Assignment file and folders

Outside the repository, beside the other recordings:

```
C:\Users\wnbot\AndroidStudioProjects\sailens-captures\detfirst-d18\
  assignment.csv
  calibration\<sessionId>\      C sessions, as exported from the phone
  sealed\SEALED                 empty marker file: the baseline test refuses to evaluate below it
  sealed\<sessionId>\           S sessions
```

`assignment.csv` template (fill the header and every planned slot before the first outing; fill
`session_id` as each slot is recorded):

```
# det-first D1.8 assignment. Written before the first recording; a slot's set is never changed.
# Rule: within each stratum C and S alternate, the first decided by a coin toss: O1=?, O2=?, O3=?, O4=?, I1=?, I2=? (date: 2026-10-__)
# Thresholds frozen: (commit) on (date). Until this line is filled, S is used for no metric.
slot,stratum,set,group,planned_date,session_id,recorded_date,status,notes
C-P-01,pilot,C,outdoor,,,,planned,pilot: pipeline check
C-P-02,pilot,C,indoor,,,,planned,pilot: pipeline check
C-O1-01,O1,C,outdoor,,,,planned,
S-O1-01,O1,S,outdoor,,,,planned,
```

- `session_id` is the capture session's id on the phone (also the export ZIP and folder name);
  `recorded_date` is the day it was recorded.
- `status`: `planned` / `recorded` / `discarded` (interrupted, TalkBack on by mistake, wrong build…).
  **A discarded slot keeps its set and is not replaced in place**: a new slot id is added instead.
- Recorded sessions go into `calibration` or `sealed` by their `set`. Keep them on the phone until
  the computer has them.

## 6. After each outing

1. Export (manual section 8), file into the right folder, update the assignment file.
2. Integrity on both sets: `capture_stats.py --baseline-tag baseline/detfirst-pr0`; exact replay may
   run on both (`CaptureReplayTest` says only whether replay matches, no metric).
3. The baseline on C only:

   ```
   gradlew :sailens-guidance:testDebugUnitTest --tests "*PromptPolicyBaselineTest*" -PreplayCapture=<...\detfirst-d18\calibration> -Pm3aPhoneHeight=1.15
   ```

   Pointed at `sealed`, it refuses. `-PunsealCapture` opens it, only after the thresholds are frozen.
4. Decide on further slots as in section 4, item 3.

## 7. When the sealed set is opened

Only after all of these are committed in `docs/experiments/`: the `SpeakerModel` calibration
thresholds (D1.4-2), the D2–D4 success thresholds (Q6), and the parameters to be used. Write the
commit into the assignment file's "thresholds frozen" line; only then remove `SEALED` or pass
`-PunsealCapture`. Results on S must not feed back into parameters; if they do, S is spent and a new
sealed set has to be recorded.
