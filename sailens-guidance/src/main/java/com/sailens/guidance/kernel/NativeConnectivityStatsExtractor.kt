package com.sailens.guidance.kernel

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.processor.analysis.ConnectivityStatsExtractor
import com.sailens.core.log.LogService

private const val TAG = "NativeConnectivityStats"

class NativeConnectivityStatsExtractor(
    private val config: AnalysisConfig,
    private val logService: LogService,
) : ConnectivityStatsExtractor {
    private val sampleLayerRatios = config.sampleLayerRatios.toFloatArray()
    private var hasLoggedBackend = false

    private val intOutputs = IntArray(INT_OUTPUT_COUNT)
    private val floatOutputs = FloatArray(FLOAT_OUTPUT_COUNT)

    // Reused across frames so packing the passable mask into JNI-friendly words costs no per-frame
    // allocation. Single-threaded: extract() runs synchronously on the perception dispatcher.
    private var packedBitsBuffer = LongArray(0)

    override fun extract(passableMask: BinaryMask): ConnectivityStats? {
        if (!NativeGuidanceLibrary.isAvailable) {
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

        packedBitsBuffer = passableMask.copyPackedBitsInto(packedBitsBuffer)
        val packedBits = packedBitsBuffer
        intOutputs.fill(0)
        floatOutputs.fill(0f)

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
            logService.info(TAG, "Connectivity stats backend: native")
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
        logService.info(TAG, "Connectivity stats backend: Kotlin fallback; $reason")
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
