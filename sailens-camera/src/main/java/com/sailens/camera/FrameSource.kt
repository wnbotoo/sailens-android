package com.sailens.camera

import com.sailens.core.frame.ImageFrame
import kotlinx.coroutines.flow.Flow

/**
 * A continuous stream of camera frames, for work that runs on every frame.
 *
 * Guidance consumes this. Collecting it is one of the two things that keeps capture converting; a
 * caller that only wants to look at the current view should use [FrameSnapshotProvider] instead of
 * subscribing to every frame it will throw away (architecture.md §6.1).
 */
interface FrameSource {
    val frames: Flow<ImageFrame>
}

/**
 * A declared need for converted frames. Capture keeps converting while at least one is open.
 *
 * Camera hardware delivers frames whenever it is bound, but turning one into an [ImageFrame] costs
 * a full copy of the planes. A lease is how a caller says "I am about to ask for a snapshot, keep
 * converting" without pretending to be a per-frame consumer.
 */
interface FrameLease : AutoCloseable {
    /** Idempotent: closing twice releases the demand once. */
    override fun close()
}

/**
 * The current view, on demand and with an explicit freshness bound.
 *
 * Returns null rather than an old frame: describing what the camera saw several seconds ago is
 * worse than saying nothing, because the person asking cannot see that it is stale.
 *
 * This is deliberately not tied to CameraX -- another capture source can implement it later.
 *
 * Snapshots do not depend on anyone collecting [FrameSource.frames]. Demand is explicit
 * (architecture.md §6.1): a snapshot lease and a stream subscription each keep conversion running
 * on their own, so Describe works while Guidance is stopped and neither has to know about the
 * other.
 */
interface FrameSnapshotProvider {
    /**
     * The frame already converted, if it is fresh enough. Does not wait and does not open demand,
     * so it answers null whenever nothing has been converting.
     *
     * @param maxAgeMs how old the frame may be, in milliseconds.
     */
    fun currentFrame(maxAgeMs: Long): ImageFrame?

    /**
     * Opens demand, waits for a frame that satisfies [maxAgeMs], and closes demand again.
     *
     * @param timeoutMs how long to wait before giving up. Returns null rather than waiting
     *   indefinitely: a person who pressed a button is owed an answer, and "I could not see" is a
     *   better answer than silence.
     */
    suspend fun awaitCurrentFrame(
        maxAgeMs: Long,
        timeoutMs: Long = DEFAULT_SNAPSHOT_TIMEOUT_MS,
    ): ImageFrame?

    /**
     * Keeps conversion running until the returned lease is closed, for a caller that will ask for
     * several snapshots and does not want to pay the camera's spin-up on each one.
     */
    fun openSnapshotLease(): FrameLease

    companion object {
        /**
         * Long enough for a bound camera to deliver a frame (~33 ms at 30 fps) plus configuration
         * slack, short enough that a blind user is not left in silence wondering whether the press
         * registered.
         */
        const val DEFAULT_SNAPSHOT_TIMEOUT_MS: Long = 1_000L
    }
}
