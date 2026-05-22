package com.friady.sailens.data.source.ml.instance

import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.ImagePixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YOLO26SegPostProcessorTest {

    private val postProcessor = YOLO26SegPostProcessor(
        confidenceThreshold = 0.25f,
        nmsThreshold = 0.5f,
        maxDetections = 10,
        enableMaskReconstruction = true,
    )

    @Test
    fun `end-to-end layout decodes top detections without legacy class scores`() {
        val frame = createFrame()
        val raw = FloatArray(38 * 4)

        setEndToEndDetection(raw, 0, x1 = 260f, y1 = 250f, x2 = 380f, y2 = 430f, classId = 0, score = 0.94f)
        setEndToEndDetection(raw, 1, x1 = 500f, y1 = 260f, x2 = 620f, y2 = 410f, classId = 2, score = 0.88f)
        setEndToEndDetection(raw, 2, x1 = 120f, y1 = 100f, x2 = 220f, y2 = 200f, classId = 9, score = 0.99f)
        setEndToEndDetection(raw, 3, x1 = 20f, y1 = 20f, x2 = 30f, y2 = 30f, classId = 0, score = 0.10f)

        val result = postProcessor.postProcess(frame, raw)

        assertEquals(2, result.size)
        assertEquals("person", result[0].className)
        assertEquals("car", result[1].className)
    }

    @Test
    fun `detection-major layout decodes and suppresses overlaps`() {
        val frame = createFrame()
        val raw = FloatArray(116 * 3)

        // detection 0: person, high confidence
        setDetection(raw, 0, cx = 320f, cy = 320f, w = 120f, h = 120f, classId = 0, score = 0.92f)
        // detection 1: overlapping person, lower confidence -> NMS should drop it
        setDetection(raw, 1, cx = 326f, cy = 324f, w = 120f, h = 120f, classId = 0, score = 0.80f)
        // detection 2: car, far right -> keep
        setDetection(raw, 2, cx = 520f, cy = 300f, w = 110f, h = 90f, classId = 2, score = 0.88f)

        val result = postProcessor.postProcess(frame, raw)

        assertEquals(2, result.size)
        assertEquals("person", result[0].className)
        assertEquals("car", result[1].className)
    }

    @Test
    fun `attribute-major layout is also decoded`() {
        val frame = createFrame()
        val detections = Array(2) { FloatArray(116) }
        detections[0][0] = 0.5f
        detections[0][1] = 0.5f
        detections[0][2] = 0.18f
        detections[0][3] = 0.22f
        detections[0][4 + 0] = 0.91f

        detections[1][0] = 0.80f
        detections[1][1] = 0.48f
        detections[1][2] = 0.12f
        detections[1][3] = 0.10f
        detections[1][4 + 2] = 0.86f

        val raw = FloatArray(116 * 2)
        for (attribute in 0 until 116) {
            for (detection in detections.indices) {
                raw[attribute * detections.size + detection] = detections[detection][attribute]
            }
        }

        val result = postProcessor.postProcess(frame, raw)

        assertEquals(2, result.size)
        assertTrue(result.all { it.confidence >= 0.25f })
    }

    @Test
    fun `prototype masks reconstruct binary mask`() {
        val frame = createFrame()
        val raw = FloatArray(116)
        setDetection(raw, 0, cx = 320f, cy = 320f, w = 200f, h = 180f, classId = 0, score = 0.95f)
        for (index in 0 until 32) {
            raw[84 + index] = 1f
        }

        val prototypes = FloatArray(32 * 160 * 160)
        for (channel in 0 until 32) {
            for (y in 45 until 115) {
                for (x in 45 until 115) {
                    prototypes[channel * 160 * 160 + y * 160 + x] = 0.8f
                }
            }
        }

        val result = postProcessor.postProcess(frame, raw, prototypes)

        assertEquals(1, result.size)
        val mask = result.first().mask
        assertTrue(mask != null)
        assertTrue(mask!!.countTrue() > 0)
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

    private fun setDetection(
        raw: FloatArray,
        detectionIndex: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        classId: Int,
        score: Float,
    ) {
        val base = detectionIndex * 116
        raw[base] = cx
        raw[base + 1] = cy
        raw[base + 2] = w
        raw[base + 3] = h
        raw[base + 4 + classId] = score
    }

    private fun setEndToEndDetection(
        raw: FloatArray,
        detectionIndex: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        classId: Int,
        score: Float,
    ) {
        val base = detectionIndex * 38
        raw[base] = x1
        raw[base + 1] = y1
        raw[base + 2] = x2
        raw[base + 3] = y2
        raw[base + 4] = score
        raw[base + 5] = classId.toFloat()
    }
}
