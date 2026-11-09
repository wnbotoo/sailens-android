"""Checks every released prompt hold in Guidance traces against the records that led to it.

A hold keeps ordinary prompts back after Guidance had to stop, until fresh results show it can judge again; the
release record (`safety_state.holdReleased`) carries the evidence. Each release gets one verdict:

- consistent: the records from the stop that began the hold to the release recount to the same evidence, and every
  counted result's frame record agrees with it;
- inconsistent: the evidence is complete and contradicts itself or the frames: a logic error;
- unverifiable: evidence is missing (records dropped by the trace's bound, frames left out by sampling, a trace written
  before holds were recorded), so nothing can be said either way.

Results are joined to their frame records by the frame's `sequenceNumber` within the session, never by a record's
`seq`. A counted result whose frame record is present without `receivedElapsedMs` had no recorded receipt: it must have
counted at its arrival, the progress record's `elapsedMs`. A missing frame record says nothing about the receipt.

Usage: python hold_releases.py TRACE.jsonl [TRACE.jsonl ...]
"""

import sys
from dataclasses import dataclass, field

from sailens_capture import load_trace

MIN_FRESH_RESULTS = 3
MIN_FRESH_SPAN_MS = 500

CONSISTENT = "consistent"
INCONSISTENT = "inconsistent"
UNVERIFIABLE = "unverifiable"


@dataclass
class Step:
    """One result the hold counted: its frame, its receipt as counted, whether that was the arrival standing in."""

    frame_seq: int
    received_ms: int
    fallback: bool
    arrival_ms: int


@dataclass
class Verdict:
    record_seq: int
    source_frame: int
    verdict: str
    reasons: list = field(default_factory=list)


def _release_verdict(trace, states, index, hold_trace):
    release = states[index]
    evidence = release["holdReleased"]
    inconsistent, unverifiable = [], []
    if not hold_trace:
        return Verdict(release["seq"], evidence["sourceSequenceNumber"], UNVERIFIABLE, ["the trace records no holds"])

    since = evidence["sinceElapsedMs"]
    start = None
    for j in range(index - 1, -1, -1):
        hold = states[j].get("hold")
        if hold is None:
            break
        if hold["stopping"] and hold["cause"] == evidence["cause"] and hold["sinceElapsedMs"] == since and states[j]["elapsedMs"] == since:
            start = j
            break
    if start is None:
        return Verdict(release["seq"], evidence["sourceSequenceNumber"], UNVERIFIABLE, ["the record that began the stop is missing"])
    window = states[start:index + 1]
    seqs = [r["seq"] for r in window]
    if seqs != list(range(seqs[0], seqs[0] + len(seqs))):
        return Verdict(release["seq"], evidence["sourceSequenceNumber"], UNVERIFIABLE, ["records are missing in the window"])

    # Layer 1: replay the progress the safety records give.
    fresh, first, last, fallbacks = 0, None, None, 0
    steps = []

    def count(received, total_fallbacks, frame_seq, arrival):
        nonlocal fresh, first, last, fallbacks
        step = total_fallbacks - fallbacks
        if step not in (0, 1):
            inconsistent.append(f"fallbacks jump from {fallbacks} to {total_fallbacks}")
        if received is None or received <= since:
            inconsistent.append(f"frame {frame_seq} counted with receipt {received}, not after {since}")
        fresh += 1
        fallbacks = total_fallbacks
        if first is None:
            first = received
        last = received
        steps.append(Step(frame_seq, received, step == 1, arrival))

    for record in window[1:]:
        hold, released = record.get("hold"), record.get("holdReleased")
        if released is not None:
            count(released["lastFreshReceivedElapsedMs"], released["receiptFallbacks"], released["sourceSequenceNumber"], record["elapsedMs"])
            if released["freshResults"] != fresh or released["firstFreshReceivedElapsedMs"] != first or released["freshSpanMs"] != last - first:
                inconsistent.append(f"the release says {released['freshResults']} in {released['freshSpanMs']} ms, the records {fresh} in {last - first} ms")
            if fresh < MIN_FRESH_RESULTS or last - first < MIN_FRESH_SPAN_MS:
                inconsistent.append(f"released with {fresh} results in {last - first} ms")
            continue
        if hold is None:
            inconsistent.append(f"record {record['seq']} drops the hold without a release")
            continue
        if hold["cause"] != evidence["cause"] or hold["sinceElapsedMs"] != since:
            inconsistent.append(f"record {record['seq']} holds for another stop")
            continue
        if hold["stopping"]:
            if hold["freshResults"] != 0:
                inconsistent.append(f"record {record['seq']} counts while the stop holds")
            continue
        if hold["freshResults"] == fresh + 1:
            count(hold.get("lastFreshReceivedElapsedMs"), hold.get("receiptFallbacks", 0), hold.get("lastFreshSequenceNumber"), record["elapsedMs"])
            if hold.get("firstFreshReceivedElapsedMs") != first or hold["freshSpanMs"] != last - first:
                inconsistent.append(f"record {record['seq']} says first {hold.get('firstFreshReceivedElapsedMs')}, span {hold['freshSpanMs']}")
            if fresh >= MIN_FRESH_RESULTS and last - first >= MIN_FRESH_SPAN_MS:
                inconsistent.append(f"frame {steps[-1].frame_seq} met both thresholds before the release")
        elif hold["freshResults"] != fresh:
            inconsistent.append(f"record {record['seq']} jumps from {fresh} to {hold['freshResults']} results")
    if steps and steps[-1].frame_seq != evidence["sourceSequenceNumber"]:
        inconsistent.append(f"the release names frame {evidence['sourceSequenceNumber']}, the last counted is {steps[-1].frame_seq}")

    # Layer 2: each counted result against its frame record, by the frame's sequence number in this session.
    session = release.get("sessionId")
    for step in steps:
        frame = trace.frames_by_seq.get(step.frame_seq)
        if frame is None or frame.get("sessionId") != session:
            unverifiable.append(f"frame {step.frame_seq} has no record")
            continue
        recorded = frame.get("receivedElapsedMs")
        if recorded is not None:
            if step.fallback or recorded != step.received_ms:
                inconsistent.append(f"frame {step.frame_seq} was received at {recorded}, counted at {step.received_ms}" + (" as a fallback" if step.fallback else ""))
        elif not step.fallback or step.received_ms != step.arrival_ms:
            inconsistent.append(f"frame {step.frame_seq} has no receipt, but was not counted at its arrival")

    if inconsistent:
        return Verdict(release["seq"], evidence["sourceSequenceNumber"], INCONSISTENT, inconsistent)
    if unverifiable:
        return Verdict(release["seq"], evidence["sourceSequenceNumber"], UNVERIFIABLE, unverifiable)
    return Verdict(release["seq"], evidence["sourceSequenceNumber"], CONSISTENT)


def check_releases(trace):
    """A verdict for every release record in [trace], in record order."""
    hold_trace = bool((trace.session_start or {}).get("holdTrace"))
    states = sorted(trace.safety_states, key=lambda r: r["seq"])
    return [_release_verdict(trace, states, i, hold_trace) for i, r in enumerate(states) if r.get("holdReleased")]


def main(paths):
    worst = 0
    for path in paths:
        for v in check_releases(load_trace(path)):
            print(f"{path}: record {v.record_seq}, frame {v.source_frame}: {v.verdict}" + (f" ({'; '.join(v.reasons)})" if v.reasons else ""))
            worst = max(worst, {CONSISTENT: 0, UNVERIFIABLE: 1, INCONSISTENT: 2}[v.verdict])
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
