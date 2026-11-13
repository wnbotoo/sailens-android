package com.sailens.vision.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class DepthInputTransformTest {

    private val sizes = listOf(720 to 1280, 1280 to 720)
    private val tensors = listOf(294 to 518, 518 to 518, 686 to 518)

    @Test
    fun `analysis to tensor and back is the identity for every policy and orientation`() {
        // The resizes `create` makes; a source's own mapping is DepthSourceMapping's (MetricDepthMapTest).
        for ((aw, ah) in sizes) for ((mw, mh) in tensors) for (policy in ResizePolicy.entries - ResizePolicy.SOURCE_MAPPED) {
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
     * A tensor marked the way the native letterbox writes it (sailens_runtime_jni.cpp): image data in
     * columns [ceil(pad), ceil(pad + content)) and rows likewise, 1; padding 1000. Building the marker
     * with the sampler's own rule would hide any disagreement between the two.
     */
    private fun nativeMarked(transform: DepthInputTransform): FloatArray {
        val w = transform.modelWidth
        val h = transform.modelHeight
        val x0 = ceil(transform.padLeft).toInt()
        val x1 = ceil(transform.padLeft + transform.sourceWidth * transform.scaleX).toInt().coerceAtMost(w)
        val y0 = ceil(transform.padTop).toInt()
        val y1 = ceil(transform.padTop + transform.sourceHeight * transform.scaleY).toInt().coerceAtMost(h)
        return FloatArray(w * h) { i -> if (i % w in x0 until x1 && i / w in y0 until y1) 1f else 1000f }
    }

    private fun assertNoPaddingSampled(transform: DepthInputTransform) {
        val map = DisparityMap(transform.modelWidth, transform.modelHeight, nativeMarked(transform), transform)
        val aw = transform.analysisWidth.toFloat()
        val ah = transform.analysisHeight.toFloat()
        for (u in listOf(0f, 0.3f, 0.5f, 1f, 2f, aw / 2, aw - 2, aw - 1, aw - 0.5f, aw - 0.01f)) {
            for (v in listOf(0f, 0.5f, ah / 2, ah - 1, ah - 0.01f)) {
                assertEquals("u=$u v=$v", 1f, map.sampleAt(u, v)!!, 0f)
            }
        }
    }

    /** Fractional padding: 960x1280 padded into 518x518 leaves 64.75 tensor pixels each side. */
    @Test
    fun `fractional padding never blends in at the image edges`() {
        val transform = DepthInputTransform.create(960, 1280, 518, 518, ResizePolicy.PAD_KEEP_ASPECT)
        assertEquals(64.75f, transform.padLeft, 1e-3f)
        assertNoPaddingSampled(transform)
    }

    /**
     * Half-pixel padding: 640x1280 into 518x518 pads 129.5 each side. The native kernel writes column
     * 129 as padding (its left edge is in the padding although its centre is not), so it is not content.
     */
    @Test
    fun `half-pixel padding takes the content from where the native kernel wrote it`() {
        val transform = DepthInputTransform.create(640, 1280, 518, 518, ResizePolicy.PAD_KEEP_ASPECT)
        assertEquals(129.5f, transform.padLeft, 1e-3f)
        val map = DisparityMap(518, 518, nativeMarked(transform), transform)
        assertEquals(130, map.contentColumns.first)
        assertEquals(387, map.contentColumns.last)
        assertNoPaddingSampled(transform)
    }

    /**
     * The five paddings the native cross-check uses, from a real frame size: the 540x960 analysis
     * image into a 224-wide tensor of five heights pads each side by 4, 6.25, 8.5, 10.75 and 1.75 --
     * fractional parts 0, 0.25, 0.5, 0.75 and the reference model's.
     */
    @Test
    fun `content bounds follow the native kernel for every fractional padding`() {
        val cases = listOf(384 to 4f, 376 to 6.25f, 368 to 8.5f, 360 to 10.75f, 392 to 1.75f)
        for ((height, pad) in cases) {
            val transform = DepthInputTransform.create(540, 960, 224, height, ResizePolicy.PAD_KEEP_ASPECT)
            assertEquals("height $height", pad, transform.padLeft, 1e-4f)
            val map = DisparityMap(224, height, FloatArray(224 * height), transform)
            val end = transform.padLeft + transform.sourceWidth * transform.scaleX
            assertEquals("height $height first", ceil(pad).toInt(), map.contentColumns.first)
            assertEquals("height $height last", ceil(end - 0.5f).toInt() - 1, map.contentColumns.last)
            assertNoPaddingSampled(transform)
        }
    }

    @Test
    fun `without padding every pixel is content`() {
        val transform = DepthInputTransform.create(720, 1280, 518, 518, ResizePolicy.CROP_KEEP_ASPECT)
        val map = DisparityMap(518, 518, FloatArray(518 * 518), transform)
        assertEquals(0..517, map.contentColumns)
        assertEquals(0..517, map.contentRows)
    }

    @Test
    fun `an unseen point samples as unknown`() {
        val transform = DepthInputTransform.create(720, 1280, 518, 518, ResizePolicy.CROP_KEEP_ASPECT)
        val map = DisparityMap(518, 518, FloatArray(518 * 518) { 1f }, transform)
        assertNull(map.sampleAt(360f, 100f))
        assertEquals(1f, map.sampleAt(360f, 640f)!!, 0f)
    }
}
