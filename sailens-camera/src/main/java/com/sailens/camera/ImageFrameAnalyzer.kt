package com.sailens.camera

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.sailens.core.frame.FrameSourceGeometry
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

public class ImageFrameAnalyzer(
    private val frameConverter: ImageFrameConverter = ImageProxyToFrameConverter(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val sourceGeometryOf: (ImageProxy) -> FrameSourceGeometry? = ::readSourceGeometry,
) : ImageAnalysis.Analyzer, FrameSource, FrameSnapshotProvider {
    private var nextSequenceNumber = 0L
    private val emittedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val skippedFramesWithoutDemand = AtomicLong(0L)
    private val snapshotLeases = AtomicInteger(0)
    private val latestFrame = LatestFrameHolder(elapsedRealtimeMs)
    private val pool = YuvFramePool()
    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    override val frames: Flow<ImageFrame> = frames(minIntervalMs = 0L)

    public val stats: ImageFrameAnalyzerStats
        get() = ImageFrameAnalyzerStats(
            emittedFrames = emittedFrames.get(),
            droppedFrames = droppedFrames.get(),
            skippedFramesWithoutDemand = skippedFramesWithoutDemand.get(),
            openSnapshotLeases = snapshotLeases.get(),
            planeArraysAllocated = pool.arraysAllocated,
        )

    /**
     * Converting a frame copies every plane, so it only happens when somebody has said they want
     * one. The two kinds of demand are independent on purpose (architecture.md §6.1): a stream
     * subscriber that is due a frame, and a snapshot lease. Either alone keeps conversion running,
     * which is what lets Describe work while Guidance is stopped.
     */
    override fun analyze(image: ImageProxy) {
        // Taken first, before demand checks, conversion or any queueing: a subscriber that reads
        // the time on its own side also measures scheduling, which would pollute frame-to-sensor
        // alignment.
        val receivedNanos = elapsedRealtimeNanos()
        image.use { proxy ->
            val now = elapsedRealtimeMs()
            val subscribed = subscriptions.toList()
            val due = subscribed.filter { it.takeIfDue(now) }
            if (due.isEmpty() && snapshotLeases.get() == 0) {
                skippedFramesWithoutDemand.incrementAndGet()
                // Nobody is sampling at a low rate either, so nothing will want a frame soon.
                if (subscribed.isEmpty()) pool.trim()
                return
            }
            val buffers = pool.acquire()
            try {
                val frame = frameConverter.convert(
                    image = proxy,
                    sequenceNumber = nextSequenceNumber++,
                    planes = buffers,
                ).copy(
                    receivedElapsedRealtimeNanos = receivedNanos,
                    sourceGeometry = sourceGeometryOf(proxy),
                )
                latestFrame.record(frame, buffers)
                if (due.isEmpty()) {
                    // Converted for a snapshot lease only. Nothing is collecting, so there is no
                    // emission to count either way.
                    return
                }
                var sent = false
                for (subscription in due) {
                    if (subscription.offer(frame, buffers)) sent = true
                }
                if (sent) emittedFrames.incrementAndGet() else droppedFrames.incrementAndGet()
            } finally {
                // The conversion's own reference; the holders above each took theirs.
                buffers.release()
            }
        }
    }

    /**
     * Each collector gets its own one-frame mailbox. A frame arriving while the previous one is
     * still waiting replaces it -- the collector is behind, and an older frame is worth less than
     * a newer one -- and the replaced frame is released by the channel, as is anything still
     * waiting when the collector stops.
     */
    override fun frames(minIntervalMs: Long): Flow<ImageFrame> {
        require(minIntervalMs >= 0) { "minIntervalMs must not be negative: $minIntervalMs" }
        return flow {
            val subscription = Subscription(minIntervalMs)
            subscriptions += subscription
            try {
                for (lent in subscription.mailbox) {
                    subscription.lend(lent)
                    emit(lent.frame)
                }
            } finally {
                subscriptions -= subscription
                subscription.close()
            }
        }
    }

    override fun releaseFrame(frame: ImageFrame) {
        for (subscription in subscriptions) {
            if (subscription.takeBack(frame)) return
        }
    }

    /**
     * The snapshot is the last frame this analyzer converted. A dropped frame still counts: it was
     * current, it just lost the race into the stream buffer.
     */
    override fun currentFrame(maxAgeMs: Long): ImageFrame? = latestFrame.currentFrame(maxAgeMs)

    override suspend fun awaitCurrentFrame(maxAgeMs: Long, timeoutMs: Long): ImageFrame? {
        if (timeoutMs < 0) return null
        return openSnapshotLease().use {
            withTimeoutOrNull(timeoutMs) { latestFrame.awaitFrame(maxAgeMs) }
        }
    }

    override fun openSnapshotLease(): FrameLease {
        snapshotLeases.incrementAndGet()
        return SnapshotLease()
    }

    private inner class SnapshotLease : FrameLease {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                snapshotLeases.decrementAndGet()
            }
        }
    }

    /** One frame lent to one subscriber: one reference on the frame's buffers, released once. */
    private class Lent(val frame: ImageFrame, private val buffers: FrameBuffers) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) buffers.release()
        }
    }

    private class Subscription(private val minIntervalMs: Long) {
        val mailbox = Channel<Lent>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { it.release() },
        )

        /** Written and read on the camera thread only. */
        private var lastDueAtMs: Long? = null

        /** Frames handed to the collector and not yet given back. */
        private val lentOut = ArrayDeque<Lent>()

        fun takeIfDue(nowMs: Long): Boolean {
            val last = lastDueAtMs
            if (minIntervalMs > 0 && last != null && nowMs - last in 0 until minIntervalMs) return false
            lastDueAtMs = nowMs
            return true
        }

        /**
         * Each subscriber gets its own [ImageFrame] object over the shared arrays, so giving one
         * back is unambiguous even when several subscribers hold the same frame.
         */
        fun offer(frame: ImageFrame, buffers: FrameBuffers): Boolean {
            buffers.retain()
            val lent = Lent(frame.copy(), buffers)
            if (mailbox.trySend(lent).isSuccess) return true
            lent.release()
            return false
        }

        fun lend(lent: Lent) {
            synchronized(lentOut) {
                lentOut.addLast(lent)
                // A collector that never gives frames back must not grow this without bound. The
                // forgotten frame is never released, so its arrays are never reused: safe.
                while (lentOut.size > MAX_LENT_OUT) lentOut.removeFirst()
            }
        }

        fun takeBack(frame: ImageFrame): Boolean {
            val lent = synchronized(lentOut) {
                val index = lentOut.indexOfFirst { it.frame === frame }
                if (index < 0) return false
                lentOut.removeAt(index)
            }
            lent.release()
            return true
        }

        /**
         * The collector has stopped. Waiting frames go back through the channel; frames already
         * handed over are forgotten rather than released, since the collector may still be reading
         * one it never gave back.
         */
        fun close() {
            mailbox.cancel()
            synchronized(lentOut) { lentOut.clear() }
        }

        companion object {
            /** Guidance holds one frame at a time; this leaves room for a slower subscriber. */
            const val MAX_LENT_OUT = 4
        }
    }
}

public data class ImageFrameAnalyzerStats(
    val emittedFrames: Long,
    val droppedFrames: Long,
    /** Frames the camera delivered while nothing wanted one, so they were never converted. */
    val skippedFramesWithoutDemand: Long,
    val openSnapshotLeases: Int,
    /** Plane arrays allocated so far. Stops growing once the frame pool has warmed up. */
    val planeArraysAllocated: Long = 0L,
)

public interface ImageFrameConverter {
    /**
     * @param planes where the plane copies go. Arrays it returns may hold an earlier frame's bytes
     *   and must be filled completely.
     */
    public fun convert(
        image: ImageProxy,
        sequenceNumber: Long,
        planes: PlaneAllocator = PlaneAllocator.Allocating,
    ): ImageFrame
}

public class ImageProxyToFrameConverter : ImageFrameConverter {
    override fun convert(
        image: ImageProxy,
        sequenceNumber: Long,
        planes: PlaneAllocator,
    ): ImageFrame {
        return when (image.format) {
            ImageFormat.YUV_420_888 -> createYuvFrame(image, sequenceNumber, planes)
            PixelFormat.RGBA_8888 -> createRgbaFrame(image, sequenceNumber)
            ImageFormat.FLEX_RGBA_8888 -> createRgbaFrame(image, sequenceNumber)
            else -> error("Unsupported image format ${image.format}; expected YUV_420_888 or RGBA_8888")
        }
    }

    private fun createYuvFrame(
        image: ImageProxy,
        sequenceNumber: Long,
        planes: PlaneAllocator,
    ): ImageFrame {
        return ImageFrame(
            width = image.width,
            height = image.height,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.YUV_420_888,
            rotationDegrees = image.imageInfo.rotationDegrees,
            timestamp = image.imageInfo.timestamp,
            sequenceNumber = sequenceNumber,
            yuvData = Yuv420FrameData(
                y = copyPlane(image.planes[0], planes.planeArray(0, image.planes[0].byteCount())),
                u = copyPlane(image.planes[1], planes.planeArray(1, image.planes[1].byteCount())),
                v = copyPlane(image.planes[2], planes.planeArray(2, image.planes[2].byteCount())),
            ),
        )
    }

    // RGBA is not pooled: analysis is configured for YUV_420_888, and this path exists only for a
    // capture source that cannot deliver it.
    private fun createRgbaFrame(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame {
        return ImageFrame(
            width = image.width,
            height = image.height,
            pixelBytes = copyRgba(image),
            pixelFormat = ImagePixelFormat.RGBA_8888,
            rotationDegrees = image.imageInfo.rotationDegrees,
            timestamp = image.imageInfo.timestamp,
            sequenceNumber = sequenceNumber,
        )
    }

    private fun copyRgba(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val rgba = ByteArray(width * height * ImagePixelFormat.RGBA_8888.bytesPerPixel)
        val plane = image.planes.firstOrNull()
            ?: error("RGBA image has no planes")
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val outputRowBytes = width * ImagePixelFormat.RGBA_8888.bytesPerPixel

        if (pixelStride == ImagePixelFormat.RGBA_8888.bytesPerPixel) {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rgba, row * outputRowBytes, outputRowBytes)
            }
            return rgba
        }

        var outputIndex = 0
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val pixelOffset = rowOffset + x * pixelStride
                rgba[outputIndex++] = buffer.get(pixelOffset)
                rgba[outputIndex++] = buffer.get(pixelOffset + 1)
                rgba[outputIndex++] = buffer.get(pixelOffset + 2)
                rgba[outputIndex++] = buffer.get(pixelOffset + 3)
            }
        }
        return rgba
    }

    /** The whole plane buffer, exactly as many bytes as it holds (the old `ByteArray(remaining())`). */
    private fun ImageProxy.PlaneProxy.byteCount(): Int = buffer.duplicate().apply { position(0) }.remaining()

    private fun copyPlane(plane: ImageProxy.PlaneProxy, bytes: ByteArray): YuvPlaneData {
        val buffer = plane.buffer.duplicate()
        buffer.position(0)
        buffer.get(bytes)
        return YuvPlaneData(
            bytes = bytes,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        )
    }
}
