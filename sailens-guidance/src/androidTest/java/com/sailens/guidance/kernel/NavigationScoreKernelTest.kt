package com.sailens.guidance.kernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.ModelTensorConfig
import com.sailens.vision.semantic.SemanticContentRegion
import com.sailens.vision.semantic.cropClassMap
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationMaskPool
import com.sailens.guidance.processor.perception.KotlinSegmentationStatsExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Layer B of the native verification plan (docs/architecture.md §12.2), semantic kernels:
 * `nativePostprocessScores` and `nativePostprocessInt8Scores`. The generic argmax kernel moved to
 * sailens-vision in G3 and is covered there.
 *
 * The fused score kernel does have a production Kotlin twin: [KotlinSegmentationStatsExtractor]
 * is the fallback the pipeline uses when `SegmentationOutput.analysisStats` is null, and it
 * computes the same [com.sailens.guidance.model.perception.SegmentationAnalysisStats] from the
 * argmax mask. So this compares the one-pass native kernel against argmax-then-Kotlin-stats,
 * field by field, which is the differential test §12.2 asks for.
 *
 * The two handle variants of these kernels cannot run here: LiteRT 2.1.5 has no API for building
 * a TensorBuffer without a compiled model, so they are layer C, covered in B with real weights.
 *
 * Needs a physical arm64 device: `./gradlew.bat :sailens-guidance:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NavigationScoreKernelTest {

    private val navigationSemantics = CityscapesNavigationSemantics
    private val analysisConfig = AnalysisConfig()

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "libsailens_guidance.so did not load: ${NativeGuidanceLibrary.loadError}",
            NativeGuidanceLibrary.isAvailable,
        )
    }

    @Test
    fun fusedScoreKernelMatchesArgmaxPlusKotlinStats() {
        val scores = syntheticScores(ImageTensorLayout.NHWC)
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = scores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )
        assertNotNull("native score postprocess returned null", result)
        checkNotNull(result)

        val expectedClassMap = referenceArgmax(scores, ImageTensorLayout.NHWC)
        assertArrayEquals("argmax mask", expectedClassMap, result.mask.classMap)

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics)
            .extract(SegmentationMask(WIDTH, HEIGHT, expectedClassMap))

        // SegmentationAnalysisStats compares masks, ratios, bottom stats and class counts, so one
        // assertion covers every field the fused kernel produces.
        assertEquals(expectedStats, result.stats)
    }

    @Test
    fun fusedScoreKernelOverAContentRegionMatchesTheCroppedArgmaxPlusKotlinStats() {
        // A letterboxed grid: the camera frame occupies the middle columns only. The fused kernel
        // must report exactly what argmax-then-Kotlin-stats reports for the cropped class map --
        // the padding must not reach a single statistic.
        val scores = syntheticScores(ImageTensorLayout.NHWC)
        val content = SemanticContentRegion(x = 11, y = 0, width = WIDTH - 22, height = HEIGHT)
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = scores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
            content = content,
        )
        checkNotNull(result) { "native score postprocess returned null" }

        val cropped = IntArray(content.pixelCount)
        cropClassMap(referenceArgmax(scores, ImageTensorLayout.NHWC), WIDTH, content, cropped)
        assertArrayEquals("cropped argmax mask", cropped, result.mask.classMap)
        assertEquals(content.width, result.mask.width)

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics)
            .extract(SegmentationMask(content.width, content.height, cropped))
        assertEquals(expectedStats, result.stats)
    }

    @Test
    fun fusedInt8ScoreKernelMatchesTheFloatKernel() {
        // The int8 kernel takes argmax over the raw quantised values: dequantisation is monotonic,
        // so the same ordering must produce the same mask and the same statistics.
        val int8Scores = ByteArray(WIDTH * HEIGHT * CLASS_COUNT)
        val floatScores = FloatArray(int8Scores.size)
        val random = Random(SEED)
        for (index in int8Scores.indices) {
            val value = random.nextInt(-128, 128)
            int8Scores[index] = value.toByte()
            floatScores[index] = value.toFloat()
        }

        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val int8Result = postprocessor.postprocessInt8Scores(
            scores = int8Scores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )
        val floatResult = postprocessor.postprocessScores(
            scores = floatScores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )

        assertNotNull("native int8 score postprocess returned null", int8Result)
        assertNotNull("native float score postprocess returned null", floatResult)
        checkNotNull(int8Result)
        checkNotNull(floatResult)

        assertArrayEquals(floatResult.mask.classMap, int8Result.mask.classMap)
        assertEquals(floatResult.stats, int8Result.stats)

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics)
            .extract(SegmentationMask(WIDTH, HEIGHT, int8Result.mask.classMap))
        assertEquals(expectedStats, int8Result.stats)
    }

    @Test
    fun fusedScoreKernelRejectsAMismatchedScoreCount() {
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = FloatArray(WIDTH * HEIGHT * CLASS_COUNT - 1),
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )

        assertEquals(null, result)
    }

    @Test
    fun pooledMasksStayIntactUntilTheirLeaseIsClosed() {
        // The ownership pattern ProcessFrameUseCase follows: the previous frame's mask is still
        // held (the cached analysis) while the next run writes, and is given back only after. Each
        // frame's scores differ, so a run that wrote into a held mask would show up in it.
        val pool = SegmentationMaskPool()
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
            maskPool = pool,
        )
        var held: SemanticPostprocessResult? = null
        var heldExpected: IntArray? = null

        repeat(6) { frame ->
            val scores = syntheticScores(ImageTensorLayout.NHWC, seed = SEED + frame)
            val expected = referenceArgmax(scores, ImageTensorLayout.NHWC)

            val result = checkNotNull(
                postprocessor.postprocessScores(
                    scores = scores,
                    width = WIDTH,
                    height = HEIGHT,
                    channels = CLASS_COUNT,
                    scoreLayout = ImageTensorLayout.NHWC,
                ),
            ) { "native score postprocess returned null on frame $frame" }

            assertArrayEquals("frame $frame mask", expected, result.mask.classMap)
            held?.let { previous ->
                assertArrayEquals("frame ${frame - 1} mask while still held", heldExpected, previous.mask.classMap)
                previous.maskLease.close()
            }
            held = result
            heldExpected = expected
        }

        assertEquals("two masks alternate: the held one and the one being written", 2L, pool.arraysAllocated)
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun tensorConfig(layout: ImageTensorLayout) = ModelTensorConfig(
        inputWidth = WIDTH,
        inputHeight = HEIGHT,
        outputWidth = WIDTH,
        outputHeight = HEIGHT,
        outputChannels = CLASS_COUNT,
        outputLayout = layout,
        mean = Triple(0f, 0f, 0f),
        std = Triple(1f, 1f, 1f),
    )

    /**
     * Scores with a deliberate structure rather than noise: the bottom rows favour the passable
     * classes and the top rows favour sky and buildings, so the bottom/centre/navigation regions
     * of the statistics are actually populated. A fixed seed keeps it reproducible, and the
     * deterministic tilt keeps ties out of the argmax.
     */
    private fun syntheticScores(layout: ImageTensorLayout, seed: Int = SEED): FloatArray {
        val random = Random(seed)
        val scores = FloatArray(WIDTH * HEIGHT * CLASS_COUNT)
        val pixelCount = WIDTH * HEIGHT

        for (y in 0 until HEIGHT) {
            val nearGround = y.toFloat() / HEIGHT
            for (x in 0 until WIDTH) {
                val pixelIndex = y * WIDTH + x
                for (channel in 0 until CLASS_COUNT) {
                    val bias = when (channel) {
                        CityscapesTaxonomy.ROAD -> 3f * nearGround
                        CityscapesTaxonomy.SIDEWALK -> 2f * nearGround
                        CityscapesTaxonomy.TERRAIN -> 1.5f * nearGround
                        CityscapesTaxonomy.SKY -> 3f * (1f - nearGround)
                        CityscapesTaxonomy.BUILDING -> 2f * (1f - nearGround)
                        CityscapesTaxonomy.PERSON -> if (x % 11 == 0) 2.5f else 0f
                        CityscapesTaxonomy.CAR -> if (x % 17 == 0) 2.5f else 0f
                        CityscapesTaxonomy.TRAFFIC_LIGHT -> if (pixelIndex % 97 == 0) 4f else 0f
                        else -> 0f
                    }
                    // nextFloat() is in [0, 1); the biases are far enough apart that no two
                    // channels land on the same float and make the argmax tie-break observable.
                    val value = random.nextFloat() + bias
                    scores[scoreIndex(pixelIndex, channel, pixelCount, layout)] = value
                }
            }
        }
        return scores
    }

    private fun scoreIndex(
        pixelIndex: Int,
        channel: Int,
        pixelCount: Int,
        layout: ImageTensorLayout,
    ): Int = when (layout) {
        ImageTensorLayout.NHWC -> pixelIndex * CLASS_COUNT + channel
        ImageTensorLayout.NCHW -> channel * pixelCount + pixelIndex
    }

    /** First-maximum-wins argmax, matching the kernel's strict `value > bestScore`. */
    private fun referenceArgmax(scores: FloatArray, layout: ImageTensorLayout): IntArray {
        val pixelCount = WIDTH * HEIGHT
        val mask = IntArray(pixelCount)
        for (pixelIndex in 0 until pixelCount) {
            var bestClass = 0
            var bestScore = scores[scoreIndex(pixelIndex, 0, pixelCount, layout)]
            for (channel in 1 until CLASS_COUNT) {
                val value = scores[scoreIndex(pixelIndex, channel, pixelCount, layout)]
                if (value > bestScore) {
                    bestScore = value
                    bestClass = channel
                }
            }
            mask[pixelIndex] = bestClass
        }
        return mask
    }

    private companion object {
        const val WIDTH = 40
        const val HEIGHT = 32
        const val CLASS_COUNT = 19
        const val SEED = 20260920
    }
}
