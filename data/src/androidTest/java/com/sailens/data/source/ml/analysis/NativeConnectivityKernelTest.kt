package com.sailens.data.source.ml.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.data.source.ml.NativeMlLibrary
import com.sailens.data.source.ml.SilentLogService
import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.model.analysis.ConnectivityStats
import com.sailens.domain.model.common.BinaryMask
import com.sailens.domain.processor.analysis.KotlinConnectivityStatsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer B of the native verification plan (docs/architecture.md §12.2), connectivity kernel:
 * `nativeExtractConnectivityStats`.
 *
 * [KotlinConnectivityStatsExtractor] is the production fallback for exactly this kernel, so this
 * is a straight differential test: same mask in, same [ConnectivityStats] out.
 *
 * `suggestedBias` is asserted exactly, never with a tolerance. It is a weighted centroid offset,
 * not a route, and the guidance layer only ever attaches a direction to a non-blocked frame -- a
 * drift here changes what a blind user is told to do, so it is not a numeric detail.
 *
 * Needs a physical arm64 device: `./gradlew.bat :data:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NativeConnectivityKernelTest {

    private val config = AnalysisConfig()
    private val nativeExtractor = NativeConnectivityStatsExtractor(config, SilentLogService)
    private val kotlinExtractor = KotlinConnectivityStatsExtractor(config)

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "libsailens_ml.so did not load: ${NativeMlLibrary.loadError}",
            NativeMlLibrary.isAvailable,
        )
    }

    @Test
    fun straightCorridorMatchesKotlin() {
        assertMatchesKotlin(corridorMask { y -> 0.30f to 0.70f })
    }

    @Test
    fun narrowingCorridorMatchesKotlin() {
        // Wide at the bottom, pinched towards the horizon: the shape that drives width retention,
        // the p25 statistic and the narrowing slope.
        assertMatchesKotlin(
            corridorMask { y ->
                val far = 1f - y
                val halfWidth = 0.08f + 0.30f * (1f - far)
                (0.5f - halfWidth) to (0.5f + halfWidth)
            },
        )
    }

    @Test
    fun corridorLeaningLeftMatchesKotlin() {
        // Off-centre on purpose: suggestedBias is the output most likely to diverge and the one
        // that must not.
        assertMatchesKotlin(corridorMask { y -> (0.05f + 0.10f * y) to (0.45f + 0.10f * y) })
    }

    @Test
    fun blockedCorridorMatchesKotlin() {
        // Passable at the bottom, a solid wall above it: flood fill must stop and report a low
        // reach ratio rather than leaking through.
        assertMatchesKotlin(
            BinaryMask(WIDTH, HEIGHT).apply {
                for (y in (HEIGHT * 3 / 4) until HEIGHT) {
                    for (x in (WIDTH / 4) until (WIDTH * 3 / 4)) {
                        set(x, y, true)
                    }
                }
            },
        )
    }

    @Test
    fun disconnectedFragmentsMatchKotlin() {
        // Several unconnected passable runs. A layer scan alone reads these as a corridor; the
        // flood statistics are what tell them apart, so the two implementations must agree here
        // in particular.
        assertMatchesKotlin(
            BinaryMask(WIDTH, HEIGHT).apply {
                for (y in 0 until HEIGHT) {
                    if ((y / 4) % 2 == 0) continue
                    val offset = (y * 3) % WIDTH
                    for (x in offset until minOf(offset + WIDTH / 5, WIDTH)) {
                        set(x, y, true)
                    }
                }
            },
        )
    }

    @Test
    fun emptyMaskMatchesKotlin() {
        assertMatchesKotlin(BinaryMask(WIDTH, HEIGHT))
    }

    private fun assertMatchesKotlin(mask: BinaryMask) {
        val nativeStats = nativeExtractor.extract(mask)
        assertNotNull(
            "native connectivity extraction returned null, so it fell back to Kotlin and this " +
                "test compared nothing",
            nativeStats,
        )
        checkNotNull(nativeStats)

        val expected = kotlinExtractor.extract(mask)

        assertEquals("validLayers", expected.validLayers, nativeStats.validLayers)
        assertEquals("totalLayers", expected.totalLayers, nativeStats.totalLayers)
        assertEquals("suggestedBias", expected.suggestedBias, nativeStats.suggestedBias)
        assertEquals(
            "widthRetentionAvg",
            expected.widthRetentionAvg,
            nativeStats.widthRetentionAvg,
            TOLERANCE,
        )
        assertEquals(
            "widthRetentionP25",
            expected.widthRetentionP25,
            nativeStats.widthRetentionP25,
            TOLERANCE,
        )
        assertEquals("widthSlope", expected.widthSlope, nativeStats.widthSlope, TOLERANCE)
        assertEquals(
            "floodReachRatio",
            expected.floodReachRatio,
            nativeStats.floodReachRatio,
            TOLERANCE,
        )
        assertEquals(
            "floodWidthRetentionP25",
            expected.floodWidthRetentionP25,
            nativeStats.floodWidthRetentionP25,
            TOLERANCE,
        )
        assertEquals(
            "floodVisitedRatio",
            expected.floodVisitedRatio,
            nativeStats.floodVisitedRatio,
            TOLERANCE,
        )
    }

    /**
     * Builds a passable mask from a per-row corridor span.
     *
     * @param span maps a row's normalised distance from the horizon (0 at the top, 1 at the
     *   bottom) to the corridor's left and right edges as fractions of the width.
     */
    private fun corridorMask(span: (Float) -> Pair<Float, Float>): BinaryMask {
        val mask = BinaryMask(WIDTH, HEIGHT)
        for (y in 0 until HEIGHT) {
            val (left, right) = span(y.toFloat() / (HEIGHT - 1))
            val startX = (left * WIDTH).toInt().coerceIn(0, WIDTH)
            val endX = (right * WIDTH).toInt().coerceIn(startX, WIDTH)
            for (x in startX until endX) {
                mask.set(x, y, true)
            }
        }
        return mask
    }

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 72

        // The kernel is built with -ffast-math and accumulates in a different order than the
        // Kotlin implementation. Ratios are in [0, 1], so this is well under a pixel's worth.
        const val TOLERANCE = 1e-3f
    }
}
