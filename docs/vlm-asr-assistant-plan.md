**English** | [简体中文](vlm-asr-assistant-plan.zh-CN.md)

# VLM + ASR assistant plan

This records the app-side plan for adding on-device VLM and ASR to Sailens. It does not pin a model
conversion pipeline, and it does not change the current realtime sem/det obstacle-avoidance
pipeline. The focus is code structure, trigger policy, ASR choice, and the boundaries of a first
release.

## 1. Baseline decisions

- **The minimum target device moves up to Snapdragon 8 Gen 2 / SM8550 class.** 8 Gen 1 stays useful
  as an experiment device for the existing vision/NPU path, but is not the product floor for the
  VLM/ASR assistant.
- **Keep the VLM under 1B parameters**, preferring models/formats that run reliably on the Qualcomm
  NPU path. FastVLM-0.5B and SmolVLM-500M are candidates, but the decision rests on measured Android
  NPU runtime, quantization format, latency, and power.
- **The VLM is a low-frequency assist path, not a realtime safety path.** Realtime obstacle
  avoidance stays with the sem/det pipeline; the VLM only answers "what is in front of me" or a
  user's question about the current view.
- **The first version ships manual shortcut triggering only**: the user presses an on-screen button
  or a hardware shortcut to summarize the current view, with no ASR involved.
- **ASR v1 uses Android's system `SpeechRecognizer`**, behind an interface. Do not use an LLM as an
  ASR: the LLM/VLM should consume text *after* ASR, not recognize audio directly.

## 2. Current repository state

Existing structure worth keeping:

- `domain/repository/SceneDescriber.kt` — the on-demand scene description interface already exists.
- `data/source/ml/vlm/*` — `LiteRtVlmEngine`, `VlmRuntimeFactory`, `UnavailableVlmRuntimeFactory`
  already provide the runtime seam.
- `SceneAnalysisViewModel` — holds `latestSceneResult`, consumes realtime analysis output and
  triggers TTS / haptics.
- `SpeechManager` — the TTS manager; announces `SceneEvent`s.

What is missing is not "put the VLM into `ProcessFrameUseCase`". It is:

- A **latest-frame snapshot layer** the assistant can read.
- A separate **assistant orchestration layer**.
- An ASR abstraction plus an Android system ASR implementation.
- A low-priority TTS channel that can carry generated text.

## 3. Target code structure

Leave the realtime pipeline alone and add an assistant vertical alongside it:

```text
domain
  model/assistant/
    AssistantTrigger.kt
    SceneSnapshot.kt
    SceneAssistantResult.kt
    VoiceCommand.kt
  repository/
    SceneDescriber.kt                 // exists, keep
    SceneSnapshotProvider.kt          // new: latest frame + lightweight realtime summary
    VoiceCommandRecognizer.kt         // new: ASR abstraction
  usecase/assistant/
    DescribeCurrentSceneUseCase.kt    // shortcut: snapshot -> VLM -> result
    AnswerSceneQuestionUseCase.kt     // ASR text question: snapshot + question -> VLM -> result

data
  source/ml/vlm/
    LiteRtVlmEngine.kt                // exists; swap in a real VlmRuntimeFactory later
    <Qualcomm/LiteRtLm>VlmRuntime.kt  // added when wiring a real model

presentation
  assistant/
    SceneAssistantViewModel.kt        // new: UI / lifecycle / cancellation / state
    SceneAssistantController.kt       // optional: trigger-source funnel and single-flight
  device/asr/
    AndroidSpeechRecognizerSource.kt  // new: SpeechRecognizer implementation
  device/input/
    AssistantShortcutTrigger.kt       // new: unifies on-screen button / hardware key into a trigger
  device/
    SpeechManager.kt                  // extend with low-priority speakText(); do NOT reuse
                                      // SceneEvent.messageKey
```

Module boundaries:

- `sailens-vlm` holds the engine contract: frame + complete prompt -> streamed text, no product policy.
- `sailens-describe` owns Describe's prompts, snapshot freshness and request scheduling.
- `sailens-runtime` owns model sources, sessions and accelerator selection for whatever runtime lands.
- `sailens-shell` owns UI, permissions, system ASR, hardware key input; `sailens-output` owns TTS arbitration.
- `:app` keeps doing Koin binding and decides which implementations an edition enables.

## 4. SceneSnapshotProvider

The VLM needs one "current frame" plus a little context per call. It does not need to hold a full
`SceneResult`'s masks/bitmaps.

Proposed:

```kotlin
data class SceneSnapshot(
    val frame: ImageFrame,
    val summary: SceneSnapshotSummary?,
    val capturedAtMs: Long,
)

data class SceneSnapshotSummary(
    val sequenceNumber: Long,
    val events: List<SceneEvent>,
    val obstacleCount: Int,
    val primaryObstacleLabel: String?,
    val passableRatio: Float?,
    val semanticBackend: String?,
    val obstacleBackend: String?,
)

interface SceneSnapshotProvider {
    fun currentSnapshot(
        maxFrameAgeMs: Long = 800,
        maxSceneAgeMs: Long = 1500,
    ): SceneSnapshot?
}
```

Implementation notes:

- Maintain the latest frame and latest analysis summary from `ImageFrameProvider.frames` and the
  realtime analysis output.
- Do not keep `SceneAnalysisViewModel.latestSceneResult` as the assistant's only data source; factor
  "hold the latest analysis summary" into a small store that both the ViewModel and the assistant
  read.
- When the snapshot is stale, fail and say "the current view is unavailable". **Never describe an
  old frame** — a description of what was there seconds ago is worse than no description for someone
  relying on it to move.
- Store only the lightweight summary; do not hold large masks or extra bitmaps long-term.

## 5. Trigger policy

Three trigger sources in v1, highest priority first:

```kotlin
enum class AssistantTriggerKind {
    QUICK_DESCRIBE,     // one-tap summary of the current view, no ASR
    VOICE_QUESTION,     // ASR recognized a user question
    AUTO_OPPORTUNISTIC, // optional later: low-frequency automatic description
}
```

### 5.1 Shortcut trigger (MVP, required)

Goal: one press, hear a summary of the current view.

- The on-screen button is the stable entry point; place it reachably on the realtime analysis main
  screen.
- A hardware key is an input adapter onto the *same* trigger; headset/Bluetooth media keys or one
  configurable shortcut are enough to start.
- The shortcut does not start ASR — it calls `SceneDescriber.describe(userPrompt = null)` directly.
- Keep output to one or two sentences, covering only what matters straight ahead: walkable area,
  obvious obstacles, people/vehicles/steps/doorways.
- Give a short haptic or UI loading state on trigger, so the user knows the press registered.

Hardware key caveats:

- The on-screen button comes first, because system dispatch of headset media keys depends on the
  foreground app, media session, vendor OS, and headset model.
- If you do wire media keys, enable them only while the app is foreground and analysis is running.
  Do not try to hijack media control globally.
- Wrap key mapping in `AssistantShortcutTrigger`; it only ever emits `QUICK_DESCRIBE` upward.

### 5.2 ASR question answering (second step)

Goal: the user holds/taps a voice button and asks "is there a step ahead?", "is anyone on my left?",
and the system answers from the current view.

- Use press-to-talk or tap-to-talk. **No always-on wake word.**
- ASR only converts audio to text.
- Rules are enough for intent parsing at first:
  - Empty text or low confidence: prompt to retry.
  - A question about the view: route to `AnswerSceneQuestionUseCase`.
  - Not about the view: v1 either ignores it or says only view-related questions are supported.
- If richer intent parsing is needed later, a small LLM can be added — still *after* ASR.

### 5.3 Automatic triggering (optional, later)

Automatic VLM must be very restrained or it will talk over realtime safety prompts:

- Off unless the user explicitly enables it.
- Only after realtime safety events have been quiet for a while — e.g. no high-priority obstacle or
  blocked prompt in the last 8–10 seconds.
- Scene relatively stable, user moving slowly, no safety event currently being announced.
- Start the global cooldown at 30–60 seconds.
- Automatic output must be low priority and must be cancelled or interrupted the moment a realtime
  safety event arrives.

## 6. Scheduling and arbitration

VLM generation is a seconds-scale task; it needs single-flight and cancellation:

- Only one VLM request may run at a time.
- A new `QUICK_DESCRIBE` may cancel the previous assistant request and re-run on the latest
  snapshot.
- Realtime safety events have top priority: when a `SceneEvent` arrives, assistant speech is
  interrupted or deferred.
- When a VLM request completes, check whether the snapshot has gone stale; if it finished too late,
  discard the result or mark it as "a moment ago".
- The VLM never participates in `collectLatest` realtime frame processing and never blocks
  `StartSceneAnalysisUseCase`.

Suggested TTS priorities:

```text
P0 realtime safety events: person / vehicle / blocked / path danger
P1 explicit quick describe result
P2 ASR/VLM answer
P3 automatic/opportunistic describe
```

`SpeechManager` can grow a generated-text entry point:

```kotlin
suspend fun speakText(
    text: String,
    priority: SpeechPriority,
    utteranceKind: String,
)
```

Do not disguise VLM-generated text as a `SceneEvent.messageKey` — it would pollute event merging,
string resources, and trace semantics.

## 7. ASR choice

Recommended for v1:

- Android's system `SpeechRecognizer`, wrapped as `VoiceCommandRecognizer`.
- Try the on-device recognizer first; fall back to the system default.
- Use `checkRecognitionSupport()` / model download support to decide whether the device supports the
  current language and offline models.
- Short utterances only, with a bounded recognition window — no always-on recording, no battery
  drain.
- Keep every Android API detail in `:presentation/device/asr`; domain only ever sees text.

Interface draft:

```kotlin
interface VoiceCommandRecognizer {
    val state: StateFlow<VoiceRecognitionState>
    suspend fun listenOnce(languageTag: String = "zh-CN"): Result<VoiceCommand>
    fun cancel()
}

data class VoiceCommand(
    val text: String,
    val confidence: Float?,
    val source: VoiceCommandSource,
)
```

Why not use an LLM as the ASR:

- Audio ASR and text/visual reasoning are different tasks; putting an LLM at the audio front-end
  adds latency, power draw, and model complexity.
- The system ASR reaches a usable MVP faster, and the interface lets it be replaced.
- If system ASR turns out unreliable on target devices, add a local Whisper-like / Parakeet-like
  implementation later — nothing above it changes.

## 8. VLM model selection principles

Do not judge candidates on parameter count alone; judge the on-device path:

- Can it convert to a format the target runtime loads (`.litertlm` or another Qualcomm NPU-loadable
  format)?
- Does it support an image input path, and can the image encoder also land on the target
  accelerator?
- Is answer quality still good enough after INT4/INT8 quantization?
- Latency of one description on an 8 Gen 2, peak memory, and thermal rise under repeated triggers.
- Is short-form description and visual QA stable in the target language?

Directions:

- **FastVLM-0.5B** — worth a spike, but the official focus is the Apple ecosystem and research
  implementations; Android NPU conversion/runtime needs verifying.
- **SmolVLM-500M-Instruct** — right size, a reasonable open-model candidate; Android NPU conversion
  and on-device quality still need verifying.
- **LiteRT-LM / Qualcomm NPU official sample models** — better for proving the NPU runtime,
  delivery, and performance first, even if not the final VLM.

The app side of v1 binds to no specific model; it only requires one real `VlmRuntimeFactory`.

## 9. Phased delivery

### Phase 1: quick summary of the current view

- Add `SceneSnapshotProvider` and a lightweight snapshot store.
- Add `DescribeCurrentSceneUseCase`.
- Add `SceneAssistantViewModel` or `SceneAssistantController`.
- Add a "summarize the current view" button on the main screen.
- Extend `SpeechManager.speakText()` and low-priority speech arbitration.
- The VLM runtime can stay unavailable; the UI hides or disables the entry point based on
  `SceneDescriber.isReady`.

Acceptance:

- With no VLM model, the app runs normally and the entry point is disabled or marked unavailable.
- With a VLM model, manual triggering does not disturb realtime obstacle prompts.
- Repeated taps never run multiple VLMs concurrently.
- Trigger, cancellation, duration, and backend all reach the logs.

### Phase 2: ASR question answering

- Add `VoiceCommandRecognizer` and `AndroidSpeechRecognizerSource`.
- Add microphone permission and press-to-talk UI.
- Add `AnswerSceneQuestionUseCase`.
- Reuse the same `SceneSnapshotProvider` and VLM scheduler.
- ASR failure, empty text, and non-view questions all get a clear response.

Acceptance:

- An explainable fallback with no network / no system offline model.
- Realtime safety prompts can still interrupt during recognition.
- Questions and answers never enter `SceneEvent.messageKey`.

### Phase 3: hardware shortcut

- Add `AssistantShortcutTrigger`.
- Accept off-screen triggers while foreground; emit a single `QUICK_DESCRIBE`.
- Gather device compatibility logs before promoting it to an official entry point.

Acceptance:

- The on-screen button and hardware key share one use case.
- Basic expectations of system media playback control are not broken.
- Key bounce is debounced.

### Phase 4: low-frequency automatic description

- Add a user toggle.
- Add quiet window, cooldown, movement state, and event-priority gates.
- Automatic results are announced at low priority and cancelled on a safety event.

Acceptance:

- Automatic description never steals the realtime obstacle announcement.
- Traces/logs explain why each trigger fired or was suppressed.

## 10. Refactors to avoid

- Do not put VLM calls into the per-frame flow of `ProcessFrameUseCase` or
  `StartSceneAnalysisUseCase`.
- Do not restructure the existing obstacle/semantic model config for the VLM's sake.
- Do not make `:domain` depend on Android `SpeechRecognizer`, `MediaSession`, or `KeyEvent`.
- Do not carry generated answer text on `SceneEvent`.
- Do not stuff ASR, VLM, and TTS back into `SceneAnalysisViewModel`; it already owns realtime
  analysis UI, and the assistant should be its own controller/viewmodel.

## 11. References

- LiteRT Qualcomm NPU: https://developers.google.com/edge/litert/next/qualcomm
- LiteRT-LM NPU: https://developers.google.com/edge/litert/next/litert_lm_npu
- Android `SpeechRecognizer`: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android media button handling: https://developer.android.com/media/legacy/media-buttons
- FastVLM: https://github.com/apple/ml-fastvlm
- SmolVLM-500M-Instruct: https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct
