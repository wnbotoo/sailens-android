# Field capture tools (M0a)

PC-side tools for the debug build's field captures
([implementation §4](../../docs/local-navigation-implementation.md)). They read an exported
capture ZIP (unpacked) or a directory pulled with `adb`, and never modify it.

```bash
python3 -m venv .venv && .venv/bin/pip install -r scripts/capture/requirements.txt
```

| Tool | What it answers |
|---|---|
| `capture_stats.py <dir>` | Storage rate (MB/hour), frame and sensor cadence, where frames were lost (capture mailbox, encoder, before the analyzer), and whether the counters balance; prints each capture's build (git SHA + model hashes). Accepts a folder of captures. With `--baseline-tag <tag>`, checks every capture against the baseline's commit and model hashes (exit code 3 on a mismatch). |
| `contact_sheet.py <capture> --markers` | What the camera saw around each "missed alert" press. |
| `contact_sheet.py <capture> --trace trace_<id>.jsonl --prompts` | What the camera saw around each prompt, centred on the exact timestamp of the frame that offered it (the nearest stored frame is highlighted; the offering frame itself may not be stored), with its delivery or revocation. |
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
