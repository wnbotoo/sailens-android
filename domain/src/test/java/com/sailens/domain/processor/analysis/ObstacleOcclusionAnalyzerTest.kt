package com.sailens.domain.processor.analysis

import com.sailens.domain.config.PerceptionConfig
import com.sailens.core.mask.BinaryMask
import com.sailens.domain.model.common.DirectionZone
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.domain.model.common.UrgencyLevel
import com.sailens.domain.model.perception.DetectedObstacle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleOcclusionAnalyzerTest {

    // occlusionMinBottomY = 0.55, occlusionBandHeightRatio = 0.35 (defaults)
    private val analyzer = ObstacleOcclusionAnalyzer(config = PerceptionConfig())

    @Test
    fun `carves ground-contact band of a near obstacle out of passable mask`() {
        val passable = filledMask(10, 10)

        // Full-frame box: maxY = 1.0 >= 0.55, band spans y in [0.65, 1.0] -> rows 6..9 (x all).
        val result = analyzer.analyze(passable, listOf(obstacle(NormalizedRect(0f, 0f, 1f, 1f))))

        assertEquals(0.4f, result.occludedPassableRatio, 1e-6f)
        assertEquals(60, result.effectivePassableMask.countTrue())
        assertFalse(result.effectivePassableMask.get(0, 9)) // ground row cleared
        assertTrue(result.effectivePassableMask.get(0, 0))  // top row untouched
        // original mask must stay intact (single-directional, copy-on-write)
        assertEquals(100, passable.countTrue())
    }

    @Test
    fun `ignores far obstacle whose bbox does not reach the near band`() {
        val passable = filledMask(10, 10)

        // maxY = 0.4 < 0.55 -> gated out.
        val result = analyzer.analyze(passable, listOf(obstacle(NormalizedRect(0f, 0f, 0.3f, 0.4f))))

        assertSame(passable, result.effectivePassableMask)
        assertEquals(0f, result.occludedPassableRatio, 1e-6f)
    }

    @Test
    fun `ignores near side obstacle outside navigation corridor`() {
        val passable = filledMask(10, 10)

        // Bottom reaches the near band, but x=0..0.1 is outside the default near corridor
        // (x=0.25..0.75), so it must not affect path connectivity.
        val result = analyzer.analyze(passable, listOf(obstacle(NormalizedRect(0f, 0.6f, 0.1f, 0.4f))))

        assertSame(passable, result.effectivePassableMask)
        assertEquals(0f, result.occludedPassableRatio, 1e-6f)
    }

    @Test
    fun `returns original mask when band does not overlap passable pixels`() {
        // Only the top-left pixel is passable; the ground band (rows 6..9) misses it.
        val passable = BinaryMask(10, 10).apply { set(0, 0, true) }

        val result = analyzer.analyze(passable, listOf(obstacle(NormalizedRect(0f, 0f, 1f, 1f))))

        assertSame(passable, result.effectivePassableMask)
        assertEquals(0f, result.occludedPassableRatio, 1e-6f)
    }

    @Test
    fun `returns original mask when there are no obstacles`() {
        val passable = filledMask(4, 4)

        val result = analyzer.analyze(passable, emptyList())

        assertSame(passable, result.effectivePassableMask)
        assertEquals(0f, result.occludedPassableRatio, 1e-6f)
    }

    private fun filledMask(width: Int, height: Int): BinaryMask {
        return BinaryMask(width, height).apply {
            for (y in 0 until height) for (x in 0 until width) set(x, y, true)
        }
    }

    private fun obstacle(boundingBox: NormalizedRect): DetectedObstacle {
        return DetectedObstacle(
            boundingBox = boundingBox,
            category = ObstacleCategory.PERSON,
            zone = DirectionZone.CENTER,
            distance = DistanceLevel.NEAR,
            urgency = UrgencyLevel.HIGH,
            confidence = 0.9f,
            stableFrames = 3,
            areaRatio = boundingBox.area,
            timestamp = 0L,
        )
    }
}
