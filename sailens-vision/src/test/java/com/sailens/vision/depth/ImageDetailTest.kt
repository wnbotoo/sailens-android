package com.sailens.vision.depth

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDetailTest {

    private fun frame(width: Int, height: Int, rowStride: Int = width, luma: (Int, Int) -> Int): ImageFrame {
        val y = ByteArray(rowStride * height) { i -> luma(i % rowStride, i / rowStride).toByte() }
        val chroma = YuvPlaneData(ByteArray((width / 2) * (height / 2)) { 128.toByte() }, rowStride = width / 2, pixelStride = 1)
        return ImageFrame(
            width = width,
            height = height,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.entries.first(),
            timestamp = 0L,
            rotationDegrees = 90,
            sequenceNumber = 1L,
            yuvData = Yuv420FrameData(YuvPlaneData(y, rowStride = rowStride, pixelStride = 1), chroma, chroma),
        )
    }

    @Test
    fun `a flat frame has no detail and a fine checkerboard has a lot`() {
        assertEquals(0f, ImageDetail.measure(frame(960, 540) { _, _ -> 120 })!!, 0f)
        val checker = ImageDetail.measure(frame(960, 540) { x, y -> if ((x + y) % 2 == 0) 40 else 200 })!!
        assertEquals(160f, checker, 0f)
    }

    @Test
    fun `row padding in the plane is not read as image`() {
        // Stride padding filled with an extreme value; the measure must not see it.
        val padded = frame(960, 540, rowStride = 1024) { x, _ -> if (x >= 960) 255 else 100 }
        assertEquals(0f, ImageDetail.measure(padded)!!, 0f)
        assertTrue(ImageDetail.GRID > 0)
    }

    @Test
    fun `a frame without YUV planes has no measure`() {
        val rgba = frame(960, 540) { _, _ -> 0 }.copy(yuvData = null)
        assertNull(ImageDetail.measure(rgba))
    }
}
