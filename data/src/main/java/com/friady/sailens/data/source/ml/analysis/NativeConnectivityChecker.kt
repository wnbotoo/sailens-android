package com.friady.sailens.data.source.ml.analysis

import android.util.Log
import com.friady.sailens.data.source.ml.NativeMlLibrary
import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.WalkPathConnectivity
import com.friady.sailens.domain.model.common.DirectionBias
import com.friady.sailens.domain.model.common.Severity
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.processor.analysis.ConnectivityAnalysisProcessor
import com.friady.sailens.domain.processor.analysis.ConnectivityChecker
import com.friady.sailens.domain.util.BooleanStabilizer
import com.friady.sailens.domain.util.NullableEnumStabilizer
import kotlin.math.abs

private const val TAG = "NativeConnectivity"

class NativeConnectivityChecker(
    private val config: AnalysisConfig,
) : ConnectivityAnalysisProcessor {
    private val fallbackChecker = ConnectivityChecker(config)
    private val blockedStabilizer = BooleanStabilizer(config.blockDebounceFrames)
    private val narrowingStabilizer = BooleanStabilizer(config.narrowDebounceFrames)
    private val biasStabilizer = NullableEnumStabilizer<DirectionBias>(config.biasDebounceFrames)
    private val sampleLayerRatios = config.sampleLayerRatios.toFloatArray()
    private var hasLoggedBackend = false

    override fun analyze(analysis: SegmentationAnalysis): WalkPathConnectivity {
        if (!NativeMlLibrary.isAvailable) {
            logBackend("Kotlin fallback; native library unavailable")
            return fallbackChecker.analyze(analysis)
        }

        val mask = analysis.passableMask
        val width = mask.width
        val height = mask.height
        val pixelCount = width * height
        if (pixelCount <= 0 || sampleLayerRatios.isEmpty()) {
            logBackend("Kotlin fallback; invalid connectivity input ${width}x$height")
            return fallbackChecker.analyze(analysis)
        }

        val wordCount = (pixelCount + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val packedBits = mask.toPackedBits().let { words ->
            if (words.size < wordCount) words.copyOf(wordCount) else words
        }
        val intOutputs = IntArray(INT_OUTPUT_COUNT)
        val floatOutputs = FloatArray(FLOAT_OUTPUT_COUNT)

        val nativeSuccess = runCatching {
            nativeAnalyzeConnectivity(
                passableWords = packedBits,
                width = width,
                height = height,
                sampleLayerRatios = sampleLayerRatios,
                minRunWidthRatio = config.minRunWidthRatio,
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
            logBackend("Kotlin fallback; native connectivity failed")
            return fallbackChecker.analyze(analysis)
        }
        logBackend("native")

        val totalLayers = intOutputs[OUT_TOTAL_LAYERS].coerceAtLeast(1)
        val validLayers = intOutputs[OUT_VALID_LAYERS]
        val verticalReachRatio = validLayers.toFloat() / totalLayers
        val widthRetentionP25 = floatOutputs[OUT_WIDTH_RETENTION_P25]
        val widthSlope = floatOutputs[OUT_WIDTH_SLOPE]
        val floodReachRatio = floatOutputs[OUT_FLOOD_REACH_RATIO]

        val blockageConfidence = calculateBlockageConfidence(
            verticalReachRatio = verticalReachRatio,
            floodReachRatio = floodReachRatio,
            widthP25 = widthRetentionP25,
        )
        val narrowingConfidence = calculateNarrowingConfidence(
            widthP25 = widthRetentionP25,
            slope = widthSlope,
        )
        val isBlockedRaw = blockageConfidence >= config.blockageThreshold
        val isNarrowingRaw = narrowingConfidence >= config.narrowingThreshold && !isBlockedRaw
        val suggestedBiasRaw = when (intOutputs[OUT_BIAS_CODE]) {
            BIAS_LEFT -> DirectionBias.LEFT
            BIAS_RIGHT -> DirectionBias.RIGHT
            else -> null
        }

        return WalkPathConnectivity(
            isBlocked = blockedStabilizer.update(isBlockedRaw),
            isNarrowing = narrowingStabilizer.update(isNarrowingRaw),
            suggestedBias = biasStabilizer.update(suggestedBiasRaw),
            blockageConfidence = blockageConfidence,
            narrowingConfidence = narrowingConfidence,
            blockageSeverity = Severity.fromConfidence(blockageConfidence),
            narrowingSeverity = Severity.fromConfidence(narrowingConfidence),
            verticalReachRatio = verticalReachRatio,
            validLayers = validLayers,
            totalLayers = totalLayers,
            widthRetentionAvg = floatOutputs[OUT_WIDTH_RETENTION_AVG],
            widthRetentionP25 = widthRetentionP25,
            widthSlope = widthSlope,
            floodReachRatio = floodReachRatio,
            floodWidthRetentionP25 = floatOutputs[OUT_FLOOD_WIDTH_P25],
            floodVisitedRatio = floatOutputs[OUT_FLOOD_VISITED_RATIO],
        )
    }

    override fun reset() {
        fallbackChecker.reset()
        blockedStabilizer.reset()
        narrowingStabilizer.reset()
        biasStabilizer.reset()
    }

    private fun calculateBlockageConfidence(
        verticalReachRatio: Float,
        floodReachRatio: Float,
        widthP25: Float,
    ): Float {
        var score = 0f
        val continuityPenaltyScale = when {
            widthP25 >= config.narrowEnterP25 -> 0.35f
            widthP25 >= config.narrowEnterP25 * 0.8f -> 0.6f
            else -> 1f
        }

        if (verticalReachRatio < config.reachRatioThreshold) {
            score += 0.35f * continuityPenaltyScale * (1 - verticalReachRatio / config.reachRatioThreshold)
        }
        if (floodReachRatio < config.minFloodReachRatio) {
            score += 0.35f * continuityPenaltyScale * (1 - floodReachRatio / config.minFloodReachRatio)
        }
        if (widthP25 < config.narrowEnterP25 * 0.6f) {
            score += 0.30f * (1 - widthP25 / (config.narrowEnterP25 * 0.6f))
        }

        return score.coerceIn(0f, 1f)
    }

    private fun calculateNarrowingConfidence(widthP25: Float, slope: Float): Float {
        var score = 0f

        if (widthP25 < config.narrowEnterP25) {
            score += 0.5f * (1 - widthP25 / config.narrowEnterP25)
        }
        if (slope < config.narrowSlopeThreshold) {
            score += 0.5f * (abs(slope) / abs(config.narrowSlopeThreshold)).coerceAtMost(1f)
        }

        return score.coerceIn(0f, 1f)
    }

    private fun logBackend(message: String) {
        if (hasLoggedBackend) return
        Log.i(TAG, "Connectivity checker backend: $message")
        hasLoggedBackend = true
    }

    private external fun nativeAnalyzeConnectivity(
        passableWords: LongArray,
        width: Int,
        height: Int,
        sampleLayerRatios: FloatArray,
        minRunWidthRatio: Float,
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
