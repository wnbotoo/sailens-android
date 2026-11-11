package com.sailens.vision.depth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.ModelInputPreprocessor
import com.sailens.runtime.ModelInputQuantization
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.ResizeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * The depth sampler against the real native letterbox (docs/models.md, "The `depth` contract"): the
 * columns and rows `DisparityMap` treats as content must be ones the kernel wrote image data into, for
 * every fractional padding and every rotation. A JVM test can only restate the kernel's rule; this
 * one runs the kernel.
 *
 * The frame is a uniform grey, so the kernel writes one value into the image and 0 into the padding:
 * any sample that blends padding in comes out below the image value.
 *
 * Needs a physical arm64 device: `./gradlew.bat :sailens-vision:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DepthContentRuleNativeTest {

    // 540x960 upright into a 224-wide tensor: horizontal padding 4, 6.25, 8.5, 10.75 and 1.75 per side.
    private val heights = listOf(384, 376, 368, 360, 392)

    @Test
    fun theSamplerNeverReadsWhatTheKernelWroteAsPadding() {
        for (rotation in listOf(0, 90, 180, 270)) for (height in heights) {
            val frame = greyFrame(rotation)
            val config = ModelTensorConfig(
                inputWidth = 224,
                inputHeight = height,
                inputLayout = ImageTensorLayout.NHWC,
                outputWidth = 224,
                outputHeight = height,
                outputChannels = 1,
                mean = Triple(0f, 0f, 0f),
                std = Triple(1f, 1f, 1f),
                resizeFilter = ResizeFilter.BILINEAR,
            )
            val tensor = FloatArray(224 * height * 3)
            val preprocessor = ModelInputPreprocessor(config, ModelInputQuantization(), preferNativeYuvPreprocessing = true)
            try {
                assertTrue("native preprocessing did not run", preprocessor.preprocessFloatNativeOnly(frame, rotation, tensor))
            } finally {
                preprocessor.close()
            }
            val red = FloatArray(224 * height) { i -> tensor[i * 3] }
            val transform = DepthInputTransform.create(540, 960, 224, height, ResizePolicy.PAD_KEEP_ASPECT)
            val map = DisparityMap(224, height, red, transform)
            val label = "rotation $rotation, height $height, pad ${transform.padLeft}"

            val image = red[(height / 2) * 224 + 112]
            assertTrue("$label: no image value written", image > 0.1f)
            // The first column the kernel wrote is the first the sampler uses; the one before is padding.
            val row = (height / 2) * 224
            val first = map.contentColumns.first
            assertEquals("$label: column ${first - 1}", 0f, red[row + first - 1], 0f)
            assertEquals("$label: column $first", image, red[row + first], 1e-5f)
            assertEquals("$label: first column", ceil(transform.padLeft).toInt(), first)

            for (u in listOf(0f, 0.3f, 1f, 270f, 538f, 539f, 539.99f)) for (v in listOf(0f, 480f, 959.99f)) {
                assertEquals("$label: u=$u v=$v", image, map.sampleAt(u, v)!!, 1e-5f)
            }
        }
    }

    /** A uniform grey YUV frame that is 540x960 once turned upright by [rotation]. */
    private fun greyFrame(rotation: Int): ImageFrame {
        val sideways = rotation == 90 || rotation == 270
        val width = if (sideways) 960 else 540
        val height = if (sideways) 540 else 960
        val y = YuvPlaneData(ByteArray(width * height) { 128.toByte() }, rowStride = width, pixelStride = 1)
        val uv = YuvPlaneData(ByteArray((width / 2) * (height / 2)) { 128.toByte() }, rowStride = width / 2, pixelStride = 1)
        return ImageFrame(
            width = width,
            height = height,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.entries.first(),
            timestamp = 0L,
            rotationDegrees = rotation,
            sequenceNumber = 1L,
            yuvData = Yuv420FrameData(y, uv, uv),
        )
    }
}
