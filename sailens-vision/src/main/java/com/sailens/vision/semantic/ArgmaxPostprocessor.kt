package com.sailens.vision.semantic

import com.sailens.runtime.ModelTensorConfig

/**
 * Generic terminal postprocess: score tensor to class map, native first and Kotlin second.
 *
 * This is the step every semantic run ends with when no fused postprocessor claimed the frame. It
 * is deliberately the only place that decides between the two argmax implementations, so the
 * `native_argmax` / `kotlin_argmax` trace names have exactly one source.
 */
class ArgmaxPostprocessor(
    config: ModelTensorConfig,
    /** Pure-JVM argmax, used when the native kernel is unavailable or declines. */
    private val kotlinArgmax: (scores: FloatArray, resultMask: IntArray) -> Unit,
) {
    private val nativeArgmax = NativeSemanticArgmaxPostprocessor(config)

    /** Writes class ids into [resultMask] and returns the trace name of the path that ran. */
    fun argmax(scores: FloatArray, resultMask: IntArray): String {
        return if (nativeArgmax.argmaxScores(scores, resultMask)) {
            BACKEND_NATIVE
        } else {
            kotlinArgmax(scores, resultMask)
            BACKEND_KOTLIN
        }
    }

    companion object {
        const val BACKEND_NATIVE: String = "native_argmax"
        const val BACKEND_KOTLIN: String = "kotlin_argmax"
    }
}
