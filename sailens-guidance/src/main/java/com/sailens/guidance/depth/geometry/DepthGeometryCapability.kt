package com.sailens.guidance.depth.geometry

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.safety.CapabilityStatus
import kotlin.math.abs

/**
 * The `DEPTH_GEOMETRY` capability for one main-pipeline frame (docs/geometry.md §6), from the frame's own
 * session snapshot, first matching row wins:
 * 1. no session (switched off, or the host has no lane), or no depth model: not configured;
 * 2. a model outside the contract, or a source that refused itself: unavailable, `model_unreadable`, or the source's
 *    reason (`mapping`);
 * 3. the native preprocessing failed on the last attempt: `preprocess_unavailable`;
 * 4. initialization failed, or too many runs in a row failed: `init_failed` / `run_failed`;
 * 5. still initializing (including the wait for an earlier session's teardown): `initializing`;
 * 6. paused for heat or for a higher-priority GPU user: `paused_thermal` / `paused_yield`;
 * 7. no observation yet, or the latest is older than [DepthGeometryConfig.maxAgePeriods] periods by measurement
 *    time (or on another time basis, or with no measurement time): `stale`, with the last skip reason when there is
 *    one -- a source that keeps measuring nothing says `no_measurement` here, while its last observation ages as it
 *    would;
 * 8. a fresh observation on degraded geometry: degraded, with the geometry's issues;
 * 9. a fresh observation: available.
 * What the observation found (no ground, inverted output, a support switch) is its content, not a failure
 * of the capability. Reads no clock: the age is the difference of the two frames' times.
 */
object DepthGeometryCapability {

    fun status(snapshot: DepthGeometrySnapshot?, frame: CameraGeometry, maxAgePeriods: Int): CapabilityStatus {
        if (snapshot == null) return CapabilityStatus.NotConfigured
        when (snapshot.state) {
            DepthLaneState.NOT_CONFIGURED -> return CapabilityStatus.NotConfigured
            DepthLaneState.MODEL_UNREADABLE -> return unavailable(MODEL_UNREADABLE)
            DepthLaneState.SOURCE_UNAVAILABLE -> return unavailable(snapshot.stateIssue ?: SOURCE_UNAVAILABLE)
            DepthLaneState.PREPROCESS_UNAVAILABLE -> return unavailable(PREPROCESS_UNAVAILABLE)
            DepthLaneState.INIT_FAILED -> return unavailable(INIT_FAILED)
            DepthLaneState.RUN_FAILED -> return unavailable(RUN_FAILED)
            DepthLaneState.INITIALIZING -> return unavailable(INITIALIZING)
            DepthLaneState.PAUSED_THERMAL -> return unavailable(PAUSED_THERMAL)
            DepthLaneState.PAUSED_YIELD -> return unavailable(PAUSED_YIELD)
            DepthLaneState.CLOSED -> return CapabilityStatus.NotConfigured
            DepthLaneState.RUNNING -> Unit
        }
        val age = ageNanos(snapshot, frame)
        if (snapshot.observation == null || age == null || abs(age) > maxAgePeriods * snapshot.periodMs * 1_000_000L) {
            val reason = snapshot.lastSkipReason ?: if (snapshot.observation == null) NO_RUN_YET else null
            return CapabilityStatus.Unavailable(setOfNotNull(STALE, reason))
        }
        return when (val quality = snapshot.geometryQuality) {
            is GeometryQuality.Degraded -> CapabilityStatus.Degraded(quality.issues.mapTo(LinkedHashSet()) { it.name.lowercase() })
            else -> CapabilityStatus.Available
        }
    }

    /**
     * Main frame time minus the observation's measurement time (a model's is its frame's); null when either is missing
     * or they are on different bases.
     */
    fun ageNanos(snapshot: DepthGeometrySnapshot, frame: CameraGeometry): Long? {
        val observed = snapshot.measurementTimeNanos ?: return null
        val now = frame.frameTimeNanos ?: return null
        if (snapshot.timeBasis != frame.timeBasis) return null
        return now - observed
    }

    private fun unavailable(issue: String) = CapabilityStatus.Unavailable(setOf(issue))

    const val MODEL_UNREADABLE: String = "model_unreadable"
    const val PREPROCESS_UNAVAILABLE: String = "preprocess_unavailable"
    const val INIT_FAILED: String = "init_failed"
    const val RUN_FAILED: String = "run_failed"
    const val INITIALIZING: String = "initializing"
    const val PAUSED_THERMAL: String = "paused_thermal"
    const val PAUSED_YIELD: String = "paused_yield"
    const val SOURCE_UNAVAILABLE: String = "source_unavailable"
    const val STALE: String = "stale"
    const val NO_RUN_YET: String = "no_run_yet"
}
