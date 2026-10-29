**English** | [简体中文](detfirst-batch1-design.zh-CN.md)

# det-first batch 1 design: PR-1a (revocation) and PR-D5 (telling the user what Guidance cannot see)

- Date: 2026-10-29; based on A `main` = `a669901`.
- Sources: det-first plan v4.1, D3a-1 (items 1–3), D5 (items 1–6), §1.6, §6; the frozen threshold document
  `docs/experiments/detfirst-thresholds-2026-10.md`, section 5 (Q11a, Q11b, Q11c, Q13).
- Two PRs. **PR-1a** only replaces how a cooldown record is taken back, and is bit-for-bit equivalent to today under the
  condition in 1.7. **PR-D5** changes what the user hears and feels; it closes the invariant-3 gap of plan §1.6. PR-D5 is
  stacked on PR-1a (it uses PR-1a's confirmation).
- Neither depends on det-first, and neither touches the sealed set S.

The code positions in plan §8 were written against `683ba08`; every one this document relies on still holds at `a669901`
(`CooldownManager.kt:125–135, 144–169, 187–195`, `DecideEventsUseCase.kt:39–57`, `GuidanceDelivery.kt:52–64`).

---

## 1. PR-1a: revocation (D3a-1, no change of meaning)

### 1.1 Today, and its defect

`DecideEventsUseCase` records a cooldown only for the frame's first event (provisionally); when the delivering side could
not deliver it, it calls `RevokeUndeliveredEventUseCase`. `CooldownManager` revokes by **snapshot**: `recordEvent` keeps
each key's record from before the write, and `revoke` puts back the keys "still written by this event" and drops one
storm entry with the same timestamp.

The record rule (`recordEvent`): on a key, a write within the previous record's base cooldown inherits that record's
priority when it is higher. The highest priority is therefore carried along a chain of consecutive writes.

Snapshot revocation is right only when it takes back the last write, before the next one. The counterexample
(interleaved revocation):

| Step | key `K` today |
|---|---|
| t = 0 write A (CRITICAL) | A, CRITICAL |
| t = 1 write B (HIGH, within the window, inherits) | B, CRITICAL (inherited from A) |
| revoke A | unchanged: `K` is B's now, and B's record still carries the CRITICAL inherited from A |
| revoke B | restored to the record before B = **A, CRITICAL** |

Neither A nor B reached the user, yet `K` holds A's CRITICAL record and mutes equal and lower prompts for a cooldown.
In plan §1.4, all 670 revocations in the 7 old recordings landed after their own decision and before the next, so this
never fired; but that order is not guaranteed by the code (there is buffering between the pipeline and the collector).

### 1.2 The new state

One `recordEvent` is one **write**: the event id, the time, the priority, and every key it writes with that key's base
cooldown (taken from the writing event's category and key, as `baseCooldownFor` does today). The cooldown keeps:

- **the summary of the confirmed prefix**: per key, the record that today's rule gives when every confirmed write is folded
  in order (time, inherited priority, writer). It is the complete fold, not a window-trimmed replay: an inheritance chain
  can be arbitrarily long (centre window 4 s: CRITICAL at t = 0, HIGH at t = 3, HIGH at t = 6 — the t = 6 record is
  still CRITICAL).
- **the writes not yet in the summary**, in write order. Each is either pending or confirmed but behind a pending one.
- **the current records** = the open writes folded in order onto the summary. Cached, rebuilt on a revocation; `filter`
  and `snapshot` read only these.

The fold is today's `recordEvent` lines, unchanged (the new record's time is the write's; it inherits the old record's
priority when within the old record's base cooldown and higher, else takes the write's; the writer is this write).

### 1.3 Confirmation, revocation, and the bound

- **`confirm(id)`** marks the write confirmed, then advances the summary: while the first open write is confirmed, it is
  folded into the summary and dropped from the list. **The summary only advances over a contiguous confirmed prefix**:
  when B is confirmed while A is pending, B stays in the list until A has an outcome. Confirmation does not change the
  current records (the fold is sequential, so folding the head into the summary changes nothing overall); it only decides
  whether the write can still be revoked.
- **`revoke(id)`**: a write still open and pending is removed, the current records are re-folded, and the summary is
  advanced. A confirmed, already-summarised or unknown id is a no-op. The return value (decisions completed when the
  revocation took effect, for M0b) is unchanged.
- **The bound**: beyond 16 open writes, the oldest is confirmed by force and folded in. This is today's `undoLog`, which
  keeps the 16 most recent and makes an older revocation a no-op. The open writes are always a suffix of all unrevoked
  writes, so there are never more of them than today's `undoLog` entries, and the bound never takes effect earlier.
- **A write that never gets an outcome** (decided, but the delivering side said nothing, e.g. the session stopped) counts
  as it does today, until the bound pushes it out or `reset()`. PR-1a leaves this alone.

### 1.4 The storm count

Each `recentEmissions` entry carries its writer's id. Which part is confirmed and which can be taken back is decided by the
write list: a revocation happens only while the write is pending, and removes **its own** entry (by id, not the last entry
with an equal timestamp). Window trimming (`trimEmissionWindow`) is unchanged.

### 1.5 When a write is confirmed (today's acceptance, kept)

Confirmed = the delivering side says "at least one channel accepted it", i.e. `deliverGuidanceEvent` returned channels.
Per channel:

| Channel | Confirmed when |
|---|---|
| own TTS | `speak` accepted it |
| TalkBack | published to the screen reader (after `announce`) |
| vibration only | the motor arbiter accepted it (`HapticManager.trigger` returned true) |
| speech and vibration off | the status card |

The two paths that do not deliver still revoke: waiting for a description (`WAITING_FOR_DESCRIPTION`) and no channel
accepting (`OUTPUT_REFUSED`). A new `ConfirmDeliveredEventUseCase` sits next to `RevokeUndeliveredEventUseCase`;
`offerGuidancePrompt` calls it on delivery. Confirming only once the prompt was heard out belongs to PR-1b (D3b-4).

### 1.6 Replay and recording: confirmations are not recorded

M0b records only where revocations landed; PR-1a adds no confirmation record and leaves the schema alone, because:

- the current records are all **unrevoked** writes folded in order onto the summary, whichever of them are confirmed;
  confirmation only decides whether a later revocation can take effect;
- in production an event is either confirmed or revoked, never both. Replay never confirms, so its open writes are the 16
  most recent unrevoked writes; the device's open writes are a suffix of those that holds every pending write. A
  revocation that took effect on the device therefore takes effect in replay, and one the bound defeated on the device is
  defeated in replay too;
- so replaying only the recorded revocations reproduces the device bit for bit. A JVM test checks this on random
  sequences ("device": confirm + revoke vs "replay": revoke only).

### 1.7 The equivalence claim

**Condition**: the same initial state, and every revocation takes back the last write that can still be revoked, before
the next write (all 670 revocations in the 7 old recordings; group C in section 4). **Under it, every key's record (time,
priority, writer) and the storm count are bit-for-bit those of today's implementation**, and so are the results of
`filter`, `snapshot` and `revoke`.

Evidence, in three layers:

1. **Property test**: the new implementation and a verbatim copy of today's (`LegacyCooldownManager`, test source set) are
   compared step by step — every key's record, the storm count and the filter results — on random sequences of writes,
   revocations, motion changes and empty decisions that meet the condition.
2. **The defect, fixed on purpose**: outside the condition the new implementation no longer leaves an undelivered record.
   Required cases: A then B written, A revoked, then B (the table in 1.1); B confirmed, then A revoked (B's record has its
   own priority, and revoking B afterwards is a no-op); the reverse revocation order; merged events with several key groups.
3. **Regression evidence**: the exact replay of group C's 14 sessions and the `PromptPolicyBaselineTest` report are
   unchanged line for line (section 4). This is not offered as the proof of equivalence.

### 1.8 What does not change

Decisions, cooldown rules and windows, speech and haptic arbitration, `SceneEvent`, the M0b schema and comparison rules,
exact replay, every existing baseline. The shell gains one `confirm` call.

---

## 2. PR-D5: telling the user what Guidance cannot see (D5)

### 2.1 The gap

A covered lens (OBSTRUCTED, CRITICAL), too little light (TOO_DARK, HIGH) and an unrecognised floor (GROUND_UNRECOGNIZED,
LOW) are ordinary navigation events today and go through `deliverGuidanceEvent`:

- when speech refuses them for an equal or higher prompt in progress nothing vibrates, and vibration obeys the "Vibration
  alerts" switch;
- only the frame's first event is offered. The floor state is LOW: while obstacle prompts keep coming it never gets a turn,
  and the user may never hear it;
- offering again next frame guarantees a **retry**, not delivery within any time.

### 2.2 Structure

- **Guidance is unchanged**: the three events are still generated, cooled down and recorded in M0b. The only addition hands
  the shell "which of these states hold in this frame": `SceneResult.visionFaults` (the debounced frame quality and whether
  the floor is confirmed unrecognised), from `EventGenerator.visionFaults(snapshot)` — the same test `generate` uses,
  including the `enableSensorQualityEvents` switch, so "the states D5 alerts on" and "the states Guidance generates state
  events for" are one thing. Decisions are bit-for-bit unchanged.
- **A new shell class, `VisionFaultAlerts`** (pure, JVM-testable, of the same kind as `GuidanceStallDetector` and
  `SpeechUnavailableAlert`), owns **all** speech and vibration for the three states: entering, repeating, compensating, the
  fallback, and recovering. Each frame in two steps (round 3, P2-2):
  - **before the navigation prompt** it reads the frame's states (`observe`) and withdraws at once a reservation still
    waiting for a state that has ended, frozen or is holding — otherwise the prompt asking for the motor would start a
    stale fault rhythm first and have its own rhythm refused;
  - **after the navigation prompt** it speaks, owes and asks for rhythms (`act`) — otherwise a higher prompt later in the
    same frame would cut off a floor notice just begun, and an obstacle rhythm would wait behind a fault rhythm asked for
    first.
  - A timer that starts a reservation just before a new frame arrives has not seen the new state; that buzz plays — it
    was owed by the state last seen.
- **When one of these events is the frame's first**, the shell no longer speaks it itself. It confirms the cooldown
  record and writes a `prompt_outcome` through a new channel, `vision_alerts`. The cooldown keeps throttling the event as
  today (status card and trace cadence unchanged); whether the user heard is `VisionFaultAlerts`' own business.
  Otherwise the state would be said twice on entry (once by the event, once by the alert). **A hand-over is no evidence
  the user got anything**, so it does not update the replay history (`lastAnnouncedEvent`): the cooldown is confirmed on the
  hand-over, the replay history only on real output (review round 1, P2-3). D5's own notices do not enter the replay
  history either: they repeat by themselves every cycle.

### 2.3 States and hysteresis

Two tracks:

- **Frame quality**: OK / OBSTRUCTED / TOO_DARK, from `FrameQualityAnalyzer`'s debounced result (six agreeing frames to
  switch — today's debounce). On top of it, a **recovery hold** H = 2 s: back to OK has to last 2 s to count as recovered.
  During the hold no cycle starts and no fallback fires; covered again within it counts as never recovered (no entry
  vibration, nothing said again) and the cycle carries on. A hand waving in front of the lens therefore does not produce
  "covered / clear / covered".
  A direct change between OBSTRUCTED and TOO_DARK starts the new state's cycle (its notice is said), without the entry
  vibration and without a recovery notice. **The deadline is not restarted**, though: see "the untold debt" below.
- **Floor**: RECOGNIZED / UNRECOGNIZED, from `GroundRecognitionAnalyzer`'s confirmed result (entering needs
  `groundUnrecognizedEnterMs`, leaving `groundUnrecognizedExitMs` — today's confirmation times); no further hold.
  **While frame quality is faulty (hold included) the floor track is frozen**: when the picture cannot be trusted, neither
  can the floor reading — the same reason `EventConflictResolver` lets SENSOR_QUALITY suppress every other event. Once frame
  quality recovers, the floor track continues from its current input: still unrecognised starts the floor notice; told
  unrecognised before and recognised now says the floor is back.

**The untold debt (review round 1, P1-1)**: each track (frame quality, floor) keeps the time since which the user has not
been told. No change of state clears it: OBSTRUCTED↔TOO_DARK, or recovering and failing again, keep the earlier time; only
**being told** (2.6) or **the fallback actually playing** clears it. The deadline D runs from it. Otherwise a state that
keeps changing could put the fallback off for ever (the review's reproduction: alternating every 4 s, no fallback in 60 s).
The cost: a fault the user was never told about leaves a debt that makes the next fault fall back at once, even much
later — there is a fault then, it just buzzes before D.

### 2.4 Notices and cadence per state

| State | Notice priority | Entry copy | Cycle P | Deadline D | Entry vibration | Vibration each cycle | Recovery copy |
|---|---|---|---|---|---|---|---|
| OBSTRUCTED | CRITICAL | `event_camera_blocked` | 8 s | 5 s | yes | yes | `event_camera_restored` |
| TOO_DARK | HIGH | `event_low_light` | 15 s | 6 s | yes | no | `event_light_restored` |
| GROUND_UNRECOGNIZED | LOW | `event_ground_unrecognized` | 60 s | 10 s | yes | no | `event_ground_recognized` |

- Cycles and priorities are today's (the key / category cooldowns 8 s, 15 s, 60 s; the event priorities CRITICAL, HIGH,
  LOW), moved from the cooldown into `VisionFaultAlerts`.
- Every vibration is `VISION_UNRELIABLE` (long-short-long), at no less than the HIGH amplitude (the CRITICAL one for a
  covered lens), as `amplitudeFor(event)` does today.
- **Vibrations go through the motor, and reserve it** (review round 1 P1-2, round 2 P1-1): `HapticManager.playOrReserve`
  claims the motor at the state's priority.
  - A rhythm of equal or higher priority still playing is **not cut**: the motor is **reserved** instead. The fault
    rhythm starts the moment that rhythm ends, ahead of any rhythm asked for after it (every claim first checks for a
    due reservation), triggered by a main-thread timer — **not by the next frame**.
  - Once started **no ordinary navigation claim cuts it** (it holds the motor as alarms do), so no navigation rhythm cuts
    it short. The unconditional `play()` — the alarms (guidance interrupted, voice broken) and the settings preview — can
    still take the motor from it (a note from round 4).
  - A strictly lower rhythm playing is interrupted at once, as today's rule says.
  - **Latest delivery**: what is left of the rhythm playing, plus the strictly higher rhythms that interrupt it meanwhile
    (at most one per priority level, and alarms) — whatever the frame rate. In the review's sequence (a frame every 2 s,
    each with a HIGH/CRITICAL obstacle rhythm first) the fault rhythm starts as the obstacle rhythm ends (≤ 360 ms), all
    10 cycles in 600 s, and no obstacle rhythm is refused.
  - **The cost** (wording corrected in round 3, P2-3): a fault rhythm holds the motor while it plays, **at most 940 ms** —
    that bounds the motor's protection, not a navigation prompt's delay. Meanwhile a new navigation rhythm is refused:
    - with speech on, the prompt is still spoken, only without its accompanying buzz;
    - a prompt delivered by vibration alone (speech off) is revoked under PR-1a's rule and **waits for the next frame**,
      so its delay is what is left of the fault rhythm plus the time to the next frame. At usual frame rates that is one
      frame more, about 0.1 s. **With frame intervals of at most 2.5 s, an estimated 3.4 s** (940 ms + 2.5 s). This is not a
      hard bound (round 4, P2): 2.5 s is the stall detector's timeout, checked only every 500 ms, and it does not limit
      the frame interval; polling and scheduling add to it (the review's probe reached 3.74 s with no stall alarm);
    - only when a fault rhythm is owed: with speech off, every 60 s for the floor, 15 s for darkness, 8 s for a covered
      lens. Bringing this delay under 940 ms too would need the prompt's re-offer scheduled on its own. **The user accepted
      this delay for this batch on 2026-10-29; scheduling the re-offer on its own is left to batch 2, PR-1b** (see 2.11).
  - One reservation at a time. A track that is not the active one (ended, frozen, or in its recovery hold) has its
    reservation cancelled at once; what it owes stays, and is reserved again when it is active again.
  - "Whatever the switches say" is about the switches, not the motor.
- Where D comes from: the D1.7 duration table at rate 1.30 gives 1.18 / 1.06 / 3.41 s for the three Chinese sentences and
  1.03 / 1.01 / 2.95 s in English; the slowest rate, 0.7, stretches them about 1.86× (2.2 / 2.0 / 6.3 s). D has to hold
  "the prompt still being spoken + this sentence". At rate 1.30 every D leaves more than twice that; at the slowest rate a
  covered lens may not make it, and the cost is one extra fallback vibration (2.6; nothing is said twice). None of the
  three is calibrated on a phone, and the code says so.
- What a cycle asks for is **one notice heard out**. The next cycle starts P after the previous one started.

### 2.5 How the notice is spoken

- **Path**: `SpeechManager.speak(Announcement)`, the navigation prompt's own path (QUEUE_FLUSH). Accepted means it starts
  now: **nothing is queued in front of it** (zero). Not `speakSystemNotice`: that path is QUEUE_ADD, queues behind
  everything, and its protected time starts at the request and includes the wait (plan D5-1).
- **Arbitration**: the navigation prompts' rule — only a strictly higher priority interrupts. Priorities in 2.4. While the
  lens is covered or it is too dark, Guidance makes no other prompts anyway (conflict resolution keeps SENSOR_QUALITY
  only), so what can hold them off is the unfinished prompt from just before and the top-level notices ("guidance
  interrupted / stopped"). The floor notice is LOW: any MEDIUM-or-higher prompt can cut it, and then compensation and the
  fallback take over (2.6).
- **Protected time**: each attempt's `expiresAtMs` = attempt time + D. Until then arbitration counts it as "speaking" and
  it holds off equal and lower speech; a lost completion callback holds the floor for at most D.
- **Description**: the navigation prompts' rule (`guidanceWaitsForDescription`). Covered lens and too dark (≥ HIGH) cut the
  description first; the floor (LOW) waits for it, while its deadline runs and the fallback vibration comes when it passes.
- **Retries**: refused (equal or higher speech in progress, engine not ready, waiting for a description) — offered again on
  later frames, at most every 500 ms (a refusal leaves a record in PR-0's lifecycle recording too and must not flood it).
  **Once accepted, it is not offered again within its own deadline window (accepted at + D, inclusive)**: it may still be
  playing, and the speech ledger still counts it as speaking at the very moment it expires, so offering it again would
  only be refused by itself (equal priority cannot interrupt) or say it twice. Window over and not heard out, it is
  resent once. **The fallback does not end the speech retries** (round 2, P1-2): they go on until heard out or the 2
  accepted attempts per cycle are used. So **at most 2 accepted attempts per cycle** (the first and one resend): a notice
  that was cut off gets a second chance, but in a busy mall the user does not hear half a sentence again and again. A
  state never has more than one notice being spoken.
- **Notice ids**: `vision_notice_<instance UUID>_<n>`. The speech engine is shared, so a late completion of an earlier
  screen instance's notice must never count for a new instance's (review round 1, P2-4).
- **TalkBack**: with speech on and the screen reader active, the notice is published to the screen reader and **this
  cycle counts as told** — acceptance, not evidence it was heard (the screen reader has no completion callback; this matches
  PR-1a's acceptance). Own TTS's heard-out guarantee does not extend to it. See 2.11.
- **Speech off**: nothing is said; see 2.7.

### 2.6 Heard out, compensation, and the fallback

- **Heard out** = the engine reported `onDone` for some submission of this notice (Android: the audible output has
  finished) — D1.3-3's completion rule: a fresh submission id after a `withdraw` hand-back is still the same notice, and
  an `onDone` later than a FLUSH still counts.
- **Implementation**: `SpeechManager.speak` gains an optional `onSpoken`, called once on the main thread when a submission
  gets `onDone` (matched by logical id: a handed-back submission's id is the original plus a suffix). Only callers that
  need the outcome pass it; at most 16 are registered, the oldest pushed out. **PR-0's lifecycle observer is not reused**:
  it is attached only while a debug recording runs, has one slot and reconciles per session, and PR-0 promised that no
  decision reads it; a production decision must not depend on whether a debug recording is running. Both read the same
  engine evidence (`onDone`).
- **Two things kept apart** (round 2, P1-2):
  - **heard**: own TTS heard it out, or the screen reader took it (acceptance, see 2.5). Being heard ends this cycle's
    speech retries.
  - **told** (clears the debt): heard; or a buzz standing for the notice played — the fallback, or with speech off this
    cycle's buzz. Being told by a buzz does **not** end the speech retries.
- **Compensation**: when debt time + D passes and the user has not been told — for any reason (dropped, cut off, expired,
  no completion callback, engine not ready, waiting for a description):
  1. **one fallback vibration, whatever the switches say** (neither navigation nor fault vibration is consulted; the
     motor still decides when, see 2.4); once it plays, the debt is cleared. If the notice is heard before it has played,
     the fallback is **withdrawn** (round 2, P2-3);
  2. speech keeps being retried (a notice never taken yet is offered every 500 ms until it is; one already taken is
     resent once its window is over; at most 2 accepted attempts per cycle).
  Every later cycle does the same: a new cycle starts a new debt, and a fallback comes at its deadline if the user is not
  told. So while the notice cannot be said, the fallback vibration keeps coming at the state's cycle (8 / 15 / 60 s)
  until the notice is heard out or the state recovers, and a state that keeps changing cannot put it off (2.3's debt).
  An engine that stays READY while the notice is cut off again and again, or never reports completion, takes exactly
  this path.
- **An extra fallback**: at a slow rate or with a late callback, the fallback can come while the notice is still being
  spoken; an equal-priority notice cannot interrupt itself, so nothing is said twice — the cost is one vibration.

### 2.7 Vibration settings

"Vibration alerts" becomes two settings:

| Setting | Default | Covers |
|---|---|---|
| Navigation vibration (today's switch, renamed) | on | the vibration of navigation prompts: obstacle direction, path blocked, path notices |
| Fault alert vibration (new) | on | the entry vibration of covered lens, too dark and unrecognised floor, and the covered lens' vibration each cycle |

Never subject to a switch: the fallback (2.6), speech unavailable (`SPEECH_UNAVAILABLE`), guidance interrupted and stopped
on error (`INTERRUPTED`; Q11b: stays impossible to turn off).

**An owed buzz is checked against the switches as they are now** (round 2, P2-4): as soon as a switch changes, not at the
next frame, and again every frame. The entry buzz and the covered lens' each-cycle buzz obey fault vibration: turning it
off withdraws one not played yet. With speech back on, the speech-off cycle buzz is withdrawn. The fallback ignores the
switches and is withdrawn only when the notice has been heard.

**Turning navigation vibration off stops navigation rhythms only** (round 3, P1-1): `HapticManager.cancelNavigation` stops
the motor only when what plays is a navigation rhythm; a fault rhythm that has started, and the alarms (guidance
interrupted, voice broken), play to their end — the switch does not govern them, and a cut one would not be repeated. A
fault rhythm's reservation is kept and starts when the motor is free. Only stopping Guidance stops everything and drops
the reservation (`cancel`, after `VisionFaultAlerts.reset()`).

**With speech off, fault alert vibration is locked on** (Q11a): the settings screen shows it on, not switchable, with the
reason ("Voice alerts are off, so vibration is the only way to tell you the lens is covered, it is too dark, or the floor
is not recognised"). The effective value is fault vibration ∨ speech off; the user's own choice stays stored and comes
back when speech is turned on again.

With speech off a notice cannot be said, so each cycle's buzz is the vibration itself: covered lens every 8 s, too dark
every 15 s, floor every 60 s, until recovery. This is plan D5-3's "keep falling back at the state's cadence" when speech is
off; there is no separate fallback, and no cycle buzzes twice. With the motor busy, the buzz is owed and plays once it is
free.

### 2.8 Recovery notices

- Three new strings, in `SceneEventMessageKeys` (Guidance's vocabulary) and `SceneEventStrings` (the shell's map), in both
  `values/` and `values-zh/`:
  - `event_camera_restored`: Camera is clear again / 镜头恢复正常
  - `event_light_restored`: Light is back to normal / 光线恢复正常
  - `event_ground_recognized`: Ground recognized again, path alerts are back / 已识别到地面，恢复通行判断
- LOW priority, best effort: accepted within 5 s of the recovery counts as said; not said by then, it is dropped. No
  completion tracking, no compensation, no vibration (a missed "recovered" leaves the user thinking it is still broken,
  which is the safe side).
- Said only on a real recovery: frame quality back to OK and held for 2 s; OBSTRUCTED↔TOO_DARK says none. The lens and the
  floor can recover in the same frame, so unsaid recovery notices queue and are said in order (at most one offered per
  frame). Dropped: all of them when frame quality fails again (the picture cannot be trusted, so "ground recognized" no
  longer holds either); only the floor's own when the floor fails again (the lens really did recover).
- With speech off there is no recovery notice (only the periodic vibration stops). A known limit (2.11).

### 2.9 Other alarms

- `SPEECH_UNAVAILABLE` (the engine gave up) as before: one dedicated vibration, no switch. D5's notice is refused while the
  engine is not ready, and the fallback comes at the deadline; the two rhythms differ (short-short-extra-long vs
  long-short-long) and say "cannot speak" and "cannot see".
- `STALLED` / `FAILED` / `INTERRUPTED` as before. Without new frames `VisionFaultAlerts` is not updated (frames drive it);
  the stall detector owns that failure.
- Stopping Guidance resets `VisionFaultAlerts`; no recovery is said.

### 2.10 Guidance vocabulary and trace

- `SceneEventMessageKeys` gains the three recovery keys (`all` grows with them; `SceneEventStringsTest` guards the map).
  `scripts/verify-release-strings.py` looks only at resources named `event_*`, which all three are.
- `PromptDeliveryChannels` gains `vision_alerts`. An older reader rejects the unknown channel in a new trace; within this
  repository reader and writer change together.
- The M0b schema is unchanged. D5's notices appear in PR-0's lifecycle recording under logical ids `vision_notice_<n>`,
  like system notices: speech that is not a navigation prompt. Future evaluation must count them as other speech holding
  the speaker (PR-1b's design will say so). A D5 notice published to the screen reader leaves no
  `screen_reader_published` record the way a navigation prompt does (that record is triggered by `prompt_outcome`);
  screen-reader sessions are already marked "completion undeterminable", and the gap is only noted here.

### 2.11 Known limits

- **TalkBack**: published counts as told — acceptance, not "heard out"; D5's "heard out or the fallback" guarantee holds
  for own TTS only. For screen-reader users the non-speech guarantee is the entry vibration (while fault vibration is on).
- **The debt never expires**: a short fault the user was never told about makes the next fault, however much later, fall
  back at once (2.3). Deliberately: any expiry could be got round by a fail–recover–fail rhythm.
- **A fault rhythm makes navigation rhythms wait** (accepted by the user on 2026-10-29, left to batch 2, PR-1b): it holds
  the motor for at most 940 ms, and a new navigation rhythm is refused meanwhile. With speech on only the accompanying
  buzz is lost; a prompt delivered by vibration alone waits for the next frame, its delay being what is left of the hold
  plus the time to the next frame — an estimated 3.4 s with frame intervals of at most 2.5 s, with polling and
  scheduling on top (2.4). This is the price of a fault rhythm that can neither be starved nor cut short by navigation
  rhythms. PR-1b schedules the prompt's re-offer on its own instead of waiting for a frame.
- **Speech off**: no recovery notice; the periodic vibration stopping is the only sign.
- **Screen only** (speech off, both vibrations wanted off): fault vibration is locked on, so complete silence is not
  possible (the choice made in Q11a).
- D, the recovery hold H and the retry interval are not calibrated on a phone.
- Indoors among people the floor notice is often cut off by obstacle prompts; the user then feels a fallback every 60 s
  and may hear only half a sentence. Deliberate: its priority is not raised (the plan does not ask for it, and it would
  suppress obstacle prompts).

### 2.12 JVM acceptance sequences

`VisionFaultAlertsTest` (pure logic, fake clock and outputs) includes at least:

1. Covered lens: vibration and the CRITICAL notice at once; heard out, no fallback; said and vibrated again after 8 s. Too
   dark vibrates only on entry.
2. **Engine READY but the notice never completes** (accepted, no `onSpoken`): not offered again before the deadline;
   fallback at the deadline (also with fault vibration off) and one resend; at most 2 accepted attempts per cycle; a
   fallback at each later cycle's deadline until heard out.
3. **The notice cut off by higher prompts again and again** (floor: accepted, cut off, refused from then on): fallback at the
   deadline, at most 2 accepted attempts.
4. **The state recovers during the fallback**: no more fallbacks or retries; the recovery notice offered once; a late
   `onSpoken` for the old notice ignored.
5. Hysteresis: OK for less than 2 s says no recovery and repeats no entry vibration; recovery only after 2 s.
   OBSTRUCTED↔TOO_DARK says no recovery and gives no entry vibration.
6. Speech off: nothing said; one vibration per cycle (fault vibration locked); never two in one cycle.
7. TalkBack: published counts as told; no fallback.
8. The floor track frozen during a frame-quality fault; after recovery the floor enters or recovers from its current state.
9. A frame fault drops every unsaid recovery notice; when the lens and the floor recover in one frame, both are said.
10. Retries at most every 500 ms.
11. Fault vibration off and the notice heard out in time: no vibration at all.
12. Floor waiting for a description within D: the description is not cut; the fallback comes at the deadline.
13. (Round 1, P1-1) Covered and dark alternating every 4 s, the notice never finishing (accepted and refused alike): the
    fallback still comes, never more than 9 s apart.
14. (Round 1, P1-1) Fail–recover–fail, each episode shorter than the deadline: the debt survives the recovery and the
    fallback comes in the second episode; the floor likewise.
15. A notice heard out clears the debt: a later change of kind starts its deadline afresh.
16. (Round 1, P1-2) With the real `HapticArbiter`: a CRITICAL obstacle rhythm accepted, then the floor entering in the same
    frame → the floor buzz waits for the obstacle rhythm to end; the fallback does not cut a higher rhythm either and
    plays as soon as it ends.
17. Speech off with the motor busy: this cycle's buzz is owed and plays once the motor is free.
18. (Round 1, P2-4) Two instances' notice ids differ; another instance's completion counts for nothing and the fallback
    comes.
19. (Round 2, P1-1) The review's sequence: a frame every 2 s, each with a HIGH/CRITICAL obstacle rhythm first, the floor
    unrecognised, 600 s. Speech off (cycle buzz) and speech on but never heard out (fallback): every one of the 10 cycles
    buzzes, ≤ 360 ms after the obstacle rhythm ends; all 300 obstacle rhythms accepted.
20. (Round 2, P1-2) The fallback does not end the speech retries: the floor waits for a description until 11 s; after
    the fallback at 10 s, the notice is still said at 11 s.
21. (Round 2, P1-2) Under the speech ledger's rule (still speaking at the moment it expires), a too-dark notice taken at
    0 s is resent at 6.1 s and not lost to the fallback at 6 s.
22. (Round 2, P2-3) The notice heard while the fallback waits for the motor: withdrawn without any frame, nothing buzzes
    when the motor frees.
23. (Round 2, P2-4) Fault vibration turned off while the entry buzz waits for the motor: withdrawn without any frame; the
    fallback at 10 s still buzzes.

24. (Round 3, P1-1) Speech off, the floor's rhythm starting at 0 s, navigation vibration turned off at 0.1 s: the fault
    rhythm is not stopped and still holds the motor; a navigation rhythm playing is stopped as before, and a reservation
    behind it starts at once.
25. (Round 3, P2-2) The fallback's reservation waits for the motor until 10.3 s; the frame at 10.3 s shows the floor
    recognised and brings a CRITICAL obstacle rhythm. In the view model's order (`observe` → prompt → `act`) the stale
    floor rhythm does not start, the obstacle rhythm is accepted, and nothing buzzes afterwards.

`HapticArbiterTest`, round 3: turning navigation vibration off releases a navigation rhythm, not an alarm or a started
reservation.

`HapticArbiterTest`, round 2, gains 5 cases: a reservation does not cut equal or higher and starts when it ends; it goes before any
claim made after it (at the same instant too); once started it cannot be cut, and starts at once over a strictly lower
rhythm; a strictly higher rhythm cutting in makes it wait; cancelling and replacing.

`GuidancePromptOfferTest` (round 1, P2-3): a `vision_alerts` hand-over confirms the cooldown but has `reachedUser` false and
does not update the replay history.

Also: `SpeechManager`'s `onSpoken` registry (`SpokenCallbacks`, JVM): a handed-back id matches the original, called once,
bounded at 16; `GuidanceDelivery`'s `vision_alerts` branch (confirmation, trace record); the effective setting (speech off →
locked on); `SceneEventStringsTest`; `EventGenerator.visionFaults` agreeing with `generate`, also with the switches off.

### 2.13 On the phone

A separate checklist for the user (each state once; each vibration switch once; one optional fallback case). Results do
not go into the repository.

---

## 3. What each PR does

| PR | Change | Changes what is heard/felt? | Evidence |
|---|---|---|---|
| PR-1a | `CooldownManager`'s new state + `confirm`; `ConfirmDeliveredEventUseCase`; the shell confirms on delivery; this document | no (bit-for-bit under 1.7's condition) | property test, defect cases, replay-equivalence test, group C report unchanged line for line |
| PR-D5 | `VisionFaults`, `SceneResult.visionFaults`, recovery copy, `VisionFaultAlerts`, `SpeechManager.speak(onSpoken)`, two vibration settings | yes | JVM sequences (2.12), group C report unchanged (Guidance decisions unchanged), phone checklist |

Not done here: PR-1b, PR-2, PR-3, PR-4; no det-first threshold changes; S stays sealed.

## 4. Effect on group C

- PR-1a: the exact replay of group C's 14 sessions and the `PromptPolicyBaselineTest` report must be identical to `main`
  line for line (except the commit in the report's header).
- PR-D5: Guidance's decisions do not change, so the report should be identical too; the shell is outside replay (replay
  uses the recorded revocation positions). It also means D5's notices holding the speaker are not in group C's evaluation —
  group C was recorded on the old code, which has no D5.

## 5. Review handling

### Round 1 (GPT, 2026-10-29; reviewed #34 `cfc1223`, #35 `2d81872`)

Verdict: design No-Go, PR-1a Go (no findings), PR-D5 No-Go. All four accepted.

| # | Finding | Handling |
|---|---|---|
| P1-1 | Covered↔dark changes restarted the cycle, so the fallback could be put off for ever (reproduced: alternating every 4 s, no fallback in 60 s) | Each track keeps an untold debt that no change of state clears (kind change, recovery and failing again); only being told or the fallback playing clears it, and the deadline runs from it (2.3, 2.6). Sequences 13–15 |
| P1-2 | The floor alert took the motor unconditionally with `play()`, cutting a CRITICAL obstacle rhythm accepted in the same frame | Fault rhythms go through `HapticManager.tryPlay` (the same arbitration as `trigger`); refused, the buzz is owed and offered again on later frames; "whatever the switches say" does not mean bypassing the motor (2.4). Sequences 16–17 |
| P2-3 | A `vision_alerts` hand-over made `reachedUser` true and replaced the replay history | The hand-over only confirms the cooldown; `reachedUser` is false and the replay history is not touched (2.2) |
| P2-4 | Notice ids restarted at `vision_notice_0` per instance, so a late completion of an old instance counted for a new one | Ids carry an instance UUID (2.5). Sequence 18 |
| Note | TalkBack must not be claimed as heard out | Wording now "published counts as told (acceptance)"; `Offer.SPOKEN` renamed `PUBLISHED`; the heard-out guarantee does not extend to the screen reader (2.5, 2.11) |

### Round 2 (GPT, 2026-10-29; reviewed #35 `2d81872 → b607f8a`)

Verdict: design No-Go, PR-1a Go (no findings), PR-D5 No-Go. Round 1's four fixes confirmed; a debt that never expires is
acceptable. All four accepted.

| # | Finding | Handling |
|---|---|---|
| P1-1 | An owed floor buzz could starve for ever: each frame's navigation rhythm took the motor first, the LOW alert was refused, and its next try waited for the next frame (0 in 600 s, with the motor busy only 33 s) | Motor reservation: when refused, the motor is reserved; the fault rhythm starts as the playing rhythm ends, ahead of later claims, by a timer, not a frame; once started it cannot be cut. Latest delivery is bounded (2.4). Sequence 19, 5 `HapticArbiterTest` cases |
| P1-2 | Once the fallback played, `told = true` ended the speech retries | "Heard" (ends the speech retries) and "told" (clears the debt) kept apart; the fallback only clears the debt (2.5, 2.6). The resend window is inclusive, as the ledger is. Sequences 20–21 |
| P2-3 | A fallback still waiting for the motor was not withdrawn when the notice was heard | Being heard withdraws a fallback not played yet, and its reservation; the entry buzz is unaffected (2.6). Sequence 22 |
| P2-4 | With fault vibration turned off, an entry buzz that had not played yet was still played | Owed buzzes are checked against the current switches: on every change and every frame; the fallback ignores them (2.7). Sequence 23 |

### Round 3 (GPT, 2026-10-29; reviewed #35 `b607f8a → 8132e5d`)

Verdict: design No-Go, PR-1a Go (no findings), PR-D5 No-Go. Round 2's four fixes pass their tests; nothing new in the
reservation's precedence at one instant, its postponement behind a higher rhythm, the production callback's synchronous
re-entry, or the stop path's order. All three accepted.

| # | Finding | Handling |
|---|---|---|
| P1-1 | Turning navigation vibration off went through the global `cancel()`, cutting a fault rhythm that had started; its debt was cleared, so nothing re-asked, and the next try was 60 s later | New `cancelNavigation`: stops the motor only when a navigation rhythm plays; a started fault rhythm or alarm plays to its end, and a reservation is kept; the global `cancel` is for stopping Guidance only (2.7). Sequence 24, 1 `HapticArbiterTest` case |
| P2-2 | On the frame showing recovery, the navigation prompt asked for the motor first and started a stale floor reservation, refusing a CRITICAL obstacle rhythm | Each frame in two steps: `observe` before the navigation prompt (read the states, withdraw stale reservations), `act` after it (speak, ask for the motor) (2.2). Sequence 25 |
| P2-3 | 940 ms bounds the motor's protection, not a navigation prompt's delay (a vibration-only prompt waits for the next frame) | Wording corrected: delay = what is left of the hold + time to the next frame, about 3.4 s at worst; with speech on only the accompanying buzz is lost; keeping it under 940 ms needs the re-offer scheduled on its own, not in this batch (2.4, 2.11) |

### Round 4 (GPT, 2026-10-29; reviewed #35 `8132e5d → 0781565`)

Verdict: **design Go, PR-1a Go, PR-D5 Go**. Round 3's three fixes confirmed; no new blocking code issue. Protecting the
alarms together with started fault rhythms is reasonable; `observe → onSceneEvents → act` fixes the stale reservation on
the recovery frame.

| # | Finding | Handling |
|---|---|---|
| P2 (documentation, non-blocking) | "About 3.4 s at worst" is not a hard bound: 2.5 s is the stall detector's timeout, checked every 500 ms, and does not limit the frame interval (the probe reached 3.74 s with no stall alarm) | Now a conditional budget: "an estimated 3.4 s with frame intervals of at most 2.5 s, with polling and scheduling on top" (2.4, 2.11). The frame-driven re-offer stays in this batch; the user accepted this delay on 2026-10-29, and scheduling it on its own is left to batch 2, PR-1b |
| Note | "Cannot be cut" means by ordinary navigation claims; the unconditional `play()` of the alarms and the settings preview can still take the motor | Stated in 2.4 |
