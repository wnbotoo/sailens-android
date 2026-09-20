package com.sailens.data.source.ml.obstacle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.data.source.ml.NativeMlLibrary
import com.sailens.runtime.TensorQuantization
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Layer B of the native verification plan (docs/architecture.md §12.2), detection kernels:
 * `nativePostProcessRawFloat` and `nativePostProcessRawInt8`.
 *
 * The scenarios are the ones `ObstaclePostProcessorTest` pins on the JVM, where the native
 * library is absent and `ObstaclePostProcessor` runs its Kotlin decoder. Asserting the same
 * outcomes here, with `backend` proving the native kernel actually ran, is the before/after
 * comparison §12.2 asks for: the JVM test holds the Kotlin result still, this one holds the
 * native kernel to it.
 *
 * The two handle variants are layer C: they need a real LiteRT output buffer, which A cannot
 * manufacture without weights.
 *
 * Needs a physical arm64 device: `./gradlew.bat :data:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NativeObstacleKernelTest {

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "libsailens_ml.so did not load: ${NativeMlLibrary.loadError}",
            NativeMlLibrary.isAvailable,
        )
    }

    @Test
    fun rawFloatKernelDecodesTopDetectionsWithNms() {
        val postProcessor = ObstaclePostProcessor(confidenceThreshold = 0.25f, maxDetections = 10)

        val output = postProcessor.postProcessWithBackend(createFrame(), scenarioDetections())

        assertEquals("native_bbox_nms", output.backend)
        assertEquals(2, output.detections.size)
        assertEquals("person", output.detections[0].className)
        assertEquals("car", output.detections[1].className)
        // The duplicate person box at 0.80 must lose to the 0.94 one, and the 0.10 box is below
        // the confidence threshold.
        assertEquals(0.94f, output.detections[0].confidence, 1e-5f)
        assertEquals(0.88f, output.detections[1].confidence, 1e-5f)
    }

    @Test
    fun rawFloatKernelKeepsStaticObstacleClassNames() {
        val postProcessor = ObstaclePostProcessor(confidenceThreshold = 0.25f, maxDetections = 10)
        val raw = FloatArray(ATTRIBUTES)
        setRawDetection(
            raw, detectionIndex = 0, detectionCount = 1,
            cx = 320f, cy = 340f, width = 120f, height = 120f, classId = 11, score = 0.94f,
        )

        val output = postProcessor.postProcessWithBackend(createFrame(), raw)

        assertEquals("native_bbox_nms", output.backend)
        assertEquals("stop_sign", output.detections.single().className)
    }

    @Test
    fun rawInt8KernelAgreesWithTheFloatKernel() {
        val postProcessor = ObstaclePostProcessor(confidenceThreshold = 0.25f, maxDetections = 10)
        val frame = createFrame()
        // Normalised box coordinates, the convention a full-integer-quant export uses: one scale
        // has to cover boxes and class scores, and `toModelPixels` maps anything <= 2.0 back up by
        // inputSize. Feeding the float kernel model pixels and the int8 kernel [0, 1] would be
        // comparing two different decodes.
        val raw = scenarioDetections(coordinateScale = 1f / INPUT_SIZE)

        val floatOutput = postProcessor.postProcessWithBackend(frame, raw)
        assertEquals("native_bbox_nms", floatOutput.backend)
        assertEquals(listOf("person", "car"), floatOutput.detections.map { it.className })

        val quantization = TensorQuantization(scale = 1f / 127f, zeroPoint = 0)
        val quantized = ByteArray(raw.size) { index ->
            val value = Math.round(raw[index] / quantization.scale + quantization.zeroPoint)
            value.coerceIn(-128, 127).toByte()
        }

        val int8Output = postProcessor.postProcessWithBackend(frame, quantized, quantization)

        assertEquals("native_bbox_nms_int8", int8Output.backend)
        assertEquals(
            floatOutput.detections.map { it.className },
            int8Output.detections.map { it.className },
        )
        floatOutput.detections.zip(int8Output.detections).forEach { (expected, actual) ->
            val box = expected.boundingBox
            val actualBox = actual.boundingBox
            assertTrue(
                "box drift for ${expected.className}: $box vs $actualBox",
                abs(box.x - actualBox.x) <= BOX_TOLERANCE &&
                    abs(box.y - actualBox.y) <= BOX_TOLERANCE &&
                    abs(box.width - actualBox.width) <= BOX_TOLERANCE &&
                    abs(box.height - actualBox.height) <= BOX_TOLERANCE,
            )
            assertEquals(expected.confidence, actual.confidence, 1f / 127f)
        }
    }

    @Test
    fun rawKernelsRejectAnInvalidTensorShape() {
        val postProcessor = ObstaclePostProcessor()

        val output = postProcessor.postProcessWithBackend(createFrame(), FloatArray(5))

        assertEquals("invalid_shape", output.backend)
        assertTrue(output.detections.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures -- the same scenario ObstaclePostProcessorTest pins for the Kotlin decoder.
    // ---------------------------------------------------------------------------------------

    /**
     * @param coordinateScale 1 keeps the model-pixel coordinates the JVM test uses; 1/inputSize
     *   turns the same boxes into the normalised form an int8 export emits.
     */
    private fun scenarioDetections(coordinateScale: Float = 1f): FloatArray {
        val raw = FloatArray(ATTRIBUTES * DETECTION_COUNT)
        fun put(index: Int, cx: Float, cy: Float, w: Float, h: Float, classId: Int, score: Float) =
            setRawDetection(
                raw = raw,
                detectionIndex = index,
                detectionCount = DETECTION_COUNT,
                cx = cx * coordinateScale,
                cy = cy * coordinateScale,
                width = w * coordinateScale,
                height = h * coordinateScale,
                classId = classId,
                score = score,
            )

        put(0, 320f, 340f, 120f, 180f, classId = 0, score = 0.94f)
        put(1, 560f, 335f, 120f, 150f, classId = 2, score = 0.88f)
        put(2, 170f, 150f, 100f, 100f, classId = 9, score = 0.99f)
        put(3, 320f, 340f, 120f, 180f, classId = 0, score = 0.80f)
        put(4, 25f, 25f, 10f, 10f, classId = 0, score = 0.10f)
        return raw
    }

    private fun createFrame() = ImageFrame(
        width = 1280,
        height = 720,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.RGBA_8888,
        timestamp = 1L,
        rotationDegrees = 0,
        sequenceNumber = 1L,
    )

    private fun setRawDetection(
        raw: FloatArray,
        detectionIndex: Int,
        detectionCount: Int,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        classId: Int,
        score: Float,
    ) {
        raw[detectionIndex] = cx
        raw[detectionCount + detectionIndex] = cy
        raw[detectionCount * 2 + detectionIndex] = width
        raw[detectionCount * 3 + detectionIndex] = height
        raw[(4 + classId) * detectionCount + detectionIndex] = score
    }

    private companion object {
        const val ATTRIBUTES = 84
        const val DETECTION_COUNT = 5
        const val INPUT_SIZE = 640f

        /**
         * One int8 step is 1/127 of the model input, which the letterbox stretches most along y:
         * a 1280x720 frame into 640 letterboxes at scale 0.5, so 640/127 model pixels become
         * (640/127)/(720*0.5) ~= 0.014 of frame height. Rounding can cost a step at each edge.
         */
        const val BOX_TOLERANCE = 0.03f
    }
}
