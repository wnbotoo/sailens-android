package com.sailens.camera

import com.sailens.core.frame.ImageFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * Holds the most recent converted frame and answers [FrameSnapshotProvider] queries about it.
 *
 * Separate from [ImageFrameAnalyzer] because this is the part with a decision in it -- is the
 * frame still fresh enough to describe? -- while the analyzer is the part with CameraX in it.
 * That keeps the freshness rule unit-testable without an ImageProxy.
 *
 * Written from the camera analysis thread and read from whichever thread asks, hence the
 * [MutableStateFlow]: it is safe across threads and lets a waiter be woken by the next frame
 * instead of polling.
 *
 * When the frame's arrays come from the pool, the slot holds one reference to them, and a snapshot
 * handed out is detached from the pool: the caller gets an ordinary frame it may keep for as long
 * as it likes, with nothing to give back.
 */
internal class LatestFrameHolder(
    private val elapsedRealtimeMs: () -> Long,
    /** Test seam: runs between reading the slot and claiming the frame read. */
    private val beforeClaim: () -> Unit = {},
) {
    private val latest = MutableStateFlow<TimedFrame?>(null)

    /**
     * Guards replacing the slot against claiming from it. A reference count alone cannot say
     * whether a buffer still belongs to the frame a reader saw: once the slot lets go, the buffer
     * can be recycled and reopened for a later frame, and its count is positive again.
     */
    private val slotLock = Any()

    /** Single writer: the camera analysis thread. */
    fun record(frame: ImageFrame, buffers: FrameBuffers? = null) {
        buffers?.retain()
        val previous = synchronized(slotLock) {
            latest.value.also { latest.value = TimedFrame(frame, buffers, elapsedRealtimeMs()) }
        }
        // Outside the lock: a claim can no longer see the previous frame as current.
        previous?.buffers?.release()
    }

    /**
     * @return the held frame when it is no older than [maxAgeMs], otherwise null. A negative
     *   bound admits nothing; zero admits only a frame recorded in this same millisecond.
     */
    fun currentFrame(maxAgeMs: Long): ImageFrame? {
        while (true) {
            val timed = latest.value ?: return null
            if (!timed.isFresh(maxAgeMs)) return null
            timed.takeForCaller()?.let { return it }
            // Replaced between the read and the claim; the slot now holds a newer frame.
        }
    }

    /**
     * Suspends until a held frame satisfies [maxAgeMs]. The caller owns the timeout — and the
     * demand that makes new frames arrive at all.
     */
    suspend fun awaitFrame(maxAgeMs: Long): ImageFrame =
        latest.mapNotNull { timed -> timed?.takeIf { it.isFresh(maxAgeMs) }?.takeForCaller() }.first()

    private fun TimedFrame.isFresh(maxAgeMs: Long): Boolean {
        if (maxAgeMs < 0) return false
        val age = elapsedRealtimeMs() - recordedAtMs
        return age in 0..maxAgeMs
    }

    /**
     * The frame, detached from the pool so it stays intact; null if it is no longer the one in
     * the slot. While it is, the slot's own reference keeps its buffer from being recycled -- that
     * reference is only released after a replacement, which cannot happen inside [slotLock].
     */
    private fun TimedFrame.takeForCaller(): ImageFrame? {
        beforeClaim()
        // Not pooled: nothing will ever write into these arrays again.
        val pooled = buffers ?: return frame
        synchronized(slotLock) {
            if (latest.value !== this) return null
            pooled.retain()
            pooled.detach()
        }
        pooled.release()
        return frame
    }

    private class TimedFrame(
        val frame: ImageFrame,
        val buffers: FrameBuffers?,
        val recordedAtMs: Long,
    )
}
