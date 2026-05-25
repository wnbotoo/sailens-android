package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.model.perception.SegmentationAnalysisStats
import com.friady.sailens.domain.model.perception.SegmentationMask
import com.friady.sailens.domain.util.BooleanStabilizer
import com.friady.sailens.domain.util.FloatSmoother

/**
 * 语义分割分析器
 * 统一承接 Kotlin/native 单帧统计，并在 domain 层完成稳定化。
 */
class SegmentationAnalyzer(
    private val config: AnalysisConfig,
    private val classMapper: ClassMapper,
    private val statsExtractor: SegmentationStatsExtractor = KotlinSegmentationStatsExtractor(config, classMapper),
) : SegmentationAnalysisProcessor {
    // 稳定器
    private val roadRatioSmoother = FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val bottomCenterRoadRatioSmoother =
        FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val navigationPassableRatioSmoother =
        FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val trafficLightStabilizer =
        BooleanStabilizer(requiredFrames = config.trafficLightDebounceFrames)

    override fun analyze(
        segmentation: SegmentationMask,
        stats: SegmentationAnalysisStats?,
    ): SegmentationAnalysis {
        val frameStats = stats ?: statsExtractor.extract(segmentation)

        // 稳定化
        val stableRoadRatio = roadRatioSmoother.update(frameStats.roadRatio)
        val stableBottomCenterRoadRatio =
            bottomCenterRoadRatioSmoother.update(frameStats.bottomCenterRoadRatio)
        val stableNavigationPassableRatio =
            navigationPassableRatioSmoother.update(frameStats.navigationPassableRatio)
        val stableHasTrafficLight = trafficLightStabilizer.update(frameStats.hasTrafficLight)
        val dominantClassNames = frameStats.classCounts
            .withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(3)
            .map { indexedValue -> classMapper.getClassName(indexedValue.index) }

        return SegmentationAnalysis(
            passableMask = frameStats.passableMask,
            obstacleMask = frameStats.obstacleMask,
            roadRatio = stableRoadRatio,
            hasTrafficLight = stableHasTrafficLight,
            bottomCenterGroundDistribution = frameStats.bottomCenterGroundDistribution,
            bottomCenterRoadRatio = stableBottomCenterRoadRatio,
            bottomStats = frameStats.bottomStats,
            passablePixelCount = frameStats.passablePixelCount,
            navigationPassableRatio = stableNavigationPassableRatio,
            obstaclePixelCount = frameStats.obstaclePixelCount,
            dominantClassNames = dominantClassNames,
            segmentation = segmentation,
            width = segmentation.width,
            height = segmentation.height,
        )
    }

    override fun reset() {
        roadRatioSmoother.reset()
        bottomCenterRoadRatioSmoother.reset()
        navigationPassableRatioSmoother.reset()
        trafficLightStabilizer.reset()
    }
}
