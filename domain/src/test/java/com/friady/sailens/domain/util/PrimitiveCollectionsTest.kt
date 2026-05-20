package com.friady.sailens.domain.util

import org.junit.Assert.assertEquals
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
}

