package com.sailens.guidance.trace

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The trace's queue and the count that bounds it, shared by every producer and the flush.
 *
 * An entry is counted before it is published and uncounted only after it was taken out, so the count is
 * "entries reserved and not yet accounted out": queued, plus reserved and not yet published, plus taken
 * and not yet uncounted. Every decrement follows its increment, so the count is never negative under any
 * interleaving; at rest (nothing being offered, flushed or trimmed) it equals the queue's length, and
 * after a trim it is at most the bound. It is not the queue's length at every instant.
 *
 * FIFO, and a trim drops the oldest. Under contention a trim may drop more than it strictly had to; the
 * guarantees are the accounting and the bound, not that the newest entries up to the bound are kept.
 *
 * @param afterReserve runs between counting an entry and publishing it; empty in production, a test
 *   parks a producer there.
 */
internal class CountedTraceQueue<T : Any>(
    private val afterReserve: () -> Unit = {},
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val counted = AtomicInteger(0)

    /** Reserved and not yet accounted out; see the class comment. */
    val count: Int get() = counted.get()

    fun isEmpty(): Boolean = queue.isEmpty()

    /** Counts [entry], then publishes it. */
    fun offerCounted(entry: T) {
        counted.incrementAndGet()
        afterReserve()
        queue.offer(entry)
    }

    /** Takes the oldest entry and uncounts it; null, and nothing uncounted, when there is none. */
    fun pollCounted(): T? {
        val entry = queue.poll() ?: return null
        counted.decrementAndGet()
        return entry
    }

    /**
     * Drops the oldest entries while more than [max] are counted. Stops when there is nothing to take: the
     * surplus is then entries other threads have reserved but not published, or taken but not yet
     * uncounted, and their own accounting settles it.
     */
    fun trimIfNeeded(max: Int) {
        while (counted.get() > max) {
            if (pollCounted() == null) return
        }
    }
}
