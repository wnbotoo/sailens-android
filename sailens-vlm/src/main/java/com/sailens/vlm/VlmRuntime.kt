package com.sailens.vlm

import android.content.Context
import com.sailens.core.frame.ImageFrame
import com.google.ai.edge.litert.Accelerator

/**
 * The actual VLM inference call, isolated behind a thin seam so [LiteRtVlmEngine] and the domain
 * compile WITHOUT pulling a GenAI dependency. A VLM does not use the `CompiledModel` + `run()` path
 * the CNN models use (it tokenizes, encodes the image, and decodes autoregressively), so it gets its
 * own runtime — but it reuses the same accelerator-selection policy
 * ([com.sailens.runtime.session.AcceleratorSelector]).
 *
 * To enable VLM: implement [VlmRuntimeFactory] with LiteRT-LM or MediaPipe LLM Inference
 * (`LlmInference` + vision modality), add the dependency, and inject it into [LiteRtVlmEngine]. See
 * docs/vlm-asr-assistant-plan.md.
 */
interface VlmRuntime : AutoCloseable {

    /**
     * Generates text for [prompt] grounded on [image] (null = text-only), reporting each decoded
     * piece to [onToken] as it arrives, and returning the full text.
     *
     * A callback rather than a `Flow` on purpose: every candidate library (LiteRT-LM,
     * MediaPipe `LlmInference`) hands tokens to a listener from its own thread, so a callback is
     * what an implementation actually has. [LiteRtVlmEngine] is what turns this into a Flow —
     * keeping the coroutine machinery on one side of the seam instead of in every runtime.
     *
     * Called on a single dedicated thread, so implementations need no internal locking. Runs to
     * completion; [shouldStop] is how cancellation gets in.
     *
     * @param shouldStop polled between tokens. Returning true ends generation early and the
     *   partial text is returned. An autoregressive decode can run for seconds, so a cancelled
     *   request that cannot stop mid-decode keeps the accelerator busy and delays the next one.
     */
    fun generate(
        prompt: String,
        image: ImageFrame?,
        shouldStop: () -> Boolean = { false },
        onToken: (String) -> Unit = {},
    ): String
}

interface VlmRuntimeFactory {
    /** Whether a real runtime + model are wired (lib present, model bundle resolvable). */
    fun isAvailable(context: Context): Boolean

    /** Loads the model for [accelerator]; MUST throw if it cannot run on that backend so the
     * accelerator selector can fall back to the next one. */
    fun create(context: Context, config: VlmModelConfig, accelerator: Accelerator): VlmRuntime
}

/**
 * Default factory: no GenAI runtime wired. Keeps [LiteRtVlmEngine] gracefully unavailable (the app
 * hides the "describe scene" action) until a real [VlmRuntimeFactory] is provided.
 */
object UnavailableVlmRuntimeFactory : VlmRuntimeFactory {
    override fun isAvailable(context: Context): Boolean = false

    override fun create(context: Context, config: VlmModelConfig, accelerator: Accelerator): VlmRuntime =
        throw IllegalStateException(
            "No VLM runtime wired. Provide a LiteRT-LM / MediaPipe VlmRuntimeFactory (see docs/vlm-asr-assistant-plan.md)."
        )
}
