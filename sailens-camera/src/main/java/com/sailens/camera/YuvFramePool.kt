package com.sailens.camera

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Where an [ImageFrameConverter] gets the arrays it copies image planes into.
 *
 * An array returned here belongs to the frame being converted. The converter must fill all of it:
 * it may be a reused array whose previous contents are still there.
 */
public fun interface PlaneAllocator {
    /**
     * @param plane 0, 1 or 2 for Y, U and V.
     * @param size the exact length the plane needs; the array returned has exactly this length.
     */
    public fun planeArray(plane: Int, size: Int): ByteArray

    public companion object {
        /** A fresh array every time: what conversion did before frames were pooled. */
        public val Allocating: PlaneAllocator = PlaneAllocator { _, size -> ByteArray(size) }
    }
}

/**
 * The plane storage behind one converted frame, and a count of who still holds that frame.
 *
 * Every place a frame goes holds one reference: the conversion itself while it is being handed
 * out, the latest-frame slot, each subscriber it was sent to. The arrays go back to the pool only
 * when the last reference is released, so a frame is never overwritten while anyone can still read
 * it. A holder that never releases keeps the arrays out of the pool for good, which costs an
 * allocation later and nothing else.
 *
 * A snapshot handed to a caller outside the camera module is [detach]ed instead: that caller gets
 * an ordinary frame with no release duty, so the arrays must never be reused.
 */
internal class FrameBuffers(private val pool: YuvFramePool) : PlaneAllocator {
    private val planes = arrayOfNulls<ByteArray>(PLANE_COUNT)
    private val references = AtomicInteger(0)

    @Volatile
    private var detached = false

    override fun planeArray(plane: Int, size: Int): ByteArray {
        require(plane in 0 until PLANE_COUNT) { "YUV_420_888 has planes 0..2, not $plane" }
        planes[plane]?.takeIf { it.size == size }?.let { return it }
        pool.onArrayAllocated()
        return ByteArray(size).also { planes[plane] = it }
    }

    /** Pool only: hands out an idle buffer with the caller's reference. */
    fun open() {
        check(references.compareAndSet(0, 1)) { "Opened a frame buffer that is still referenced" }
    }

    /** Adds a holder. Only legal while the caller already holds a reference. */
    fun retain() {
        val before = references.getAndIncrement()
        check(before > 0) { "Retained a frame buffer that had already been released" }
    }

    /**
     * Adds a holder unless the buffer has already been released by everyone: the caller does not
     * hold a reference and is racing the last release.
     */
    fun tryRetain(): Boolean {
        while (true) {
            val current = references.get()
            if (current <= 0) return false
            if (references.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "Released a frame buffer more often than it was retained" }
        if (remaining == 0 && !detached) pool.recycle(this)
    }

    /** The arrays now belong to whoever holds the frame; they never return to the pool. */
    fun detach() {
        detached = true
    }

    private companion object {
        const val PLANE_COUNT = 3
    }
}

/**
 * Idle [FrameBuffers], so that converting a frame reuses arrays instead of allocating ~1 MB each
 * time the camera delivers one.
 *
 * Bounded: at most [maxIdle] buffers wait here. When all of them are out, [acquire] makes a new
 * one, which is exactly what conversion did before pooling.
 */
internal class YuvFramePool(private val maxIdle: Int = DEFAULT_MAX_IDLE) {
    private val idle = ArrayDeque<FrameBuffers>()
    private val allocatedArrays = AtomicLong(0L)

    /** Plane arrays created so far, pooled or not. Flat once the pool has warmed up. */
    val arraysAllocated: Long get() = allocatedArrays.get()

    /** A buffer holding one reference, owned by the caller. */
    fun acquire(): FrameBuffers {
        val buffers = synchronized(idle) { idle.removeLastOrNull() } ?: FrameBuffers(this)
        buffers.open()
        return buffers
    }

    fun recycle(buffers: FrameBuffers) {
        synchronized(idle) {
            if (idle.size < maxIdle) idle.addLast(buffers)
        }
    }

    /** Drops idle buffers, for when nothing will want a frame for a while. */
    fun trim() {
        synchronized(idle) { idle.clear() }
    }

    internal fun onArrayAllocated() {
        allocatedArrays.incrementAndGet()
    }

    companion object {
        /**
         * Enough for Guidance at full rate (the frame being processed, one waiting, one being
         * converted) plus a low-rate subscriber holding one of its own.
         */
        const val DEFAULT_MAX_IDLE: Int = 4
    }
}
