package com.sailens.vision.semantic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.ModelTensorConfig
import com.sailens.vision.NativeVisionLibrary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Layer B of the native verification plan (docs/architecture.md §12.2) for the generic semantic
 * argmax kernel, which moved to sailens-vision in G3. The fused Guidance scoring kernel is a
 * different kernel in a different library and is covered by :data's suite.
 *
 * Needs a physical arm64 device: `./gradlew.bat :sailens-vision:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NativeSemanticArgmaxKernelTest {

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "libsailens_vision.so did not load: ${NativeVisionLibrary.loadError}",
            NativeVisionLibrary.isAvailable,
        )
    }

    @Test
    fun argmaxMatchesKotlinForNhwcScores() {
        assertMatchesReference(ImageTensorLayout.NHWC)
    }

    @Test
    fun argmaxMatchesKotlinForNchwScores() {
        assertMatchesReference(ImageTensorLayout.NCHW)
    }

    @Test
    fun argmaxRejectsAMismatchedScoreCount() {
        val postprocessor = NativeSemanticArgmaxPostprocessor(tensorConfig(ImageTensorLayout.NHWC))

        val accepted = postprocessor.argmaxScores(
            scores = FloatArray(WIDTH * HEIGHT * CLASS_COUNT - 1),
            resultMask = IntArray(WIDTH * HEIGHT),
        )

        assertTrue("a short score tensor must be refused, not read past its end", !accepted)
    }

    private fun assertMatchesReference(layout: ImageTensorLayout) {
        val scores = syntheticScores(layout)
        val postprocessor = NativeSemanticArgmaxPostprocessor(tensorConfig(layout))
        val mask = IntArray(WIDTH * HEIGHT)

        assertTrue(postprocessor.argmaxScores(scores, mask))

        assertArrayEquals(referenceArgmax(scores, layout), mask)
    }

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

    private fun syntheticScores(layout: ImageTensorLayout): FloatArray {
        val random = Random(SEED)
        val scores = FloatArray(WIDTH * HEIGHT * CLASS_COUNT)
        val pixelCount = WIDTH * HEIGHT
        for (pixelIndex in 0 until pixelCount) {
            for (channel in 0 until CLASS_COUNT) {
                scores[scoreIndex(pixelIndex, channel, pixelCount, layout)] = random.nextFloat()
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
