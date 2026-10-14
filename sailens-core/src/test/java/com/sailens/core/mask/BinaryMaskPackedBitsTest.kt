package com.sailens.core.mask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Random

class BinaryMaskPackedBitsTest {

    /** The packing the native connectivity kernel and the regression CRC read: bit i in word i/64, bit i%64. */
    private fun reference(mask: BinaryMask): LongArray {
        val words = LongArray((mask.width * mask.height + 63) / 64)
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask.get(x, y)) {
                val i = y * mask.width + x
                words[i ushr 6] = words[i ushr 6] or (1L shl (i and 63))
            }
        }
        return words
    }

    @Test
    fun `packed bits match the per-pixel packing, into a reused or a fresh buffer`() {
        val random = Random(3)
        for ((w, h) in listOf(1 to 1, 7 to 10, 64 to 1, 65 to 2, 63 to 3, 360 to 640)) {
            for (density in listOf(0.0, 0.01, 0.5, 1.0)) {
                val mask = BinaryMask(w, h)
                for (y in 0 until h) for (x in 0 until w) mask.set(x, y, random.nextDouble() < density)
                val expected = reference(mask)
                assertArrayEquals("${w}x$h $density fresh", expected, mask.copyPackedBitsInto(LongArray(0)))
                // A reused buffer holding stale bits from a bigger mask: the words in range are rewritten.
                val dirty = LongArray(expected.size + 3) { -1L }
                val reused = mask.copyPackedBitsInto(dirty)
                assertSame(dirty, reused)
                assertArrayEquals("${w}x$h $density reused", expected, reused.copyOf(expected.size))
            }
        }
    }
}
