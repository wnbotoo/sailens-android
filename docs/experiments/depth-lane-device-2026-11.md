**English** | [简体中文](depth-lane-device-2026-11.zh-CN.md)

# Experiment note: the depth lane on a device (2026-11-12)

What the depth lane ([`../geometry.md`](../geometry.md) §6) costs and how faithfully it runs on the
target phone: the budget beside sem+det, the depth model on its own, the accelerator consistency check,
the capture's depth evidence against its reproduction on a PC ([`../field-capture.md`](../field-capture.md)
§11), and a model swap. The lane decides nothing, so none of this is about prompts. Captures and traces
stay on the recording machine; the analysis scripts are in
[`scripts/experiments/depth_lane_budget/`](../../scripts/experiments/depth_lane_budget/).

## Environment

- OnePlus CPH2747, `qti_sm8850`, Android 17 (SDK 37). Debug build of the reference host
  (`com.sailens.reference`); app code as at `bd66f7b` (the outdoor runs used a build whose only
  difference is an instrumented test). LiteRT 2.1.5.
- Guidance in the `DEFAULT` profile (sem + det on the GPU, the library's default precision). Depth on
  the GPU, FP16, LOW queue priority, 3 Hz (1 Hz when warm), ground fit on a 90×160 grid.
- Models (sha256):

| File | What | sha256 |
|---|---|---|
| `depth.tflite` | Depth Anything V2 Small, portrait 392×224, from [`scripts/depth/dav2/export.py`](../../scripts/depth/dav2/export.py) | `4927dbc07167407e40ac708cb92ff3344ed1c8cd010841577179f0b924b4d26a` |
| `sem.tflite` | semantic segmentation | `d29dee0289f29be7e1446095e843109cdff7bdddba9bfd2ffd525425fbc5f557` |
| `det.tflite` | obstacle detection | `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73` |
| ZipDepth, portrait 512×288 | swap only, local; its weights carry no licence statement of their own | `ff32d708eedba0c5455ececa17508a1ce58b4723432b25bb0d9a474bb72bc404` |

- PC reproduction: WSL Ubuntu, Python 3.12.3, `ai-edge-litert` 2.2.0, `numpy` 2.5.3, float32 on the CPU.

## 1. Budget beside sem+det

**Protocol.** Phone on a stand facing a fixed indoor scene, not charging, other apps closed. The lane
switch was set before each Guidance start. In order: three pairs of 90 s sessions, lane off then on;
one 15-minute session with the lane; 10 starts of about 15 s with the lane and 10 without; 10 stops
and restarts with the lane, five stopped within 1–4 s of the start and five after about 10 s; two more
stopped within 1 s, before the recordings below. A host-side sampler (`sampler.ps1`) read the app's
thread count, memory and the thermal status every 10–15 s. `analyze.py` and `idle.py` reproduce every
number below from the 37 traces and the sampler's file.

sem and det are each the sum of the trace's preprocess, inference, output-read and postprocess times;
the drop rate is dropped frames over offered frames; a stall is a `pipelineOutputIntervalMs` above
500 ms.

| | Lane off (3 × 90 s) | Lane on (3 × 90 s) | Lane on, 15 min |
|---|---|---|---|
| sem p95 | 58 ms | 53 ms (−8.6 %) | 46 ms (−20.7 %) |
| det p95 | 33 ms | 31 ms (−6.1 %) | 28 ms (−15.2 %) |
| receipt to decision p95 | 85.5 ms | 82.1 ms (−4.0 %) | 74.5 ms (−12.9 %) |
| drop rate | 32.0 % | 23.0 % | 18.5 % |
| stalls > 500 ms / longest interval | 0 / 86 ms | 0 / 84 ms | 0 / 78 ms |

With the lane on, sem and det were *faster* and fewer frames were dropped, in all three pairs. The
likely reason is that the extra GPU and CPU work keeps the clocks higher; it was not measured, and
neither was the power this costs.

| Depth run (all 4036 published runs) | p50 | p95 | max |
|---|---|---|---|
| infer + output read (GPU) | 32 ms | 43 ms | 70 ms (7 runs above 60 ms) |
| prepare (native preprocessing, CPU) | 7–9 ms | 13–16 ms | 24 ms |
| wait for admission | 0 ms | 0 ms | 2 ms |
| ground fit (CPU) | 146 ms | 196 ms | 242 ms |
| observation age (frames carrying one) | 299 ms | 499 ms | 566 ms |

- **The ground fit is most of a run**: about three quarters of the time from prepare to the fit's end,
  about 0.4 of a CPU core at the observed 2.86 runs a second (the configured period is 333 ms). Over
  the 15 minutes its p95 grew from 151 to 217 ms as the phone warmed; the GPU part did not.
- **Cold start.** Guidance's first decision, from the start of initializing: median 69 ms with the
  lane, 76 ms without; worst 93 and 137 ms. The depth model compiled in 1.3 s (median, worst 3.1 s);
  over that window sem p95 was 51 ms (56 ms without the lane over the same span from start), the drop
  rate 29.4 % (30.7 %) and the longest output interval 73 ms (76 ms).
- **15 minutes.** Thermal status 0 (none) throughout, battery up to 41.6 °C; the lane held 2.86 runs a
  second and never stepped down.
- **Stop and restart.** Over the 24 depth sessions of these traces and the two extra stops, every depth
  session's records are its own (its generation, in order), no run was published after its session
  closed, no frame carried an observation from another session or from before its session's first
  publish, and no run failed. Stops landed while the model compiled (5) and while the lane ran (21):
  inside prepare 1, inside inference 4, inside the fit 6 and between runs 10. Inference is about 30 ms of
  each 333 ms period, so a stop lands inside it about one time in ten.
- **Resources.** Idle threads 78–83 after the first session (67 before any), at most 87 while running;
  idle graphics memory back to 61–62 MB. GL mtrack moved from 1536 to 2304 KB over the 24 depth
  sessions, and between 1920 and 2496 KB within one idle stretch: under 1 MB, not shown to be a leak.
- No stall, no hold; all 44 008 frames qualified; the safety state went only initializing → guiding →
  idle. Prompts were too few to compare delivery intervals. This build has no interactive GPU work, so
  no yield interval occurred.

## 2. The depth model on its own

A local instrumentation loop on the app's LiteRT runtime: one `CompiledModel` on the GPU at FP16,
input written from one fixed tensor of Gaussian noise clipped to [−2.1, 2.6] (not image values in the
`[0,1]` the app feeds), 10 warm-up runs then 200, timing the run and the output
read. All 715 nodes were delegated to the GPU (one partition); outputs finite; thermal status 0.

| Queue priority | p50 | p95 (three or two runs) | GPU busy |
|---|---|---|---|
| DEFAULT | 19 ms | 23.1, 28.1, 23.3 ms | 83–89 % |
| LOW | 125–135 ms | 129, 751, 1093 ms | 94–97 % |

The model's own cost (DEFAULT) is within 35 ms; an export of the same weights without the folded
normalization measured 22.2 ms p95 the same way. At LOW priority with nothing else on the GPU it ran five to six times slower while the
GPU stayed busy, which looks like the low-priority queue not raising the GPU clock (the clock could
not be read to confirm it). In the app the lane runs only beside sem+det, which run every frame and
keep the GPU up: there LOW costs nothing (section 1). Running the lane without sem+det, or at a much
lower sem+det rate, would need the priority looked at again.

## 3. Accelerator consistency check

The debug Model consistency check, accelerator against CPU on live frames (8 frames).

| Where | sem | det | depth |
|---|---|---|---|
| Indoors, first | inconclusive: mean 0.895, worst frame 0.748 | pass | pass: worst median 0.64 %, validity 8/8, labels ≥ 97.8 % |
| Indoors, second | pass: mean 0.995 | pass: 24/24 | pass: worst median 0.68 %, 8/8, labels ≥ 99.6 % |
| Outdoors | pass: mean 0.989, worst 0.972 | pass: 24/24 | pass: worst median 0.53 %, 8/8, labels ≥ 99.2 % |

## 4. Depth evidence against the PC

Four scenes, each one Guidance session recorded from its start with the phone held at the chest (phone
height preset 1.2 m): an indoor floor, the top of an indoor staircase, a granite sidewalk and a curb.
Field-evidence capture with depth evidence. Where a scene was recorded more than once, the one judged is
the first that met the preconditions (complete, at least 300 acceptable runs, nothing dropped before
them, the scene in view); all are listed. A run dropped at the end (`droppedAtEnd`: the one in flight
when the session stopped) comes after every recorded run and cuts nothing.

Judged per [`../field-capture.md`](../field-capture.md) §11: output median relative error ≤ 1 %; fit
agreement ≥ 95 %, the PC's output of each run fitted from the phone's history and compared on validity, on
the guard state it leaves and on what the history it leaves gives later runs until it is the phone's again;
label agreement ≥ 95 % over the runs
where both of those fits are valid. The validity agreement with each side's own fitter fitting the session
in order is reported.

| Scene | Runs | Exact replay | Output error (median) | Fit (disagreeing runs) | Labels (runs) | In session order | Verdict |
|---|---|---|---|---|---|---|---|
| **Indoor floor** | 470 | 470/470 | 0.49 % | 97.9 % (10) | 99.3 % (97) | 75.1 % | pass |
| **Staircase top** | 446 | 446/446 | 0.59 % | 98.0 % (9) | 98.9 % (178) | 77.4 % | pass |
| **Granite sidewalk** | 455 | 455/455 | 0.58 % | 96.5 % (16) | 96.9 % (251) | 56.0 % | pass |
| Granite sidewalk, second | 442 | 442/442 | 0.77 % | 96.2 % (17) | 97.6 % (419) | 95.2 % | pass |
| **Curb** | 370 | 370/370 | 0.64 % | 98.4 % (6) | 98.4 % (292) | 91.4 % | pass |
| Curb, second | 383 | 383/383 | 0.65 % | 96.9 % (12) | 96.4 % (375) | 96.6 % | pass |
| Curb, third | 450 | 450/450 | 0.79 % | 99.3 % (3) | 98.4 % (356) | 99.6 % | pass |

The curb was in view: 20 of the judged curb session's 293 valid fits had at least 2 % of the grid below
ground (up to 50 %). Over the seven sessions 73 of 3016 runs disagreed: 46 on the guard alone, a valid fit
whose below-ground region crossed the 2 % that arms the guard on one side only (the PC's output armed it 30
times, the phone's 16); 24 on validity (phone `SUPPORT_SWITCH` and PC another validity 11 times, the reverse
4, the rest between `NO_GROUND`, `NO_SUPPORT` and `VALID`); 3 only through a later run, in all three the
next one. At the end of three sessions one run's history was still different and had shown nothing. Where
the session-order
agreement is low, one such call put the two fitters' holds at different runs and kept them apart until both
saw two levels again (30–74 runs indoors).

## 5. Swapping in ZipDepth

ZipDepth (a CNN, portrait 512×288, normalization in the model, output `[1,1,512,288]`) was put in as
`depth.tflite` and the app rebuilt with no code change. Preflight compatible (the lane went from
initializing to available); the consistency check passed for all three models (depth worst median
0.43 %, validity 7/8; no frame had both fits valid, so no labels were compared). In 86 s with the lane:
238 runs, all valid; infer + read p50 7 ms, p95 31 ms, max 39 ms; fit p50 76 ms; sem/det and the drop
rate as with the reference model. The reference model was then put back.

## 6. Findings that matter before any decision reads depth

- **The support-switch guard fires on flat ground.** Of the phone's own fits, 330 of 470 indoors on the
  floor, 239 of 446 at the staircase top and 203 of 455 on the first sidewalk session were
  `SUPPORT_SWITCH`, and the hold then lasted until two levels were seen together again. It is the same
  on the PC's outputs.
- **The fit's CPU cost** (p50 146 ms a run, growing as the phone warms) is the largest part of the
  lane's cost and of the observation's age.
- **LOW queue priority relies on sem+det keeping the GPU busy** (section 2).
- GL mtrack's small rise over many depth sessions (section 1) is unexplained.

## Reproduce

```
python scripts/experiments/depth_lane_budget/analyze.py <traces>
python scripts/experiments/depth_lane_budget/idle.py <traces> <sampler csv>
python scripts/capture/depth_evidence.py <captures> --model depth.tflite --out <pc dir>
gradlew :sailens-guidance:testDebugUnitTest --tests "*DepthEvidenceCaptureReplayTest*" -PreplayDepth=<captures> -PdepthPcOutputs=<pc dir>
```

`sampler.ps1 -Out <csv>` samples the app while the protocol runs (adb to the phone, the debug build).
