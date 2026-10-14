package com.sailens.vision.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthInputTransformTest {

    private val sizes = listOf(720 to 1280, 1280 to 720)
    private val tensors = listOf(294 to 518, 518 to 518, 686 to 518)

    @Test
    fun `analysis to tensor and back is the identity for every policy and orientation`() {
        for ((aw, ah) in sizes) for ((mw, mh) in tensors) for (policy in ResizePolicy.entries) {
            val t = DepthInputTransform.create(aw, ah, mw, mh, policy)
            for ((u, v) in listOf(0f to 0f, aw / 2f to ah / 2f, aw - 0.5f to ah - 0.5f, 123.4f to 567.8f)) {
                assertEquals("$policy ${aw}x$ah→${mw}x$mh u", u, t.toAnalysisX(t.toTensorX(u)), 1e-3f)
                assertEquals("$policy ${aw}x$ah→${mw}x$mh v", v, t.toAnalysisY(t.toTensorY(v)), 1e-3f)
            }
        }
    }

    @Test
    fun `each policy maps the image as its name says`() {
        val stretch = DepthInputTransform.create(720, 1280, 518, 518, ResizePolicy.STRETCH)
        assertEquals(518f, stretch.toTensorX(720f), 1e-3f)
        assertEquals(518f, stretch.toTensorY(1280f), 1e-3f)
        assertTrue(stretch.scaleX != stretch.scaleY)

        // Crop to a square: the middle 720x720 is seen, top and bottom are not.
        val crop = DepthInputTransform.create(720, 1280, 518, 518, ResizePolicy.CROP_KEEP_ASPECT)
        assertEquals(0f, crop.toTensorY(280f), 1e-3f)
        assertEquals(518f, crop.toTensorY(1000f), 1e-3f)
        assertTrue(crop.covers(360f, 640f))
        assertFalse(crop.covers(360f, 100f))
        assertFalse(crop.covers(360f, 1200f))

        // Pad to a square: the whole image fits, with bars left and right.
        val pad = DepthInputTransform.create(720, 1280, 518, 518, ResizePolicy.PAD_KEEP_ASPECT)
        assertEquals(0f, pad.toTensorY(0f), 1e-3f)
        assertEquals(518f, pad.toTensorY(1280f), 1e-3f)
        assertEquals((518 - 720 * 518f / 1280) / 2, pad.toTensorX(0f), 1e-3f)
        assertTrue(pad.covers(0f, 0f))
        assertFalse(pad.covers(-1f, 0f))
    }

    @Test
    fun `sampling reads a linear field exactly and never blends padding in`() {
        val transform = DepthInputTransform.create(720, 1280, 518, 518, ResizePolicy.PAD_KEEP_ASPECT)
        // A field linear in tensor coordinates, with padding set to a value that would show.
        val values = FloatArray(518 * 518) { i ->
            val x = i % 518 + 0.5f
            val y = i / 518 + 0.5f
            if (x < transform.padLeft || x >= 518 - transform.padLeft) 1000f else 0.01f * x + 0.02f * y
        }
        val map = DisparityMap(518, 518, values, transform)
        val u = 360f
        val v = 700f
        val expected = 0.01f * transform.toTensorX(u) + 0.02f * transform.toTensorY(v)
        assertEquals(expected, map.sampleAt(u, v)!!, 1e-3f)
        // At the image's left edge the nearest content pixel is used, not the bar beside it.
        val edge = map.sampleAt(0f, 640f)
        assertNotNull(edge)
        assertTrue("edge sample $edge must not mix in padding", edge!! < 100f)
    }

    /**
     * Fractional padding (review finding on #25): 960x1280 padded into 518x518 leaves 64.75 tensor
     * pixels each side, so the first content pixel is not where the padding ends.
     */
    @Test
    fun `fractional padding never blends in at the image edges`() {
        val transform = DepthInputTransform.create(960, 1280, 518, 518, ResizePolicy.PAD_KEEP_ASPECT)
        assertEquals(64.75f, transform.padLeft, 1e-3f)
        // Content pixels (centre in [pad, size − pad)) hold 1, padding 1000.
        val values = FloatArray(518 * 518) { i ->
            val centre = i % 518 + 0.5f
            if (centre < transform.padLeft || centre >= 518 - transform.padLeft) 1000f else 1f
        }
        val map = DisparityMap(518, 518, values, transform)
        for (u in listOf(0f, 0.5f, 1f, 2f, 480f, 958f, 959f, 959.9f)) {
            for (v in listOf(0f, 640f, 1279.9f)) assertEquals("u=$u v=$v", 1f, map.sampleAt(u, v)!!, 0f)
        }
    }

    /**
     * Half-pixel padding (review finding on #25): 640x1280 into 518x518 pads 129.5 each side, so
     * pixel 388's centre sits exactly on the open right end of the content and is padding.
     */
    @Test
    fun `half-pixel padding leaves the pixel on the right edge out`() {
        val transform = DepthInputTransform.create(640, 1280, 518, 518, ResizePolicy.PAD_KEEP_ASPECT)
        assertEquals(129.5f, transform.padLeft, 1e-3f)
        val values = FloatArray(518 * 518) { i ->
            val centre = i % 518 + 0.5f
            if (centre < transform.padLeft || centre >= 518 - transform.padLeft) 1000f else 1f
        }
        val map = DisparityMap(518, 518, values, transform)
        for (u in listOf(0f, 0.3f, 320f, 639.5f, 639.9f)) {
            for (v in listOf(0f, 640f, 1279.9f)) assertEquals("u=$u v=$v", 1f, map.sampleAt(u, v)!!, 0f)
        }
    }

    @Test
    fun `an unseen point samples as unknown`() {
        val transform = DepthInputTransform.create(720, 1280, 518, 518, ResizePolicy.CROP_KEEP_ASPECT)
        val map = DisparityMap(518, 518, FloatArray(518 * 518) { 1f }, transform)
        assertNull(map.sampleAt(360f, 100f))
        assertEquals(1f, map.sampleAt(360f, 640f)!!, 0f)
    }
}
