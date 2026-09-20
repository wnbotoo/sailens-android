package com.sailens.data.source.ml.session

import android.content.Context
import com.sailens.data.source.ml.LiteRtCompiledModelFactory
import com.sailens.data.source.ml.LiteRtCompiledModelHandle
import com.sailens.data.source.ml.ModelSource
import com.sailens.core.log.LogService
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.TensorBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds a [LiteRtSession] for a model source: accelerator selection + fallback (via
 * [AcceleratorSelector]), per-attempt source resolution, model compilation, and IO buffer creation.
 *
 * Buffer creation is inside the per-accelerator attempt on purpose: `createOutputBuffers` can fail
 * for a specific accelerator (e.g. an int8 TransposeConv that the CPU/GPU prepare path rejects but
 * the QNN HTP compiler accepts), and that failure must fall through to the next accelerator rather
 * than abort the whole init.
 */
object LiteRtSessionFactory {

    fun create(
        context: Context,
        sourceResolver: (Accelerator) -> ModelSource,
        selection: AcceleratorSelection,
        logTag: String,
        modelLabel: String,
        logService: LogService? = null,
    ): LiteRtSession {
        val resolved = AcceleratorSelector.select(selection, logTag, modelLabel, logService) { accelerator ->
            val source = sourceResolver(accelerator)
            buildModel(context, source, accelerator, logService)
        }
        val built = resolved.value
        return LiteRtSession(
            handle = built.handle,
            source = built.source,
            accelerator = resolved.accelerator,
            acceleratorSelection = resolved.selectionLabel,
            inputBuffers = built.inputBuffers,
            outputBuffers = built.outputBuffers,
        )
    }

    private fun buildModel(
        context: Context,
        source: ModelSource,
        accelerator: Accelerator,
        logService: LogService?,
    ): BuiltModel {
        source.ensureAvailable(context)
        val handle = LiteRtCompiledModelFactory.create(
            context = context,
            source = source,
            accelerator = accelerator,
            logService = logService,
        )
        return try {
            BuiltModel(
                handle = handle,
                source = source,
                inputBuffers = handle.model.createInputBuffers(),
                outputBuffers = handle.model.createOutputBuffers(),
            )
        } catch (error: CancellationException) {
            handle.close()
            throw error
        } catch (error: Exception) {
            handle.close()
            throw error
        } catch (error: UnsatisfiedLinkError) {
            handle.close()
            throw error
        }
    }

    private data class BuiltModel(
        val handle: LiteRtCompiledModelHandle,
        val source: ModelSource,
        val inputBuffers: List<TensorBuffer>,
        val outputBuffers: List<TensorBuffer>,
    )
}
