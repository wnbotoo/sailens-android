package com.sailens.guidance.depth.geometry

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.log.LogService
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.UnqualifiedReason
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.trace.NoOpTraceService
import com.sailens.runtime.accelerator.AcceleratorCoordinator
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The depth lane with a fake runner whose calls return when the test says (docs/geometry.md §6): the barrier chain
 * across sessions, stopping at every stage, the order of frame return, admission and inference, and failures that
 * stay inside the lane. The fake's initialize and infer cannot be cancelled midway, like LiteRT's.
 */
class DepthGeometryLaneTest {

    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val laneExecutor = Executors.newSingleThreadExecutor()
    private val laneDispatcher = laneExecutor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        laneExecutor.shutdownNow()
    }

    private inner class FakeRunner(val name: String) : DepthModelRunner {
        var initGate: CompletableDeferred<Unit>? = null
        var initError: Throwable? = null
        var inferGate: CompletableDeferred<Unit>? = null
        val inferStarted = CompletableDeferred<Unit>()
        var inferError: Throwable? = null
        var prepareNull = false
        var onPrepare: () -> Unit = {}
        var nonFinite = 0
        var releaseError: Throwable? = null

        override suspend fun initialize() {
            log += "$name init start"
            initGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            initError?.let { throw it }
            log += "$name init"
        }

        override suspend fun prepare(frame: ImageFrame): DepthPreparedInput? {
            log += "$name prepare ${frame.sequenceNumber}"
            onPrepare()
            return if (prepareNull) null else DepthPreparedInput(transform, detail = 0.5f, preprocessMs = 1)
        }

        override suspend fun infer(input: DepthPreparedInput): DepthRun {
            log += "$name infer"
            inferStarted.complete(Unit)
            withContext(NonCancellable) {
                inferGate?.await()
                log += "$name infer done"
            }
            inferError?.let { throw it }
            val values = FloatArray(MODEL_W * MODEL_H) { 1f }
            repeat(nonFinite) { values[it] = Float.NaN }
            return DepthRun(DisparityMap(MODEL_W, MODEL_H, values, transform), nonFinite, 0.5f, 1, 2, 3, MlRuntimeInfo("gpu", "explicit", "native_yuv", "kotlin_ground_fit"), "1x3x16x9>1x16x9")
        }

        override suspend fun release() {
            log += "$name release"
            releaseError?.let { throw it }
        }
    }

    private class Records : TraceService by NoOpTraceService {
        val all: MutableList<DepthRunTrace> = Collections.synchronizedList(mutableListOf())
        override fun recordDepthRun(record: DepthRunTrace): Boolean {
            all += record
            return true
        }

        fun of(generation: Long) = all.filter { it.generation == generation }
    }

    private inner class Logs : LogService {
        val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) {
            log += "warning $message"
        }
        override fun error(tag: String, message: String, throwable: Throwable?) {
            errors += message
        }
    }

    private val records = Records()
    private val logs = Logs()
    private val coordinator = AcceleratorCoordinator()
    private val runners = mutableListOf<FakeRunner>()
    private var configure: (FakeRunner) -> Unit = {}
    private var threadCpu: () -> Long = { -1L }

    private fun lane(
        check: DepthModelCheck = DepthModelCheck { DepthModelCheckResult.Compatible },
        thermal: ThermalStatusSource = ThermalStatusSource.None,
        fitDispatcher: CoroutineDispatcher = Dispatchers.Default,
        maxFailures: Int = 30,
        evidence: DepthEvidenceRecorder = DepthEvidenceRecorder.None,
    ) = DepthGeometryLane(
        runnerFactory = { FakeRunner(NAMES[runners.size]).also { configure(it); runners += it } },
        modelCheck = check,
        coordinator = coordinator,
        thermal = thermal,
        traceService = records,
        config = DepthGeometryConfig(maxConsecutiveFailures = maxFailures, gridWidth = 9, gridHeight = 16),
        logService = logs,
        elapsedMs = { 0L },
        threadCpuNanos = { threadCpu() },
        laneDispatcher = laneDispatcher,
        fitDispatcher = fitDispatcher,
        evidence = evidence,
    )

    /** A depth evidence recorder that logs what the lane hands it, and can be made to throw. */
    private inner class Evidence(
        val copyError: Throwable? = null,
        val submitError: Throwable? = null,
        val discardError: Throwable? = null,
    ) : DepthEvidenceRecorder {
        val settled: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame): DepthEvidenceSlot {
            copyError?.let { throw it }
            log += "evidence copy ${frame.sequenceNumber}"
            return object : DepthEvidenceSlot {
                override fun submit(run: DepthEvidenceRun) {
                    settled += "submit $runId ${run.outcome} ${run.fitOrdinal}"
                    submitError?.let { throw it }
                }

                override fun discard() {
                    settled += "discard $runId"
                    discardError?.let { throw it }
                }
            }
        }
    }

    /** One session's frame stream, the frames it gave back, and its baseline and geometry. */
    private inner class Feed(val tag: String) {
        val channel = Channel<ImageFrame>(Channel.UNLIMITED)
        var baseline: BaselineQualification? = BaselineQualification.Qualified
        var geometryQuality: GeometryQuality = GeometryQuality.Ok

        fun context() = DepthStartContext(
            frames = channel.receiveAsFlow(),
            releaseFrame = { log += "$tag release frame ${it.sequenceNumber}" },
            baseline = { baseline },
            geometry = { frame -> geometry(frame.timestamp, geometryQuality) },
            traceSessionId = "trace",
        )

        fun send(sequence: Long, timestampMs: Long = sequence * 333) {
            channel.trySend(frame(sequence, timestampMs))
        }
    }

    private fun frame(sequence: Long, timestampMs: Long) =
        ImageFrame(ANALYSIS_W, ANALYSIS_H, ByteArray(0), ImagePixelFormat.YUV_420_888, timestampMs * 1_000_000L, 0, sequence)

    private fun geometry(timeNanos: Long, quality: GeometryQuality): CameraGeometry {
        val unavailable = quality is GeometryQuality.Unavailable
        return CameraGeometry(
            frameTimeNanos = timeNanos,
            timeBasis = TimeBasis.CAMERA_REALTIME,
            intrinsics = if (unavailable) null else CameraIntrinsics(500f, 500f, 270f, 480f, ANALYSIS_W, ANALYSIS_H, IntrinsicsSource.CALIBRATION),
            gravityInCamera = if (unavailable) null else Vec3(0f, 0.9f, 0.43f),
            gravityAgeNanos = 0,
            phone = PhoneGeometrySettings.DEFAULT,
            quality = quality,
        )
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(5) }
        } catch (error: Exception) {
            throw AssertionError("timed out waiting for: $what; log = $log; records = ${records.all.map { "${it.generation}:${it.runId}:${it.outcome}" }}", error)
        }
    }

    private fun index(entry: String): Int = log.indexOf(entry).also { check(it >= 0) { "'$entry' not in $log" } }

    private fun count(entry: String) = log.count { it == entry }

    private fun outcomes(generation: Long) = records.of(generation).map { it.outcome }

    // ---- configuration and initialization

    @Test
    fun `no model - not configured, nothing built`() = runBlocking {
        val session = lane(check = { DepthModelCheckResult.Missing }).start(Feed("a").context())
        withTimeout(10_000) { session.job!!.join() }
        assertEquals(DepthLaneState.NOT_CONFIGURED, session.latest().state)
        assertTrue(runners.isEmpty())
    }

    @Test
    fun `a model outside the contract, or a check that throws, is unreadable and nothing is built`() = runBlocking {
        val unreadable = lane(check = { DepthModelCheckResult.Unreadable("output_size_mismatch") }).start(Feed("a").context())
        withTimeout(10_000) { unreadable.job!!.join() }
        assertEquals(DepthLaneState.MODEL_UNREADABLE, unreadable.latest().state)
        val throwing = lane(check = { throw IllegalStateException("cannot read") }).start(Feed("b").context())
        withTimeout(10_000) { throwing.job!!.join() }
        assertEquals(DepthLaneState.MODEL_UNREADABLE, throwing.latest().state)
        assertTrue(runners.isEmpty())
    }

    @Test
    fun `an initialization that throws, even an Error, is init_failed, released, and nothing escapes`() = runBlocking {
        configure = { it.initError = UnsatisfiedLinkError("no native library") }
        val feed = Feed("a")
        val session = lane().start(feed.context())
        withTimeout(10_000) { session.done!!.join() }
        assertEquals(DepthLaneState.INIT_FAILED, session.latest().state)
        assertTrue("released", "a release" in log)
        assertTrue("the lane's last guard was not needed", logs.errors.isEmpty())
    }

    // ---- one run

    @Test
    fun `a run publishes, the frame goes back once, before the accelerator`() = runBlocking {
        val feed = Feed("a")
        val session = lane().start(feed.context())
        feed.send(1)
        until("published") { session.latest().runId == 1L }
        assertTrue(index("a release frame 1") < index("a infer"))
        assertEquals(1, count("a release frame 1"))
        assertEquals(listOf(DepthRunTrace.PUBLISHED), outcomes(1))
        val record = records.all.single()
        assertEquals(1L, record.frameSequence)
        assertEquals("1x3x16x9>1x16x9", record.modelShape)
        assertEquals("gpu", record.accelerator)
        assertEquals(DepthLaneState.RUNNING, session.latest().state)
        assertEquals(emptyMap<Any, Any>(), coordinator.state.value.admitted.filterValues { it > 0 })
    }

    @Test
    fun `a yield that opens after the preprocessing drops the attempt without inference`() = runBlocking {
        var interval: AutoCloseable? = null
        configure = { runner -> runner.onPrepare = { if (interval == null) interval = coordinator.yieldFor(AcceleratorCoordinator.Priority.INTERACTIVE) } }
        val feed = Feed("a")
        val session = lane().start(feed.context())
        feed.send(1)
        until("dropped") { records.all.isNotEmpty() }
        assertEquals(listOf(DepthRunTrace.YIELD_DROPPED), outcomes(1))
        assertFalse("a infer" in log)
        // While the interval is open, frames are skipped before anything starts.
        feed.send(2)
        until("paused") { session.latest().state == DepthLaneState.PAUSED_YIELD }
        assertFalse("a prepare 2" in log)
        assertEquals(1, count("a release frame 2"))
        interval!!.close()
        feed.send(3)
        // Frame 2 never began, so frame 3's attempt is run 2.
        until("published after the yield") { session.latest().runId == 2L }
        assertEquals(DepthLaneState.RUNNING, session.latest().state)
        assertEquals(mapOf(DepthGeometryLane.SKIP_YIELD to 1), records.all.last().skipsSinceLast)
    }

    @Test
    fun `an admitted run finishes when a yield opens during it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        configure = { it.inferGate = gate }
        val feed = Feed("a")
        val session = lane().start(feed.context())
        feed.send(1)
        withTimeout(10_000) { runners.awaitFirst().inferStarted.await() }
        val interval = coordinator.yieldFor(AcceleratorCoordinator.Priority.INTERACTIVE)
        assertEquals(1, coordinator.state.value.admitted[AcceleratorCoordinator.Priority.BACKGROUND])
        gate.complete(Unit)
        until("published") { session.latest().runId == 1L }
        assertEquals(0, coordinator.state.value.admitted[AcceleratorCoordinator.Priority.BACKGROUND] ?: 0)
        interval.close()
    }

    @Test
    fun `severe heat pauses the lane, moderate heat runs it at the throttled rate`() = runBlocking {
        var level = ThermalLevel.SEVERE
        val feed = Feed("a")
        val session = lane(thermal = { level }).start(feed.context())
        feed.send(1, timestampMs = 0)
        until("paused") { session.latest().state == DepthLaneState.PAUSED_THERMAL }
        assertFalse("a prepare 1" in log)
        assertEquals(1, count("a release frame 1"))
        level = ThermalLevel.MODERATE
        for ((sequence, ms) in listOf(2L to 100L, 3L to 433L, 4L to 766L, 5L to 1_100L, 6L to 1_433L)) feed.send(sequence, ms)
        until("all frames handled") { (2..6).all { count("a release frame $it") == 1 } }
        // Attempts at 100 ms and 1100 ms; the frames between them are within the 1 s period.
        assertEquals(listOf("a prepare 2", "a prepare 5"), log.filter { it.startsWith("a prepare") })
        assertEquals(1_000L, session.latest().periodMs)
        assertEquals(mapOf(DepthGeometryLane.SKIP_THERMAL to 1), records.of(1).first().skipsSinceLast)
        assertEquals(mapOf(DepthGeometryLane.SKIP_THERMAL_CADENCE to 2), records.of(1)[1].skipsSinceLast)
    }

    @Test
    fun `no baseline yet, an unqualified frame or unavailable geometry skip the frame before anything starts`() = runBlocking {
        val feed = Feed("a")
        feed.baseline = null
        val lane = lane()
        val session = lane.start(feed.context())
        feed.send(1)
        until("skipped 1") { count("a release frame 1") == 1 }
        assertEquals(DepthGeometryLane.SKIP_NO_BASELINE, session.latest().lastSkipReason)
        feed.baseline = BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, emptySet())
        feed.send(2)
        until("skipped 2") { count("a release frame 2") == 1 }
        feed.baseline = BaselineQualification.Qualified
        feed.geometryQuality = GeometryQuality.Unavailable(setOf(GeometryIssue.entries.first()))
        feed.send(3)
        until("skipped 3") { count("a release frame 3") == 1 }
        assertTrue(log.none { it.startsWith("a prepare") })
        assertEquals(DepthGeometryLane.SKIP_GEOMETRY, session.latest().lastSkipReason)
        lane.stop(session)
        assertEquals(
            mapOf(DepthGeometryLane.SKIP_NO_BASELINE to 1, DepthGeometryLane.SKIP_FRAME_UNQUALIFIED to 1, DepthGeometryLane.SKIP_GEOMETRY to 1),
            records.all.single().skipsSinceLast,
        )
    }

    @Test
    fun `a native preprocessing failure runs no inference`() = runBlocking {
        configure = { it.prepareNull = true }
        val feed = Feed("a")
        val session = lane().start(feed.context())
        feed.send(1)
        until("settled") { records.all.isNotEmpty() }
        assertEquals(listOf(DepthRunTrace.PREPROCESS_UNAVAILABLE), outcomes(1))
        assertEquals(DepthLaneState.PREPROCESS_UNAVAILABLE, session.latest().state)
        assertFalse("a infer" in log)
        assertEquals(1, count("a release frame 1"))
    }

    @Test
    fun `non-finite output fails the run, too many in a row stop the lane and later frames count as stopped`() = runBlocking {
        configure = { it.nonFinite = 3 }
        val feed = Feed("a")
        val lane = lane(maxFailures = 2)
        val session = lane.start(feed.context())
        feed.send(1)
        feed.send(2)
        until("stopped") { session.latest().state == DepthLaneState.RUN_FAILED }
        feed.send(3)
        until("frame 3 back") { count("a release frame 3") == 1 }
        assertNull(session.latest().observation)
        lane.stop(session)
        assertEquals(listOf(DepthRunTrace.NON_FINITE_OUTPUT, DepthRunTrace.NON_FINITE_OUTPUT, DepthRunTrace.CLOSED), outcomes(1))
        val closed = records.all.last()
        assertEquals(mapOf(DepthGeometryLane.SKIP_STOPPED to 1), closed.skipsSinceLast)
        assertEquals(2, closed.failuresTotal)
        assertEquals("run_failed", closed.finalState)
    }

    @Test
    fun `a throw from inference or from a port settles the attempt, gives the frame back and the lane goes on`() = runBlocking {
        configure = { it.inferError = RuntimeException("gpu lost") }
        val heatThrows = java.util.concurrent.atomic.AtomicBoolean(false)
        val feed = Feed("a")
        val session = lane(thermal = { if (heatThrows.get()) throw IllegalStateException("thermal service died") else ThermalLevel.NONE }).start(feed.context())
        feed.send(1)
        until("failed") { records.all.isNotEmpty() }
        assertEquals(1, count("a release frame 1"))
        runners.first().inferError = null
        heatThrows.set(true)
        feed.send(2)
        until("frame 2 back") { count("a release frame 2") == 1 }
        heatThrows.set(false)
        feed.send(3)
        until("published") { session.latest().runId != null }
        // The thermal throw came before the attempt began: counted as a failure, settled by no record.
        assertEquals(listOf(DepthRunTrace.FAILED, DepthRunTrace.PUBLISHED), outcomes(1))
        assertEquals(listOf(1L, 2L), records.all.map { it.runId })
        assertTrue(logs.errors.isEmpty())
    }

    // ---- stopping

    @Test
    fun `stopped during initialization, then a new session - the new one initializes only after the old one's release`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        configure = { if (it.name == "a") it.initGate = gate }
        val lane = lane()
        val feedA = Feed("a")
        val a = lane.start(feedA.context())
        until("a compiling") { "a init start" in log }
        lane.stop(a)
        val feedB = Feed("b")
        val b = lane.start(feedB.context())
        delay(100)
        assertFalse("b started before a finished", "b init start" in log)
        gate.complete(Unit)
        until("b running") { b.latest().state == DepthLaneState.RUNNING }
        assertTrue(index("a release") < index("b init start"))
        assertEquals(listOf(DepthRunTrace.CLOSED), outcomes(1))
        assertNull(a.latest().observation)
        feedB.send(1)
        until("b published") { b.latest().runId == 1L }
    }

    @Test
    fun `three sessions - A stopped while fitting, B stopped before it ran, C initializes only after A's release`() = runBlocking {
        val fitTasks = LinkedBlockingQueue<Runnable>()
        val fit = Executor { fitTasks.add(it) }.asCoroutineDispatcher()
        val lane = lane(fitDispatcher = fit)
        val feedA = Feed("a")
        val a = lane.start(feedA.context())
        feedA.send(1)
        until("a fitting") { fitTasks.isNotEmpty() }
        lane.stop(a)
        // Hold the lane's thread, so B is cancelled before its body ever runs.
        val held = CountDownLatch(1)
        laneExecutor.execute { held.await(10, TimeUnit.SECONDS) }
        val b = lane.start(Feed("b").context())
        lane.stop(b)
        held.countDown()
        val feedC = Feed("c")
        val c = lane.start(feedC.context())
        delay(100)
        assertFalse("c started before a finished", log.any { it.endsWith("init start") && !it.startsWith("a") })
        while (fitTasks.isNotEmpty()) fitTasks.poll()!!.run()
        until("c running") { c.latest().state == DepthLaneState.RUNNING }
        // B never built a runner: the second runner is C's.
        assertEquals(listOf("a", "b"), runners.map { it.name })
        assertTrue(index("a release") < index("b init start"))
        assertEquals(listOf(DepthRunTrace.CLOSED), outcomes(1))
        assertEquals(1L, records.of(1).single().cancelledRunId)
        assertEquals(DepthGeometryLane.STAGE_FIT, records.of(1).single().cancelledStage)
        assertEquals(listOf(DepthRunTrace.CLOSED), outcomes(2))
        assertNull(a.latest().observation)
        assertTrue(b.job!!.isCancelled)
    }

    @Test
    fun `B started before A is stopped - A still closes and releases, B initializes after it and numbers its own runs`() = runBlocking {
        val lane = lane()
        val feedA = Feed("a")
        val a = lane.start(feedA.context())
        feedA.send(1)
        until("a published") { a.latest().runId == 1L }
        val feedB = Feed("b")
        val b = lane.start(feedB.context())
        delay(100)
        assertFalse("b started while a runs", "b init start" in log)
        lane.stop(a)
        until("b running") { b.latest().state == DepthLaneState.RUNNING }
        assertTrue(index("a release") < index("b init start"))
        assertEquals(listOf(DepthRunTrace.PUBLISHED, DepthRunTrace.CLOSED), outcomes(1))
        feedB.send(1)
        until("b published") { b.latest().runId == 1L }
        assertEquals(listOf(1L), records.of(2).map { it.runId })
    }

    @Test
    fun `the fit's own CPU time is read on the fit's thread around the fit, and none is recorded where the platform cannot`() = runBlocking {
        var fitThread: Thread? = null
        val fitExecutor = Executors.newSingleThreadExecutor { Thread(it).also { thread -> fitThread = thread } }
        try {
            val readers = Collections.synchronizedList(mutableListOf<Thread>())
            val readings = ArrayDeque(listOf(5_000_000L, 7_250_000L))
            threadCpu = {
                readers += Thread.currentThread()
                synchronized(readings) { readings.removeFirstOrNull() ?: -1L }
            }
            val lane = lane(fitDispatcher = fitExecutor.asCoroutineDispatcher())
            val feed = Feed("a")
            val session = lane.start(feed.context())
            feed.send(1)
            until("first published") { session.latest().runId == 1L }
            feed.send(2)
            until("second published") { session.latest().runId == 2L }
            val published = records.all.filter { it.outcome == DepthRunTrace.PUBLISHED }
            assertEquals(2_250L, published[0].fitCpuUs)
            assertNull("a platform that cannot measure records nothing", published[1].fitCpuUs)
            assertEquals(List(4) { checkNotNull(fitThread) }, readers.toList())
            lane.stop(session)
        } finally {
            fitExecutor.shutdownNow()
        }
    }

    @Test
    fun `stopped during inference - the run returns, nothing is published, then the runner is released`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        configure = { it.inferGate = gate }
        val lane = lane()
        val feed = Feed("a")
        val session = lane.start(feed.context())
        feed.send(1)
        withTimeout(10_000) { runners.awaitFirst().inferStarted.await() }
        lane.stop(session)
        val closed = records.all.single()
        assertEquals(DepthRunTrace.CLOSED, closed.outcome)
        assertEquals(1L, closed.cancelledRunId)
        assertEquals(DepthGeometryLane.STAGE_INFER, closed.cancelledStage)
        delay(50)
        assertFalse("released while inferring", "a release" in log)
        gate.complete(Unit)
        withTimeout(10_000) { session.done!!.join() }
        assertTrue(index("a infer done") < index("a release"))
        assertNull(session.latest().observation)
        assertEquals(1, records.all.size)
        assertEquals(0, coordinator.state.value.admitted[AcceleratorCoordinator.Priority.BACKGROUND] ?: 0)
    }

    @Test
    fun `a release that throws is logged, and the chain lets the next session through`() = runBlocking {
        configure = { if (it.name == "a") it.releaseError = IllegalStateException("release failed") }
        val lane = lane()
        val a = lane.start(Feed("a").context())
        until("a running") { a.latest().state == DepthLaneState.RUNNING }
        lane.stop(a)
        val feedB = Feed("b")
        val b = lane.start(feedB.context())
        feedB.send(1)
        until("b published") { b.latest().runId == 1L }
        assertTrue("warning Depth runner release failed" in log)
        assertTrue(logs.errors.isEmpty())
    }

    @Test
    fun `stop twice writes one closing record, and the cancellation reaches the session's job`() = runBlocking {
        val lane = lane()
        val feed = Feed("a")
        val session = lane.start(feed.context())
        until("running") { session.latest().state == DepthLaneState.RUNNING }
        lane.stop(session)
        lane.stop(session)
        // The frame stream never ends by itself: only a cancellation the lane did not swallow ends the job.
        withTimeout(10_000) { session.done!!.join() }
        assertTrue(session.job!!.isCancelled)
        assertEquals(listOf(DepthRunTrace.CLOSED), outcomes(1))
        assertEquals(DepthLaneState.CLOSED, session.latest().state)
        feed.send(9)
        delay(50)
        assertFalse("a prepare 9" in log)
    }

    @Test
    fun `a cancellation thrown inside a step is rethrown, not counted as a failure, and the session ends`() = runBlocking {
        configure = { runner -> runner.onPrepare = { throw kotlinx.coroutines.CancellationException("the runner gave up") } }
        val lane = lane()
        val feed = Feed("a")
        val session = lane.start(feed.context())
        until("running") { session.latest().state == DepthLaneState.RUNNING }
        feed.send(1)
        feed.send(2)
        withTimeout(10_000) { session.done!!.join() }
        assertEquals(1, count("a release frame 1"))
        assertFalse("a frame after the cancellation was handled", "a prepare 2" in log)
        assertTrue("nothing settled as failed", records.all.none { it.outcome == DepthRunTrace.FAILED })
        assertTrue("released", "a release" in log)
        lane.stop(session)
    }

    // ---- the debug capture's depth evidence

    /**
     * Four frames: published, no input, a non-finite output, published. Returns the session's outcomes and run ids,
     * with [evidence] attached.
     */
    private suspend fun fourRuns(evidence: DepthEvidenceRecorder): List<String> {
        records.all.clear()
        runners.clear()
        val feed = Feed("a")
        val lane = lane(evidence = evidence)
        val session = lane.start(feed.context())
        feed.send(1)
        until("run 1") { records.all.size == 1 }
        runners.first().prepareNull = true
        feed.send(2)
        until("run 2") { records.all.size == 2 }
        runners.first().prepareNull = false
        runners.first().nonFinite = 3
        feed.send(3)
        until("run 3") { records.all.size == 3 }
        runners.first().nonFinite = 0
        feed.send(4)
        until("run 4") { records.all.size == 4 }
        lane.stop(session)
        // The closing record carries the session's failure count and its state at the stop.
        return records.all.map { r ->
            if (r.outcome == DepthRunTrace.CLOSED) "closed failures=${r.failuresTotal} state=${r.finalState}" else "${r.runId} ${r.outcome}"
        }
    }

    @Test
    fun `evidence copies the planes before the frame goes back, and every run is settled once`() = runBlocking {
        val evidence = Evidence()
        val outcomes = fourRuns(evidence)
        assertEquals(
            listOf("1 published", "2 preprocess_unavailable", "3 non_finite_output", "4 published", "closed failures=2 state=running"),
            outcomes,
        )
        for (sequence in 1..4) assertTrue("copy of frame $sequence before it went back", index("evidence copy $sequence") < index("a release frame $sequence"))
        // The fit ordinals count the fitter's own calls: the runs without a fit leave no gap.
        assertEquals(
            listOf("submit 1 published 0", "discard 2", "submit 3 non_finite_output null", "submit 4 published 1"),
            evidence.settled.toList(),
        )
    }

    @Test
    fun `a recorder that throws changes nothing the lane does or records`() = runBlocking {
        val without = fourRuns(DepthEvidenceRecorder.None)
        // At each of the three calls, an exception and an Error: the lane's records, its failure count and its final
        // state are what they are without a recorder.
        val throwers = listOf<() -> Throwable>({ IllegalStateException("probe") }, { OutOfMemoryError("probe") }, { StackOverflowError("probe") })
        for (thrown in throwers) {
            val name = thrown().javaClass.simpleName
            assertEquals("copy throws $name", without, fourRuns(Evidence(copyError = thrown())))
            assertEquals("submit throws $name", without, fourRuns(Evidence(submitError = thrown())))
            assertEquals("discard throws $name", without, fourRuns(Evidence(discardError = thrown())))
        }
        assertTrue("the lane's last guard was not needed", logs.errors.isEmpty())
    }

    @Test
    fun `without a trace session nothing is copied`() = runBlocking {
        val evidence = Evidence()
        val feed = Feed("a")
        val lane = lane(evidence = evidence)
        val session = lane.start(
            feed.context().let { DepthStartContext(it.frames, it.releaseFrame, it.baseline, it.geometry, traceSessionId = null) },
        )
        feed.send(1)
        until("published") { session.latest().runId == 1L }
        lane.stop(session)
        assertFalse("evidence copy 1" in log)
        assertTrue(evidence.settled.isEmpty())
    }

    private suspend fun List<FakeRunner>.awaitFirst(): FakeRunner {
        until("a runner") { isNotEmpty() }
        return first()
    }

    private companion object {
        const val ANALYSIS_W = 540
        const val ANALYSIS_H = 960
        const val MODEL_W = 9
        const val MODEL_H = 16
        val NAMES = listOf("a", "b", "c", "d")
        val transform: DepthInputTransform = DepthInputTransform.create(ANALYSIS_W, ANALYSIS_H, MODEL_W, MODEL_H, ResizePolicy.PAD_KEEP_ASPECT)
    }
}
