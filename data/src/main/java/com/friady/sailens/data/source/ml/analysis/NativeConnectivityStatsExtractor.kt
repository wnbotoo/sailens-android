package com.friady.sailens.data.source.ml.analysis

import android.util.Log
import com.friady.sailens.data.source.ml.NativeMlLibrary
import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.ConnectivityStats
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.DirectionBias
import com.friady.sailens.domain.processor.analysis.ConnectivityStatsExtractor

private const val TAG = "NativeConnectivityStats"

class NativeConnectivityStatsExtractor(
    private val config: AnalysisConfig,
) : ConnectivityStatsExtractor {
    private val sampleLayerRatios = config.sampleLayerRatios.toFloatArray()
    private var hasLoggedBackend = false

    override fun extract(passableMask: BinaryMask): ConnectivityStats? {
        if (!NativeMlLibrary.isAvailable) {
            logFallback("native library unavailable")
            return null
        }

        val width = passableMask.width
        val height = passableMask.height
        val pixelCount = width * height
        if (pixelCount <= 0 || sampleLayerRatios.isEmpty()) {
            logFallback("invalid connectivity input ${width}x$height")
            return null
        }

        val wordCount = (pixelCount + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val packedBits = passableMask.copyPackedBits().let { words ->
            if (words.size < wordCount) words.copyOf(wordCount) else words
        }
        val intOutputs = IntArray(INT_OUTPUT_COUNT)
        val floatOutputs = FloatArray(FLOAT_OUTPUT_COUNT)

        val nativeSuccess = runCatching {
            nativeExtractConnectivityStats(
                passableWords = packedBits,
                width = width,
                height = height,
                sampleLayerRatios = sampleLayerRatios,
                minRunWidthRatio = config.minRunWidthRatio,
                bottomRatio = config.connectivityBottomRatio,
                floodWindowTopRatio = config.floodWindowTopRatio,
                maxFloodNodes = config.maxFloodNodes,
                floodEarlyStopReachRatio = config.floodEarlyStopReachRatio,
                floodEarlyStopWidthRetention = config.floodEarlyStopWidthRetention,
                directionBiasThreshold = config.directionBiasThreshold,
                intOutputs = intOutputs,
                floatOutputs = floatOutputs,
            )
        }.getOrDefault(false)

        if (!nativeSuccess) {
            logFallback("native connectivity extraction failed")
            return null
        }

        if (!hasLoggedBackend) {
            Log.i(TAG, "Connectivity stats backend: native")
            hasLoggedBackend = true
        }

        return ConnectivityStats(
            validLayers = intOutputs[OUT_VALID_LAYERS],
            totalLayers = intOutputs[OUT_TOTAL_LAYERS],
            widthRetentionAvg = floatOutputs[OUT_WIDTH_RETENTION_AVG],
            widthRetentionP25 = floatOutputs[OUT_WIDTH_RETENTION_P25],
            widthSlope = floatOutputs[OUT_WIDTH_SLOPE],
            floodReachRatio = floatOutputs[OUT_FLOOD_REACH_RATIO],
            floodWidthRetentionP25 = floatOutputs[OUT_FLOOD_WIDTH_P25],
            floodVisitedRatio = floatOutputs[OUT_FLOOD_VISITED_RATIO],
            suggestedBias = when (intOutputs[OUT_BIAS_CODE]) {
                BIAS_LEFT -> DirectionBias.LEFT
                BIAS_RIGHT -> DirectionBias.RIGHT
                else -> null
            },
        )
    }

    private fun logFallback(reason: String) {
        if (hasLoggedBackend) return
        Log.i(TAG, "Connectivity stats backend: Kotlin fallback; $reason")
        hasLoggedBackend = true
    }

    private external fun nativeExtractConnectivityStats(
        passableWords: LongArray,
        width: Int,
        height: Int,
        sampleLayerRatios: FloatArray,
        minRunWidthRatio: Float,
        bottomRatio: Float,
        floodWindowTopRatio: Float,
        maxFloodNodes: Int,
        floodEarlyStopReachRatio: Float,
        floodEarlyStopWidthRetention: Float,
        directionBiasThreshold: Float,
        intOutputs: IntArray,
        floatOutputs: FloatArray,
    ): Boolean

    private companion object {
        private const val OUT_VALID_LAYERS = 0
        private const val OUT_TOTAL_LAYERS = 1
        private const val OUT_BIAS_CODE = 2
        private const val INT_OUTPUT_COUNT = 3

        private const val OUT_WIDTH_RETENTION_AVG = 0
        private const val OUT_WIDTH_RETENTION_P25 = 1
        private const val OUT_WIDTH_SLOPE = 2
        private const val OUT_FLOOD_REACH_RATIO = 3
        private const val OUT_FLOOD_WIDTH_P25 = 4
        private const val OUT_FLOOD_VISITED_RATIO = 5
        private const val FLOAT_OUTPUT_COUNT = 6

        private const val BIAS_LEFT = -1
        private const val BIAS_RIGHT = 1
    }
}
