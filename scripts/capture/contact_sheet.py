"""Contact sheets of the captured frames around a prompt or a "missed alert" marker.

    python contact_sheet.py <capture dir> --markers [--window 2] [--out sheets/]
    python contact_sheet.py <capture dir> --trace trace_<id>.jsonl --prompts [--event <eventId>]

One PNG per event: the stored frames within ±window seconds, upright, each labelled with its
offset from the event. The anchor is exact on each side's own clock:
- a prompt is centred on the frame that offered it: the trace's ``sourceSequenceNumber`` gives that
  frame's camera timestamp (``frameTimestamp``), which the capture records as
  ``sensorTimestampNanos``; its delivery or revocation time is shown in the title;
- a marker is centred on the moment the press was observed (``elapsedRealtimeNanos``), matched to
  the frames' ``receivedElapsedRealtimeNanos``.
Field evidence stores about five frames a second, so the offering frame itself may not be stored;
the tile closest to 0 s is highlighted either way.
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sailens_capture import load_capture, load_trace, read_image  # noqa: E402

TILE_LONG_SIDE = 240
COLUMNS = 6
LABEL_HEIGHT = 18
TITLE_HEIGHT = 40


def frames_around(capture, center_nanos, window_s, clock):
    """(offset seconds, frame) for stored frames within ±window of the center on [clock]."""
    key = "sensorTimestampNanos" if clock == "camera" else "receivedElapsedRealtimeNanos"
    half = window_s * 1e9
    return [((f[key] - center_nanos) / 1e9, f) for f in capture.frames if abs(f[key] - center_nanos) <= half]


def render(capture, items, title, path):
    import numpy as np
    from PIL import Image, ImageDraw

    if not items:
        return False
    tiles = []
    for offset, frame in items:
        image = read_image(capture, frame)
        tile = Image.fromarray(image if image.ndim == 3 else np.stack([image] * 3, axis=-1))
        tile.thumbnail((TILE_LONG_SIDE, TILE_LONG_SIDE))
        tiles.append((offset, frame, tile))
    tile_w = max(t.width for _, _, t in tiles)
    tile_h = max(t.height for _, _, t in tiles) + LABEL_HEIGHT
    rows = (len(tiles) + COLUMNS - 1) // COLUMNS
    sheet = Image.new("RGB", (tile_w * min(COLUMNS, len(tiles)), TITLE_HEIGHT + rows * tile_h), "white")
    draw = ImageDraw.Draw(sheet)
    draw.text((6, 4), title, fill="black")
    closest = min(range(len(tiles)), key=lambda i: abs(tiles[i][0]))
    for i, (offset, frame, tile) in enumerate(tiles):
        x, y = (i % COLUMNS) * tile_w, TITLE_HEIGHT + (i // COLUMNS) * tile_h
        sheet.paste(tile, (x, y + LABEL_HEIGHT))
        color = "red" if i == closest else "black"
        draw.text((x + 4, y + 2), f"{offset:+.2f}s  #{frame['seq']}", fill=color)
        if i == closest:
            draw.rectangle([x, y + LABEL_HEIGHT, x + tile.width - 1, y + LABEL_HEIGHT + tile.height - 1], outline="red", width=3)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    sheet.save(path)
    return True


def prompt_events(capture, trace, event_id=None):
    for outcome in trace.prompt_outcomes:
        if event_id and outcome.get("eventId") != event_id:
            continue
        source = trace.frames_by_seq.get(outcome["sourceSequenceNumber"])
        if source is None:
            print(f"skip {outcome.get('eventId')}: offering frame {outcome['sourceSequenceNumber']} not in the trace",
                  file=sys.stderr)
            continue
        if outcome.get("deliveredAt") is not None:
            fate = f"delivered via {','.join(outcome.get('deliveredVia', []))}"
            at = outcome["deliveredAt"]
        else:
            fate = f"revoked ({outcome.get('revokeReason')})"
            at = outcome["revokedAt"]
        # How long after the offering frame the prompt reached (or failed to reach) the person,
        # on the elapsed clock: trace wall ms -> anchors; frame receipt time from the capture.
        latency = ""
        stored = next((f for f in capture.frames if f["seq"] == outcome["sourceSequenceNumber"]), None)
        if stored is not None:
            ms = (capture.wall_ms_to_elapsed_nanos(at) - stored["receivedElapsedRealtimeNanos"]) / 1e6
            latency = f", {ms:+.0f} ms after the frame"
        title = f"prompt {outcome['messageKey']} [{outcome.get('priority')}] {fate}{latency}  (event {outcome['eventId']})"
        yield f"prompt_{outcome['eventId']}", source["frameTimestamp"], "camera", title


def marker_events(capture):
    for i, marker in enumerate(capture.markers):
        title = f"missed alert #{i + 1} via {marker.get('source')}, last stored frame #{marker.get('lastStoredFrameSeq')}"
        yield f"marker_{i + 1:03d}", marker["elapsedRealtimeNanos"], "elapsed", title


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("capture", help="capture directory")
    parser.add_argument("--trace", help="the session's trace JSONL (needed for --prompts)")
    parser.add_argument("--prompts", action="store_true", help="one sheet per prompt outcome in the trace")
    parser.add_argument("--event", help="only this prompt eventId")
    parser.add_argument("--markers", action="store_true", help="one sheet per missed-alert marker")
    parser.add_argument("--window", type=float, default=2.0, help="seconds either side (default 2)")
    parser.add_argument("--out", default="sheets", help="output directory (default ./sheets)")
    args = parser.parse_args(argv)
    if not (args.prompts or args.markers or args.event):
        parser.error("choose --prompts, --event or --markers")
    capture = load_capture(args.capture)
    for warning in capture.warnings:
        print(f"~ {warning}", file=sys.stderr)

    events = []
    if args.prompts or args.event:
        if not args.trace:
            parser.error("--prompts needs --trace")
        events += list(prompt_events(capture, load_trace(args.trace), args.event))
    if args.markers:
        events += list(marker_events(capture))

    written = 0
    for name, center, clock, title in events:
        path = os.path.join(args.out, f"{capture.manifest.get('sessionId')}_{name}.png")
        if render(capture, frames_around(capture, center, args.window, clock), title, path):
            written += 1
            print(path)
        else:
            print(f"skip {name}: no stored frames within ±{args.window} s", file=sys.stderr)
    return 0 if written or not events else 1


if __name__ == "__main__":
    sys.exit(main())
