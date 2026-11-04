package com.sailens.guidance.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The trace queue's count: reserved before an entry is published, released only after one was taken, so it is never
 * negative whatever the interleaving, and at rest it is the queue's length. Interleavings are forced with the hook
 * between reserving and publishing.
 */
class CountedTraceQueueTest {

    /** Parks the threads it is told to between reserving and publishing. */
    private class Parking {
        private val gates = ConcurrentHashMap<Thread, Pair<CountDownLatch, CountDownLatch>>()

        fun hook() {
            val (arrived, release) = gates[Thread.currentThread()] ?: return
            arrived.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "never released" }
        }

        /** Starts [block] on a thread that parks after its first reservation; returns it parked, and its release. */
        fun parked(block: () -> Unit): Pair<Thread, CountDownLatch> {
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = Thread(block)
            gates[worker] = arrived to release
            worker.start()
            check(arrived.await(10, TimeUnit.SECONDS)) { "never reserved" }
            return worker to release
        }
    }

    private fun drain(queue: CountedTraceQueue<String>): List<String> = generateSequence { queue.pollCounted() }.toList()

    @Test
    fun `a flush and a trim between reserving and publishing never see a negative count`() {
        val parking = Parking()
        val queue = CountedTraceQueue<String>(parking::hook)
        queue.offerCounted("a")

        val (producer, release) = parking.parked { queue.offerCounted("b") }
        // Counted, not yet published: another thread flushes and trims meanwhile.
        val seen = mutableListOf<Any?>()
        thread {
            seen += queue.count
            seen += queue.pollCounted()
            seen += queue.count
            seen += queue.pollCounted()
            seen += queue.count
            queue.trimIfNeeded(0)
            seen += queue.count
        }.join()
        assertEquals(listOf(2, "a", 1, null, 1, 1), seen)

        release.countDown()
        producer.join()
        assertEquals(1, queue.count)
        assertEquals(listOf("b"), drain(queue))
        assertEquals(0, queue.count)
    }

    @Test
    fun `a trim that finds nothing to take leaves the reservations alone`() {
        val parking = Parking()
        val queue = CountedTraceQueue<String>(parking::hook)
        val max = 1

        val (first, releaseFirst) = parking.parked { queue.offerCounted("first"); queue.trimIfNeeded(max) }
        val (second, releaseSecond) = parking.parked { queue.offerCounted("second"); queue.trimIfNeeded(max) }
        assertEquals(2, queue.count)

        // Two reserved, none published: over the bound, with nothing to take.
        thread { queue.trimIfNeeded(max) }.join()
        assertEquals(2, queue.count)

        // The first to publish trims its own surplus as usual: the oldest goes.
        releaseFirst.countDown()
        first.join()
        assertEquals(1, queue.count)
        releaseSecond.countDown()
        second.join()
        assertEquals(1, queue.count)

        assertEquals(listOf("second"), drain(queue))
        assertEquals(0, queue.count)
    }

    @Test
    fun `under contention the count is never negative, and at rest it is the length within the bound`() {
        val max = 8
        val queue = CountedTraceQueue<String>()
        val lowest = AtomicInteger(Int.MAX_VALUE)
        val start = CountDownLatch(1)
        fun watch() = lowest.accumulateAndGet(queue.count, ::minOf)

        val producers = (0 until 6).map { p ->
            thread {
                start.await()
                repeat(3_000) { i ->
                    // Frames, alerts and session records alike: one queue, one count.
                    queue.offerCounted("p$p-$i")
                    queue.trimIfNeeded(max)
                    watch()
                }
            }
        }
        val flusher = thread {
            start.await()
            repeat(2_000) {
                while (queue.pollCounted() != null) watch()
                watch()
                Thread.yield()
            }
        }
        start.countDown()
        producers.forEach { it.join() }
        flusher.join()

        assertTrue("count went to ${lowest.get()}", lowest.get() >= 0)
        queue.trimIfNeeded(max)
        val atRest = queue.count
        assertTrue("$atRest over the bound", atRest <= max)
        assertEquals(atRest, drain(queue).size)
        assertEquals(0, queue.count)
    }
}
