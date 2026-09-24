package com.sailens.vlm

import android.content.Context
import android.os.SystemClock
import com.sailens.runtime.session.AcceleratorSelection
import com.sailens.runtime.session.AcceleratorSelector
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.vlm.SceneDescriber
import com.sailens.vlm.SceneDescription
import com.sailens.vlm.SceneDescriptionChunk
import com.sailens.vlm.SceneDescriptionRequest
import com.sailens.core.log.LogService
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * VLM scene-description engine. Reuses the CNN path's accelerator-selection policy
 * ([AcceleratorSelector]) but runs through a [VlmRuntime] (LLM/GenAI API) instead of a
 * `CompiledModel`, because a VLM is autoregressive, not a single forward pass.
 *
 * The actual LLM library is injected via [runtimeFactory]; with the default
 * [UnavailableVlmRuntimeFactory] the engine reports `isReady == false` and [initialize] throws, so
 * the app simply hides the "describe scene" action until a model is wired. See
 * docs/vlm-asr-assistant-plan.md for the LiteRT-LM / MediaPipe runtime wiring.
 */
public class LiteRtVlmEngine(
    private val context: Context,
    private val config: VlmModelConfig,
    private val runtimeFactory: VlmRuntimeFactory = UnavailableVlmRuntimeFactory,
    private val logService: LogService? = null,
) : SceneDescriber {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var runtime: VlmRuntime? = null
    private var backendLabel: String = MlRuntimeInfo.UNKNOWN

    @Volatile
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override val isAvailable: Boolean get() = runtimeFactory.isAvailable(context)

    override suspend fun initialize() {
        if (_isReady) return

        withContext(singleThreadDispatcher) {
            // Checked again here, where calls are serialized: two callers can both pass the check
            // above, and without this the second one would tear down the runtime the first had just
            // built — possibly under a description already running on it.
            if (_isReady) return@withContext
            cleanupInternal()
            check(runtimeFactory.isAvailable(context)) {
                "VLM runtime unavailable (no GenAI library/model wired)."
            }
            // Same accelerator selection + fallback as the CNN models; only `build` differs (creates
            // a VlmRuntime instead of a CompiledModel session).
            val resolved = AcceleratorSelector.select(
                selection = AcceleratorSelection(
                    mode = config.acceleratorSelectionMode,
                    preferredBackend = config.acceleratorBackend,
                    fallbackOrder = ACCELERATOR_FALLBACK_ORDER,
                ),
                logTag = TAG,
                modelLabel = "VLM",
                logService = logService,
            ) { accelerator ->
                runtimeFactory.create(context, config, accelerator)
            }
            runtime = resolved.value
            backendLabel = resolved.selectionLabel
            _isReady = true
            logService?.info(TAG, "VLM engine initialized with ${resolved.accelerator}")
        }
    }

    /**
     * Bridges [VlmRuntime]'s token callback into a cold Flow.
     *
     * `trySendBlocking` rather than `trySend`: the callback fires on [singleThreadDispatcher]'s
     * thread while the collector runs elsewhere, so blocking the producer for a moment is harmless,
     * whereas `trySend` on a rendezvous channel would silently DROP tokens — and a dropped token is
     * a word missing from the middle of a sentence spoken to someone who cannot check the screen.
     *
     * A runtime that cannot stream (returns everything at the end and never calls `onToken`) is
     * still correct here: it simply produces no [SceneDescriptionChunk.Delta], and the single
     * [SceneDescriptionChunk.Completed] carries the whole text. Consumers must handle that.
     */
    override fun describe(request: SceneDescriptionRequest): Flow<SceneDescriptionChunk> = channelFlow {
        withContext(singleThreadDispatcher) {
            // Read on the engine's own thread, after any initialize/release queued before this
            // request: a reference taken outside could be a runtime that is closed by the time the
            // decode starts.
            val activeRuntime = runtime ?: error("VLM engine not initialized")
            val start = SystemClock.uptimeMillis()
            var firstTokenAt = 0L

            val text = activeRuntime.generate(
                prompt = buildPrompt(request.userPrompt),
                image = request.frame,
                shouldStop = { !isActive },
                onToken = { token ->
                    if (firstTokenAt == 0L) firstTokenAt = SystemClock.uptimeMillis()
                    if (token.isNotEmpty()) trySendBlocking(SceneDescriptionChunk.Delta(token))
                },
            )

            val finishedAt = SystemClock.uptimeMillis()
            val description = SceneDescription(
                text = text.trim(),
                backend = backendLabel,
                latencyMs = finishedAt - start,
                // No token ever arrived (non-streaming runtime): time-to-first-token is the whole
                // generation, which is the honest number — the user heard nothing until the end.
                timeToFirstTokenMs = (if (firstTokenAt == 0L) finishedAt else firstTokenAt) - start,
            )
            logService?.info(
                TAG,
                "Scene description generated",
                mapOf(
                    "latencyMs" to description.latencyMs,
                    "timeToFirstTokenMs" to description.timeToFirstTokenMs,
                    "chars" to description.text.length,
                    "backend" to description.backend,
                ),
            )
            send(SceneDescriptionChunk.Completed(description))
        }
    }

    override suspend fun release() {
        withContext(singleThreadDispatcher) { cleanupInternal() }
    }

    private fun cleanupInternal() {
        runCatching { runtime?.close() }
        runtime = null
        backendLabel = MlRuntimeInfo.UNKNOWN
        _isReady = false
    }

    private fun buildPrompt(userPrompt: String?): String =
        if (userPrompt.isNullOrBlank()) config.systemPrompt
        else "${config.systemPrompt}\n\n$userPrompt"

    private companion object {
        const val TAG = "LiteRtVlmEngine"

        // Preference order, not an availability claim: as of the 2026-09 device testing a plain
        // .litertlm bundle does NOT get the Qualcomm NPU (that needs per-SoC TF_LITE_AUX embedded in
        // the model), so in practice this resolves to GPU today. Keeping NPU first costs nothing and
        // means the path lights up on its own if/when an NPU-capable bundle is wired.
        val ACCELERATOR_FALLBACK_ORDER = listOf(
            Accelerator.NPU,
            Accelerator.GPU,
            Accelerator.CPU,
        )
    }
}
