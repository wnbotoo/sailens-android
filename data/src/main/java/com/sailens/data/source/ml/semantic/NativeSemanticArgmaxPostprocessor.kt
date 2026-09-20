package com.sailens.data.source.ml.semantic

import com.sailens.data.source.ml.NativeMlLibrary
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.nativeValue

internal class NativeSemanticArgmaxPostprocessor(
    private val config: ModelTensorConfig,
) {
    fun argmaxScores(
        scores: FloatArray,
        resultMask: IntArray,
    ): Boolean {
        if (!NativeMlLibrary.isAvailable) return false
        if (scores.size != config.outputWidth * config.outputHeight * config.outputChannels) return false
        if (resultMask.size != config.outputWidth * config.outputHeight) return false

        return runCatching {
            nativeArgmaxScores(
                scores = scores,
                resultMask = resultMask,
                width = config.outputWidth,
                height = config.outputHeight,
                channels = config.outputChannels,
                scoreLayout = config.outputLayout.nativeValue,
            )
        }.getOrDefault(false)
    }

    private external fun nativeArgmaxScores(
        scores: FloatArray,
        resultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: Int,
    ): Boolean
}
