package com.sailens.guidance.world

import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.depth.geometry.ObservationId
import com.sailens.guidance.depth.geometry.ScaleBasis
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.motion.MotionState
import com.sailens.guidance.motion.OrientationQuality
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.vision.depth.DepthKind
import com.sailens.vision.depth.MeasurementKey
import com.sailens.guidance.depth.geometry.DepthUnit

internal enum class DepthRejection {
    NOT_CONFIGURED, UNAVAILABLE, DEGRADED, FIT_INVALID, FORMAT_UNPROVEN, TIME_UNPROVEN,
    SUPPORT_UNPROVEN, INPUT_UNPROVEN, INPUT_UNQUALIFIED, TRANSLATION_UNKNOWN, ROTATION_UNCERTAINTY_UNKNOWN,
}

internal data class RejectedDepth(
    val reasons: Set<DepthRejection>,
    val capabilityIssues: Set<String> = emptySet(),
    val fit: GroundFitValidity? = null,
    val observationId: ObservationId? = null,
    val key: MeasurementKey? = null,
    val headingDeltaDegrees: Float? = null,
)

/** The only depth input to the world builder. Its result type can express rejection and diagnostics. */
internal object DepthContributionPolicy {
    fun assess(
        reading: DepthReading,
        geometry: CameraGeometry,
        baseline: BaselineQualification,
        viewMotion: MotionState?,
        frameMotion: MotionState?,
    ): RejectedDepth {
        val fit = reading.observation?.validity
        val id = reading.observationId
        val times = reading.times
        val key = times?.measurementKey
        when (val status = reading.status) {
            CapabilityStatus.NotConfigured -> return notConfigured
            is CapabilityStatus.Unavailable -> return RejectedDepth(setOf(DepthRejection.UNAVAILABLE), status.issues, fit, id, key)
            is CapabilityStatus.Degraded -> return RejectedDepth(setOf(DepthRejection.DEGRADED), status.issues, fit, id, key)
            CapabilityStatus.Available -> Unit
        }
        if (fit != GroundFitValidity.VALID) return RejectedDepth(setOf(DepthRejection.FIT_INVALID), fit = fit, observationId = id, key = key)

        val view = reading.view
        val cells = reading.cells
        val reasons = mutableSetOf(
            DepthRejection.SUPPORT_UNPROVEN,
            if (baseline == BaselineQualification.Qualified) DepthRejection.INPUT_UNPROVEN else DepthRejection.INPUT_UNQUALIFIED,
            DepthRejection.TRANSLATION_UNKNOWN,
            DepthRejection.ROTATION_UNCERTAINTY_UNKNOWN,
        )
        val unitsMatch = when (reading.kind) {
            DepthKind.METRIC_DEPTH -> cells?.unit == DepthUnit.METERS && reading.scaleBasis == ScaleBasis.MEASURED
            DepthKind.RELATIVE_DISPARITY ->
                cells?.unit == DepthUnit.PHONE_HEIGHTS && reading.scaleBasis == ScaleBasis.ASSUMED_PHONE_HEIGHT
            null -> false
        }
        if (view == null || cells == null || cells.width != view.gridWidth || cells.height != view.gridHeight ||
            cells.width <= 0 || cells.height <= 0 || id == null || !unitsMatch
        ) reasons += DepthRejection.FORMAT_UNPROVEN

        val alignedTime = times?.viewTimeNanos != null && times.measurementTimeNanos != null &&
            times.readAtNanos != null && times.readAtNanos == geometry.frameTimeNanos &&
            geometry.timeBasis == TimeBasis.CAMERA_REALTIME && view?.timeBasis == geometry.timeBasis &&
            view.quality == GeometryQuality.Ok
        if (!alignedTime) reasons += DepthRejection.TIME_UNPROVEN
        val delta = if (alignedTime && viewMotion?.timestampNanos == times?.viewTimeNanos &&
            frameMotion?.timestampNanos == times?.readAtNanos
        ) headingDelta(viewMotion, frameMotion) else null
        return RejectedDepth(reasons, fit = fit, observationId = id, key = key, headingDeltaDegrees = delta)
    }

    private fun headingDelta(view: MotionState?, frame: MotionState?): Float? {
        if (view?.orientationQuality != OrientationQuality.OK || frame?.orientationQuality != OrientationQuality.OK) return null
        val from = view?.headingRad?.takeIf(Float::isFinite) ?: return null
        val to = frame?.headingRad?.takeIf(Float::isFinite) ?: return null
        val turn = 2.0 * Math.PI
        val delta = ((to.toDouble() - from + Math.PI) % turn + turn) % turn - Math.PI
        return Math.toDegrees(delta).toFloat()
    }

    private val notConfigured = RejectedDepth(setOf(DepthRejection.NOT_CONFIGURED))
}
