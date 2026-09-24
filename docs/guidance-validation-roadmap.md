# Guidance Device Validation and Spatial Earcon Roadmap

> Status: **deferred plan**. Device, participant, and field-sampling resources are not currently available. This document is not validation evidence and does not claim release readiness.

## Purpose

Before extending guidance output, verify on physical devices that:

1. Risk escalation bypasses cooldown while duplicate or flickering detections do not spam output.
2. TalkBack, app TTS, and haptics neither duplicate one another nor fail silently together.
3. Live status represents only a still-valid risk, while replay history remains historical.
4. Guidance interruption, TTS failure, and camera failure are detectable through a non-visual channel.
5. Spatial earcons enter the production guidance path only after reliable recognition on target headsets.

## Prerequisites

Prepare at least two Android devices from different vendors, one bone-conduction headset, one conventional stereo headset, TalkBack, two TTS engines, concurrent podcast/navigation audio, Sailens log/trace export, screen/audio recording, and at least one participant who did not design the cues. Claims about target-user usability require blind or low-vision participants.

The thresholds below are engineering gates; target-user testing remains authoritative for usability.

## Phase A: Validate the existing guidance path

### A1. TalkBack channel exclusivity

Enable and disable TalkBack during guidance, trigger mixed-priority events, and replay guidance.

Pass when each event is heard once, app TTS releases focus while TalkBack owns output, app TTS recovers afterward, and replay uses the currently valid speech channel.

### A2. Cooldown and escalation

Produce HIGH, CRITICAL, HIGH, and CRITICAL events on the same cooldown key, including escalation within 0–800ms.

Pass when the first CRITICAL event is immediate, de-escalation remains filtered, later CRITICAL events cannot ratchet through cooldown by class flicker, and trace order matches generation, filtering, and output.

### A3. TTS failure

Disable or break the selected TTS engine so it transitions from INITIALIZING/READY to UNAVAILABLE.

Pass when `SPEECH_UNAVAILABLE` plays once per transition, does not repeat while the state remains unavailable, does not alert when TalkBack owns output or speech is disabled, and is distinguishable from `VISION_UNRELIABLE` and `INTERRUPTED`.

### A4. Live status and replay

Trigger an event, observe cooldown-empty frames, wait beyond `expiresAt`, and then replay it.

Pass when empty frames do not immediately clear the card, expired risks leave live status, replay remains available as history, and stopping the session clears both live status and replay history.

### A5. Lifecycle interruption

Test screen-off, incoming calls, Home/recents, permission revocation, and process reclamation.

Pass when unexpected guidance loss produces a one-shot haptic independent of speech, Activity recreation does not create a false interruption, and the UI cannot continue to claim active protection. If background or screen-off guidance becomes a product promise, complete the separate [background guidance plan](background-guidance-plan.md).

### A6. Audio coordination

Run podcast, map navigation, and TalkBack audio while producing normal, high-priority, and replay output.

Pass when guidance remains intelligible, ducking does not pump or persist, focus is restored after completion/interruption, and stale utterance callbacks cannot release focus owned by a newer utterance.

## Phase B: Haptic vocabulary blind test

Without revealing the cue, test navigation obstacles, `SPEECH_UNAVAILABLE`, `VISION_UNRELIABLE` (covered camera / too dark / ground judgement unavailable: "can't tell if the way ahead is clear, stop and check"), and `INTERRUPTED` while handheld, pocketed, and walking. Preserve a confusion matrix, missed-cue rate, recognition time, and device model.

`VISION_UNRELIABLE` deliberately covers three causes with one rhythm, because for a haptic-only user they mean the same thing: no "path blocked" does not mean the way is clear. Also record whether testers act correctly on it in each cause. If the ground-judgement case needs a different response from a covered camera that users cannot infer, split it into its own symbol rather than overloading the label.

Provisional engineering gate: at least 90% overall accuracy and no systematic confusion among the three failure alerts. If it fails, adjust timing rather than using visual copy to compensate for an indistinguishable cue.

## Phase C: Spatial earcon experiment

Earcons begin in a standalone experiment/calibration screen, outside production event decisions and disabled by default.

### C0. Minimal left/right prototype

- Use one short timbre with LEFT and RIGHT positions only.
- Generate enveloped stereo PCM at runtime with `AudioTrack`.
- Do not request audio focus for the earcon.
- Enable direction only for headphone routes; fall back to speech on speaker/mono output.
- Record p50/p95 event-to-audio-start latency.

Provisional gate: at least 90% left/right blind-test accuracy on bone-conduction headphones, detection in street noise, and no more than 250ms p95 start latency. If it fails, retain non-directional guidance instead of compensating with excessive volume.

### C1. Distance and category

Only after C0 passes, validate in order:

1. repetition rate for distance; never encode distance with volume;
2. confusion among person, vehicle, and static-obstacle timbres;
3. earcon followed by short speech for CRITICAL/HIGH events;
4. earcon-only MEDIUM/LOW output, which requires target-user evidence and cannot be justified from interruption reduction alone.

### C2. Degradation and settings

- Offer speech-only, earcon-only, and combined modes; choose the default after target-user testing.
- Provide a repeatable calibration screen.
- Fall back to speech when headphones disconnect, output is mono, or channel separation is inadequate.
- Trace output mode, audio route, earcon parameters, request time, and actual audio start.

## Evidence package

For every run preserve device/OS/headset/TTS/TalkBack versions, Sailens commit SHA, runtime profile and settings, logs and traces, screen/audio recordings, expected and actual outcomes with timestamps, reproducibility notes, and raw haptic/earcon trials rather than summary percentages alone.

## Definition of done

The existing guidance system moves from **Conditional Go** to device-level **Go** only after all Phase A gates pass. Failure or deferral of Phase B/C does not block a speech/haptic-only release, but the affected vocabulary or earcon feature must remain disabled. Change gates only from retained device evidence; do not substitute relaxed thresholds for missing capability.
