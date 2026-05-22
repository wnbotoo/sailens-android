package com.friady.sailens.data.source.ml.semantic

import com.friady.sailens.data.source.ml.NativeMlLibrary

internal class YOLO26SemNativePostProcessor(
    private val config: SegmenterConfig,
) {
    fun postProcess(
        scores: FloatArray,
        resultMask: IntArray,
    ): Boolean {
        if (!NativeMlLibrary.isAvailable) return false
        if (scores.size != config.outputWidth * config.outputHeight * config.outputChannels) return false
        if (resultMask.size != config.outputWidth * config.outputHeight) return false

        return runCatching {
            nativeArgMax(
                scores = scores,
                resultMask = resultMask,
                width = config.outputWidth,
                height = config.outputHeight,
                channels = config.outputChannels,
            )
        }.getOrDefault(false)
    }

    private external fun nativeArgMax(
        scores: FloatArray,
        resultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
    ): Boolean
}
