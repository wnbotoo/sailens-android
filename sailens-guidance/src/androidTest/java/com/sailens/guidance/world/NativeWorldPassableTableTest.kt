package com.sailens.guidance.world

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.kernel.NativeGuidanceLibrary
import com.sailens.guidance.kernel.NativeWorldGrid
import com.sailens.guidance.model.perception.SegmentationMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class NativeWorldPassableTableTest {
    @Test
    fun nativeAndKotlinCountsAgreeAcrossWordEdgesAndReusedStorage() {
        assertTrue(NativeGuidanceLibrary.isAvailable)
        val reference = WorldPassableTable(preferNative = false)
        val random = Random(9)
        val words = LongArray(16)
        for ((width, height) in listOf(131 to 5, 65 to 4, 64 to 5, 9 to 3, 130 to 4)) {
            val size = width * height
            val mask = BinaryMask(width, height)
            val labels = SegmentationMask(width, height, IntArray(size) {
                when (it % 7) {
                    0 -> -1
                    1 -> Int.MIN_VALUE
                    2 -> 256
                    3 -> Int.MAX_VALUE
                    else -> random.nextInt(33)
                }
            })
            for (index in 0 until size) {
                mask.set(index % width, index / width, index in 32..300 || index % 7 != 2)
            }
            repeat(2) { attempt ->
                if (attempt > 0) {
                    mask.clear()
                    mask.set(width - 1, height - 1, true)
                }
                mask.copyPackedBitsInto(words)
                assertArrayEquals(mask.copyPackedBits().copyOf((size + 63) / 64), words.copyOf((size + 63) / 64))
                for (classCount in listOf(3, 32, 33)) {
                    val passable = BooleanArray(classCount) { it % 3 != 1 }
                    val sums = IntArray((width + 1) * (height + 1)) { -7 }
                    assertTrue(NativeWorldGrid.fill(width, height, labels.classMap, words, passable, sums))
                    assertTrue(reference.fill(mask, labels, passable))
                    for (bottom in 1..height) {
                        for (right in 1..width) {
                            assertEquals(reference.accepted(PixelArea(0, 0, right, bottom)), sums[bottom * (width + 1) + right])
                        }
                    }
                    assertTrue(sums.take(width + 1).all { it == 0 })
                    assertTrue((1..height).all { sums[it * (width + 1)] == 0 })
                }
            }
        }
    }

    @Test
    fun invalidDimensionsAndBuffersAreRejectedWithoutOverwritingOutput() {
        assertTrue(NativeGuidanceLibrary.isAvailable)
        val output = intArrayOf(9, 8, 7, 6)
        assertFalse(NativeWorldGrid.fill(Int.MAX_VALUE, Int.MAX_VALUE, intArrayOf(0), longArrayOf(1), booleanArrayOf(true), output))
        assertFalse(NativeWorldGrid.fill(1, 1, intArrayOf(0), longArrayOf(), booleanArrayOf(true), output))
        assertFalse(NativeWorldGrid.fill(2, 2, output, longArrayOf(15), booleanArrayOf(true), output))
        assertArrayEquals(intArrayOf(9, 8, 7, 6), output)
    }
}
