# Field capture tools (M0a)

PC-side tools for the debug build's field captures
([implementation §4](../../docs/local-navigation-implementation.md)). They read an exported
capture ZIP (unpacked) or a directory pulled with `adb`, and never modify it.

```bash
python3 -m venv .venv && .venv/bin/pip install -r scripts/capture/requirements.txt
```

| Tool | What it answers |
|---|---|
| `capture_stats.py <dir>` | Storage rate (MB/hour), frame and sensor cadence, where frames were lost (capture mailbox, encoder, before the analyzer), and whether the counters balance. Accepts a folder of captures. |
| `contact_sheet.py <capture> --markers` | What the camera saw around each "missed alert" press. |
| `contact_sheet.py <capture> --trace trace_<id>.jsonl --prompts` | What the camera saw around each prompt, centred on the frame that offered it, with its delivery or revocation. |
| `timing_align.py <timing_sync capture>` | The offset between frames and the gyroscope: on camera timestamps only when the camera reports a REALTIME timestamp source, otherwise on source receipt times. Says whether the burst is usable for M0 qualification (exit code 0) or must be recorded again. |

Getting the data off the phone: Settings → Diagnostics → Field capture → Export shares a ZIP. In
bulk, without the share sheet:

```bash
adb exec-out run-as com.sailens.reference tar -cf - files/captures files/traces > captures.tar
```

The trace for a capture is `traces/trace_<sessionId>.jsonl`; the capture directory has the same
session id.

**Timing-sync burst.** On the field capture page, arm the burst, start Guidance, point the phone at a
detailed scene and turn it slowly left and right, then up and down, for the ~15 s it records. Then
run `timing_align.py` on that capture. The burst changes the device load, so never use it for
performance numbers.

Tests: `python3 -m unittest discover -s scripts/capture` (synthetic captures in the app's format,
including a burst with a known offset).
