package com.sailens.runtime.session

import com.sailens.runtime.LiteRtCompiledModelHandle
import com.sailens.runtime.ModelSource
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable

/**
 * A ready-to-run LiteRT model: the compiled model plus its input/output buffers and the accelerator
 * that actually won selection. Owns the handle and the buffers; [close] releases all of them.
 *
 * Buffers are exposed as lists (subgraph-IO order) so each model binds its own tensors by index —
 * the session itself stays model-agnostic. [model] is exposed for the rare buffer-introspection
 * case (e.g. zero-copy output handles); prefer [run].
 */
class LiteRtSession internal constructor(
    private val handle: LiteRtCompiledModelHandle,
    val source: ModelSource,
    val accelerator: Accelerator,
    val acceleratorSelection: String,
    val inputBuffers: List<TensorBuffer>,
    val outputBuffers: List<TensorBuffer>,
) : Closeable {

    val model: CompiledModel get() = handle.model

    /** Runs inference over the input buffers into the output buffers (synchronous on the caller). */
    fun run() {
        handle.model.run(inputBuffers, outputBuffers)
    }

    override fun close() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        runCatching { handle.close() }
    }
}
