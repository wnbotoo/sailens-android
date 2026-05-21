package com.friady.sailens.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrimitiveCollectionsTest {
    @Test
    fun `packed coordinates round trip`() {
        val coordinates = listOf(
            0 to 0,
            1 to 2,
            255 to 127,
            640 to 360,
            4095 to 4095,
            65535 to 65535,
        )

        coordinates.forEach { (x, y) ->
            val packed = packCoordinate(x, y)
            assertEquals(x, unpackCoordinateX(packed))
            assertEquals(y, unpackCoordinateY(packed))
        }
    }

    @Test
    fun `int array queue preserves fifo order across growth`() {
        val queue = IntArrayQueue(initialCapacity = 2)

        for (value in 0 until 16) {
            queue.addLast(value)
        }

        for (expected in 0 until 16) {
            assertEquals(expected, queue.removeFirst())
        }
    }

    @Test
    fun `int array list rejects index equal to size`() {
        val list = IntArrayList(initialCapacity = 4)
        list.add(42)

        assertEquals(42, list[0])
        assertThrows(IllegalArgumentException::class.java) {
            list[1]
        }
    }
}
