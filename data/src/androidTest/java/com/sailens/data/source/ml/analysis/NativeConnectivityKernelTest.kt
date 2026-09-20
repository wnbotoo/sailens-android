package com.sailens.data.source.ml.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.data.source.ml.NativeMlLibrary
import com.sailens.data.source.ml.SilentLogService
import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.model.analysis.ConnectivityStats
import com.sailens.domain.model.common.BinaryMask
import com.sailens.domain.model.common.DirectionBias
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
 * [KotlinConnectivityStatsExtractor] is the production fallback for this kernel, so most of this is
 * a straight differential test: same mask in, same [ConnectivityStats] out. It holds exactly for a
 * corridor of constant width, for a blocked corridor and for an empty mask.
 *
 * It does **not** hold for a corridor that narrows with distance, and that is a pre-existing
 * divergence in the product, not a tolerance problem. See
 * [nativeWidthRetentionIgnoresThePerspectiveScaleKotlinApplies] for the mechanism and the numbers.
 * Those fixtures therefore pin the native values directly: the native kernel is the production
 * path, so pinning what it actually returns is what protects the upcoming module split, whereas
 * asserting an equivalence that was never true would only have pinned a wish.
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

    // -------------------------------------------------------------------------------------------
    // Masks where the two implementations agree on every field
    // -------------------------------------------------------------------------------------------

    @Test
    fun straightCorridorMatchesKotlin() {
        assertFullyMatchesKotlin(straightCorridorMask())
    }

    @Test
    fun blockedCorridorMatchesKotlin() {
        assertFullyMatchesKotlin(blockedCorridorMask())
    }

    @Test
    fun emptyMaskMatchesKotlin() {
        assertFullyMatchesKotlin(BinaryMask(WIDTH, HEIGHT))
    }

    @Test
    fun corridorLeaningLeftMatchesKotlinAndKeepsTheBias() {
        val mask = corridorLeaningLeftMask()
        val stats = extractNative(mask)
        val expected = kotlinExtractor.extract(mask)

        assertEquals("suggestedBias", expected.suggestedBias, stats.suggestedBias)
        assertEquals("the off-centre mask should bias LEFT", DirectionBias.LEFT, stats.suggestedBias)
        assertEquals("validLayers", expected.validLayers, stats.validLayers)
        assertEquals("totalLayers", expected.totalLayers, stats.totalLayers)
        assertEquals("widthRetentionAvg", expected.widthRetentionAvg, stats.widthRetentionAvg, TOLERANCE)
        assertEquals("widthRetentionP25", expected.widthRetentionP25, stats.widthRetentionP25, TOLERANCE)
        assertEquals("widthSlope", expected.widthSlope, stats.widthSlope, TOLERANCE)
        assertEquals("floodReachRatio", expected.floodReachRatio, stats.floodReachRatio, TOLERANCE)
        assertEquals("floodVisitedRatio", expected.floodVisitedRatio, stats.floodVisitedRatio, TOLERANCE)

        // The corridor is a constant 0.40 of the width, but integer truncation makes some rows 38
        // wide against a 39-wide bottom run. Kotlin's clamp rounds that back up to 1.0; the native
        // kernel reports the raw ratio.
        assertEquals("floodWidthRetentionP25", 38f / 39f, stats.floodWidthRetentionP25, TOLERANCE)
        assertEquals(1f, expected.floodWidthRetentionP25, TOLERANCE)
    }

    // -------------------------------------------------------------------------------------------
    // Masks where the native kernel is pinned directly, because Kotlin is not an oracle for it
    // -------------------------------------------------------------------------------------------

    @Test
    fun narrowingCorridorKeepsItsNativeValues() {
        val stats = extractNative(narrowingCorridorMask())
        val expected = kotlinExtractor.extract(narrowingCorridorMask())

        // Still a real differential check on the fields that do agree.
        assertEquals("validLayers", expected.validLayers, stats.validLayers)
        assertEquals("totalLayers", expected.totalLayers, stats.totalLayers)
        assertEquals("suggestedBias", expected.suggestedBias, stats.suggestedBias)

        // Captured from the native kernel on an arm64 device. The layer runs are measured against a
        // 73-pixel bottom run: the narrowest sampled layer is 47 wide, hence p25 = 47/73 and
        // slope = 47/73 - 1. The flood's narrowest quartile row is 43 wide.
        assertEquals("widthRetentionAvg", 0.7716895f, stats.widthRetentionAvg, TOLERANCE)
        assertEquals("widthRetentionP25", 47f / 73f, stats.widthRetentionP25, TOLERANCE)
        assertEquals("widthSlope", 47f / 73f - 1f, stats.widthSlope, TOLERANCE)
        assertEquals("floodReachRatio", 1f, stats.floodReachRatio, TOLERANCE)
        assertEquals("floodWidthRetentionP25", 43f / 73f, stats.floodWidthRetentionP25, TOLERANCE)
        assertEquals("floodVisitedRatio", 0.5602083f, stats.floodVisitedRatio, TOLERANCE)
    }

    @Test
    fun disconnectedFragmentsKeepTheirNativeValues() {
        val mask = disconnectedFragmentsMask()
        val stats = extractNative(mask)
        val expected = kotlinExtractor.extract(mask)

        assertEquals("validLayers", expected.validLayers, stats.validLayers)
        assertEquals("totalLayers", expected.totalLayers, stats.totalLayers)
        assertEquals("suggestedBias", expected.suggestedBias, stats.suggestedBias)
        assertEquals("widthSlope", expected.widthSlope, stats.widthSlope, TOLERANCE)

        // The fragments never connect, so the flood cannot leave its seed row: reach stays 0 and
        // both implementations agree there. Only the layer widths carry the perspective difference.
        assertEquals("floodReachRatio", expected.floodReachRatio, stats.floodReachRatio, TOLERANCE)
        assertEquals("floodVisitedRatio", expected.floodVisitedRatio, stats.floodVisitedRatio, TOLERANCE)
        assertEquals(
            "floodWidthRetentionP25",
            expected.floodWidthRetentionP25,
            stats.floodWidthRetentionP25,
            TOLERANCE,
        )

        // Two valid layers, 12 and 19 wide, against a 19-wide bottom run.
        assertEquals("widthRetentionAvg", (12f / 19f + 1f) / 2f, stats.widthRetentionAvg, TOLERANCE)
        assertEquals("widthRetentionP25", 12f / 19f, stats.widthRetentionP25, TOLERANCE)
    }

    // -------------------------------------------------------------------------------------------
    // The divergence itself, recorded so it cannot be changed by accident
    // -------------------------------------------------------------------------------------------

    /**
     * Records a **pre-existing behavioural divergence** between the native kernel and its Kotlin
     * fallback. This test is not describing something desirable; it exists so that the difference
     * is a checked fact rather than a surprise, and so that closing it has to be a deliberate,
     * reviewed change rather than a silent one.
     *
     * [KotlinConnectivityStatsExtractor.widthRetention] divides a run width by
     * `bottomWidth * perspectiveWidthScale(normalizedY)` and clamps the result to `[0, 1]`. The
     * scale runs from `connectivityPerspectiveMinWidthScale` (0.50) at the horizon to 1.0 at the
     * bottom of the frame, so a corridor that recedes normally reads as *full* retention. The
     * native kernel divides by `bottomWidth` alone and does not clamp -- it is not even passed
     * `connectivityPerspectiveHorizonY` or `connectivityPerspectiveMinWidthScale`, so it cannot
     * apply the correction.
     *
     * On a perspective-normal corridor that costs, measured on an arm64 device:
     *
     * | field | native | Kotlin |
     * |---|---|---|
     * | widthRetentionAvg | 0.772 | 0.995 |
     * | widthRetentionP25 | 0.644 | 0.985 |
     * | widthSlope | -0.356 | -0.015 |
     * | floodReachRatio | 1.000 | 0.714 |
     * | floodWidthRetentionP25 | 0.589 | 0.994 |
     *
     * The flood fields diverge because the flood's early stop compares that same retention against
     * `floodEarlyStopWidthRetention` (0.80): Kotlin's scaled value clears the bar and stops early,
     * the native raw value never does, so the two floods terminate at different points. The
     * divergence is therefore not a shifted number but a different traversal.
     *
     * Which side is correct is a product decision, not a refactor decision, and the two directions
     * are not symmetric: against Kotlin the native layer statistics over-report narrowing while its
     * flood reach under-reports blockage. The native kernel is the shipping path and the thresholds
     * in [AnalysisConfig] may well have been tuned against it, so this refactor changes neither.
     */
    @Test
    fun nativeWidthRetentionIgnoresThePerspectiveScaleKotlinApplies() {
        val mask = narrowingCorridorMask()
        val native = extractNative(mask)
        val fallback = kotlinExtractor.extract(mask)

        assertTrue(
            "native widthRetentionAvg ${native.widthRetentionAvg} should still sit far below the " +
                "perspective-corrected ${fallback.widthRetentionAvg}; if this now matches, the " +
                "perspective scale was ported to the native kernel and that is a behaviour change " +
                "the guidance thresholds have to be re-reviewed against",
            native.widthRetentionAvg < fallback.widthRetentionAvg - 0.1f,
        )
        assertTrue(
            "native floodReachRatio ${native.floodReachRatio} should still sit above the " +
                "early-stopping ${fallback.floodReachRatio}",
            native.floodReachRatio > fallback.floodReachRatio + 0.1f,
        )
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private fun extractNative(mask: BinaryMask): ConnectivityStats {
        val stats = nativeExtractor.extract(mask)
        assertNotNull(
            "native connectivity extraction returned null, so it fell back to Kotlin and this " +
                "test compared nothing",
            stats,
        )
        return checkNotNull(stats)
    }

    private fun assertFullyMatchesKotlin(mask: BinaryMask) {
        val stats = extractNative(mask)
        val expected = kotlinExtractor.extract(mask)

        assertEquals("validLayers", expected.validLayers, stats.validLayers)
        assertEquals("totalLayers", expected.totalLayers, stats.totalLayers)
        assertEquals("suggestedBias", expected.suggestedBias, stats.suggestedBias)
        assertEquals("widthRetentionAvg", expected.widthRetentionAvg, stats.widthRetentionAvg, TOLERANCE)
        assertEquals("widthRetentionP25", expected.widthRetentionP25, stats.widthRetentionP25, TOLERANCE)
        assertEquals("widthSlope", expected.widthSlope, stats.widthSlope, TOLERANCE)
        assertEquals("floodReachRatio", expected.floodReachRatio, stats.floodReachRatio, TOLERANCE)
        assertEquals(
            "floodWidthRetentionP25",
            expected.floodWidthRetentionP25,
            stats.floodWidthRetentionP25,
            TOLERANCE,
        )
        assertEquals("floodVisitedRatio", expected.floodVisitedRatio, stats.floodVisitedRatio, TOLERANCE)
    }

    private fun straightCorridorMask() = corridorMask { 0.30f to 0.70f }

    /** Wide at the bottom, pinched towards the horizon: an ordinary receding sidewalk. */
    private fun narrowingCorridorMask() = corridorMask { y ->
        val halfWidth = 0.08f + 0.30f * y
        (0.5f - halfWidth) to (0.5f + halfWidth)
    }

    /** Constant width, off-centre on purpose: this is the fixture that exercises suggestedBias. */
    private fun corridorLeaningLeftMask() =
        corridorMask { y -> (0.05f + 0.10f * y) to (0.45f + 0.10f * y) }

    /** Passable at the bottom, a solid wall above it: the flood must stop rather than leak through. */
    private fun blockedCorridorMask() = BinaryMask(WIDTH, HEIGHT).apply {
        for (y in (HEIGHT * 3 / 4) until HEIGHT) {
            for (x in (WIDTH / 4) until (WIDTH * 3 / 4)) {
                set(x, y, true)
            }
        }
    }

    /**
     * Several unconnected passable runs. A layer scan alone reads these as a corridor; the flood
     * statistics are what tell them apart, so the two implementations must agree there.
     */
    private fun disconnectedFragmentsMask() = BinaryMask(WIDTH, HEIGHT).apply {
        for (y in 0 until HEIGHT) {
            if ((y / 4) % 2 == 0) continue
            val offset = (y * 3) % WIDTH
            for (x in offset until minOf(offset + WIDTH / 5, WIDTH)) {
                set(x, y, true)
            }
        }
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
