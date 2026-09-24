package com.sailens.guidance.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Horizontal thresholds are fractions of the frame's long side, which is what the old 640-wide
 * letterboxed mask measured in both orientations.
 */
class WidthFractionOfLongSideTest {

    @Test
    fun `portrait keeps the effective values the letterboxed mask had`() {
        // Old mask: 0.05 x 640 = 32 px; the frame is 360 of those 640 columns wide.
        assertEquals(32f / 360f, widthFractionOfLongSide(0.05f, width = 360, height = 640), 1e-5f)
        assertEquals(256f / 360f, widthFractionOfLongSide(0.40f, width = 360, height = 640), 1e-5f)
    }

    @Test
    fun `landscape keeps the values unchanged, as the old mask width was the full frame width`() {
        assertEquals(0.05f, widthFractionOfLongSide(0.05f, width = 640, height = 360), 1e-6f)
        assertEquals(0.40f, widthFractionOfLongSide(0.40f, width = 640, height = 360), 1e-6f)
    }

    @Test
    fun `never exceeds the whole width`() {
        assertEquals(1f, widthFractionOfLongSide(0.9f, width = 100, height = 640), 0f)
    }
}
