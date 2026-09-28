**English** | [简体中文](field-recording-manual.zh-CN.md)

# Field Recording Manual (stage 2)

> The recording tool is M0a field capture (see
> [`local-navigation-implementation.md`](local-navigation-implementation.md) §4), implemented in #12–#15
> and measured on SM8850 (OnePlus CPH2747) and SM8450 (Samsung S22) on 2026-09-27/28 (results:
> implementation doc §4). **Record only with the baseline build** (section 3, item 1).

## 1. What this recording is for

This round records **M0a field evidence**: sampled frames (4.3–5 Hz), motion sensors and camera facts,
plus the trace of every prompt. It serves:

| Purpose | Needs |
|---|---|
| **False-alarm / miss baseline** (every stage 3 change is compared against it) | every prompt the user actually received, and the frames around it |
| **#5 ground-gate threshold calibration** | `unrecognizedGroundRatio` indoors, on sidewalks, at grass edges, facing walls |
| **Ground geometry evidence** (M3) | frames + gravity/rotation/gyro + camera intrinsics, time-aligned |
| **Scenario design for the simulator** (M2), and choosing the scenes M0b must re-record | the frames and prompts of each scene |

It does **not** give exact replay of perception. Field evidence stores a sample of frames, not the
model outputs Guidance acted on, so a decision cannot be re-run from it. That is **M0b** (model
regression recording), which is not built yet: scenes marked **regression** in section 5 must be
recorded again once M0b lands. Do not treat this round as exact-replay evidence.

Capture is controlled on **Settings → Diagnostics → Field capture** (debug builds only):

- **Field evidence capture** (the "Capture during Guidance" switch): 640 px JPEG at 4.3–5 Hz + gravity,
  rotation and gyroscope (~47 Hz) + camera intrinsics, for the whole Guidance session. **This manual
  assumes it throughout.**
- **Timing-sync burst** (one-shot, armed on the same page): about 15 s of small frames at full camera
  rate, to measure frame-to-gyroscope timing once per device and build. See section 6a.

## 2. Safety and privacy (read first)

- **The person recording is sighted and watches the path.** The app is the thing under test, not a
  guide; confirm "clear" yourself.
- **At crossings, attention stays on the road.** Keep recording if you like, but never slow down or
  change how you cross for the recording; stop if it is not safe.
- Captures contain **faces and places**. They stay on the phone and your own computer; **never commit
  them to a repository or upload them**. Delete segments you no longer need in the capture list.
- **Captures are cleaned up automatically**, in two ways:
  - **Age**: a session is deleted 7 days after recording unless it is **Kept** or has been exported.
  - **Size**: once the total passes 2 GB (only about **3 hours** of field evidence), the oldest sessions
    that are **not Kept** are deleted — **exported ones included**.

  "Exported" only means the ZIP was made and the share sheet opened, not that your computer received
  it. So: **tap Keep on every session you want**, export it the same day, check the ZIP is actually on
  your computer, and only then tap "Stop keeping" or Delete on the phone.
- Record indoors only where you are allowed to (your home, areas your workplace permits).

## 3. Pre-recording checklist (every outing)

1. **The build is the baseline — code and model weights.** Guidance's prompts depend on both, and the
   weights are not in git, so the baseline is the annotated tag **`baseline/stage2-1`** *plus* the model
   files it names. Every stage 3 change is compared against this data; mixing builds or weights breaks
   the comparison.
   - **Recording build:** a clean checkout of the tag, with exactly the tag's model files in
     `app/src/main/assets/` (they are ignored by git, so they do not make the build dirty).
   - **Every day, check the captures:**
     ```bash
     python3 scripts/capture/capture_stats.py <folder> --baseline-tag baseline/stage2-1
     ```
     Each capture records the commit it was built from and the SHA-256 of every packaged model. Any
     mismatch — another commit, `-dirty`, other weights — prints `NOT THE BASELINE`; such captures do
     not belong in the baseline set.
   - **Creating the tag (once, after the manual's PR is merged):** on that merge commit, with the
     chosen weights' hashes (`sha256sum app/src/main/assets/*.tflite`):
     ```bash
     git tag -a baseline/stage2-1 <merge commit> -m "Stage 2 baseline" -m "model det.tflite sha256:<hex>
     model sem.tflite sha256:<hex>"
     ```
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
5. Battery above 60%, free storage above 5 GB. Field evidence takes **0.56–0.74 GB/hour** (measured on
   SM8850; 0.65 on an outdoor walk), a timing burst 41 MB. The phone heats up; rest about every 20
   minutes, since thermal throttling makes the data unrepresentative.
6. The **capture switch** ("Capture during Guidance") is on. It takes effect when the next Guidance
   session starts and is locked while one is recording.
7. **Once per device and build**: three usable timing-sync bursts (section 6a).
8. Bring a **scene card** (section 7) and fill in one row per segment.

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
**★** marks scenes needed for #5 threshold calibration; **regression** marks scenes that must be
recorded again with model regression recording once M0b is built (it does not exist yet; record them
as field evidence now).

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

Capture starts and stops with the Guidance session; there is no separate start/stop button. So **one
"Start guidance → Stop guidance" is one segment**.

1. Go to the start, check the capture switch is on, and fill in the first half of the scene-card row
   (class, place type, light).
2. Tap "Start guidance" in the app (capture starts with it).
3. **Sync mark: cover the camera fully with your palm for 3 s**, until you hear "Camera is covered", then
   uncover. It leaves an `event_camera_blocked` in the trace and dark frames in the capture — the
   segment's start, afterwards.
4. Walk the scene. **If you notice a miss** (an obvious hazard with no prompt), **press volume down once**
   (a short buzz confirms, only once the marker is written), or tap "Mark missed alert" on screen. It
   records the moment you pressed and the last stored frame, which labelling uses to find misses. No
   need to look at the screen; keep your eyes on the path. Works with TalkBack on (checked on SM8850).
5. Before finishing, cover the camera for 3 s again as the end mark.
6. Tap "Stop guidance" (capture stops with it).
7. Complete the scene-card row (duration, anything unusual).

Do not switch apps or lock the screen during a segment; if it gets interrupted, stop and start a new
segment. Volume down is only intercepted while capture is recording; otherwise it changes the volume
as usual. While a ZIP is being exported, Guidance will not start ("Finish exporting the capture before
starting Guidance").

## 6a. Timing-sync burst (once per device and build)

Measures how camera frames line up in time with the gyroscope, which the geometry layer needs. It is
not part of the baseline and changes the device load, so never use it for performance numbers.

1. Settings → Diagnostics → Field capture → **"Arm for next Guidance start"**. The burst applies to
   the next start only: **arm it again before every burst**.
2. Stand still somewhere with a detailed scene a few metres away, in good light.
3. Start Guidance and **at once turn the phone in place**: briskly left and right about once a second
   (about ±30°), then up and down, pivoting at the wrist. Keep going for about 15 s; the burst then
   ends on its own and Guidance carries on without capture.
   - **Walking forward with the phone held steady is not turning.** The gyroscope sees almost nothing
     and the burst is rejected (this happened on the first attempts).
   - Slow, small movements are rejected too: the phone must turn, not just drift.
4. Stop Guidance. Record three such bursts per device.
5. On the computer: `python3 scripts/capture/timing_align.py <folder with the captures>`. It lists
   every burst (usable, or rejected with the reason) and the result for this device and build: the
   median offset over the usable bursts with its range. Record again until there are at least three
   usable ones.

## 7. Scene card

One row per segment, on paper or in a notes app, typed up afterwards:

| Segment | Date/time | Class | Place type (no exact address) | Light / weather | Hold / chest height | Duration | Misses | Other |
|---|---|---|---|---|---|---|---|---|
| 01 | 2026-10-02 10:15 | B | Residential sidewalk | Sunny | Chest / 1.30 m | 3′10″ | ~1′40″ low post on the left not announced | |

## 8. Export

- **Trace**: Settings → Diagnostics → Trace reports → pick the session → "Share JSONL", to your computer.
  Or with adb: `files/traces/trace_<sessionId>.jsonl` (debug builds: `adb shell run-as
  com.sailens.reference`).
- **Capture** (frames, sensors, intrinsics, markers): capture list → the session's **Export** → system
  share, one ZIP per session. Keep the session until you have checked the ZIP arrived (section 2).
  Everything at once, captures and traces together, over USB:
  ```bash
  adb exec-out run-as com.sailens.reference tar -cf - files/captures files/traces > captures.tar
  ```
- Check a day's recordings: `python3 scripts/capture/capture_stats.py <folder>` (duration, storage,
  frame and sensor rates, losses, whether the counters balance). See `scripts/capture/README.md`.
- Keep each segment's trace and capture in one folder named after the scene-card segment number.

## 9. Labelling

### 9.1 Build the sheet

```bash
py scripts/export_prompt_labels.py <segment folder>/trace_*.jsonl -o <segment>.csv
```

Each row is a prompt the user **actually received** (a delivered `prompt_outcome` in the trace). Revoked
prompts were never heard, so they cannot be false alarms and are left out by default; add
`--include-revoked` to see them. `delivered_via` says how it reached the user (`speech`,
`screen_reader`, `haptics`, `status_card`): a prompt with only `haptics` was felt, not heard, even with
voice on. `output_settings` is context only. Each row also carries that frame's ground-recognition
state, whether it was judged blocked, the tracked obstacles and the dominant classes.

### 9.2 Label each row

Compare with the frames a few seconds around the prompt, and fill in `label`. Frames are viewed on the
computer (there is no in-app viewer):

```bash
python3 scripts/capture/contact_sheet.py <capture folder> --trace <trace>.jsonl --prompts --window 2
python3 scripts/capture/contact_sheet.py <capture folder> --markers --window 3
```

One PNG per prompt, centred on the **timestamp** of the frame that raised it (exact, from the trace)
and titled with the message and how it was delivered. Field evidence stores only a sample of frames
(4.3–5 Hz), so that frame's image is often not stored: the stored frame nearest to it is highlighted,
which can be up to about 0.12 s away — keep that in mind when judging `late` or a fast-moving obstacle. The
delivery latency ("… ms after the frame") appears only when the raising frame itself was stored.
One per miss marker, centred on the moment the press was observed.

| Label | Meaning |
|---|---|
| `tp` | True, and it matters for walking |
| `fp` | Nothing like that ahead, or there is but it is not worth a prompt (e.g. a distant person off the route) |
| `wrong` | Something is there, but the prompt got it wrong (direction or category) |
| `late` | True but too late (already at it when announced) |
| `unsure` | Cannot tell from the frames; say why in `note` |

### 9.3 Misses

Keep a second sheet, `<segment>_misses.csv`, one row per miss: `segment, rough time (seconds into the
segment), what was missed, estimated distance, note`. Misses come from the volume-down markers (section
6, step 4; every marker gets a row, filled in once confirmed, or `accidental` if pressed by mistake) and
from reviewing the frames. A miss is: an obstacle within about 2 m on the route, a path that really is
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
- **Geometry**: read with the capture tools (`scripts/capture/`); see the implementation doc §4.
  **Replay** of decisions needs M0b recordings, not this round.
