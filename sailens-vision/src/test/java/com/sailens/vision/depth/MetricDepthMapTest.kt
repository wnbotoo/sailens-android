package com.sailens.vision.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A metric source's depth on the analysis image: placed by the mapping it reports, whatever the two images' aspects,
 * the turn to upright or the crop; unknown wherever it does not cover the image or did not measure.
 */
class MetricDepthMapTest {

    // A 16:9 camera buffer and a 4:3 depth image over its centre, full height: 6 buffer pixels a depth pixel.
    private val bufferW = 1280
    private val bufferH = 720
    private val depthW = 160
    private val depthH = 120
    private val toBuffer = floatArrayOf(6f, 0f, 160f, 0f, 6f, 0f)

    /** Where a buffer point lands upright, continuous coordinates. */
    private fun upright(x: Double, y: Double, w: Double, h: Double, turn: Int): Pair<Double, Double> = when (turn) {
        0 -> x to y
        90 -> (h - y) to x
        180 -> (w - x) to (h - y)
        else -> y to (w - x)
    }

    @Test
    fun `every depth pixel maps to the analysis point the source reports, in each of the four turns`() {
        for (turn in listOf(0, 90, 180, 270)) {
            val mapped = DepthSourceMapping.map(depthW, depthH, bufferW, bufferH, turn, toBuffer) as DepthSourceMapping.Result.Mapped
            val t = mapped.transform
            val turned = turn == 90 || turn == 270
            assertEquals(if (turned) depthH else depthW, mapped.uprightWidth)
            assertEquals(if (turned) bufferH else bufferW, t.analysisWidth)
            for ((x, y) in listOf(0.5 to 0.5, 80.5 to 60.5, 159.5 to 119.5, 3.25 to 117.75)) {
                // The truth: depth pixel -> buffer, by the source's report; both turned upright alike.
                val (bx, by) = 6.0 * x + 160.0 to 6.0 * y
                val (u, v) = upright(bx, by, bufferW.toDouble(), bufferH.toDouble(), turn)
                val (ux, uy) = upright(x, y, depthW.toDouble(), depthH.toDouble(), turn)
                assertEquals("turn $turn, ($x, $y)", ux, t.toTensorX(u.toFloat()).toDouble(), 1e-3)
                assertEquals("turn $turn, ($x, $y)", uy, t.toTensorY(v.toFloat()).toDouble(), 1e-3)
                assertTrue(t.covers(u.toFloat(), v.toFloat()))
            }
        }
    }

    @Test
    fun `outside the crop is not covered, on each of its four edges`() {
        // Upright (90): the buffer's left and right become the analysis image's top and bottom, 160 px each.
        val t = (DepthSourceMapping.map(depthW, depthH, bufferW, bufferH, 90, toBuffer) as DepthSourceMapping.Result.Mapped).transform
        assertEquals(0f, t.sourceLeft)
        assertEquals(160f, t.sourceTop)
        assertEquals(720f, t.sourceWidth)
        assertEquals(960f, t.sourceHeight)
        assertTrue(t.covers(360f, 160.01f))
        assertFalse(t.covers(360f, 159.99f))
        assertTrue(t.covers(360f, 1119.99f))
        assertFalse(t.covers(360f, 1120.01f))
        // A crop of the buffer's centre in both axes: the depth image's sides lie outside the analysis image.
        val wide = DepthSourceMapping.map(200, 120, bufferW, bufferH, 0, floatArrayOf(8f, 0f, -160f, 0f, 8f, -120f)) as DepthSourceMapping.Result.Mapped
        assertEquals(0f, wide.transform.sourceLeft)
        assertEquals(1280f, wide.transform.sourceWidth)
        assertEquals(20f, wide.transform.padLeft, 1e-4f)
        assertEquals(15f, wide.transform.padTop, 1e-4f)
        assertFalse(wide.transform.covers(-0.01f, 100f))
        assertFalse(wide.transform.covers(1280.01f, 100f))
    }

    @Test
    fun `a mapping that turns, shears or mirrors is refused, not approximated`() {
        val turned = floatArrayOf(0f, 6f, 160f, 6f, 0f, 0f)
        val sheared = floatArrayOf(6f, 0.5f, 160f, 0f, 6f, 0f)
        val mirrored = floatArrayOf(-6f, 0f, 1120f, 0f, 6f, 0f)
        val elsewhere = floatArrayOf(6f, 0f, 5000f, 0f, 6f, 0f)
        for (affine in listOf(turned, sheared, mirrored, elsewhere)) {
            assertTrue(affine.contentToString(), DepthSourceMapping.map(depthW, depthH, bufferW, bufferH, 90, affine) is DepthSourceMapping.Result.Refused)
        }
    }

    private fun map(depth: FloatArray, confidence: FloatArray? = null, w: Int = 4, h: Int = 4) = MetricDepthMap(
        w, h, depth, confidence, minConfidence = 0.5f,
        transform = DepthInputTransform(w, h, w, h, ResizePolicy.SOURCE_MAPPED, 0f, 0f, w.toFloat(), h.toFloat(), 1f, 1f, 0f, 0f),
        times = DepthTimes(1L, 2L, MeasurementKey(1, 7)),
    )

    @Test
    fun `samples are 1 over Z between known pixels, and unknown next to a hole or a low confidence`() {
        val depth = FloatArray(16) { 2f }.also { it[5] = 4f }
        val m = map(depth)
        assertEquals(DepthKind.METRIC_DEPTH, m.kind)
        // At a pixel centre the value is that pixel's.
        assertEquals(0.5f, m.sampleAt(0.5f, 0.5f)!!, 1e-6f)
        assertEquals(4f, m.depthAt(1.5f, 1.5f)!!, 1e-6f)
        assertEquals(0.25f, m.sampleAt(1.5f, 1.5f)!!, 1e-6f)
        // Halfway between 2 m and 4 m: inverse depth interpolated as inverse depth, depth as depth.
        assertEquals((0.5f + 0.25f) / 2, m.sampleAt(1.0f, 1.5f)!!, 1e-6f)
        assertEquals(3f, m.depthAt(1.0f, 1.5f)!!, 1e-6f)
        assertEquals(0f, m.unknownFraction)
        for (bad in listOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY)) {
            val holed = map(depth.copyOf().also { it[5] = bad })
            assertNull("$bad", holed.sampleAt(1.0f, 1.5f))
            assertNull("$bad", holed.depthAt(1.5f, 1.5f))
            assertFalse(holed.isKnownAt(1.2f, 1.2f))
            assertTrue("away from it", holed.isKnownAt(3.5f, 3.5f))
            assertEquals(1f / 16, holed.unknownFraction)
        }
        val unsure = map(depth, FloatArray(16) { 1f }.also { it[5] = 0.4f })
        assertNull(unsure.sampleAt(1.5f, 1.5f))
        assertTrue(unsure.sampleAt(3.5f, 3.5f) != null)
        // A mixed pixel at an edge -- between a jump to each side -- is unknown; a thin object one pixel wide is not.
        val row = floatArrayOf(2f, 2f, 3f, 4f, 2f, 2f, 3f, 4f, 2f, 2f, 3f, 4f, 2f, 2f, 3f, 4f)
        val edge = map(row)
        assertFalse(edge.isKnownAt(2.5f, 1.5f))
        assertTrue(edge.isKnownAt(0.6f, 1.5f))
        val post = map(FloatArray(16) { 4f }.also { for (y in 0 until 4) it[y * 4 + 2] = 2f })
        assertTrue(post.isKnownAt(2.5f, 1.5f))
        assertEquals(2f, post.depthAt(2.5f, 1.5f)!!, 1e-6f)
        // Off the covered region nothing is known.
        assertNull(m.sampleAt(-0.1f, 1f))
        assertTrue(m.sampleOrNaN(4.1f, 1f).isNaN())
    }
}
