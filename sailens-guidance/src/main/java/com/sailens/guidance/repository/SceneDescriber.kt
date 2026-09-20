package com.sailens.guidance.repository

import com.sailens.core.frame.ImageFrame
import kotlinx.coroutines.flow.Flow

/**
 * On-demand scene description via a vision-language model (VLM).
 *
 * This is a SEPARATE, low-frequency path from the realtime perception pipeline (sem/det). The
 * realtime models answer "is something in my way right now" every frame; the [SceneDescriber]
 * answers "what is in front of me" when the user asks — seconds-scale, not per-frame.
 *
 * The domain stays runtime-agnostic: the concrete engine (LiteRT-LM / MediaPipe LLM Inference) and
 * its accelerator (NPU/GPU/CPU) live in the data layer.
 */
interface SceneDescriber {

    /**
     * True when a runtime and a model bundle exist, so [initialize] has a chance of succeeding.
     *
     * Distinct from [isReady] and deliberately cheap: the UI has to decide whether to offer the
     * "describe scene" action *before* paying for a model load that takes seconds and hundreds of
     * megabytes. Asking [isReady] instead would mean either loading the VLM on every cold start or
     * offering an action that turns out to be dead.
     */
    val isAvailable: Boolean

    /** True once a model is loaded and [describe] can run. */
    val isReady: Boolean

    /** Loads the VLM. Throws if no model/runtime is available (caller decides if that is fatal). */
    suspend fun initialize()

    /**
     * Describes [request]'s frame, **streaming** the text as it is decoded.
     *
     * Streaming is not a nicety here, it is the product requirement. A VLM is autoregressive: the
     * full sentence only exists once the last token is decoded, which on a phone is seconds after
     * the user pressed the button. Waiting for it means seconds of silence, and silence is the one
     * thing this app must never answer with — the user cannot see a spinner. So the caller speaks
     * [SceneDescriptionChunk.Delta]s at clause boundaries and starts talking while the model is
     * still working.
     *
     * The flow is cold: collecting starts a generation, cancelling the collection stops it. It
     * emits zero or more [SceneDescriptionChunk.Delta] followed by exactly one
     * [SceneDescriptionChunk.Completed], or fails.
     */
    fun describe(request: SceneDescriptionRequest): Flow<SceneDescriptionChunk>

    /** Releases the model and frees native resources. */
    suspend fun release()
}

/**
 * @param frame the image to describe.
 * @param userPrompt optional question ("是不是有台阶?"); when null the engine uses its system prompt.
 */
data class SceneDescriptionRequest(
    val frame: ImageFrame,
    val userPrompt: String? = null,
)

/** One step of a streaming description. */
sealed interface SceneDescriptionChunk {

    /**
     * Text decoded since the previous chunk. Deltas are token-shaped, not sentence-shaped — a
     * consumer that speaks them must buffer to a clause boundary itself.
     */
    data class Delta(val text: String) : SceneDescriptionChunk

    /** Terminal chunk: the whole description plus timings. */
    data class Completed(val description: SceneDescription) : SceneDescriptionChunk
}

/**
 * @param text the full generated description.
 * @param backend the accelerator selection trace (e.g. "prefer_backend[preferred=NPU; ...]").
 * @param latencyMs wall-clock time for the whole generation.
 * @param timeToFirstTokenMs wall-clock time until the first [SceneDescriptionChunk.Delta].
 *   This, not [latencyMs], is the number the product gate is about: it is how long the user stood
 *   there hearing nothing after asking. Tracked separately so it can be measured on device.
 */
data class SceneDescription(
    val text: String,
    val backend: String,
    val latencyMs: Long,
    val timeToFirstTokenMs: Long,
)
