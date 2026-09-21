package com.sailens.camera

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The freshness bound behind [FrameSnapshotProvider].
 *
 * Returning a stale frame is the failure that matters here: a person who cannot see the camera
 * has no way to tell that the answer describes somewhere they have already walked away from.
 */
class LatestFrameHolderTest {

    // Read from the waiter's thread as well as the test thread.
    @Volatile
    private var now = 1_000L
    private val holder = LatestFrameHolder { now }

    @Test
    fun `returns nothing before any frame is recorded`() {
        assertNull(holder.currentFrame(maxAgeMs = 1_000))
    }

    @Test
    fun `returns the frame while it is within the bound`() {
        holder.record(frame(sequenceNumber = 7))

        now += 200

        assertEquals(7L, holder.currentFrame(maxAgeMs = 500)?.sequenceNumber)
    }

    @Test
    fun `returns the frame exactly on the bound`() {
        holder.record(frame(sequenceNumber = 1))

        now += 500

        assertEquals(1L, holder.currentFrame(maxAgeMs = 500)?.sequenceNumber)
    }

    @Test
    fun `drops the frame once it is past the bound`() {
        holder.record(frame(sequenceNumber = 1))

        now += 501

        assertNull(holder.currentFrame(maxAgeMs = 500))
    }

    @Test
    fun `keeps only the most recent frame`() {
        holder.record(frame(sequenceNumber = 1))
        now += 100
        holder.record(frame(sequenceNumber = 2))
        now += 100

        assertEquals(2L, holder.currentFrame(maxAgeMs = 150)?.sequenceNumber)
    }

    @Test
    fun `a newer frame revives a snapshot that had gone stale`() {
        holder.record(frame(sequenceNumber = 1))
        now += 5_000
        assertNull(holder.currentFrame(maxAgeMs = 500))

        holder.record(frame(sequenceNumber = 2))

        assertEquals(2L, holder.currentFrame(maxAgeMs = 500)?.sequenceNumber)
    }

    @Test
    fun `a negative bound admits nothing`() {
        holder.record(frame(sequenceNumber = 1))

        assertNull(holder.currentFrame(maxAgeMs = -1))
    }

    @Test
    fun `awaiting returns immediately when the held frame already satisfies the bound`() = runBlocking {
        holder.record(frame(sequenceNumber = 4))
        now += 100

        assertEquals(4L, holder.awaitFrame(maxAgeMs = 500).sequenceNumber)
    }

    @Test
    fun `awaiting a frame that does not exist yet waits for the next one`() = runBlocking {
        val awaited = async(Dispatchers.Default) { holder.awaitFrame(maxAgeMs = 500) }

        holder.record(frame(sequenceNumber = 2))

        assertEquals(2L, withTimeout(2_000) { awaited.await() }.sequenceNumber)
    }

    @Test
    fun `awaiting keeps waiting while every frame is already too old`() = runBlocking {
        holder.record(frame(sequenceNumber = 1))
        now += 5_000

        assertNull(withTimeoutOrNull(100) { holder.awaitFrame(maxAgeMs = 500) })
    }

    private fun frame(sequenceNumber: Long) = ImageFrame(
        width = 4,
        height = 4,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.YUV_420_888,
        timestamp = sequenceNumber,
        rotationDegrees = 0,
        sequenceNumber = sequenceNumber,
    )
}
