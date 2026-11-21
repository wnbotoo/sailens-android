# Field capture tools

PC-side tools for the debug build's field captures
([`docs/field-capture.md`](../../docs/field-capture.md)). They read an exported
capture ZIP (unpacked) or a directory pulled with `adb`, and never modify it.

```bash
python3 -m venv .venv && .venv/bin/pip install -r scripts/capture/requirements.txt
```

| Tool | What it answers |
|---|---|
| `capture_stats.py <dir>` | Storage rate (MB/hour), frame and sensor cadence, where frames were lost (capture mailbox, encoder, before the analyzer), and whether the counters balance; prints each capture's build (git SHA + model hashes). Accepts a folder of captures. With `--baseline-tag <tag>`, checks every capture against the baseline's commit and model hashes (exit code 3 on a mismatch). |
| `contact_sheet.py <capture> --markers` | What the camera saw around each "missed alert" press. |
| `contact_sheet.py <capture> --trace trace_<id>.jsonl --prompts` | What the camera saw around each prompt, centred on the exact timestamp of the frame that offered it (the nearest stored frame is highlighted; the offering frame itself may not be stored), with its delivery or revocation. |
| `depth_evidence.py <capture or folder> --model depth.tflite --out <dir>` | The capture's depth runs redone from their recorded planes: the native preprocessing reproduced, the model in float32 on the CPU (needs `ai-edge-litert`, `tflite-runtime` or TensorFlow besides the requirements), one output per run for `DepthEvidenceCaptureReplayTest` to compare with the phone's ([`docs/field-capture.md`](../../docs/field-capture.md) §11). |
| `timing_align.py <burst or folder>` | The offset between frames and the gyroscope: on camera timestamps when the camera reports a REALTIME timestamp source, otherwise on source receipt times. For a folder, lists every burst (usable or rejected, with the reason) and the result per device and build (git SHA + packaged model hashes, from the capture manifest; bursts from different builds are never pooled, and captures without a SHA give no result unless `--allow-unknown-build`): the median over at least three usable bursts, with its range as the uncertainty. |

Getting the data off the phone: Settings → Diagnostics → Field capture → Export shares a ZIP. In
bulk, without the share sheet:

```bash
adb exec-out run-as com.sailens.reference tar -cf - files/captures files/traces > captures.tar
```

The trace for a capture is `traces/trace_<sessionId>.jsonl`; the capture directory has the same
session id.

**Timing-sync burst.** On the field capture page, arm the burst (it applies to the next Guidance start
only; arm it again before every burst). Stand still, start Guidance, and at once turn the phone **in
place**: briskly left and right about once a second (about ±30°), then up and down, pivoting at the
wrist, pointed at a detailed scene a few metres away, for the ~15 s it records. Walking forward with
the phone held steady is not turning; the gyroscope sees almost nothing and `timing_align.py`
rejects the burst ("the phone barely turned"). Then run `timing_align.py` on the folder of bursts. The burst
changes the device load, so never use it for performance numbers.

Tests: `python3 -m unittest discover -s scripts/capture` (synthetic captures in the app's format,
including a burst with a known offset).

## Guidance pipeline performance

A debug install can enable profiling with a local `files/pipeline-timing` marker. Create it before
launching the app. Timing uses ordinary Guidance JSONL and does not need field capture.
Stop Guidance so the session summary is written before pulling the trace.

```sh
adb shell "run-as com.sailens sh -c 'mkdir -p files && echo race > files/pipeline-timing'"
adb shell am force-stop com.sailens
# Launch the app and run Guidance.
adb logcat -d -s Camera:I
adb shell dumpsys media.camera > camera-service.txt
# Stop Guidance.
adb exec-out run-as com.sailens cat files/traces/trace_SESSION_ID.jsonl > trace.jsonl
python scripts/experiments/pipeline_performance.py trace.jsonl --warmup 15
```

Use `com.sailens.reference` for the platform reference host. Remove the marker and restart the
process for the default path with profiling off. Use `sem-nchw-untimed` for semantic-first
preprocessing, NEON argmax, and direct NCHW output with profiling off. Compare it with
`sem-nchw-indexed-untimed` to isolate precomputed nearest-neighbour offsets in the shared
float preprocessing path. The `CameraX negotiated` log reports the bound sizes for
analysis and preview. The trace report shows the buffer dimensions delivered to Guidance.
The camera service dump taken during the run shows the active preview and YUV streams.

Use `race` for the existing order and `sem-first` for the preferred producer, with the same
APK and model files. Alternate arms in `race, sem-first, sem-first, race` order under the same
camera setup and thermal conditions. Restart the process for each arm and use equal steady
windows after warm-up with `--seconds`. Pass all traces to the report in run order. The trace records the arm,
the number of reservations, and which preprocessing backend each model actually used.
Reservations apply to uncapped semantic runs with identical resolved float inputs in the same
cache. Detection uses the existing bounded wait and can still compute on timeout.

Within preprocessing, `cacheAwait` is the wait for a producer and `cacheMonitorWait` is the
initial cache monitor acquisition. Both are already in `cacheWait`. `cacheCopy` measures
`copyInto` on hits. Native YUV producers also report JNI array acquisition and release,
the body of `preprocessYuv` under the selected resize filter, and the Kotlin HWC-to-NCHW
transpose when applicable. The JNI access total includes acquisition and release.
`jni_copy_counts` tallies the JNI `isCopy` flag separately for Y, U, V and the output.
These producer-only stages omit cache hits. `preprocessOther` is the remaining time,
including fallback paths and untimed stages in older traces.

Each timing record follows one dequeued camera frame and can include completed model calls even
when that frame produced no decision. Rates use completion events in the elapsed-realtime window.
Loss uses the half-open interval of camera frame IDs bracketed by dequeued frames in the
uncapped Guidance stream. ID gaps are lost in the mailbox. Dequeued frames without a decision
are counted separately. CameraX may discard images before the analyzer, outside that interval's
denominator. Copy durations sample dequeued frames. Sensor ages require a REALTIME camera
timestamp source. Semantic age follows the analysis used by the decision. Detection age follows
the latest adopted detector measurement, including tracker-only frames.

Sem and det can overlap. A model's input write, LiteRT call, output access and remaining
postprocessing durations are distinct. A zero-copy read lock can include waiting for the
accelerator and mapping the output. The end of output postprocessing counts one new model result.

On the semantic handle path, `sem.score` reports Kotlin scratch preparation, JNI array
acquisition, the fused kernel, JNI array release, and Kotlin assembly of the masks and
statistics. LiteRT lock and unlock keep their own clocks. With `race` or `sem-first`, every
eighth camera frame ID enables scalar pixel sampling with a rotating offset and a stride of 257
content pixels. That sample estimates
argmax, class map and packed-mask writes, and the intervening lookups and statistics, with a
back-to-back clock measurement subtracted from each sampled interval. These per-pixel estimates
help rank work inside the fused loop. Use the full kernel duration to compare runs, and compare
sampled and unsampled kernel durations to check the cost of sampling.

For the semantic argmax A/B, use `sem-scalar, sem-simd, sem-simd, sem-scalar`. Both modes
use the preferred semantic producer and disable pixel sampling. `sem-simd` requests ARM
NEON for finite NCHW FLOAT32 LiteRT output handles with at least four content columns.
Non-finite scores use the scalar kernel. Trace rows report the actual kernel in
`argmaxImplementation`. Verify it in
`backends["sem.score.argmaxImplementation"]` before comparing the arms.

For the native NCHW input A/B, use `sem-simd, sem-nchw, sem-nchw, sem-simd`. Both modes
use the preferred semantic producer and the same argmax selection. `sem-nchw` writes
NEAREST FLOAT32 NCHW tensors in the native YUV preprocessor, so no Kotlin transpose follows
that native call. Check the producer's `nativeOutputLayout` counts and compare total
preprocessing time as well as `nativeNearestBody` and `kotlinHwcToNchw`.

To compare the existing processing path with the combined candidate, use
`race-unsampled, sem-nchw, sem-nchw, race-unsampled`. The control keeps the original
cache race, scalar argmax and Kotlin transpose. Both arms collect whole-stage timing
with pixel sampling disabled. Check producer reservations, argmax selection and
native output layouts in each arm.
