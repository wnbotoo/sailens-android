package com.sailens.camera

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot race a reference count cannot see on its own (ABA): a reader takes frame A from
 * the slot, and before it claims A the camera replaces A, A's buffer goes back to the pool and is
 * reopened for a later frame. The buffer's count is positive again, but it no longer holds A.
 * Handing out A then would pair A's sequence number and timestamp with another frame's pixels,
 * possibly still being written.
 *
 * The holder's claim seam runs the camera side at exactly that point, so the order is forced
 * rather than hoped for.
 */
class LatestFrameHolderRaceTest {

    private var now = 1_000L
    private val pool = YuvFramePool()
    private var raced = false
    private lateinit var bufferOfA: FrameBuffers
    private val holder = LatestFrameHolder(
        elapsedRealtimeMs = { now },
        beforeClaim = { if (!raced) { raced = true; cameraMovesOnAndReusesA() } },
    )

    @Test
    fun `a snapshot never returns a frame whose buffer was recycled and reopened under it`() {
        recordA()

        val snapshot = holder.currentFrame(maxAgeMs = 10_000)

        assertTrue("the race must actually have happened", raced)
        assertEquals("the reader moves on to the frame now in the slot", 2L, snapshot?.sequenceNumber)
        assertStamped(snapshot!!)
    }

    @Test
    fun `an awaited snapshot never returns a frame whose buffer was recycled and reopened under it`() = runBlocking {
        recordA()

        val snapshot = withTimeout(2_000) { holder.awaitFrame(maxAgeMs = 10_000) }

        assertTrue("the race must actually have happened", raced)
        assertEquals(2L, snapshot.sequenceNumber)
        assertStamped(snapshot)
    }

    private fun recordA() {
        bufferOfA = pool.acquire()
        holder.record(stampedFrame(1, bufferOfA), bufferOfA)
        bufferOfA.release() // the conversion's reference; only the slot holds A now
    }

    /** Frame C replaces A; A's buffer is recycled and immediately reopened for frame E. */
    private fun cameraMovesOnAndReusesA() {
        val bufferOfC = pool.acquire()
        holder.record(stampedFrame(2, bufferOfC), bufferOfC)
        bufferOfC.release()

        val bufferOfE = pool.acquire()
        assertSame("the scenario needs A's buffer to be the one reopened", bufferOfA, bufferOfE)
        stampedFrame(3, bufferOfE) // E's conversion writes into what used to be A's arrays
    }

    private fun stampedFrame(sequenceNumber: Long, planes: PlaneAllocator): ImageFrame {
        val stamp = sequenceNumber.toByte()
        return ImageFrame(
            width = 4,
            height = 2,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.YUV_420_888,
            timestamp = sequenceNumber,
            rotationDegrees = 0,
            sequenceNumber = sequenceNumber,
            yuvData = Yuv420FrameData(
                y = YuvPlaneData(planes.planeArray(0, 8).apply { fill(stamp) }, 4, 1),
                u = YuvPlaneData(planes.planeArray(1, 3).apply { fill(stamp) }, 4, 2),
                v = YuvPlaneData(planes.planeArray(2, 3).apply { fill(stamp) }, 4, 2),
            ),
        )
    }

    private fun assertStamped(frame: ImageFrame) {
        val stamp = frame.sequenceNumber.toByte()
        val yuv = frame.yuvData!!
        listOf(yuv.y, yuv.u, yuv.v).forEach { plane ->
            assertTrue("frame ${frame.sequenceNumber} carries another frame's pixels", plane.bytes.all { it == stamp })
        }
    }
}
