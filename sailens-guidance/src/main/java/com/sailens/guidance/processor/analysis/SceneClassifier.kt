package com.sailens.guidance.processor.analysis

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.analysis.SceneElements
import com.sailens.guidance.model.perception.SegmentationAnalysis
import com.sailens.guidance.util.BooleanStabilizer

/**
 * 场景分类器
 */
class SceneClassifier(
    private val config: AnalysisConfig,
) {
    private val intersectionStabilizer = BooleanStabilizer(config.intersectionDebounceFrames)

    fun classify(analysis: SegmentationAnalysis): SceneElements {
        val hasIntersectionRaw = config.enableIntersectionFallback &&
            analysis.hasTrafficLight &&
            analysis.roadRatio > config.intersectionRoadRatioThreshold
        val hasIntersection = intersectionStabilizer.update(hasIntersectionRaw)

        return SceneElements(
            hasIntersection = hasIntersection,
            hasCrosswalk = false,
            hasTactilePaving = false,
            hasTrafficLight = analysis.hasTrafficLight
        )
    }

    fun reset() {
        intersectionStabilizer.reset()
    }
}
