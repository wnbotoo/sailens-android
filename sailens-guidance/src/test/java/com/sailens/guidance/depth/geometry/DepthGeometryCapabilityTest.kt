package com.sailens.guidance.depth.geometry

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.safety.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `DEPTH_GEOMETRY` capability's nine rows (docs/geometry.md §6), first match wins, and the age boundaries. */
class DepthGeometryCapabilityTest {

    private val period = 333L
    private val frameTime = 10_000_000_000L

    private fun frame(timeNanos: Long? = frameTime, basis: TimeBasis = TimeBasis.CAMERA_REALTIME) = CameraGeometry(
        frameTimeNanos = timeNanos,
        timeBasis = basis,
        intrinsics = null,
        gravityInCamera = null,
        gravityAgeNanos = null,
        phone = PhoneGeometrySettings.DEFAULT,
        quality = GeometryQuality.Ok,
    )

    private val observation = GroundObservation(
        frameTimeNanos = frameTime,
        validity = GroundFitValidity.VALID,
        groundMask = BinaryMask(2, 2),
        aboveGroundMask = BinaryMask(2, 2),
        belowGroundMask = BinaryMask(2, 2),
        disparityScale = 1f,
        disparityShift = 0f,
        inlierRatio = 1f,
        planeAgreesWithGravity = true,
        confidence = 1f,
    )

    private fun snapshot(
        state: DepthLaneState = DepthLaneState.RUNNING,
        observed: Boolean = true,
        observedAtNanos: Long? = frameTime,
        basis: TimeBasis = TimeBasis.CAMERA_REALTIME,
        quality: GeometryQuality = GeometryQuality.Ok,
        periodMs: Long = period,
        skip: String? = null,
    ) = DepthGeometrySnapshot(
        generation = 1,
        state = state,
        lastSkipReason = skip,
        runId = if (observed) 1 else null,
        observation = if (observed) observation else null,
        frameTimeNanos = if (observed) observedAtNanos else null,
        timeBasis = if (observed) basis else null,
        geometryQuality = if (observed) quality else null,
        periodMs = periodMs,
    )

    private fun status(snapshot: DepthGeometrySnapshot?, frame: CameraGeometry = frame()) = DepthGeometryCapability.status(snapshot, frame, maxAgePeriods = 2)

    private fun unavailable(vararg issues: String) = CapabilityStatus.Unavailable(issues.toSet())

    @Test
    fun `the rows, in order`() {
        val cases = listOf(
            "1 no session" to (status(null) to CapabilityStatus.NotConfigured),
            "1 no model" to (status(snapshot(DepthLaneState.NOT_CONFIGURED)) to CapabilityStatus.NotConfigured),
            "2 model outside the contract" to (status(snapshot(DepthLaneState.MODEL_UNREADABLE)) to unavailable(DepthGeometryCapability.MODEL_UNREADABLE)),
            "3 native preprocessing" to (status(snapshot(DepthLaneState.PREPROCESS_UNAVAILABLE)) to unavailable(DepthGeometryCapability.PREPROCESS_UNAVAILABLE)),
            "4 init failed" to (status(snapshot(DepthLaneState.INIT_FAILED)) to unavailable(DepthGeometryCapability.INIT_FAILED)),
            "4 run failed" to (status(snapshot(DepthLaneState.RUN_FAILED)) to unavailable(DepthGeometryCapability.RUN_FAILED)),
            "5 initializing" to (status(snapshot(DepthLaneState.INITIALIZING, observed = false)) to unavailable(DepthGeometryCapability.INITIALIZING)),
            "6 thermal" to (status(snapshot(DepthLaneState.PAUSED_THERMAL)) to unavailable(DepthGeometryCapability.PAUSED_THERMAL)),
            "6 yield" to (status(snapshot(DepthLaneState.PAUSED_YIELD)) to unavailable(DepthGeometryCapability.PAUSED_YIELD)),
            "7 no run yet" to (status(snapshot(observed = false)) to unavailable(DepthGeometryCapability.STALE, DepthGeometryCapability.NO_RUN_YET)),
            "7 no run yet, with the skip" to (
                status(snapshot(observed = false, skip = DepthGeometryLane.SKIP_NO_BASELINE)) to
                    unavailable(DepthGeometryCapability.STALE, DepthGeometryLane.SKIP_NO_BASELINE)
                ),
            "7 too old" to (status(snapshot(observedAtNanos = frameTime - 3 * period * 1_000_000L)) to unavailable(DepthGeometryCapability.STALE)),
            "8 degraded geometry" to (
                status(snapshot(quality = GeometryQuality.Degraded(setOf(GeometryIssue.entries.first())))) to
                    CapabilityStatus.Degraded(setOf(GeometryIssue.entries.first().name.lowercase()))
                ),
            "9 fresh" to (status(snapshot()) to CapabilityStatus.Available),
            "closed reads as not configured" to (status(snapshot(DepthLaneState.CLOSED)) to CapabilityStatus.NotConfigured),
        )
        for ((name, case) in cases) assertEquals(name, case.second, case.first)
    }

    @Test
    fun `a failed state wins over a fresh observation`() {
        assertEquals(unavailable(DepthGeometryCapability.RUN_FAILED), status(snapshot(DepthLaneState.RUN_FAILED)))
        assertEquals(unavailable(DepthGeometryCapability.PAUSED_THERMAL), status(snapshot(DepthLaneState.PAUSED_THERMAL)))
    }

    @Test
    fun `the age boundary is two periods by frame time, either way`() {
        val limit = 2 * period * 1_000_000L
        assertEquals(CapabilityStatus.Available, status(snapshot(observedAtNanos = frameTime - limit)))
        assertEquals(unavailable(DepthGeometryCapability.STALE), status(snapshot(observedAtNanos = frameTime - limit - 1)))
        // The depth frame can be newer than the main frame: a negative age, judged by its size.
        assertEquals(CapabilityStatus.Available, status(snapshot(observedAtNanos = frameTime + limit)))
        assertEquals(unavailable(DepthGeometryCapability.STALE), status(snapshot(observedAtNanos = frameTime + limit + 1)))
    }

    @Test
    fun `the period is the session's, so a throttled lane is judged by its own cadence`() {
        val age = 1_500L * 1_000_000L
        assertEquals(unavailable(DepthGeometryCapability.STALE), status(snapshot(observedAtNanos = frameTime - age)))
        assertEquals(CapabilityStatus.Available, status(snapshot(observedAtNanos = frameTime - age, periodMs = 1_000L)))
    }

    @Test
    fun `different time bases, or a missing time, are stale`() {
        assertEquals(unavailable(DepthGeometryCapability.STALE), status(snapshot(basis = TimeBasis.SOURCE_RECEIPT)))
        assertEquals(unavailable(DepthGeometryCapability.STALE), status(snapshot(), frame(timeNanos = null)))
        assertEquals(unavailable(DepthGeometryCapability.STALE), status(snapshot(observedAtNanos = null)))
        assertEquals(null, DepthGeometryCapability.ageNanos(snapshot(basis = TimeBasis.SOURCE_RECEIPT), frame()))
        assertEquals(-5L, DepthGeometryCapability.ageNanos(snapshot(observedAtNanos = frameTime + 5), frame()))
    }
}
