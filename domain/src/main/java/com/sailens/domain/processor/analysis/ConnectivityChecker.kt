package com.sailens.domain.processor.analysis

import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.model.analysis.ConnectivityStats
import com.sailens.domain.model.analysis.WalkPathConnectivity
import com.sailens.core.mask.BinaryMask
import com.sailens.domain.model.common.DirectionBias
import com.sailens.domain.model.common.Severity
import com.sailens.domain.model.perception.SegmentationAnalysis
import com.sailens.domain.util.BooleanStabilizer
import com.sailens.domain.util.NullableEnumStabilizer
import kotlin.math.abs

/**
 * 连通性分析器。Native/JVM extractor 只提供单帧统计，稳定化和阈值判定统一在这里完成。
 */
class ConnectivityChecker(
    private val config: AnalysisConfig,
    private val statsExtractor: ConnectivityStatsExtractor = KotlinConnectivityStatsExtractor(config),
) : ConnectivityAnalysisProcessor {
    private val fallbackStatsExtractor = KotlinConnectivityStatsExtractor(config)
    private val blockedStabilizer = BooleanStabilizer(config.blockDebounceFrames)
    private val narrowingStabilizer = BooleanStabilizer(config.narrowDebounceFrames)
    private val biasStabilizer = NullableEnumStabilizer<DirectionBias>(config.biasDebounceFrames)

    override fun analyze(analysis: SegmentationAnalysis): WalkPathConnectivity {
        return analyze(analysis.passableMask)
    }

    override fun analyze(passableMask: BinaryMask): WalkPathConnectivity {
        // 主路径是注入的 native extractor;返回 null(native 库不可用)时才退到 JVM fallback。
        // fallback 的每帧分配只在这条降级路径上发生,见 KotlinConnectivityStatsExtractor 的说明。
        val stats = statsExtractor.extract(passableMask)
            ?: fallbackStatsExtractor.extract(passableMask)
        return buildConnectivity(stats)
    }

    private fun buildConnectivity(stats: ConnectivityStats): WalkPathConnectivity {
        val totalLayers = stats.totalLayers.coerceAtLeast(1)
        val verticalReachRatio = stats.validLayers.toFloat() / totalLayers

        val blockageConfidence = calculateBlockageConfidence(
            verticalReachRatio = verticalReachRatio,
            floodReachRatio = stats.floodReachRatio,
            widthP25 = stats.widthRetentionP25,
        )
        val blockageReason = blockageReason(
            verticalReachRatio = verticalReachRatio,
            floodReachRatio = stats.floodReachRatio,
            widthP25 = stats.widthRetentionP25,
        )
        val narrowingConfidence = calculateNarrowingConfidence(
            widthP25 = stats.widthRetentionP25,
            slope = stats.widthSlope,
        )

        val isBlockedRaw = blockageConfidence >= config.blockageThreshold
        val isNarrowingRaw = narrowingConfidence >= config.narrowingThreshold && !isBlockedRaw

        return WalkPathConnectivity(
            isBlocked = blockedStabilizer.update(isBlockedRaw),
            isNarrowing = narrowingStabilizer.update(isNarrowingRaw),
            suggestedBias = biasStabilizer.update(stats.suggestedBias),
            blockageConfidence = blockageConfidence,
            narrowingConfidence = narrowingConfidence,
            blockageReason = blockageReason,
            blockageSeverity = Severity.fromConfidence(blockageConfidence),
            narrowingSeverity = Severity.fromConfidence(narrowingConfidence),
            verticalReachRatio = verticalReachRatio,
            validLayers = stats.validLayers,
            totalLayers = totalLayers,
            widthRetentionAvg = stats.widthRetentionAvg,
            widthRetentionP25 = stats.widthRetentionP25,
            widthSlope = stats.widthSlope,
            floodReachRatio = stats.floodReachRatio,
            floodWidthRetentionP25 = stats.floodWidthRetentionP25,
            floodVisitedRatio = stats.floodVisitedRatio,
        )
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

    private fun blockageReason(
        verticalReachRatio: Float,
        floodReachRatio: Float,
        widthP25: Float,
    ): String {
        val reasons = mutableListOf<String>()
        if (verticalReachRatio < config.reachRatioThreshold) {
            reasons += "vertical"
        }
        if (floodReachRatio < config.minFloodReachRatio) {
            reasons += "flood"
        }
        if (widthP25 < config.narrowEnterP25 * 0.6f) {
            reasons += "width"
        }
        return reasons.ifEmpty { listOf("none") }.joinToString("+")
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

    override fun reset() {
        blockedStabilizer.reset()
        narrowingStabilizer.reset()
        biasStabilizer.reset()
    }
}
