**English** | [简体中文](field-capture.zh-CN.md)

# Field capture and the model-regression record

What a debug build records about a Guidance session, in what format, and how the PC tools and the
JVM replay read it back. This document describes the code as it is; where the two disagree, the code
is the fact and this document gets corrected.

The format and the JVM reader live in `sailens-guidance` (`…trace.capture`, `…trace.regression`);
the writer in the shell's debug source set (`…shell.capture`); the PC tools in `scripts/capture/`
(see its README). Release builds contain no capture code.

## 1. What a capture is, and privacy

A capture is one directory per Guidance session under the app's `files/captures/`, named after the
trace session id. A **field evidence** capture holds sampled frames, motion sensors and camera facts;
a **timing-sync** capture is a short burst for measuring frame/sensor alignment; a
**model-regression** capture adds what exact replay of the decision path needs (§7).

**Privacy.** Captures contain faces and places. They are excluded from Auto Backup
(`backup_rules.xml`) and from cloud backup and device transfer (`data_extraction_rules.xml`); export
ZIPs live in `cacheDir`, which is not backed up. Nothing leaves the device except by explicit export.
Captures never enter this repository or CI.

## 2. Capture format

`CaptureSchema`. One directory per session:

| File | Content |
|---|---|
| `manifest.json` | `CaptureManifest`: schema major/minor, session id, mode (`field_evidence` / `timing_sync` / `model_regression`), start wall and elapsed-realtime time, app version, build provenance (`gitSha`: the commit, "-dirty" if the tree differed; `modelArtifacts`: SHA-256 of every packaged model — the weights are not in git), device, SDK, hardware profile, camera characteristics (filled in on the first frame if the camera bound after the session started), sensors that registered (`sensorsAvailable`), `complete`, `failureReason`, `pinned`, `exportedAtWallMs`, capture counters |
| `frames.jsonl` + `frames/` | `FrameRecord` per stored frame: seq, camera timestamp, source-side receipt time, source size and rotation, CameraX sensor-to-buffer transform (active array → source buffer) and crop rect, encoding (`jpeg` / `luma8`), file, stored size |
| `sensors.jsonl` | `SensorRecord`: sensor (`gravity`, `game_rotation_vector`, `gyroscope`), event timestamp, accuracy, values |
| `anchors.jsonl` | `ClockAnchorRecord`: (wall ms, elapsed-realtime ns) at start, every 30–60 s, and at the end |
| `markers.jsonl` | `MarkerRecord`: "missed alert", wall and elapsed time (the exact reference), last *stored* frame seq (its image exists), source |

- `manifest.json` is written with `complete = false` when the directory is created and rewritten
  atomically (temp file + rename) with `complete = true` after a normal end. A killed app, a crash, an
  unplugged USB or a full disk leaves `complete = false`, which readers report.
- Every JSONL line has a `type`; a torn last line is skipped with a warning.
- **Versioning.** `schemaMajor` and `schemaMinor` are required. The reader reads them from the raw
  JSON **before** decoding the body, refuses a missing version and an unknown major version (as
  "unsupported", never as "unreadable"), then decodes. A **minor** version may only add: optional or
  defaulted manifest fields, record types, fields of existing records, and values of open string
  fields (the capture mode is a string for this reason, so an older reader carries a newer mode
  through with a warning). It may not remove or rename a required field or change a field's meaning or
  type; that is a major version. Closed enums inside records are not extended in a minor version.
  Minor versions have added build provenance (1.2), the model-regression record (1.3), recorded
  geometry (1.4) and the speech lifecycle (1.5).
- Sensor cadence is computed from event timestamps; `SENSOR_DELAY_GAME` is only the requested rate.
- **Counters** are separate from Guidance's dropped frames, so a gap can be explained. For a complete
  capture they account for everything the engine received: `framesOffered` = `framesEncoded` +
  `framesDroppedByEncoder`; `sensorEvents` (persisted) + `sensorEventsDropped` (capture queue full) =
  samples received. Frames are lost at three places:
  - **before the analyzer** (the camera, or CameraX keeping only the latest image): such a frame gets
    no sequence number, is counted nowhere and shows only as a longer camera-timestamp interval;
  - **in capture's own mailbox** (converted and offered, but capture had not taken the previous one):
    `framesMissedBySubscriber`, from gaps in the received sequence numbers, counted only in
    `timing_sync`, where capture wants every frame (field evidence samples, and the analyzer also
    numbers frames it converts for other subscribers, so its gaps are by design);
  - **in capture's encode queue**: `framesDroppedByEncoder`.

  For a burst the tools report the **known** loss (the two counters) on its own, never replaced by an
  estimate, and only *estimate* loss before the analyzer: camera-timestamp slots missing between
  stored frames that no sequence gap explains, with the analyzer period taken as interval ÷ sequence
  step, so steady loss after the analyzer cannot hide in the cadence. Steady loss before the analyzer
  can, so the period is checked against the camera's rate. The overall fraction is known + estimated
  and never lower than the known loss. An **incomplete** capture may hold unaccounted records at the
  failure boundary and is evidence of lower standing.

## 3. Timing and camera facts

- **Frame timing is taken at the source**: `ImageFrameAnalyzer.analyze()` stamps
  `receivedElapsedRealtimeNanos` before any demand check, conversion or queueing, and the camera's own
  `ImageFrame.timestamp` keeps its meaning. The capture records both. The trace uses wall-clock ms; the
  anchors put both on one timeline, and periodic anchors reveal a wall clock changed mid-session.
- **Camera facts**: `CameraCharacteristicsProvider.currentSnapshot()` in `sailens-camera` returns a
  plain-value `CameraCharacteristicsSnapshot`; Camera2 interop stays inside `sailens-camera`. The
  snapshot is read on every bind and cleared on unbind, never cached across bindings (some logical
  cameras change `SENSOR_ORIENTATION` with device state). The analysis size is taken from the first
  frame, not from the requested resolution. Per frame, CameraX's sensor-to-buffer matrix and crop rect
  are recorded too: the active-array-to-buffer mapping cannot be rebuilt reliably from the active
  array, buffer size and rotation alone, and the geometry needs it to map intrinsics
  ([geometry.md](geometry.md) §2).
- **Timing contract.** The raw camera timestamp is the canonical frame time, with **no per-device
  correction** (no calibration table, nothing hardcoded per phone), and frame content is taken to lie
  within **about 20 ms** of it. The basis, from timing-sync bursts with Guidance running:

  | | SM8850 — OnePlus CPH2747, SDK 36 | SM8450 — Samsung SM-S9010 (S22), SDK 36 |
  |---|---|---|
  | Camera timestamp source | `REALTIME` (all cameras) | `REALTIME` |
  | Back camera | Logical multi-camera (ultrawide 2.31 mm, main 5.59 mm, tele 12.19 mm); recorded intrinsics are the main camera's | Logical multi-camera; recorded intrinsics are the 5.4 mm main camera's |
  | Physical-camera switching | None seen (close-up at a few cm, dim room) | None seen (close-up at 5–10 cm, dim room) |
  | Analysis stream | 1280×720 (16:9 crop of the 4:3 array), 30 fps | 1280×720, 30 fps |
  | Lens distortion reported | All zeros | 0.073, −0.107, 0.047, 0, 0 |
  | Gyroscope rate (`SENSOR_DELAY_GAME`) | 46.8 Hz | 49.7 Hz |
  | Frame → gyro offset, camera clock (gyro ≈ frame + δ) | +2.3 ms median, −5.2…+8.6, sd 4.8 (11 bursts) | +12.9 ms median, +8.2…+17.2, sd 3.5 (8 bursts) |
  | Camera time → app receipt | 60.8 ms, ±1 ms | 67.4 ms |

  Both devices are `REALTIME`, and receipt adds 60–70 ms of delivery latency, so camera timestamps are
  used. The camera clock itself is stable (camera-to-receipt within ±1 ms), but where the image content
  sits relative to its start-of-exposure timestamp varies by 9–14 ms between bursts on one phone,
  likely with exposure time, which is not recorded. 20 ms at a walking turn rate of 0.2–0.5 rad/s is
  0.2–0.6°. A tighter budget would first need per-frame `SENSOR_EXPOSURE_TIME` /
  `SENSOR_ROLLING_SHUTTER_SKEW` (the same capture-result hook would also give
  `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`). Physical-camera switching was not seen, so static
  intrinsics stand.

## 4. Session wiring and failure isolation

`GuidanceModule` builds the base `TraceService` (file or no-op) and applies an optional
`TraceServiceDecorator` if one is bound; the debug shell binds one that adds capture, release binds
none. Rules:

- capture failure never fails Guidance: a full disk, a sensor that will not register or a crashed
  encoder marks the capture failed / incomplete, and nothing is thrown out of `TraceService` calls;
- capture start and stop never block a `TraceService` call;
- the capture mode is read once, at session start;
- field capture exists only in debug builds with tracing enabled (`TraceRuntimeConfig.enabled`).

## 5. Frame writer, timing-sync burst and the alignment clock contract

**Field evidence.** `FrameSource.frames(minIntervalMs = 200)` → downscale-copy to NV21 →
`releaseFrame` at once → a bounded queue (capacity 1–2, the oldest pending dropped) → background JPEG
encode (640 px long side, quality 80, pixels not rotated; `rotationDegrees` recorded). The sampler
takes the first frame arriving at least 200 ms after the last; at 30 fps six frames are 199.96 ms,
right on that edge, so it stores every 6th or 7th frame depending on arrival jitter: 4.3–5 Hz.

**Timing-sync burst.** Mode `timing_sync`: about 15 s at camera rate, small luma frames (`luma8`, Y
plane area-averaged to 400 px long side — averaging, not point sampling, so fine texture does not
alias into false motion), exact per-frame timestamps, gyroscope at `SENSOR_DELAY_GAME`. Used only to
measure frame/sensor alignment; never a performance baseline. Armed once on the field capture page,
independent of the capture switch, and used up by the next Guidance start (in memory only, so a
killed app forgets it). It takes every frame the source delivers, queues up to 4, and ends itself
after 15 s as a complete capture while Guidance continues; capture start and end are serialised, so
the two ends cannot interleave. Files are `frames/NNNNNN.y`. A capture — burst or field evidence,
ended or failed — stays active until it is finalised: `isCapturing` turns false only after a normal
end has written the END anchor and the complete manifest, or a failure has made its attempt to write
the incomplete manifest, so the capture list never reads a half-written manifest.

**Alignment** (`scripts/capture/timing_align.py`): phase-correlated image motion per frame pair
against gyroscope x/y rotation over the same interval, lag by best correlation, with the spread over
four parts of the burst as the error bar. On synthetic bursts it is within about 1.5 ms of the truth;
that is numerical accuracy only, not a device floor.

**Clock contract.** Camera timestamps are compared with the gyroscope **only when the camera's
timestamp source is `REALTIME`**, which Android defines as sharing `SensorEvent`'s timebase. `UNKNOWN`
is monotonic with an unspecified origin, and an unreported source is no better: the camera result is
then not measured (`cameraComparable = false`), must never qualify the camera clock however good a
correlation looks, and the source receipt time is the authoritative measurement. A burst is
**usable** only if it is complete, capture dropped no sensor samples, the phone really turned (median
gyro rate across the optical axis ≥ 0.15 rad/s: walking with the phone steady measured 0.01–0.09,
turning in place 0.20–0.24), the image-motion/gyro correlation on the authoritative clock is at least
0.5 (a negative control with real motion unrelated to the gyro gives about 0.3), all four parts could
be checked and the offset is not at the search edge. Disagreement between the parts and heavy frame
loss are warnings. A device result is per device **and build** (the manifest's `gitSha` and
`modelArtifacts`; bursts are never pooled across builds, and captures without a SHA give no result):
the median offset over at least three usable bursts, with their range and standard deviation as the
uncertainty the geometry has to design for.

## 6. Controls (debug builds)

| Need | Design |
|---|---|
| Mode switch | Settings → Diagnostics → Field capture: on / off, plus "arm the timing-sync burst for the next Guidance start" (one-shot). The mode is read when a session starts, so the switch takes effect at the next session and is locked while a capture is recording |
| Start / stop | Follows the Guidance session while the mode is on; no separate button |
| Keep, delete | Capture list: start time, size, status (recording / complete / incomplete with reason), kept, exported, frames and markers. Keep, delete and export are refused while any capture is recording (the tool must not disturb the evidence it is collecting); delete asks for confirmation; a refused or failed action is reported |
| Export | Finished sessions only. A ZIP built into `cacheDir/capture_exports/` (free space checked first) off the writer thread, with the session protected from retention and delete meanwhile. Capture and export never overlap: while a ZIP is being built, starting Guidance is refused ("finish exporting first", debug `GuidanceStartGate`), and a capture that starts anyway ends at once as incomplete with `failureReason = export_in_progress`. A ZIP whose session cannot be marked exported is discarded and reported. Shared via FileProvider `<cache-path>`; `files/captures/` itself is never exposed. Exports older than a day are cleared at app start and when the list opens. `adb` + `run-as` is the bulk fallback (`scripts/capture/README.md`) |
| Viewing frames around a prompt | On the PC: `scripts/capture/contact_sheet.py` renders ±N s around a `prompt_outcome` (centred on the offering frame: trace `sourceSequenceNumber` → `frameTimestamp` = capture `sensorTimestampNanos`) or a marker |
| Storage | Field evidence about 0.5–0.75 GB an hour indoors and 1.15–1.27 GB outdoors (SM8850), 0.73 on SM8450, plus about 41 MB per timing burst; `scripts/capture/capture_stats.py` measures rate, cadence, gaps, drops, counter balance and wall-clock jumps |
| Retention | Runs at debug app start, before a capture starts, when the capture list opens, and every 30 s during a capture. A session older than 7 days, not exported or pinned, is deleted at the next of these. The total cap (10 GB by default; 2 / 5 / 10 selectable) is enforced during capture: older unpinned sessions go first, and if the active capture alone would exceed it, it ends as incomplete with `failureReason = storage_limit_reached`. Capture stops, or refuses to start, below about 2 GB free |
| "Missed alert" marker | Volume Down while capture is active: only `ACTION_DOWN` with `repeatCount == 0` counts, the key is consumed only then, the time, session and last stored frame are fixed when the press is observed (a busy writer delays the write, not the event), and a short vibration confirms only once the marker was written. Plus a large on-screen button. Works eyes-free in a foreground session under TalkBack (checked on SM8850 and SM8450); no promise for a locked screen or background |

## 7. The model-regression record

A `model_regression` capture is field evidence plus a record that lets the decision path be replayed
**exactly** on the JVM, so a behaviour change can be compared frame by frame (`RegressionSchema`,
schema 1.3 and later, in the same directory).

**Replay boundary.** Everything the JVM cannot compute the same way is recorded at its boundary;
everything else — `ProcessFrameUseCase` → `AnalyzeSceneUseCase` → frame-quality debouncing →
`DecideEventsUseCase` — runs as real code with fakes at the boundary:

| Boundary | Recorded per frame | At replay |
|---|---|---|
| sem model + native fused pass | class map (`classmaps.bin`, zlib, one byte per class id); native scalars; CRC32 of the passable / obstacle masks; ran / cached / failed | masks rebuilt from the class map through `NavigationSemantics`, checked against the CRCs; scalars used as recorded (native and Kotlin float paths need not agree) |
| det model + native decode/NMS | the detections (class, label, confidence, box), when det ran | served by a fake `ObstacleProvider` |
| native connectivity | `ConnectivityStats` + CRC32 of the input mask | recorded stats when the replayed input has the same CRC (the Kotlin fallback is known to diverge from the kernel); otherwise upstream changed: computed in Kotlin and the frame marked approximate |
| injected clocks (perception, ground, decide) | every value returned, per component, in call order | served back in order; exact mode asserts each list is used up |
| frame quality | luma mean, luma std, sharpness (recorded only), per-frame classification | `FrameQualityAnalyzer.analyzeMeasured` continues from the measurement |
| `isStationary` | the value the decision step read | fake source |
| camera geometry (schema 1.4) | the `CameraGeometry` the frame was processed with | used as recorded ([geometry.md](geometry.md) §5) |
| revocations (UI thread) | event id + number of decisions made before it, read under the cooldown lock | applied before that decision; ids mapped to replayed events by position |
| configs | `PerceptionConfig` / `AnalysisConfig` in full, taxonomy, depth source | decoded leniently |
| decisions | every decided event, all fields | the expected output; compared without the random id |

Replay is exact only over an unbroken run of frames: the first frame the record loses
(`RegressionStats.firstLostSeq`) ends it, because smoothers and trackers cannot be resumed.
Transcendental functions may differ by an ulp between ART and HotSpot. Confirmations of delivery are
not recorded: they change no cooldown decision, so replay reproduces revocations alone.

**Event windows.** Full analysis-resolution frames around delivered prompts (1.0 s before to 0.5 s
after) and markers (2 s before), at 10 fps, JPEG — for re-running models offline on the same pixels.
Frames are copied into a ring before Guidance returns them (planes only, 2.5 s at 10 Hz, about 46 MB
on SM8850) and encoded only when a trigger fires. Revoked prompts do not trigger: at about 14 a minute
they would multiply the storage.

**Storage** (SM8850, outdoors): field evidence about 1.2 GB an hour, class maps 0.6–0.8 (1.0 at p90),
per-frame JSONL about 0.15, event windows about 1.2: about 3.2–3.6 GB an hour outdoors, half indoors.

**Segmentation.** One scene is one Guidance session; the manifest's `group` (outdoor / indoor) and
`sceneLabel` name it. There is no visual cue and no inference from the images. The in-app text asks to
record on SM8850 only; nothing in the code enforces it.

**Checks.** In CI, `RegressionReplayRoundTripTest` runs a synthetic session through the real
`StartSceneAnalysisUseCase` with recording on and off, requires identical decisions, and replays the
record exactly. Recording costs were measured on SM8850 by alternating recording off and on on the
same scene with the phone on a stand.

## 8. Replaying a capture

Locally only:

```
gradlew :sailens-guidance:testDebugUnitTest --tests "*CaptureReplayTest*" -PreplayCapture=<capture dir or a folder of them>
```

One report per capture goes to `sailens-guidance/build/replay/<sessionId>.txt`: frames replayed, the
first mismatch (frame, what, expected, actual), approximate frames, and where a lost frame stopped it.
The same property feeds the capture evaluations (`GroundContactCaptureEvaluationTest`,
`PromptPolicyBaselineTest`). A capture under a directory holding a `SEALED` file belongs to a sealed
acceptance set and is skipped unless `-PunsealCapture` is given (the PC tools take `--unseal`).

## 9. The speech lifecycle record

Schema 1.5. A debug capture also records what became of every utterance handed to the speech engine:
`RegressionUtteranceRecord` lines in `regression.jsonl` (submitted / refused / started / done / stopped
/ error / requeued / requeue_failed / superseded / cancelled / unknown_terminal, and
screen_reader_published), timed on the thread that observed them, with the attempt id, the logical id
(a Guidance prompt's event id) and the engine generation. They have their own queue and drop counter
(`RegressionStats.utteranceEventsDropped`), so they never cost the record a frame; replay ignores them.
`RegressionManifest.utteranceLifecycle` is false in every older record, which therefore has no
lifecycle evidence — not zero failures. `PromptPolicyBaselineTest` reads them
([experiments/detfirst-pr0-baseline-2026-10.md](experiments/detfirst-pr0-baseline-2026-10.md)).

## 10. Labelling prompts

Build a sheet of the prompts the user actually received:

```bash
py scripts/export_prompt_labels.py <segment folder>/trace_*.jsonl -o <segment>.csv
```

Each row is a delivered `prompt_outcome` from the trace. Revoked prompts were never heard, so they
cannot be false alarms and are left out by default (`--include-revoked` lists them). `delivered_via`
says how a prompt reached the user (`speech`, `screen_reader`, `haptics`, `status_card`): a prompt
with only `haptics` was felt, not heard, even with voice on. `output_settings` is context only. Each
row also carries that frame's ground-recognition state, whether it was judged blocked, the tracked
obstacles and the dominant classes.

Compare with the frames around the prompt (`contact_sheet.py <capture> --trace <trace>.jsonl
--prompts --window 2`; markers with `--markers --window 3`). Field evidence stores only a sample of
frames, so the frame that raised a prompt is often not stored: the nearest stored frame is
highlighted, up to about 0.12 s away. Fill in `label`:

| Label | Meaning |
|---|---|
| `tp` | True, and it matters for walking |
| `fp` | Nothing like that ahead, or there is but it is not worth a prompt (e.g. a distant person off the route) |
| `wrong` | Something is there, but the prompt got it wrong (direction or category) |
| `late` | True but too late (already at it when announced) |
| `unsure` | Cannot tell from the frames; say why in `note` |

Misses go in a second sheet, one row per miss: segment, rough time (seconds into the segment), what was
missed, estimated distance, note. Every "missed alert" marker gets a row (`accidental` if pressed by
mistake). A miss is an obstacle within about 2 m on the route, a path that really is blocked, or a
step or kerb, with no prompt before it was reached.
