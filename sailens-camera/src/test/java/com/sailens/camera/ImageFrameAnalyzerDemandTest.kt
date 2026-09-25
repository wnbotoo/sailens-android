package com.sailens.camera

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Frame demand, against the real analyzer rather than a fake provider.
 *
 * The rule under test is the one that used to be a gap: converting a frame costs a copy of every
 * plane, so it happens only on demand — but "demand" is not the same as "Guidance is running".
 * Describe has to be able to ask for one frame while navigation is stopped, and a person who
 * pressed the describe button must get an answer rather than silence (architecture.md §6.1).
 *
 * The camera side is modelled the way CameraX actually behaves: a background thread delivering
 * images regardless of who wants them, with the analyzer deciding what to do with each one.
 */
class ImageFrameAnalyzerDemandTest {

    // Read from the fake camera thread as well as the test thread.
    @Volatile
    private var now = 1_000L
    private val converted = AtomicInteger(0)
    private val analyzer = ImageFrameAnalyzer(
        frameConverter = CountingConverter(converted),
        elapsedRealtimeMs = { now },
        elapsedRealtimeNanos = { now * 1_000_000 },
    )

    private val cameraRunning = AtomicBoolean(false)
    private val cameraThread = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    @After
    fun tearDown() {
        cameraRunning.set(false)
        cameraThread.shutdownNow()
        scope.cancel()
    }

    @Test
    fun `nothing is converted while nothing wants a frame`() {
        repeat(3) { analyzer.analyze(FakeImageProxy()) }

        assertEquals(0, converted.get())
        assertEquals(3L, analyzer.stats.skippedFramesWithoutDemand)
        assertNull(analyzer.currentFrame(maxAgeMs = 10_000))
    }

    @Test
    fun `an open snapshot lease is demand on its own`() {
        analyzer.openSnapshotLease().use {
            analyzer.analyze(FakeImageProxy())
        }

        assertEquals(1, converted.get())
        assertEquals(0L, analyzer.stats.skippedFramesWithoutDemand)
        assertEquals(0L, analyzer.currentFrame(maxAgeMs = 10_000)?.sequenceNumber)
    }

    @Test
    fun `demand ends when the lease is closed`() {
        val lease = analyzer.openSnapshotLease()
        analyzer.analyze(FakeImageProxy())
        lease.close()
        analyzer.analyze(FakeImageProxy())

        assertEquals(1, converted.get())
        assertEquals(1L, analyzer.stats.skippedFramesWithoutDemand)
    }

    @Test
    fun `closing a lease twice releases the demand once`() {
        val first = analyzer.openSnapshotLease()
        val second = analyzer.openSnapshotLease()
        first.close()
        first.close()

        assertEquals(1, analyzer.stats.openSnapshotLeases)

        second.close()

        assertEquals(0, analyzer.stats.openSnapshotLeases)
    }

    @Test
    fun `a stream subscription is still demand, and still receives frames`() = runBlocking {
        val received = AtomicInteger(0)
        val collector = analyzer.frames.onEach { received.incrementAndGet() }.launchIn(scope)

        // The subscription registers asynchronously, exactly as it does when Guidance starts, so
        // keep offering frames the way the camera would until one lands.
        awaitTrue("the collector to receive a frame") {
            analyzer.analyze(FakeImageProxy())
            received.get() > 0
        }
        collector.cancel()

        assertTrue("a collected stream must keep conversion on", converted.get() >= 1)
        assertTrue(analyzer.stats.emittedFrames >= 1)
        assertNotNull("the snapshot rides along on the stream", analyzer.currentFrame(maxAgeMs = 10_000))
    }

    @Test
    fun `a snapshot request gets a frame while nothing is collecting the stream`() = runBlocking {
        startCamera()

        val frame = analyzer.awaitCurrentFrame(maxAgeMs = 10_000, timeoutMs = 2_000)

        assertNotNull("Describe must work with Guidance stopped", frame)
        assertEquals(0L, analyzer.stats.emittedFrames)
        assertTrue("conversion must have been switched on by the request", converted.get() >= 1)
    }

    @Test
    fun `the request's demand is released once it is answered`() = runBlocking {
        startCamera()

        analyzer.awaitCurrentFrame(maxAgeMs = 10_000, timeoutMs = 2_000)
        cameraRunning.set(false)

        assertEquals(0, analyzer.stats.openSnapshotLeases)
    }

    @Test
    fun `a request that the camera cannot answer gives up instead of waiting forever`() = runBlocking {
        // Camera bound but delivering nothing, e.g. still configuring.
        val frame = analyzer.awaitCurrentFrame(maxAgeMs = 10_000, timeoutMs = 150)

        assertNull(frame)
        assertEquals("the lease must not leak on timeout", 0, analyzer.stats.openSnapshotLeases)
    }

    @Test
    fun `a request refuses a frame older than the bound even though one exists`() = runBlocking {
        analyzer.openSnapshotLease().use { analyzer.analyze(FakeImageProxy()) }
        now += 5_000

        val frame = analyzer.awaitCurrentFrame(maxAgeMs = 1_000, timeoutMs = 150)

        assertNull("a stale frame is worse than no answer", frame)
    }

    private fun startCamera() {
        cameraRunning.set(true)
        cameraThread.execute {
            while (cameraRunning.get()) {
                analyzer.analyze(FakeImageProxy())
                Thread.sleep(5)
            }
        }
    }

    @Test
    fun `a frame carries the time the analyzer received it, taken before conversion`() {
        var nowNanos = 5_000_000L
        val slowConverter = object : ImageFrameConverter {
            override fun convert(image: ImageProxy, sequenceNumber: Long, planes: PlaneAllocator): ImageFrame {
                nowNanos += 40_000_000L // conversion takes time; the receipt time must not include it
                return CountingConverter(AtomicInteger()).convert(image, sequenceNumber, planes)
            }
        }
        val timed = ImageFrameAnalyzer(
            frameConverter = slowConverter,
            elapsedRealtimeMs = { now },
            elapsedRealtimeNanos = { nowNanos },
        )

        timed.openSnapshotLease().use { timed.analyze(FakeImageProxy()) }

        val frame = timed.currentFrame(maxAgeMs = 10_000)
        assertEquals(5_000_000L, frame?.receivedElapsedRealtimeNanos)
        // The camera's own timestamp keeps its meaning.
        assertEquals(0L, frame?.timestamp)
    }

    private fun awaitTrue(what: String, deadlineMs: Long = 2_000, condition: () -> Boolean) {
        val giveUpAt = System.currentTimeMillis() + deadlineMs
        while (!condition()) {
            if (System.currentTimeMillis() > giveUpAt) error("Timed out waiting for $what")
            Thread.sleep(1)
        }
    }

    private class CountingConverter(private val count: AtomicInteger) : ImageFrameConverter {
        override fun convert(image: ImageProxy, sequenceNumber: Long, planes: PlaneAllocator): ImageFrame {
            count.incrementAndGet()
            return ImageFrame(
                width = 4,
                height = 4,
                pixelBytes = ByteArray(0),
                pixelFormat = ImagePixelFormat.YUV_420_888,
                rotationDegrees = 0,
                timestamp = sequenceNumber,
                sequenceNumber = sequenceNumber,
            )
        }
    }

    /**
     * Only [close] matters: the analyzer hands the proxy straight to the converter, which is faked
     * here, so every other accessor would be an unused Android call in a JVM test.
     */
    private class FakeImageProxy : ImageProxy {
        override fun close() = Unit
        override fun getCropRect(): Rect = unsupported()
        override fun setCropRect(rect: Rect?) = unsupported()
        override fun getFormat(): Int = unsupported()
        override fun getHeight(): Int = unsupported()
        override fun getWidth(): Int = unsupported()
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = unsupported()
        override fun getImageInfo(): ImageInfo = unsupported()
        override fun getImage(): Image? = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("The analyzer must not touch the proxy itself")
    }
}
