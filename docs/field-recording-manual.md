**English** | [简体中文](field-recording-manual.zh-CN.md)

# Field Recording Manual (stage 2)

> Status: **draft**. The recording tool is M0 field capture (see
> [`local-navigation-implementation.md`](local-navigation-implementation.md) §4), which is still being
> built; steps marked **[M0 TBD]** are filled in once its UI and export are settled. Everything else can
> be used to prepare now. **Do not start the real recording before M0 is ready**: trace-only data, with
> no frames or sensors, can later be used neither for threshold calibration nor for geometry or replay,
> and would have to be recorded again.

## 1. What this recording is for

One recording serves four purposes, so record every segment as described here:

| Purpose | Needs |
|---|---|
| **False-alarm / miss baseline** (every stage 3 change is compared against it) | every prompt the user actually received, and the frames around it |
| **#5 ground-gate threshold calibration** | `unrecognizedGroundRatio` indoors, on sidewalks, at grass edges, facing walls |
| **Ground geometry calibration** (M3) | frames + gravity/rotation/gyro + camera intrinsics, time-aligned |
| **Replay / simulation regression** (M2) | as above, plus full frames around events (M0 "model regression recording") |

M0 captures in two modes, chosen per segment **[M0 TBD: where the switch is]**:

- **Field evidence capture**: 5 Hz JPEG at 640 px on the long side + sensors. For labelling and
  calibration; not an exact replay of perception. **This manual assumes it by default.**
- **Model regression recording**: per-frame perception output + full frames in a window around events,
  for exact replay. Turn it on only for scenes marked "regression" in section 5.

## 2. Safety and privacy (read first)

- **The person recording is sighted and watches the path.** The app is the thing under test, not a
  guide; confirm "clear" yourself.
- **At crossings, attention stays on the road.** Keep recording if you like, but never slow down or
  change how you cross for the recording; stop if it is not safe.
- Captures contain **faces and places**. They stay on the phone and your own computer; **never commit
  them to a repository or upload them**. Delete segments you no longer need in the debug UI
  **[M0 TBD: delete entry]**.
- Record indoors only where you are allowed to (your home, areas your workplace permits).

## 3. Pre-recording checklist (every outing)

1. **The build is the baseline.** Recordings must come from the frozen baseline commit (a `baseline/...`
   tag; fill in here once tagged). Every stage 3 change is compared against this data; mixing builds
   breaks the comparison.
2. **Run the full device test suite first, then install the recording build.**
   `connectedDebugAndroidTest` **uninstalls** the app when it finishes, so the order is:
   ```bash
   ./gradlew.bat --no-daemon connectedDebugAndroidTest
   ./gradlew.bat --no-daemon :app:installDebug
   ```
   With local weights in the A working copy, the zero-model tests fail; that is known. Note it and carry
   on.
3. **Open the right app.** The launcher may show both "Sailens" (the official build, repo B) and
   **"Sailens Reference"** (repo A, `com.sailens.reference`). Record with the **Sailens Reference** debug
   build (only debug builds capture and trace).
4. **Settings:**
   - Voice alerts on; vibration alerts on (so a label can tell heard from felt).
   - Perception profile: **Standard** (sem + det).
   - TalkBack off (unless the segment tests the screen reader).
   - Debug panel on. It shows "Ground: … (unrecognised N%)", so the #5 gate state is visible live.
5. Battery above 60%, free storage above 5 GB **[M0 TBD: adjust to the measured data rate]**. The phone
   heats up; rest about every 20 minutes, since thermal throttling makes the data unrepresentative.
6. Bring a **scene card** (section 7) and fill in one row per segment.

## 4. How to hold the phone

Keep the hold the same across the whole recording, or the geometry calibration is useless.

- **Portrait, at the chest**, camera facing forward and **tilted slightly down**, about 20–30° (the
  bottom of the frame is roughly the ground 1–2 m ahead of your feet).
- About 1.2–1.4 m above the ground; measure your chest height once and write it on the scene card.
- Use a chest phone mount if you have one; otherwise hold it steady with both hands. **Do not change the
  hold within a segment.**
- Only the **M** scenes in section 5 change the hold on purpose (tilted up, pointing down), to test the
  gate's edges.

## 5. Scene list

Record each class for "count × duration". About 90–120 minutes in total, spread over several days.
**★** marks scenes needed for #5 threshold calibration; **regression** marks scenes where model
regression recording is also turned on.

| Class | Scene | Count × duration | Notes |
|---|---|---|---|
| A | Open sidewalk, few people | 3 × 3 min | The "quiet" part of the baseline; false alarms should be rare |
| B | Sidewalk with static obstacles (poles, trees, parked bikes, bins, benches) | 3 × 3 min | Pass obstacles at the side, and also walk straight at one and step around |
| C | Crowded (shopping street, station entrance) | 2 × 5 min | Once at peak, once off-peak |
| D | Crossings: approach, wait for the light, cross | 4 crossings | Stand still at least 30 s while waiting (tests cooldown while stationary) |
| E | Kerbs, steps, ramps: approach slowly and stop | 2 each | **regression**; today's model does not see steps; this is material for the geometry layer |
| F ★ | Grass edges, park paths, dirt paths | 2 × 3 min | Terrain is not passable; check whether "path blocked" repeats |
| G ★ | Indoors: corridor, hall, home | 3 × 3 min | Good light; the #5 gate should engage and say "Can't see the ground…" |
| H ★ | Indoor/outdoor transitions: entering, leaving | 4 each | Check the gate's entry (1.5 s) and exit (1 s) feel right |
| I ★ | Facing a wall / building facade: approach slowly from 5 m, stop 1 m away | 2 outdoors, 2 indoors | **regression**; safety-critical: "path blocked" must be announced while the wall is still distant; watch the gate as it gets close |
| J | Car park, cars parked at the kerb | 2 × 3 min | Vehicle prompts are CRITICAL; watch for false alarms |
| K | Lighting: street lights at night, strong backlight | 2 × 3 min each | Night triggers "too dark to see"; record as is |
| L | Standing still (waiting, talking) | 2 × 2 min | Cooldown lengthens when stationary; watch for repeats |
| M ★ | Hold edges: phone level / pointed at the sky, pointed at your feet | 1 × 1 min each | How the gate behaves when no ground is in view |

## 6. Recording one segment

1. Go to the start; fill in the first half of the scene-card row (class, place type, light).
2. Start capture **[M0 TBD: how]**.
3. Tap "Start guidance" in the app.
4. **Sync mark: cover the camera fully with your palm for 3 s**, until you hear "Camera is covered", then
   uncover. It leaves an `event_camera_blocked` in the trace and dark frames in the capture — the
   segment's start, afterwards.
5. Walk the scene. **If you notice a miss** (an obvious hazard with no prompt), immediately
   **[M0 TBD: marker button, writes a timestamped marker]**; until M0 has one, note roughly which minute
   and what, in the scene card's notes.
6. Before finishing, cover the camera for 3 s again as the end mark.
7. Tap "Stop guidance", then stop capture **[M0 TBD: how]**.
8. Complete the scene-card row (duration, anything unusual).

Do not switch apps or lock the screen during a segment; if it gets interrupted, stop and start a new
segment.

## 7. Scene card

One row per segment, on paper or in a notes app, typed up afterwards:

| Segment | Date/time | Class | Place type (no exact address) | Light / weather | Hold / chest height | Duration | Misses | Other |
|---|---|---|---|---|---|---|---|---|
| 01 | 2026-10-02 10:15 | B | Residential sidewalk | Sunny | Chest / 1.30 m | 3′10″ | ~1′40″ low post on the left not announced | |

## 8. Export

- **Trace**: Settings → Diagnostics → Trace reports → pick the session → "Share JSONL", to your computer.
  Or with adb: `files/traces/trace_<sessionId>.jsonl` (debug builds: `adb shell run-as
  com.sailens.reference`).
- **Capture** (frames, sensors, intrinsics): **[M0 TBD: export]**.
- Keep each segment's trace and capture in one folder named after the scene-card segment number.

## 9. Labelling

### 9.1 Build the sheet

```bash
py scripts/export_prompt_labels.py <segment folder>/trace_*.jsonl -o <segment>.csv
```

Each row is a prompt the user **actually received** (a delivered `prompt_outcome` in the trace). Revoked
prompts were never heard, so they cannot be false alarms and are left out by default; add
`--include-revoked` to see them. Each row also carries that frame's ground-recognition state, whether it
was judged blocked, the tracked obstacles and the dominant classes.

### 9.2 Label each row

Compare with the frames a few seconds around the prompt **[M0 TBD: how to view frames by time]**, and
fill in `label`:

| Label | Meaning |
|---|---|
| `tp` | True, and it matters for walking |
| `fp` | Nothing like that ahead, or there is but it is not worth a prompt (e.g. a distant person off the route) |
| `wrong` | Something is there, but the prompt got it wrong (direction or category) |
| `late` | True but too late (already at it when announced) |
| `unsure` | Cannot tell from the frames; say why in `note` |

### 9.3 Misses

Keep a second sheet, `<segment>_misses.csv`, one row per miss: `segment, rough time (seconds into the
segment), what was missed, estimated distance, note`. Misses come from field marks (step 5) and from
reviewing the frames. A miss is: an obstacle within about 2 m on the route, a path that really is
blocked, or a step or kerb — with no prompt before you reached it.

### 9.4 Label consistency

Labelling is partly subjective. After the first 3 segments, label the same 3 again a day later. For any
label class where the two passes differ by more than 10%, write the deciding rule into this section
before continuing.

## 10. How this data is used (for later development)

- **Baseline metrics**: per prompt category, `fp / delivered` and the miss count — the reference for
  stage 3. The script comes with stage 3.
- **#5 thresholds**: the `unrecognizedGroundRatio` distribution in classes F, G, H, I and M sets the
  entry/exit thresholds and the confirmation time; A and B confirm nothing triggers outdoors.
- **Geometry / replay**: consumed by the M0 reader; see the implementation doc §4.
