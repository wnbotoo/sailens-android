package com.sailens.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Reusing frame arrays is only safe if a frame is never overwritten while anybody can still read
 * it. These tests pin that down from the outside: every frame is stamped with its own sequence
 * number in every plane byte, so any reuse that came too early shows up as a frame whose bytes no
 * longer match its number.
 *
 * Collectors run on [Dispatchers.Unconfined], so a frame sent to a subscriber is delivered inside
 * the [ImageFrameAnalyzer.analyze] call that produced it and each test reads as a plain sequence.
 */
class ImageFrameAnalyzerPoolTest {

    @Volatile
    private var now = 1_000L
    private var converted = 0
    private val analyzer = ImageFrameAnalyzer(
        frameConverter = StampingConverter { converted++ },
        elapsedRealtimeMs = { now },
        elapsedRealtimeNanos = { now * 1_000_000 },
    )
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `pooled conversion copies exactly the bytes the old per-frame arrays held`() {
        // Padded rows and interleaved chroma, the layout a real camera hands over.
        val proxy = YuvImageProxy(width = 6, height = 4, rowStride = 8, seed = 3)
        val converter = ImageProxyToFrameConverter()
        val pool = YuvFramePool()

        val reference = converter.convert(proxy, sequenceNumber = 9, planes = PlaneAllocator.Allocating)
        // A reused buffer: first filled with a different frame, then with this one.
        val buffers = pool.acquire()
        converter.convert(YuvImageProxy(width = 6, height = 4, rowStride = 8, seed = 77), 8, buffers)
        val pooled = converter.convert(proxy, sequenceNumber = 9, planes = buffers)

        assertEquals(reference, pooled)
        val planes = { frame: ImageFrame -> frame.yuvData!!.let { listOf(it.y, it.u, it.v) } }
        planes(reference).zip(planes(pooled)).forEach { (expected, actual) ->
            assertArrayEquals(expected.bytes, actual.bytes)
            assertEquals(expected.rowStride, actual.rowStride)
            assertEquals(expected.pixelStride, actual.pixelStride)
        }
    }

    @Test
    fun `a frame the subscriber still holds is never overwritten`() {
        val received = collect(analyzer.frames, release = false)

        repeat(20) { analyzer.analyze(NoPixelsImageProxy()) }

        assertEquals(20, received.size)
        received.forEach { assertStamped(it) }
        assertEquals("nothing released, so nothing may be shared", 20, distinctYArrays(received))
    }

    @Test
    fun `released frames are reused instead of allocated`() {
        val received = collect(analyzer.frames, release = true)

        repeat(3) { analyzer.analyze(NoPixelsImageProxy()) }
        val warmedUp = analyzer.stats.planeArraysAllocated
        repeat(50) { analyzer.analyze(NoPixelsImageProxy()) }

        assertEquals(53, received.size)
        assertEquals("steady state must not allocate", warmedUp, analyzer.stats.planeArraysAllocated)
    }

    @Test
    fun `a frame is reused only after every subscriber has given it back`() {
        val first = collect(analyzer.frames, release = false)
        val second = collect(analyzer.frames, release = true)

        repeat(10) { analyzer.analyze(NoPixelsImageProxy()) }

        first.forEach { assertStamped(it) }
        assertEquals(10, distinctYArrays(first))
        assertEquals(10, second.size)
        first.zip(second).forEach { (a, b) ->
            assertNotSame("each subscriber gets its own frame object", a, b)
            assertSame(a.yuvData!!.y.bytes, b.yuvData!!.y.bytes)
        }
    }

    @Test
    fun `a snapshot taken while Guidance runs stays intact after the stream moves on`() {
        collect(analyzer.frames, release = true)
        repeat(3) { analyzer.analyze(NoPixelsImageProxy()) }

        val snapshot = analyzer.currentFrame(maxAgeMs = 10_000)
        repeat(20) { analyzer.analyze(NoPixelsImageProxy()) }

        assertNotNull(snapshot)
        assertEquals(2L, snapshot!!.sequenceNumber)
        assertStamped(snapshot)
    }

    @Test
    fun `a snapshot with Guidance stopped comes from pooled conversion and is detached`() = runBlocking {
        analyzer.openSnapshotLease().use {
            repeat(5) { analyzer.analyze(NoPixelsImageProxy()) }
            val snapshot = analyzer.awaitCurrentFrame(maxAgeMs = 10_000, timeoutMs = 1_000)
            repeat(20) { analyzer.analyze(NoPixelsImageProxy()) }

            assertNotNull("Describe must work with Guidance stopped", snapshot)
            assertEquals(4L, snapshot!!.sequenceNumber)
            assertStamped(snapshot)
        }
        assertEquals(0L, analyzer.stats.emittedFrames)
    }

    @Test
    fun `lease-only conversion recycles instead of allocating every frame`() {
        analyzer.openSnapshotLease().use {
            repeat(3) { analyzer.analyze(NoPixelsImageProxy()) }
            val warmedUp = analyzer.stats.planeArraysAllocated
            repeat(30) { analyzer.analyze(NoPixelsImageProxy()) }

            assertEquals(warmedUp, analyzer.stats.planeArraysAllocated)
        }
    }

    @Test
    fun `a low-rate subscriber is sent only due frames and the rest are not even converted`() {
        val received = collect(analyzer.frames(minIntervalMs = 200), release = true)

        // A 30 fps camera for one second.
        repeat(30) {
            analyzer.analyze(NoPixelsImageProxy())
            now += 33
        }

        // Due at t=0, 231, 462, 693, 924 (the first frame at or after each 200 ms step).
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), received.map { it.sequenceNumber })
        assertEquals(5, converted)
        assertEquals(25L, analyzer.stats.skippedFramesWithoutDemand)
    }

    @Test
    fun `a low-rate subscriber alongside Guidance gets its samples without holding frames up`() {
        val guidance = collect(analyzer.frames, release = true)
        val sampler = collect(analyzer.frames(minIntervalMs = 200), release = true)

        repeat(3) {
            analyzer.analyze(NoPixelsImageProxy())
            now += 33
        }
        val warmedUp = analyzer.stats.planeArraysAllocated
        repeat(27) {
            analyzer.analyze(NoPixelsImageProxy())
            now += 33
        }

        assertEquals(30, guidance.size)
        assertEquals(5, sampler.size)
        assertEquals(warmedUp, analyzer.stats.planeArraysAllocated)
    }

    @Test
    fun `frames a stalled subscriber never took go back to the pool`() {
        val stall = CompletableDeferred<Unit>()
        val received = mutableListOf<Long>()
        analyzer.frames.onEach { frame ->
            assertStamped(frame)
            received += frame.sequenceNumber
            stall.await()
            analyzer.releaseFrame(frame)
        }.launchIn(scope)

        // Frame 0 is stuck in the collector; every later frame replaces the one waiting before it,
        // and each replaced frame has to be returned or the pool would keep allocating.
        repeat(3) { analyzer.analyze(NoPixelsImageProxy()) }
        val warmedUp = analyzer.stats.planeArraysAllocated
        repeat(20) { analyzer.analyze(NoPixelsImageProxy()) }
        assertEquals(warmedUp, analyzer.stats.planeArraysAllocated)

        stall.complete(Unit)

        assertEquals("the collector resumes with the newest frame, intact", listOf(0L, 22L), received)
    }

    @Test
    fun `a subscriber that stops gives back the frame still waiting for it`() {
        val stall = CompletableDeferred<Unit>()
        val collector = analyzer.frames.onEach { stall.await() }.launchIn(scope)
        analyzer.analyze(NoPixelsImageProxy()) // frame 0: taken, then stuck in the collector
        analyzer.analyze(NoPixelsImageProxy()) // frame 1: waiting in the mailbox, and the latest

        collector.cancel()

        // Frame 0 was never given back, so it is forgotten and never reused. Frame 1 is still the
        // latest frame; once frame 2 replaces it, frame 1's arrays must be free for frame 3 --
        // which they are only if cancelling the subscriber returned the mailbox's reference.
        analyzer.openSnapshotLease().use {
            analyzer.analyze(NoPixelsImageProxy())
            val afterFrame2 = analyzer.stats.planeArraysAllocated
            repeat(8) { analyzer.analyze(NoPixelsImageProxy()) }

            assertEquals(afterFrame2, analyzer.stats.planeArraysAllocated)
        }
    }

    @Test
    fun `releasing a frame twice or releasing a stranger does nothing`() {
        val received = collect(analyzer.frames, release = false)
        analyzer.analyze(NoPixelsImageProxy())
        val frame = received.single()

        analyzer.releaseFrame(frame)
        analyzer.releaseFrame(frame)
        analyzer.releaseFrame(frame.copy())
        analyzer.analyze(NoPixelsImageProxy())

        // The latest-frame slot still held frame 0 when frame 1 was converted, so frame 1 must have
        // been given fresh arrays rather than frame 0's: a double release would have freed them.
        assertStamped(frame)
    }

    @Test
    fun `the default low-rate stream samples by timestamp and releases what it skips`() = runBlocking {
        val released = mutableListOf<Long>()
        val source = object : FrameSource {
            override val frames: Flow<ImageFrame> =
                (0L until 10L).map { stampedFrame(it, timestampNs = it * 100_000_000L) }.asFlow()

            override fun releaseFrame(frame: ImageFrame) {
                released += frame.sequenceNumber
            }
        }

        val sampled = source.frames(minIntervalMs = 250).toList().map { it.sequenceNumber }

        assertEquals(listOf(0L, 3L, 6L, 9L), sampled)
        assertEquals(listOf(1L, 2L, 4L, 5L, 7L, 8L), released)
    }

    private fun collect(frames: Flow<ImageFrame>, release: Boolean): MutableList<ImageFrame> {
        val received = mutableListOf<ImageFrame>()
        frames.onEach { frame ->
            // Check before handing it back: after release the arrays may legitimately be reused.
            assertStamped(frame)
            received += frame
            if (release) analyzer.releaseFrame(frame)
        }.launchIn(scope)
        return received
    }

    private fun distinctYArrays(frames: List<ImageFrame>): Int {
        val distinct = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
        frames.forEach { distinct += it.yuvData!!.y.bytes }
        return distinct.size
    }

    private fun assertStamped(frame: ImageFrame) {
        val stamp = frame.sequenceNumber.toByte()
        val yuv = frame.yuvData!!
        listOf(yuv.y, yuv.u, yuv.v).forEach { plane ->
            assertTrue(
                "frame ${frame.sequenceNumber} was overwritten",
                plane.bytes.all { it == stamp },
            )
        }
    }

    /** Fills every plane byte with the frame's sequence number, through the pool it is given. */
    private class StampingConverter(private val onConvert: () -> Unit) : ImageFrameConverter {
        override fun convert(image: ImageProxy, sequenceNumber: Long, planes: PlaneAllocator): ImageFrame {
            onConvert()
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
    }

    private fun stampedFrame(sequenceNumber: Long, timestampNs: Long) = ImageFrame(
        width = 4,
        height = 2,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.YUV_420_888,
        timestamp = timestampNs,
        rotationDegrees = 0,
        sequenceNumber = sequenceNumber,
    )

    /** The analyzer hands the proxy straight to the (fake) converter and only closes it. */
    private class NoPixelsImageProxy : ImageProxy {
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

    /**
     * A YUV_420_888 image laid out like a real camera's: Y rows padded to [rowStride], U and V
     * interleaved (pixel stride 2) and sharing one buffer offset by a byte, so each chroma buffer
     * ends one byte short of a full row -- the `remaining()` that conversion has to reproduce.
     */
    private class YuvImageProxy(
        private val width: Int,
        private val height: Int,
        private val rowStride: Int,
        seed: Int,
    ) : ImageProxy {
        private val yPlane = plane(ByteArray(rowStride * height) { (it * 7 + seed).toByte() }, rowStride, 1)
        private val chroma = ByteArray(rowStride * (height / 2)) { (it * 13 + seed * 5).toByte() }
        private val uPlane = plane(chroma.copyOfRange(0, chroma.size - 1), rowStride, 2)
        private val vPlane = plane(chroma.copyOfRange(1, chroma.size), rowStride, 2)

        override fun close() = Unit
        override fun getCropRect(): Rect = throw UnsupportedOperationException()
        override fun setCropRect(rect: Rect?) = Unit
        override fun getFormat(): Int = ImageFormat.YUV_420_888
        override fun getHeight(): Int = height
        override fun getWidth(): Int = width
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(yPlane, uPlane, vPlane)
        override fun getImage(): Image? = null
        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = throw UnsupportedOperationException()
            override fun getTimestamp(): Long = 123_456_789L
            override fun getRotationDegrees(): Int = 90
            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        private fun plane(bytes: ByteArray, rowStride: Int, pixelStride: Int) = object : ImageProxy.PlaneProxy {
            // Read-only and positioned mid-buffer, as a consumer might leave it: conversion must
            // copy the whole plane regardless.
            private val buffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer().apply { position(bytes.size / 2) }
            override fun getRowStride(): Int = rowStride
            override fun getPixelStride(): Int = pixelStride
            override fun getBuffer(): ByteBuffer = buffer
        }
    }
}
