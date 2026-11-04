package com.sailens.guidance.safety

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.evaluation.EvaluationFixtures
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.motion.MotionState
import com.sailens.guidance.motion.OrientationQuality
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.safety.BaselineQualification.Degraded
import com.sailens.guidance.safety.BaselineQualification.Qualified
import com.sailens.guidance.safety.BaselineQualification.Unqualified
import com.sailens.guidance.safety.DegradeReason.GROUND_UNRECOGNIZED
import com.sailens.guidance.safety.UnqualifiedReason.CAMERA_OBSTRUCTED
import com.sailens.guidance.safety.UnqualifiedReason.TOO_DARK
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The qualification is derived by the generator's own test, so it can replace it later without changing a decision:
 * every combination of its inputs, by a hand-written table, and the equivalences that make it safe to read.
 */
class GuidanceQualifierTest {

    private data class Row(val quality: FrameQuality, val ground: GroundRecognition, val sensorQuality: Boolean, val expected: BaselineQualification)

    private val table = listOf(
        Row(FrameQuality.OK, GroundRecognition.RECOGNIZED, true, Qualified),
        Row(FrameQuality.OK, GroundRecognition.UNCERTAIN, true, Qualified),
        Row(FrameQuality.OK, GroundRecognition.UNRECOGNIZED, true, Degraded(setOf(GROUND_UNRECOGNIZED))),
        Row(FrameQuality.OBSTRUCTED, GroundRecognition.RECOGNIZED, true, Unqualified(CAMERA_OBSTRUCTED, emptySet())),
        Row(FrameQuality.OBSTRUCTED, GroundRecognition.UNCERTAIN, true, Unqualified(CAMERA_OBSTRUCTED, emptySet())),
        Row(FrameQuality.OBSTRUCTED, GroundRecognition.UNRECOGNIZED, true, Unqualified(CAMERA_OBSTRUCTED, setOf(GROUND_UNRECOGNIZED))),
        Row(FrameQuality.TOO_DARK, GroundRecognition.RECOGNIZED, true, Unqualified(TOO_DARK, emptySet())),
        Row(FrameQuality.TOO_DARK, GroundRecognition.UNCERTAIN, true, Unqualified(TOO_DARK, emptySet())),
        Row(FrameQuality.TOO_DARK, GroundRecognition.UNRECOGNIZED, true, Unqualified(TOO_DARK, setOf(GROUND_UNRECOGNIZED))),
        // Sensor-quality events off: frame quality counts for nothing, the floor still does.
        Row(FrameQuality.OK, GroundRecognition.RECOGNIZED, false, Qualified),
        Row(FrameQuality.OK, GroundRecognition.UNCERTAIN, false, Qualified),
        Row(FrameQuality.OK, GroundRecognition.UNRECOGNIZED, false, Degraded(setOf(GROUND_UNRECOGNIZED))),
        Row(FrameQuality.OBSTRUCTED, GroundRecognition.RECOGNIZED, false, Qualified),
        Row(FrameQuality.OBSTRUCTED, GroundRecognition.UNCERTAIN, false, Qualified),
        Row(FrameQuality.OBSTRUCTED, GroundRecognition.UNRECOGNIZED, false, Degraded(setOf(GROUND_UNRECOGNIZED))),
        Row(FrameQuality.TOO_DARK, GroundRecognition.RECOGNIZED, false, Qualified),
        Row(FrameQuality.TOO_DARK, GroundRecognition.UNCERTAIN, false, Qualified),
        Row(FrameQuality.TOO_DARK, GroundRecognition.UNRECOGNIZED, false, Degraded(setOf(GROUND_UNRECOGNIZED))),
    )

    /** A scene that makes other prompts too: a person ahead and a blocked path. */
    private fun snapshot(row: Row) = EvaluationFixtures.snapshot(
        obstacles = listOf(EvaluationFixtures.obstacle(1)),
        blocked = true,
        frameQuality = row.quality,
    ).copy(groundRecognition = row.ground)

    @Test
    fun `the table covers every combination once`() {
        assertEquals(FrameQuality.entries.size * GroundRecognition.entries.size * 2, table.size)
        assertEquals(table.size, table.map { Triple(it.quality, it.ground, it.sensorQuality) }.toSet().size)
    }

    @Test
    fun `every combination is qualified as the table says, and projects onto what the generator tests`() {
        for (row in table) {
            val config = AnalysisConfig(enableSensorQualityEvents = row.sensorQuality)
            val snapshot = snapshot(row)
            val baseline = GuidanceQualifier.baseline(snapshot, config)

            assertEquals("$row", row.expected, baseline)
            assertEquals("$row: projection", EventGenerator(config).visionFaults(snapshot), baseline.visionFaults())
            assertEquals("$row: path prompts", row.ground.pausesPathPrompts, baseline.pausesPathPrompts)
        }
    }

    @Test
    fun `unqualified exactly when a sensor-quality event is made and the resolver leaves only it, switches off or at launch`() {
        for (detFirst in listOf(DetFirstConfig(), DetFirstConfig.LAUNCH)) {
            for (row in table) {
                val config = AnalysisConfig(enableSensorQualityEvents = row.sensorQuality, detFirst = detFirst)
                val snapshot = snapshot(row)
                val raw = EventGenerator(config).generate(snapshot, now = 1_000L)
                val resolved = EventConflictResolver(detFirst.keepBlockedWithObstacle).resolve(raw)
                val suppressesAll = raw.any { it.category == EventCategory.SENSOR_QUALITY } &&
                    resolved.size == 1 && resolved.single().category == EventCategory.SENSOR_QUALITY

                assertEquals("$detFirst $row", GuidanceQualifier.baseline(snapshot, config) is Unqualified, suppressesAll)
            }
        }
    }

    private fun decision(config: AnalysisConfig, clock: () -> Long = { 1_000L }) = DecideEventsUseCase(
        eventGenerator = EventGenerator(config),
        conflictResolver = EventConflictResolver(),
        eventMerger = EventMerger(),
        cooldownManager = CooldownManager(),
        deviceSensorRepository = object : DeviceSensorRepository {
            override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
            override val deviceRotationValue: Int = 0
            override val deviceRotationDegree: Int = 0
            override val isStationary: StateFlow<Boolean> = MutableStateFlow(false)
            override fun startObserving() = Unit
            override fun stopObserving() = Unit
        },
        clock = clock,
    )

    @Test
    fun `the decision step qualifies by the config its generator decides with`() {
        for (row in table) {
            assertEquals("$row", row.expected, decision(AnalysisConfig(enableSensorQualityEvents = row.sensorQuality)).qualification(snapshot(row)))
        }
    }

    @Test
    fun `the decision time is the reading the decision made, not one more reading`() {
        var reads = 0
        val decide = decision(AnalysisConfig()) { reads++; 5_000L + reads }
        assertNull(decide.lastDecisionAtMs)

        decide(snapshot(table.first()))
        decide.qualification(snapshot(table.first()))
        assertEquals(1, reads)
        assertEquals(5_001L, decide.lastDecisionAtMs)
    }

    @Test
    fun `each level has its own trace name and the reasons put the unqualified one first`() {
        val levels = listOf(
            Qualified,
            Degraded(setOf(GROUND_UNRECOGNIZED)),
            Unqualified(CAMERA_OBSTRUCTED, emptySet()),
        ).map { it.traceLevel }
        assertEquals(listOf("qualified", "degraded", "unqualified"), levels)

        assertEquals(emptyList<String>(), Qualified.traceReasons)
        assertEquals(listOf("ground_unrecognized"), Degraded(setOf(GROUND_UNRECOGNIZED)).traceReasons)
        assertEquals(listOf("too_dark", "ground_unrecognized"), Unqualified(TOO_DARK, setOf(GROUND_UNRECOGNIZED)).traceReasons)
        assertEquals(listOf("camera_obstructed"), Unqualified(CAMERA_OBSTRUCTED, emptySet()).traceReasons)
    }

    @Test
    fun `a degraded qualification without a reason cannot be made`() {
        assertThrows(IllegalArgumentException::class.java) { Degraded(emptySet()) }
    }

    @Test
    fun `each capability status has its own trace name`() {
        val statuses = listOf(
            CapabilityStatus.NotConfigured,
            CapabilityStatus.Available,
            CapabilityStatus.Degraded(setOf("b", "a")),
            CapabilityStatus.Unavailable(setOf("c")),
        )
        assertEquals(listOf("not_configured", "available", "degraded", "unavailable"), statuses.map { it.traceStatus })
        assertEquals(listOf(emptyList(), emptyList(), listOf("a", "b"), listOf("c")), statuses.map { it.traceIssues })
    }

    // ---- capabilities

    private fun geometry(quality: GeometryQuality, height: HeightSource = HeightSource.PRESET) = CameraGeometry(
        frameTimeNanos = 1L,
        timeBasis = TimeBasis.CAMERA_REALTIME,
        intrinsics = null,
        gravityInCamera = null,
        gravityAgeNanos = null,
        phone = PhoneGeometrySettings(heightMeters = 1.2f, source = height),
        quality = quality,
    )

    private fun motion(quality: OrientationQuality) = MotionState(0L, 0f, 0f, isStationary = false, orientationQuality = quality)

    private val on = GroundContactDistanceConfig(enabled = true)
    private val off = GroundContactDistanceConfig(enabled = false)

    @Test
    fun `orientation is not configured without a source, otherwise as the tracker rates it`() {
        val g = geometry(GeometryQuality.Ok)
        fun orientation(quality: OrientationQuality, configured: Boolean) =
            GuidanceQualifier.capabilities(g, motion(quality), configured, off).getValue(GuidanceCapability.ORIENTATION)

        assertEquals(CapabilityStatus.NotConfigured, orientation(OrientationQuality.OK, configured = false))
        assertEquals(CapabilityStatus.Available, orientation(OrientationQuality.OK, configured = true))
        assertEquals(CapabilityStatus.Degraded(setOf("heading_carried_over")), orientation(OrientationQuality.DEGRADED, configured = true))
        assertEquals(CapabilityStatus.Unavailable(setOf("no_rotation_sample")), orientation(OrientationQuality.UNAVAILABLE, configured = true))
    }

    @Test
    fun `camera geometry is as the resolver rates it, issues in lower case`() {
        fun cameraGeometry(quality: GeometryQuality) =
            GuidanceQualifier.capabilities(geometry(quality), motion(OrientationQuality.OK), true, off).getValue(GuidanceCapability.CAMERA_GEOMETRY)

        assertEquals(CapabilityStatus.Available, cameraGeometry(GeometryQuality.Ok))
        assertEquals(
            CapabilityStatus.Degraded(setOf("estimated_intrinsics", "unmeasured_alignment")),
            cameraGeometry(GeometryQuality.Degraded(setOf(GeometryIssue.ESTIMATED_INTRINSICS, GeometryIssue.UNMEASURED_ALIGNMENT))),
        )
        assertEquals(CapabilityStatus.Unavailable(setOf("no_gravity")), cameraGeometry(GeometryQuality.Unavailable(setOf(GeometryIssue.NO_GRAVITY))))
    }

    @Test
    fun `the ground contact distance is off, held back by a seed height, or as usable as the estimator finds the geometry`() {
        fun contact(quality: GeometryQuality, height: HeightSource = HeightSource.PRESET, config: GroundContactDistanceConfig = on) =
            GuidanceQualifier.capabilities(geometry(quality, height), motion(OrientationQuality.OK), true, config)
                .getValue(GuidanceCapability.GROUND_CONTACT_DISTANCE)

        assertEquals(CapabilityStatus.NotConfigured, contact(GeometryQuality.Ok, config = off))
        assertEquals(CapabilityStatus.Unavailable(setOf("height_seed")), contact(GeometryQuality.Ok, HeightSource.DEFAULT_SEED))
        assertEquals(CapabilityStatus.Available, contact(GeometryQuality.Ok, HeightSource.CALIBRATED))
        // Estimated intrinsics are accepted; stale gravity is not.
        assertEquals(CapabilityStatus.Available, contact(GeometryQuality.Degraded(setOf(GeometryIssue.ESTIMATED_INTRINSICS))))
        assertEquals(
            CapabilityStatus.Unavailable(setOf("stale_gravity")),
            contact(GeometryQuality.Degraded(setOf(GeometryIssue.STALE_GRAVITY))),
        )
        assertEquals(CapabilityStatus.Unavailable(setOf("no_intrinsics")), contact(GeometryQuality.Unavailable(setOf(GeometryIssue.NO_INTRINSICS))))
    }

    @Test
    fun `every capability is reported, whatever the inputs`() {
        for (quality in OrientationQuality.entries) {
            for (configured in listOf(true, false)) {
                val capabilities = GuidanceQualifier.capabilities(geometry(GeometryQuality.Ok), motion(quality), configured, on)
                assertEquals(GuidanceCapability.entries.toSet(), capabilities.keys)
            }
        }
    }
}
