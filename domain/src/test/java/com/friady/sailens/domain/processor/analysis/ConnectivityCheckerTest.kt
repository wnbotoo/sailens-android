package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.BinaryMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityCheckerTest {
    @Test
    fun `layer scan samples lower image ratios for forward corridor`() {
        val checker = ConnectivityChecker(
            AnalysisConfig(
                sampleLayerRatios = listOf(0.85f, 0.70f, 0.55f),
                minRunWidthRatio = 0.20f,
                reachRatioThreshold = 0.40f,
                minFloodReachRatio = 0.20f,
                blockageThreshold = 0.30f,
                blockDebounceFrames = 1,
                narrowDebounceFrames = 1,
                biasDebounceFrames = 1,
            )
        )

        val mask = BinaryMask(width = 10, height = 10).apply {
            for (y in 5..9) {
                for (x in 2..7) {
                    set(x, y, true)
                }
            }
        }

        val result = checker.analyze(mask)

        assertFalse(result.isBlocked)
        assertEquals(3, result.validLayers)
        assertTrue(result.verticalReachRatio >= 1.0f)
    }

    @Test
    fun `flood fill seeds from strongest bottom run row when last row is empty`() {
        val checker = ConnectivityChecker(
            AnalysisConfig(
                sampleLayerRatios = listOf(0.30f, 0.40f, 0.50f),
                minRunWidthRatio = 0.20f,
                reachRatioThreshold = 0.40f,
                minFloodReachRatio = 0.20f,
                blockageThreshold = 0.30f,
                blockDebounceFrames = 1,
                narrowDebounceFrames = 1,
                biasDebounceFrames = 1,
            )
        )

        val mask = BinaryMask(width = 10, height = 10).apply {
            for (y in 3..8) {
                for (x in 2..7) {
                    set(x, y, true)
                }
            }
        }

        val result = checker.analyze(mask)

        assertFalse(result.isBlocked)
        assertTrue(result.floodReachRatio >= 0.20f)
    }

    @Test
    fun `wide near field corridor does not become blocked from short reach alone`() {
        val checker = ConnectivityChecker(
            AnalysisConfig(
                sampleLayerRatios = listOf(0.85f, 0.70f, 0.55f),
                minRunWidthRatio = 0.20f,
                reachRatioThreshold = 0.40f,
                minFloodReachRatio = 0.22f,
                blockageThreshold = 0.50f,
                blockDebounceFrames = 1,
                narrowDebounceFrames = 1,
                biasDebounceFrames = 1,
            )
        )

        val mask = BinaryMask(width = 10, height = 20).apply {
            for (y in 18..19) {
                for (x in 2..7) {
                    set(x, y, true)
                }
            }
        }

        val result = checker.analyze(mask)

        assertFalse(result.isBlocked)
        assertTrue(result.widthRetentionP25 >= 0.6f)
        assertTrue(result.floodReachRatio < 0.22f)
    }

    @Test
    fun `flood fill bridges one row gap inside lower corridor`() {
        val checker = ConnectivityChecker(
            AnalysisConfig(
                sampleLayerRatios = listOf(0.85f, 0.70f, 0.55f),
                minRunWidthRatio = 0.20f,
                reachRatioThreshold = 0.40f,
                minFloodReachRatio = 0.22f,
                blockageThreshold = 0.50f,
                blockDebounceFrames = 1,
                narrowDebounceFrames = 1,
                biasDebounceFrames = 1,
            )
        )

        val mask = BinaryMask(width = 10, height = 12).apply {
            for (y in 5..11) {
                if (y == 8) continue
                for (x in 2..7) {
                    set(x, y, true)
                }
            }
        }

        val result = checker.analyze(mask)

        assertFalse(result.isBlocked)
        assertTrue(result.floodReachRatio >= 0.30f)
    }

    @Test
    fun `precomputed connectivity stats path matches direct analysis path`() {
        val config = AnalysisConfig(
            sampleLayerRatios = listOf(0.85f, 0.70f, 0.55f),
            minRunWidthRatio = 0.20f,
            reachRatioThreshold = 0.40f,
            minFloodReachRatio = 0.22f,
            blockageThreshold = 0.50f,
            blockDebounceFrames = 1,
            narrowDebounceFrames = 1,
            biasDebounceFrames = 1,
        )
        val mask = BinaryMask(width = 10, height = 12).apply {
            for (y in 4..11) {
                for (x in 2..7) {
                    set(x, y, true)
                }
            }
        }
        val stats = KotlinConnectivityStatsExtractor(config).extract(mask)
        val statsExtractor = object : ConnectivityStatsExtractor {
            override fun extract(passableMask: BinaryMask) = stats
        }

        val direct = ConnectivityChecker(config).analyze(mask)
        val fromStats = ConnectivityChecker(config, statsExtractor).analyze(mask)

        assertEquals(direct, fromStats)
    }

    @Test
    fun `failed connectivity stats extractor falls back to kotlin extractor`() {
        val config = AnalysisConfig(
            sampleLayerRatios = listOf(0.85f, 0.70f, 0.55f),
            minRunWidthRatio = 0.20f,
            reachRatioThreshold = 0.40f,
            minFloodReachRatio = 0.22f,
            blockageThreshold = 0.50f,
            blockDebounceFrames = 1,
            narrowDebounceFrames = 1,
            biasDebounceFrames = 1,
        )
        val mask = BinaryMask(width = 10, height = 12).apply {
            for (y in 4..11) {
                for (x in 2..7) {
                    set(x, y, true)
                }
            }
        }
        val failedStatsExtractor = object : ConnectivityStatsExtractor {
            override fun extract(passableMask: BinaryMask) = null
        }

        val result = ConnectivityChecker(config, failedStatsExtractor).analyze(mask)

        assertFalse(result.isBlocked)
        assertTrue(result.validLayers > 0)
    }
}
