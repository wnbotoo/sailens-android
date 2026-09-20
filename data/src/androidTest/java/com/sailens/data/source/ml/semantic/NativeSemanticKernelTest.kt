package com.sailens.data.source.ml.semantic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.data.source.mapper.CityscapesClassMapper
import com.sailens.data.source.ml.ImageTensorLayout
import com.sailens.data.source.ml.ModelTensorConfig
import com.sailens.data.source.ml.NativeMlLibrary
import com.sailens.data.source.ml.SilentLogService
import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.model.perception.SegmentationMask
import com.sailens.domain.processor.perception.KotlinSegmentationStatsExtractor
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
 * `nativeArgmaxScores`, `nativePostprocessScores` and `nativePostprocessInt8Scores`.
 *
 * The fused score kernel does have a production Kotlin twin: [KotlinSegmentationStatsExtractor]
 * is the fallback the pipeline uses when `SegmentationOutput.analysisStats` is null, and it
 * computes the same [com.sailens.domain.model.perception.SegmentationAnalysisStats] from the
 * argmax mask. So this compares the one-pass native kernel against argmax-then-Kotlin-stats,
 * field by field, which is the differential test §12.2 asks for.
 *
 * The two handle variants of these kernels cannot run here: LiteRT 2.1.5 has no API for building
 * a TensorBuffer without a compiled model, so they are layer C, covered in B with real weights.
 *
 * Needs a physical arm64 device: `./gradlew.bat :data:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NativeSemanticKernelTest {

    private val classMapper = CityscapesClassMapper()
    private val analysisConfig = AnalysisConfig()

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "libsailens_ml.so did not load: ${NativeMlLibrary.loadError}",
            NativeMlLibrary.isAvailable,
        )
    }

    @Test
    fun argmaxMatchesKotlinForNhwcScores() {
        val scores = syntheticScores(ImageTensorLayout.NHWC)
        val postprocessor = NativeSemanticArgmaxPostprocessor(tensorConfig(ImageTensorLayout.NHWC))
        val mask = IntArray(WIDTH * HEIGHT)

        assertTrue(postprocessor.argmaxScores(scores, mask))

        assertArrayEquals(referenceArgmax(scores, ImageTensorLayout.NHWC), mask)
    }

    @Test
    fun argmaxMatchesKotlinForNchwScores() {
        val scores = syntheticScores(ImageTensorLayout.NCHW)
        val postprocessor = NativeSemanticArgmaxPostprocessor(tensorConfig(ImageTensorLayout.NCHW))
        val mask = IntArray(WIDTH * HEIGHT)

        assertTrue(postprocessor.argmaxScores(scores, mask))

        assertArrayEquals(referenceArgmax(scores, ImageTensorLayout.NCHW), mask)
    }

    @Test
    fun fusedScoreKernelMatchesArgmaxPlusKotlinStats() {
        val scores = syntheticScores(ImageTensorLayout.NHWC)
        val postprocessor = NativeSemanticScorePostprocessor(
            config = analysisConfig,
            classMapper = classMapper,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = scores,
            reusableResultMask = IntArray(WIDTH * HEIGHT),
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )
        assertNotNull("native score postprocess returned null", result)
        checkNotNull(result)

        val expectedClassMap = referenceArgmax(scores, ImageTensorLayout.NHWC)
        assertArrayEquals("argmax mask", expectedClassMap, result.mask.classMap)

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, classMapper)
            .extract(SegmentationMask(WIDTH, HEIGHT, expectedClassMap))

        // SegmentationAnalysisStats compares masks, ratios, bottom stats and class counts, so one
        // assertion covers every field the fused kernel produces.
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

        val postprocessor = NativeSemanticScorePostprocessor(
            config = analysisConfig,
            classMapper = classMapper,
            logService = SilentLogService,
        )

        val int8Result = postprocessor.postprocessInt8Scores(
            scores = int8Scores,
            reusableResultMask = IntArray(WIDTH * HEIGHT),
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )
        val floatResult = postprocessor.postprocessScores(
            scores = floatScores,
            reusableResultMask = IntArray(WIDTH * HEIGHT),
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

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, classMapper)
            .extract(SegmentationMask(WIDTH, HEIGHT, int8Result.mask.classMap))
        assertEquals(expectedStats, int8Result.stats)
    }

    @Test
    fun fusedScoreKernelRejectsAMismatchedScoreCount() {
        val postprocessor = NativeSemanticScorePostprocessor(
            config = analysisConfig,
            classMapper = classMapper,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = FloatArray(WIDTH * HEIGHT * CLASS_COUNT - 1),
            reusableResultMask = IntArray(WIDTH * HEIGHT),
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )

        assertEquals(null, result)
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
    private fun syntheticScores(layout: ImageTensorLayout): FloatArray {
        val random = Random(SEED)
        val scores = FloatArray(WIDTH * HEIGHT * CLASS_COUNT)
        val pixelCount = WIDTH * HEIGHT

        for (y in 0 until HEIGHT) {
            val nearGround = y.toFloat() / HEIGHT
            for (x in 0 until WIDTH) {
                val pixelIndex = y * WIDTH + x
                for (channel in 0 until CLASS_COUNT) {
                    val bias = when (channel) {
                        CityscapesClassMapper.ROAD -> 3f * nearGround
                        CityscapesClassMapper.SIDEWALK -> 2f * nearGround
                        CityscapesClassMapper.TERRAIN -> 1.5f * nearGround
                        CityscapesClassMapper.SKY -> 3f * (1f - nearGround)
                        CityscapesClassMapper.BUILDING -> 2f * (1f - nearGround)
                        CityscapesClassMapper.PERSON -> if (x % 11 == 0) 2.5f else 0f
                        CityscapesClassMapper.CAR -> if (x % 17 == 0) 2.5f else 0f
                        CityscapesClassMapper.TRAFFIC_LIGHT -> if (pixelIndex % 97 == 0) 4f else 0f
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
