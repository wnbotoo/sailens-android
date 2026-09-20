package com.sailens.guidance.processor.analysis

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.guidance.model.perception.SegmentationAnalysis
import com.sailens.guidance.model.perception.SegmentationMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClassifierTest {
    @Test
    fun `road ratio alone does not create intersection event`() {
        val classifier = SceneClassifier(
            AnalysisConfig(
                enableIntersectionFallback = false,
                intersectionDebounceFrames = 1,
            )
        )

        val result = classifier.classify(
            analysis = analysis(roadRatio = 0.8f, hasTrafficLight = true),
        )

        assertFalse(result.hasIntersection)
    }

    @Test
    fun `intersection fallback is explicitly gated`() {
        val classifier = SceneClassifier(
            AnalysisConfig(
                enableIntersectionFallback = true,
                intersectionDebounceFrames = 1,
            )
        )

        val result = classifier.classify(
            analysis = analysis(roadRatio = 0.8f, hasTrafficLight = true),
        )

        assertTrue(result.hasIntersection)
    }

    private fun analysis(
        roadRatio: Float,
        hasTrafficLight: Boolean,
    ): SegmentationAnalysis {
        val mask = BinaryMask(width = 1, height = 1)
        val segmentation = SegmentationMask(width = 1, height = 1, classMap = intArrayOf(0))
        return SegmentationAnalysis(
            passableMask = mask,
            obstacleMask = mask,
            roadRatio = roadRatio,
            hasTrafficLight = hasTrafficLight,
            bottomCenterGroundDistribution = emptyMap(),
            bottomCenterRoadRatio = roadRatio,
            bottomStats = BottomStats(
                coverage = roadRatio,
                maxRunWidth = 1,
                maxRunWidthRatio = 1f,
                maxRunRow = 0,
                maxRunStart = 0,
                maxRunEnd = 0,
                maxRunCenter = 0.5f,
            ),
            passablePixelCount = 1,
            navigationPassableRatio = roadRatio,
            obstaclePixelCount = 0,
            dominantClassNames = listOf("road"),
            segmentation = segmentation,
            width = 1,
            height = 1,
        )
    }
}
