package com.sailens.guidance.model.perception

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ownership of one [SegmentationMask]'s class map.
 *
 * The array comes from a [SegmentationMaskPool] and goes back to it when the lease is closed, after
 * which the next semantic run may overwrite it. So exactly one party holds the lease at a time and
 * closes it once nothing will read the mask again. In Guidance that is ProcessFrameUseCase: it keeps
 * the mask for the frames that reuse the cached analysis and closes the lease when a newer analysis
 * replaces it.
 *
 * A lease that is never closed is safe: its array is simply never reused.
 */
class SegmentationMaskLease internal constructor(
    val mask: SegmentationMask,
    private val pool: SegmentationMaskPool,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** Idempotent. The mask must not be read after this. */
    override fun close() {
        if (closed.compareAndSet(false, true)) pool.recycle(mask.classMap)
    }
}

/**
 * Reusable class-map arrays for semantic masks, so a semantic run does not allocate a new ~0.9 MB
 * array every time.
 *
 * In steady state two arrays alternate: the one behind the cached analysis, which Guidance is still
 * reading, and the one the next run writes. Arrays are handed out only through
 * [SegmentationMaskLease], and only an array whose lease was closed is ever handed out again.
 */
class SegmentationMaskPool(private val maxIdle: Int = DEFAULT_MAX_IDLE) {
    private val idle = ArrayDeque<IntArray>()
    private val allocated = AtomicLong(0L)

    /** Class-map arrays created so far. Stops growing once the pool has warmed up. */
    val arraysAllocated: Long get() = allocated.get()

    /**
     * An array of exactly [width] × [height] for the caller to fill, wrapped as a mask. Its previous
     * contents are unspecified.
     */
    fun lease(width: Int, height: Int): SegmentationMaskLease {
        val pixelCount = width * height
        val reused = synchronized(idle) {
            // The content region changes with orientation; arrays of another size are of no use.
            idle.removeAll { it.size != pixelCount }
            idle.removeLastOrNull()
        }
        val classMap = reused ?: IntArray(pixelCount).also { allocated.incrementAndGet() }
        return SegmentationMaskLease(SegmentationMask(width, height, classMap), this)
    }

    /** Drops idle arrays, for when no semantic run will happen for a while. */
    fun trim() {
        synchronized(idle) { idle.clear() }
    }

    internal fun recycle(classMap: IntArray) {
        synchronized(idle) {
            if (idle.size < maxIdle) idle.addLast(classMap)
        }
    }

    companion object {
        /** The mask behind the cached analysis plus the one being written. */
        const val DEFAULT_MAX_IDLE: Int = 2
    }
}
