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
) {
    private val latest = MutableStateFlow<TimedFrame?>(null)

    /** Single writer: the camera analysis thread. */
    fun record(frame: ImageFrame, buffers: FrameBuffers? = null) {
        buffers?.retain()
        val previous = latest.value
        latest.value = TimedFrame(frame, buffers, elapsedRealtimeMs())
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
            // Replaced and recycled between the read and the claim. The slot only lets go of a
            // frame after a newer one is in it, so the next read sees that one.
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

    /** The frame, detached from the pool so it stays intact; null if it was already recycled. */
    private fun TimedFrame.takeForCaller(): ImageFrame? {
        val pooled = buffers ?: return frame
        if (!pooled.tryRetain()) return null
        pooled.detach()
        pooled.release()
        return frame
    }

    private class TimedFrame(
        val frame: ImageFrame,
        val buffers: FrameBuffers?,
        val recordedAtMs: Long,
    )
}
