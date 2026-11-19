package com.sailens.guidance.world

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.kernel.NativeGuidanceLibrary
import com.sailens.guidance.kernel.NativeWorldGrid
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.runtime.InputImageMapping
import com.sailens.vision.semantic.SemanticContentRegion
import com.sailens.vision.semantic.SemanticImageMapping
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeWorldGridParityTest {
    private val edges = DoubleArray(LocalGroundGrid.COLUMNS + 1, LocalGroundGrid::rightEdge)
    private val width = 73
    private val height = 65

    @Test
    fun everyCellMatchesTheKotlinClosedFootprintAndSupportRules() {
        assertTrue(NativeGuidanceLibrary.isAvailable)
        val input = InputImageMapping(
            104, 81, 100, 100,
            InputImageMapping.Axis(.93, 1.25, 2, 98),
            InputImageMapping.Axis(.91, 11.5, 12, 85),
            0,
        )
        val labels = SegmentationMask(width, height, IntArray(width * height) { if (it % 157 == 0) 1 else 0 })
        val effective = BinaryMask(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) effective.set(x, y, x !in 23..27 || y !in 38..44)
        }
        val mapping = SemanticImageMapping(input, 81, 87, SemanticContentRegion(3, 10, width, height))
        val semantic = checkNotNull(WorldSemanticFootprint.from(mapping, labels))
        val table = WorldPassableTable(preferNative = false)
        assertTrue(table.fill(effective, labels, booleanArrayOf(true, false)))
        val axes = DoubleArray(WorldSemanticFootprint.AXES_SIZE).also(semantic::writeAxes)
        val reference = WorldGridRasterizer(preferNative = false)
        for (slope in listOf(9.0, -9.0, Math.nextUp(9.0))) {
            for (useSem in listOf(true, false)) {
                for (rawCount in listOf(0, Long.SIZE_BITS)) {
                    fillRows(reference.rows, slope)
                    val raw = List(rawCount) { i ->
                        if (i == Long.SIZE_BITS - 1) ImageFootprint(Bound(24.0, 28.0), Bound(21.0, 43.0))
                        else ImageFootprint(Bound(200.0, 210.0), Bound(200.0, 210.0))
                    }
                    val packed = DoubleArray(raw.size * 4) { at ->
                        val box = raw[at / 4]
                        when (at % 4) {
                            0 -> box.x.low
                            1 -> box.x.high
                            2 -> box.y.low
                            else -> box.y.high
                        }
                    }
                    val expected = LocalWorldCells(emptyList(), emptyList())
                    val actual = LocalWorldCells(emptyList(), emptyList())
                    for (i in expected.contacts.indices step 31) {
                        expected.contacts[i] = 1L
                        actual.contacts[i] = 1L
                    }
                    val expectedProjectable = reference.classify(raw, semantic.takeIf { useSem }, table, expected)
                    val actualProjectable = NativeWorldGrid.classify(
                        reference.rows, edges, packed, rawCount, axes.takeIf { useSem },
                        width, height, table.prefixSums().takeIf { useSem }, actual.contacts, actual.conflicts, actual.surface,
                    )
                    assertEquals(if (expectedProjectable) 1 else 0, actualProjectable)
                    assertArrayEquals(expected.conflicts, actual.conflicts)
                    assertArrayEquals(expected.surface, actual.surface)
                    assertArrayEquals(expected.contacts, actual.contacts)
                    if (useSem) assertTrue(actual.surface.any { it })
                    if (rawCount > 0) assertTrue(actual.conflicts.any { it == Long.MIN_VALUE })
                }
            }
        }
    }

    @Test
    fun unprojectableRowsClearOutputsAndAnInvalidLayoutLeavesThemAlone() {
        assertTrue(NativeGuidanceLibrary.isAvailable)
        val output = LocalWorldCells(emptyList(), emptyList())
        val rows = DoubleArray((LocalGroundGrid.ROWS + 1) * WORLD_ROW_WIDTH) { Double.NaN }
        output.conflicts.fill(5)
        output.surface.fill(true)
        assertEquals(-1, NativeWorldGrid.classify(rows, DoubleArray(3), doubleArrayOf(), 0, null, 0, 0, null,
            output.contacts, output.conflicts, output.surface))
        assertTrue(output.conflicts.all { it == 5L })
        assertTrue(output.surface.all { it })
        assertEquals(0, NativeWorldGrid.classify(rows, edges, doubleArrayOf(), 0, null, 0, 0, null,
            output.contacts, output.conflicts, output.surface))
        assertTrue(output.conflicts.all { it == 0L })
        assertTrue(output.surface.none { it })
    }

    private fun fillRows(target: DoubleArray, slope: Double) {
        for (row in 0..LocalGroundGrid.ROWS) {
            val at = row * WORLD_ROW_WIDTH
            if (row < 2 || row == 25) {
                target.fill(Double.NaN, at, at + WORLD_ROW_WIDTH)
                continue
            }
            val x = 35.0 + row * .0625
            val y = 2.0 + row
            target[at] = Math.nextDown(x)
            target[at + 1] = Math.nextUp(x)
            target[at + 2] = Math.nextDown(y)
            target[at + 3] = Math.nextUp(y)
            target[at + 4] = Math.nextDown(slope)
            target[at + 5] = Math.nextUp(slope)
            target[at + 6] = Math.nextDown(-.5)
            target[at + 7] = Math.nextUp(-.5)
        }
    }
}
