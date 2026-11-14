package com.sailens.guidance.depth.geometry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import com.sailens.core.log.LogService
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.trace.NoOpTraceService
import com.sailens.runtime.ModelSource
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelType
import com.sailens.runtime.accelerator.AcceleratorCoordinator
import com.sailens.vision.depth.DepthModelConfig
import com.sailens.vision.depth.DepthMap
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.LiteRtDepthRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Stopping a depth session while its call into the real runner is in flight (docs/geometry.md §6), on a device: the
 * lane with `LiteRtDepthRunner` on the GPU, configured as the app configures it, and a gate in front of its inference.
 * The stop lands in the inference stage every time, not by timing: the gate holds the call until the stop has
 * returned, then lets the real inference run to the end.
 *
 * What it proves is an order: the session is sealed while the call is in flight; the runner then finishes the
 * inference and reads its output; nothing is published; the runner is released only after; a session started before
 * the stopped one is done initializes only after that release; and after five such stops a session runs normally and
 * publishes. It does not prove that the GPU was executing at the instant of the stop. The lane never touches the
 * runner during the call, so the two are one path in the lane; but the gate holds the call in `NonCancellable`, so
 * the stop's cancellation cannot end it before the real inference has run, while in the app the cancellation is seen
 * when the runner's own `withContext` returns.
 *
 * Needs a depth model the depth contract accepts at this test app's `files/depth.tflite`, so it is skipped in CI:
 *
 * ```
 * adb exec-in run-as com.sailens.guidance.test sh -c 'mkdir -p files && cat > files/depth.tflite' < depth.tflite
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DepthLaneStopDuringInferenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = File(context.filesDir, "depth.tflite")
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private object Silent : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private class Records : TraceService by NoOpTraceService {
        val all: MutableList<DepthRunTrace> = Collections.synchronizedList(mutableListOf())
        override fun recordDepthRun(record: DepthRunTrace): Boolean {
            all += record
            return true
        }
    }

    /** The real runner behind a gate on its inference; [gated] false lets every call straight through. */
    private inner class Gated(private val delegate: DepthProducer, val name: String, gated: Boolean) : DepthProducer {
        override val needsAccelerator: Boolean get() = delegate.needsAccelerator
        val inferEntered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>().also { if (!gated) it.complete(Unit) }

        @Volatile
        var output: DepthRun<DepthMap>? = null

        override suspend fun initialize() {
            log += "$name init start"
            delegate.initialize()
            log += "$name init done"
        }

        override suspend fun prepare(frame: ImageFrame): DepthPreparedInput? = delegate.prepare(frame)

        // The stop cancels the session's job: outside NonCancellable the wait would end there, and the runner's own
        // withContext would not start the inference at all.
        override suspend fun infer(input: DepthPreparedInput): DepthRun<DepthMap> = withContext(NonCancellable) {
            inferEntered.complete(Unit)
            proceed.await()
            log += "$name infer start"
            delegate.infer(input).also {
                output = it
                log += "$name infer done"
            }
        }

        override suspend fun release() {
            log += "$name release start"
            delegate.release()
            log += "$name release done"
        }
    }

    private val config = DepthModelConfig()
    private val resolver = object : ModelSourceResolver {
        override fun source(type: ModelType, accelerator: Accelerator): ModelSource = ModelSource.File(model)
    }
    private val records = Records()
    private val coordinator = AcceleratorCoordinator()
    private val runners: MutableList<Gated> = Collections.synchronizedList(mutableListOf())

    /** How many runners the lane builds next are gated, in order. */
    private var gatedNext = 0

    private fun lane() = DepthGeometryLane(
        producerFactory = {
            val name = "s${runners.size + 1}"
            Gated(LiteRtDepthProducer(LiteRtDepthRunner(context, config, resolver, Silent)), name, gated = gatedNext-- > 0)
                .also { runners += it }
        },
        modelCheck = LiteRtDepthModelCheck(context, config, resolver),
        coordinator = coordinator,
        thermal = ThermalStatusSource.None,
        traceService = records,
        config = DepthGeometryConfig(),
        logService = Silent,
    )

    private inner class Feed {
        val channel = Channel<ImageFrame>(Channel.UNLIMITED)
        private var sequence = 0L

        fun context() = DepthStartContext(
            frames = channel.receiveAsFlow(),
            releaseFrame = {},
            baseline = { BaselineQualification.Qualified },
            geometry = { facts -> geometry(facts.sensorTimestampNanos) },
            traceSessionId = "trace",
        )

        fun send() {
            sequence++
            channel.trySend(frame(sequence))
        }
    }

    /** An upright 540x960 YUV frame with a vertical gradient, so the model has something to see. */
    private fun frame(sequence: Long): ImageFrame {
        val width = 540
        val height = 960
        val y = YuvPlaneData(ByteArray(width * height) { i -> (40 + (i / width) * 180 / height).toByte() }, rowStride = width, pixelStride = 1)
        val uv = YuvPlaneData(ByteArray((width / 2) * (height / 2)) { 128.toByte() }, rowStride = width / 2, pixelStride = 1)
        return ImageFrame(
            width = width,
            height = height,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.YUV_420_888,
            timestamp = sequence * 333_000_000L,
            rotationDegrees = 0,
            sequenceNumber = sequence,
            yuvData = Yuv420FrameData(y, uv, uv),
        )
    }

    private fun geometry(timeNanos: Long) = CameraGeometry(
        frameTimeNanos = timeNanos,
        timeBasis = TimeBasis.CAMERA_REALTIME,
        intrinsics = CameraIntrinsics(500f, 500f, 270f, 480f, 540, 960, IntrinsicsSource.CALIBRATION),
        gravityInCamera = Vec3(0f, 0.9f, 0.43f),
        gravityAgeNanos = 0,
        phone = PhoneGeometrySettings.DEFAULT,
        quality = GeometryQuality.Ok,
    )

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(60_000) { while (!condition()) delay(10) }
        } catch (error: Exception) {
            throw AssertionError("timed out waiting for: $what; log = $log", error)
        }
    }

    private fun index(entry: String): Int = log.indexOf(entry).also { check(it >= 0) { "'$entry' not in $log" } }

    @Test
    fun stoppedFiveTimesWhileTheRealInferenceIsInFlight_thenASessionPublishes() = runBlocking {
        assumeTrue("push a depth model to ${model.path}", model.isFile)
        val lane = lane()
        gatedNext = 5
        var next: Pair<DepthSession, Feed>? = null
        for (cycle in 1..5) {
            val (session, feed) = next ?: Feed().let { lane.start(it.context()) to it }
            next = null
            feed.send()
            until("s$cycle in inference") { runners.size >= cycle && runners[cycle - 1].inferEntered.isCompleted }
            val runner = runners[cycle - 1]
            lane.stop(session)
            log += "s$cycle stop returned"

            val closing = records.all.filter { it.generation == session.generation }
            assertEquals("$cycle: the closing record is all the session wrote", listOf(DepthRunTrace.CLOSED), closing.map { it.outcome })
            assertEquals(1L, closing.single().cancelledRunId)
            assertEquals(DepthGeometryLane.STAGE_INFER, closing.single().cancelledStage)

            // Once, the next session is started before this one is done: it must wait for this release.
            if (cycle == 3) next = Feed().let { lane.start(it.context()) to it }

            runner.proceed.complete(Unit)
            withTimeout(60_000) { session.done!!.join() }
            val output = assertNotNullOutput(runner)
            assertEquals("$cycle: the inference read a whole, finite output", 0, output.nonFiniteCount)
            assertTrue((output.map as DisparityMap).values.isNotEmpty())
            assertTrue("$cycle: stopped before the inference ran", index("s$cycle stop returned") < index("s$cycle infer start"))
            assertTrue("$cycle: released after the inference", index("s$cycle infer done") < index("s$cycle release start"))
            assertTrue(index("s$cycle release start") < index("s$cycle release done"))
            assertEquals("$cycle: nothing published", listOf(DepthRunTrace.CLOSED), records.all.filter { it.generation == session.generation }.map { it.outcome })
            assertEquals(0, coordinator.state.value.admitted[AcceleratorCoordinator.Priority.BACKGROUND] ?: 0)
        }
        // The session started during cycle 3 (s4) initialized only after s3's runner was released.
        assertTrue("s4 initialized before s3 was released: $log", index("s3 release done") < index("s4 init start"))

        // After five stops in inference, a session runs as usual: it publishes its first run, then stops.
        val feed = Feed()
        val last = lane.start(feed.context())
        until("the last session's runner") { runners.size == 6 }
        feed.send()
        until("published") { last.latest().runId == 1L }
        assertEquals(DepthRunTrace.PUBLISHED, records.all.first { it.generation == last.generation }.outcome)
        lane.stop(last)
        withTimeout(60_000) { last.done!!.join() }
        assertTrue(index("s5 release done") < index("s6 init start"))
    }

    private fun assertNotNullOutput(runner: Gated): DepthRun<DepthMap> {
        val output = runner.output
        assertNotNull("${runner.name}: the real inference never returned an output", output)
        return checkNotNull(output)
    }
}
