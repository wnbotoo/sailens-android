package com.sailens.vision.semantic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the camera frame lands in the letterboxed score grid. These numbers are the ones the
 * native preprocess produces for the shipped 960x540 analysis stream and a 640x640 model.
 */
class SemanticContentRegionTest {

    @Test
    fun `a portrait frame occupies the centre 360 columns`() {
        val region = SemanticContentRegion.forLetterbox(960, 540, rotationDegrees = 90, 640, 640, 640, 640)
        assertEquals(SemanticContentRegion(140, 0, 360, 640), region)
    }

    @Test
    fun `a landscape frame occupies the centre 360 rows`() {
        val region = SemanticContentRegion.forLetterbox(960, 540, rotationDegrees = 0, 640, 640, 640, 640)
        assertEquals(SemanticContentRegion(0, 140, 640, 360), region)
    }

    @Test
    fun `a smaller output grid scales the region with it`() {
        val region = SemanticContentRegion.forLetterbox(960, 540, rotationDegrees = 270, 640, 640, 160, 160)
        assertEquals(SemanticContentRegion(35, 0, 90, 160), region)
    }

    @Test
    fun `a square frame has no padding`() {
        val region = SemanticContentRegion.forLetterbox(640, 640, rotationDegrees = 0, 640, 640, 640, 640)
        assertEquals(SemanticContentRegion(0, 0, 640, 640), region)
    }

    @Test
    fun `cropping keeps only the content rows and columns`() {
        // 4x3 grid, content = columns 1..2 of all rows
        val grid = intArrayOf(
            9, 1, 2, 9,
            9, 3, 4, 9,
            9, 5, 6, 9,
        )
        val out = IntArray(6)
        cropClassMap(grid, gridWidth = 4, SemanticContentRegion(1, 0, 2, 3), out)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), out)
    }
}
