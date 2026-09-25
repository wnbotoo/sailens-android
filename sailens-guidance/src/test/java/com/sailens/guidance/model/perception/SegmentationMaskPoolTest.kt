package com.sailens.guidance.model.perception

import com.sailens.guidance.usecase.scene.SegmentationMaskSnapshots
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rule that makes reusing ~0.9 MB class maps safe: an array is handed out again only after the
 * lease that held it was closed, and a mask that leaves the pipeline is a copy.
 */
class SegmentationMaskPoolTest {

    @Test
    fun `a lease is exactly the requested size`() {
        val lease = SegmentationMaskPool().lease(width = 3, height = 5)

        assertEquals(15, lease.mask.classMap.size)
        assertEquals(3, lease.mask.width)
        assertEquals(5, lease.mask.height)
    }

    @Test
    fun `an array is not handed out while its lease is open`() {
        val pool = SegmentationMaskPool()
        val held = pool.lease(4, 4)
        val other = pool.lease(4, 4)

        assertNotSame(held.mask.classMap, other.mask.classMap)
        assertEquals(2L, pool.arraysAllocated)
    }

    @Test
    fun `a closed lease's array is reused`() {
        val pool = SegmentationMaskPool()
        val first = pool.lease(4, 4)
        first.close()

        assertSame(first.mask.classMap, pool.lease(4, 4).mask.classMap)
        assertEquals(1L, pool.arraysAllocated)
    }

    @Test
    fun `closing a lease twice gives its array back once`() {
        val pool = SegmentationMaskPool()
        val lease = pool.lease(4, 4)
        lease.close()
        lease.close()

        val a = pool.lease(4, 4)
        val b = pool.lease(4, 4)

        assertNotSame("a double close must not let two holders share one array", a.mask.classMap, b.mask.classMap)
    }

    @Test
    fun `an array of another size is not reused`() {
        // Portrait and landscape crop the letterbox differently.
        val pool = SegmentationMaskPool()
        pool.lease(4, 8).close()

        val landscape = pool.lease(8, 3)

        assertEquals(24, landscape.mask.classMap.size)
        assertEquals(2L, pool.arraysAllocated)
    }

    @Test
    fun `the pool keeps at most its idle bound`() {
        val pool = SegmentationMaskPool(maxIdle = 2)
        List(4) { pool.lease(2, 2) }.forEach { it.close() }

        repeat(4) { pool.lease(2, 2) }

        assertEquals("4 made first, 2 of them kept, so 2 more", 6L, pool.arraysAllocated)
    }

    @Test
    fun `after a trim the pool stays empty even when an older lease closes`() {
        val pool = SegmentationMaskPool()
        val idleBefore = pool.lease(4, 4)
        val stillHeld = pool.lease(4, 4)
        idleBefore.close()

        pool.trim()
        stillHeld.close()
        val afterTrim = pool.lease(4, 4)

        assertNotSame(idleBefore.mask.classMap, afterTrim.mask.classMap)
        assertNotSame(stillHeld.mask.classMap, afterTrim.mask.classMap)
        assertEquals(3L, pool.arraysAllocated)

        // Leases taken after the trim are pooled as usual.
        afterTrim.close()
        assertSame(afterTrim.mask.classMap, pool.lease(4, 4).mask.classMap)
    }

    @Test
    fun `a snapshot for the UI is a copy that survives the source being rewritten`() {
        val pool = SegmentationMaskPool()
        val snapshots = SegmentationMaskSnapshots()
        val lease = pool.lease(2, 2)
        intArrayOf(1, 0, 0, 1).copyInto(lease.mask.classMap)

        val snapshot = snapshots.of(lease.mask)
        lease.close()
        intArrayOf(7, 7, 7, 7).copyInto(pool.lease(2, 2).mask.classMap)

        assertNotSame(lease.mask.classMap, snapshot.classMap)
        assertArrayEquals(intArrayOf(1, 0, 0, 1), snapshot.classMap)
    }

    @Test
    fun `frames that reuse one semantic run share one snapshot`() {
        val pool = SegmentationMaskPool()
        val snapshots = SegmentationMaskSnapshots()
        val run = pool.lease(2, 2).mask
        val nextRun = pool.lease(2, 2).mask

        val first = snapshots.of(run)

        assertSame(first, snapshots.of(run))
        assertNotSame(first, snapshots.of(nextRun))
    }
}
