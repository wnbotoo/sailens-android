package com.sailens.runtime.session

import com.sailens.runtime.ModelAcceleratorSelectionMode
import com.sailens.core.log.LogService
import com.google.ai.edge.litert.Accelerator
import kotlin.coroutines.cancellation.CancellationException

/** A successful selection: the built runtime, which accelerator won, and the trace label. */
data class ResolvedAccelerator<T>(
    val value: T,
    val accelerator: Accelerator,
    val selectionLabel: String,
)

/**
 * Runs the EXPLICIT / PREFER_BACKEND / FIRST_AVAILABLE orchestration over a model-agnostic [build]
 * step, with fallback and the `acceleratorSelection` trace label handled once. [build] must create
 * the runtime for ONE accelerator and throw on failure.
 *
 * This is the reusable accelerator-policy seam: today [LiteRtSessionFactory] uses it to build a
 * CompiledModel session; a future VLM engine can use the same orchestration with its own [build]
 * (e.g. creating an LlmInference) instead of duplicating the loop.
 */
object AcceleratorSelector {

    fun <T> select(
        selection: AcceleratorSelection,
        logTag: String,
        modelLabel: String,
        logService: LogService? = null,
        build: (Accelerator) -> T,
    ): ResolvedAccelerator<T> {
        val priorFailures = mutableListOf<String>()
        var lastError: Throwable? = null

        for (accelerator in selection.attemptOrder()) {
            try {
                logService?.info(logTag, "Initializing $modelLabel with $accelerator accelerator (${selection.mode})")
                val value = build(accelerator)
                return ResolvedAccelerator(
                    value = value,
                    accelerator = accelerator,
                    selectionLabel = selectionLabel(selection, priorFailures, accelerator),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = recordFailure(priorFailures, accelerator, logTag, modelLabel, logService, error)
            } catch (error: UnsatisfiedLinkError) {
                lastError = recordFailure(priorFailures, accelerator, logTag, modelLabel, logService, error)
            }
        }

        throw IllegalStateException(
            "No accelerator could initialize $modelLabel; attempts=${priorFailures.joinToString(" -> ")}",
            lastError,
        )
    }

    private fun recordFailure(
        priorFailures: MutableList<String>,
        accelerator: Accelerator,
        logTag: String,
        modelLabel: String,
        logService: LogService?,
        error: Throwable,
    ): Throwable {
        priorFailures += "${accelerator.name}:failed"
        logService?.warning(logTag, "$modelLabel $accelerator initialization failed", throwable = error)
        return error
    }

    private fun selectionLabel(
        selection: AcceleratorSelection,
        priorFailures: List<String>,
        active: Accelerator,
    ): String {
        val trail = (priorFailures + "${active.name}:active").joinToString(" -> ")
        return when (selection.mode) {
            ModelAcceleratorSelectionMode.EXPLICIT -> "explicit[$trail]"
            ModelAcceleratorSelectionMode.PREFER_BACKEND ->
                "prefer_backend[preferred=${selection.preferredAccelerator.name}; $trail]"
            ModelAcceleratorSelectionMode.FIRST_AVAILABLE -> "first_available[$trail]"
        }
    }
}
