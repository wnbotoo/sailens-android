package com.sailens.guidance.depth.geometry

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.log.LogService
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.trace.NoOpTraceService
import com.sailens.runtime.accelerator.AcceleratorCoordinator
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthMap
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.DepthTimes
import com.sailens.vision.depth.MeasurementKey
import com.sailens.vision.depth.MetricDepthMap
import com.sailens.vision.depth.ResizePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Metric time and recovery through the real lane, with every step completed before the next frame is emitted. */
class MetricDepthLaneContractTest {
    private data class Step(
        val at: Long,
        val times: DepthTimes = DepthTimes.ofFrame(at),
        val thermal: ThermalLevel = ThermalLevel.NONE,
        val empty: Boolean = false,
    )

    private class Run(val snapshots: List<DepthGeometrySnapshot>, val records: List<DepthRunTrace>, val frame: CameraGeometry)

    private val intrinsics = CameraIntrinsics(45f, 45f, 16f, 32f, 32, 64, IntrinsicsSource.CALIBRATION)
    private val down = Vec3(0f, 0.9063078f, 0.4226183f)
    private val transform = DepthInputTransform.create(32, 64, 32, 64, ResizePolicy.STRETCH)

    private fun geometry(facts: FrameFacts, basis: TimeBasis) = CameraGeometry(
        if (basis == TimeBasis.CAMERA_REALTIME) facts.sensorTimestampNanos else facts.receivedElapsedRealtimeNanos,
        basis, intrinsics, down, 0L, PhoneGeometrySettings.DEFAULT,
        if (basis == TimeBasis.CAMERA_REALTIME) GeometryQuality.Ok else GeometryQuality.Degraded(setOf(GeometryIssue.UNMEASURED_ALIGNMENT)),
    )

    private fun run(steps: List<Step>, basis: TimeBasis): Run = runBlocking {
        var current = steps.first()
        val records = mutableListOf<DepthRunTrace>()
        val trace = object : TraceService by NoOpTraceService {
            override fun recordDepthRun(record: DepthRunTrace): Boolean {
                records += record.copy(seq = records.size + 1L)
                return true
            }
        }
        val producer = object : DepthProducer {
            override val needsAccelerator = false
            override suspend fun initialize() = Unit
            override suspend fun release() = Unit
            override suspend fun prepare(frame: ImageFrame) = DepthPreparedInput(transform, null, 0)
            override suspend fun infer(input: DepthPreparedInput): DepthRun<DepthMap> {
                val values = FloatArray(32 * 64) { i ->
                    val q = down.y * (i / 32 + 0.5f - intrinsics.cy) / intrinsics.fy + down.z
                    if (current.empty) Float.NaN else if (q > 0f) 1.3f / q else 50f
                }
                return DepthRun(MetricDepthMap(32, 64, values, null, 0.5f, transform, current.times), 0, null, 0, 0, 0, MlRuntimeInfo(), "metric")
            }
        }
        val lane = DepthGeometryLane(
            producerFactory = { producer }, modelCheck = { DepthModelCheckResult.Compatible },
            coordinator = AcceleratorCoordinator(), thermal = { current.thermal }, traceService = trace,
            config = DepthGeometryConfig(gridWidth = 9, gridHeight = 16), logService = Silent,
            elapsedMs = { 0L }, threadCpuNanos = { -1L },
        )
        val ready = CompletableDeferred<DepthSession>()
        val snapshots = mutableListOf<DepthGeometrySnapshot>()
        fun frame(i: Int, step: Step) = ImageFrame(32, 64, byteArrayOf(), ImagePixelFormat.YUV_420_888, step.at, 0, i + 1L, receivedElapsedRealtimeNanos = step.at + RECEIPT_OFFSET)
        val session = lane.start(DepthStartContext(
            frames = flow {
                val owner = ready.await()
                steps.forEachIndexed { i, step ->
                    current = step
                    emit(frame(i, step))
                    snapshots += owner.latest()
                }
            },
            releaseFrame = {}, baseline = { BaselineQualification.Qualified },
            geometry = { geometry(it, basis) }, traceSessionId = "metric-contract",
        ))
        ready.complete(session)
        try {
            withTimeout(10_000) { checkNotNull(session.job).join() }
        } finally {
            lane.stop(session)
        }
        Run(snapshots, records.toList(), geometry(FrameFacts.of(frame(steps.lastIndex, steps.last())), basis))
    }

    @Test
    fun `a missing metric view cannot publish an old measurement as current, on either clock`() {
        for (basis in TimeBasis.entries) {
            val result = run(listOf(Step(2_000_000_000L, DepthTimes(1_000_000_000L, null, MeasurementKey(1, 7)))), basis)
            val snapshot = result.snapshots.single()
            assertNull(basis.name, snapshot.observation)
            assertNull(snapshot.measurementTimeNanos)
            assertEquals(DepthGeometryLane.SKIP_TIME_MISMATCH, snapshot.lastSkipReason)
            assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE, DepthGeometryLane.SKIP_TIME_MISMATCH)), DepthGeometryCapability.status(snapshot, result.frame, 2))
            assertEquals(listOf(DepthRunTrace.DEPTH_TIME_MISMATCH, DepthRunTrace.CLOSED), result.records.map { it.outcome })
            assertEquals(0, result.records.last().failuresTotal)
        }
    }

    @Test
    fun `a missing view preserves the last observation and lets its measurement age on either clock`() {
        for (basis in TimeBasis.entries) {
            val result = run(listOf(Step(2_000_000_000L), Step(3_000_000_000L, DepthTimes(1_000_000_000L, null, MeasurementKey(1, 7)))), basis)
            val (first, last) = result.snapshots
            assertSame(first.observation, last.observation)
            assertSame(first.cells, last.cells)
            assertEquals(first.runId, last.runId)
            assertEquals(first.measurementTimeNanos, last.measurementTimeNanos)
            assertEquals(1_000_000_000L, DepthGeometryCapability.ageNanos(last, result.frame))
            assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE, DepthGeometryLane.SKIP_TIME_MISMATCH)), DepthGeometryCapability.status(last, result.frame, 2))
            assertEquals(listOf(1L, 2L, 2L), result.records.map { it.runId })
            assertEquals(listOf(1L, 2L, 3L), result.records.map { it.seq })
        }
    }

    @Test
    fun `a known displaced view maps the measurement age through either clock`() {
        for (basis in TimeBasis.entries) {
            val result = run(listOf(Step(2_000_000_000L, DepthTimes(1_000_000_000L, 2_020_000_000L, MeasurementKey(1, 7)))), basis)
            val snapshot = result.snapshots.single()
            val offset = if (basis == TimeBasis.SOURCE_RECEIPT) RECEIPT_OFFSET else 0L
            assertEquals(1_000_000_000L + offset, snapshot.measurementTimeNanos)
            assertEquals(2_020_000_000L + offset, snapshot.viewTimeNanos)
            assertEquals(1_000_000_000L, DepthGeometryCapability.ageNanos(snapshot, result.frame))
            assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE)), DepthGeometryCapability.status(snapshot, result.frame, 2))
        }
    }

    @Test
    fun `after heat, empty maps and mismatched views restore running without refreshing the old observation`() {
        for (empty in listOf(true, false)) for (fresh in listOf(true, false)) {
            val resumedAt = if (fresh) 1_200_000_000L else 3_000_000_000L
            val resumed = Step(resumedAt, times = if (empty) DepthTimes.ofFrame(resumedAt) else DepthTimes(resumedAt, null, null), empty = empty)
            val result = run(listOf(Step(1_000_000_000L), Step(1_100_000_000L, thermal = ThermalLevel.SEVERE), resumed), TimeBasis.CAMERA_REALTIME)
            val (first, paused, last) = result.snapshots
            assertEquals(DepthLaneState.PAUSED_THERMAL, paused.state)
            assertEquals(DepthLaneState.RUNNING, last.state)
            assertSame(first.observation, last.observation)
            assertEquals(first.measurementTimeNanos, last.measurementTimeNanos)
            val skip = if (empty) DepthGeometryLane.SKIP_NO_MEASUREMENT else DepthGeometryLane.SKIP_TIME_MISMATCH
            val expected = if (fresh) CapabilityStatus.Available else CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE, skip))
            assertEquals(expected, DepthGeometryCapability.status(last, result.frame, 2))
            assertEquals(listOf(1L, 2L, 2L), result.records.map { it.runId })
            assertEquals(mapOf(DepthGeometryLane.SKIP_THERMAL to 1), result.records[1].skipsSinceLast)
            assertEquals(0, result.records.last().failuresTotal)
            assertEquals("running", result.records.last().finalState)
        }
    }

    private object Silent : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object { const val RECEIPT_OFFSET = 10_000_000_000L }
}
