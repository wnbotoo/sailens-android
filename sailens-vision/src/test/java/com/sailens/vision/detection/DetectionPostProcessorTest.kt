package com.sailens.vision.detection

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPostProcessorTest {

    /**
     * The classes a navigation product asks for. Supplied by the caller now rather than derived
     * inside the detector (architecture.md §6.3) -- which is why class 9, traffic light, is absent
     * and its 0.99 detection is expected to be dropped.
     */
    private val obstacleClassIds = intArrayOf(0, 1, 2, 3, 5, 7, 11, 13, 56, 58)

    @Test
    fun `raw output decodes top detections with nms`() {
        val postProcessor = DetectionPostProcessor(
            confidenceThreshold = 0.25f,
            maxDetections = 10,
            allowedClassIds = obstacleClassIds,
        )
        val frame = createFrame()
        val raw = FloatArray(84 * 5)

        setRawDetection(raw, 0, detectionCount = 5, cx = 320f, cy = 340f, width = 120f, height = 180f, classId = 0, score = 0.94f)
        setRawDetection(raw, 1, detectionCount = 5, cx = 560f, cy = 335f, width = 120f, height = 150f, classId = 2, score = 0.88f)
        setRawDetection(raw, 2, detectionCount = 5, cx = 170f, cy = 150f, width = 100f, height = 100f, classId = 9, score = 0.99f)
        setRawDetection(raw, 3, detectionCount = 5, cx = 320f, cy = 340f, width = 120f, height = 180f, classId = 0, score = 0.80f)
        setRawDetection(raw, 4, detectionCount = 5, cx = 25f, cy = 25f, width = 10f, height = 10f, classId = 0, score = 0.10f)

        val result = postProcessor.postProcess(frame, raw)

        assertEquals(2, result.size)
        assertEquals("person", result[0].label)
        assertEquals("car", result[1].label)
    }

    @Test
    fun `raw output keeps readable static obstacle class names`() {
        val postProcessor = DetectionPostProcessor(
            confidenceThreshold = 0.25f,
            maxDetections = 10,
            allowedClassIds = obstacleClassIds,
        )
        val frame = createFrame()
        val raw = FloatArray(84)

        setRawDetection(raw, 0, detectionCount = 1, cx = 320f, cy = 340f, width = 120f, height = 120f, classId = 11, score = 0.94f)

        val result = postProcessor.postProcess(frame, raw)

        assertEquals(1, result.size)
        assertEquals("stop_sign", result.single().label)
    }

    @Test
    fun `end to end output decodes filtered detections`() {
        val postProcessor = DetectionPostProcessor(
            detectionLayout = DetectionLayout.END_TO_END,
            confidenceThreshold = 0.25f,
            maxDetections = 10,
            allowedClassIds = obstacleClassIds,
        )
        val frame = createFrame()
        val raw = FloatArray(6 * 3)

        setEndToEndDetection(raw, 0, attributes = 6, left = 260f, top = 220f, right = 380f, bottom = 430f, classId = 0, score = 0.94f)
        setEndToEndDetection(raw, 1, attributes = 6, left = 520f, top = 250f, right = 640f, bottom = 410f, classId = 2, score = 0.88f)
        setEndToEndDetection(raw, 2, attributes = 6, left = 20f, top = 20f, right = 40f, bottom = 40f, classId = 0, score = 0.10f)

        val output = postProcessor.postProcessWithBackend(frame, raw)

        assertEquals("kotlin_e2e", output.backend)
        assertEquals(2, output.detections.size)
        assertEquals("person", output.detections[0].label)
        assertEquals("car", output.detections[1].label)
    }

    @Test
    fun `invalid output shape returns no detections`() {
        val postProcessor = DetectionPostProcessor()

        val output = postProcessor.postProcessWithBackend(createFrame(), FloatArray(5))

        assertEquals("invalid_shape", output.backend)
        assertTrue(output.detections.isEmpty())
    }

    private fun createFrame(): ImageFrame {
        return ImageFrame(
            width = 1280,
            height = 720,
            pixelBytes = ByteArray(1280 * 720 * 4),
            pixelFormat = ImagePixelFormat.RGBA_8888,
            timestamp = 1L,
            rotationDegrees = 0,
            sequenceNumber = 1L,
        )
    }

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

    private fun setEndToEndDetection(
        raw: FloatArray,
        detectionIndex: Int,
        attributes: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        classId: Int,
        score: Float,
    ) {
        val base = detectionIndex * attributes
        raw[base] = left
        raw[base + 1] = top
        raw[base + 2] = right
        raw[base + 3] = bottom
        raw[base + 4] = score
        raw[base + 5] = classId.toFloat()
    }
}
