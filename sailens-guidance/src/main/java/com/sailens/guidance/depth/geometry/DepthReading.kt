package com.sailens.guidance.depth.geometry

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.vision.depth.DepthKind
import com.sailens.vision.depth.MeasurementKey

/** Where a reading's lengths get their scale. */
enum class ScaleBasis {
    /** Relative depth: lengths are in units of the phone height the settings assume, which nothing measured. */
    ASSUMED_PHONE_HEIGHT,

    /** Metric depth: lengths are the source's measurements, in metres. */
    MEASURED,
}

/** The scale a source's lengths have. */
internal fun scaleBasisOf(kind: DepthKind): ScaleBasis = when (kind) {
    DepthKind.RELATIVE_DISPARITY -> ScaleBasis.ASSUMED_PHONE_HEIGHT
    DepthKind.METRIC_DEPTH -> ScaleBasis.MEASURED
}

/**
 * Metric depth measures the phone's height: the fitted ground's `1/A`. This sets it beside the one the settings
 * assume, for the trace, the debug panel and a reader. It changes nothing: the settings, the ground-contact distance
 * and every prompt keep the assumed height. A relative reading has none, which is not a check passed.
 *
 * @param relativeDifference `(measured − setting) / setting`.
 */
data class PhoneHeightCheck(
    val measuredM: Float,
    val settingM: Float,
    val settingSource: HeightSource,
    val relativeDifference: Float,
) {
    companion object {
        fun of(measuredM: Float, setting: PhoneGeometrySettings): PhoneHeightCheck =
            PhoneHeightCheck(measuredM, setting.heightMeters, setting.source, (measuredM - setting.heightMeters) / setting.heightMeters)
    }
}

/** Which run of which depth session an observation came from: for reconciling records and traces, never for counting evidence. */
data class ObservationId(val generation: Long, val runId: Long)

/**
 * A reading's times, on the main pipeline's time basis ([CameraGeometry.timeBasis]), nanoseconds.
 *
 * @param measurementTimeNanos when the depth was measured; the age counts from it.
 * @param viewTimeNanos the moment of the camera view the observation's cells belong to: its geometry is that moment's.
 * @param measurementKey which measurement: evidence is counted once per key, however many main frames, runs or views
 *   carry it. For a model every run is a new measurement, keyed by its run.
 * @param readAtNanos the main frame that read it.
 * @param ageNanos [readAtNanos] minus [measurementTimeNanos]; null when either is missing or their bases differ.
 * @param maxAgeNanos beyond this age the capability calls the observation stale.
 */
data class DepthReadingTimes(
    val measurementTimeNanos: Long?,
    val viewTimeNanos: Long?,
    val measurementKey: MeasurementKey,
    val readAtNanos: Long?,
    val ageNanos: Long?,
    val maxAgeNanos: Long,
)

/**
 * What one main-pipeline frame reads of the depth lane: the `DEPTH_GEOMETRY` capability, and the latest observation
 * with everything needed to place its cells -- who made it and when, the geometry of its view, the fit and the cells'
 * own depths -- without the depth map, the source or its model. One per main frame; frames that read the same run
 * share its observation, view and cells, which are never copied and never written.
 *
 * Facts, not trust. An observation that fits says this frame's candidate plane fits; it does not say the plane is the
 * one under the user, that the picture shows real ground, or that the cells still line up with the camera now. Nothing
 * reads a reading to decide anything yet.
 *
 * @param kind the source's kind; null until it has published an observation.
 * @param phoneHeight metric depth with a valid fit: the measured phone height against the setting.
 */
data class DepthReading(
    val status: CapabilityStatus,
    val kind: DepthKind?,
    val scaleBasis: ScaleBasis?,
    val observationId: ObservationId?,
    val times: DepthReadingTimes?,
    val view: DepthViewGeometry?,
    val observation: GroundObservation?,
    val cells: DepthCells?,
    val phoneHeight: PhoneHeightCheck? = null,
) {
    companion object {
        /** No depth session: nothing to read. */
        val NOT_CONFIGURED: DepthReading = DepthReading(CapabilityStatus.NotConfigured, null, null, null, null, null, null, null)

        /**
         * The reading of [snapshot] by the main frame [frame], whose capability is [status] (`DepthGeometryCapability`).
         * Reads no clock.
         */
        fun of(snapshot: DepthGeometrySnapshot?, status: CapabilityStatus, frame: CameraGeometry, maxAgePeriods: Int): DepthReading {
            if (snapshot == null) return NOT_CONFIGURED.copy(status = status)
            val observation = snapshot.observation
            val runId = snapshot.runId
            val key = snapshot.measurementKey
            if (observation == null || runId == null || key == null) {
                return DepthReading(status, snapshot.kind, snapshot.kind?.let(::scaleBasisOf), null, null, null, null, null)
            }
            return DepthReading(
                status = status,
                kind = snapshot.kind,
                scaleBasis = snapshot.kind?.let(::scaleBasisOf),
                observationId = ObservationId(snapshot.generation, runId),
                times = DepthReadingTimes(
                    measurementTimeNanos = snapshot.measurementTimeNanos,
                    viewTimeNanos = snapshot.viewTimeNanos,
                    measurementKey = key,
                    readAtNanos = frame.frameTimeNanos,
                    ageNanos = DepthGeometryCapability.ageNanos(snapshot, frame),
                    maxAgeNanos = maxAgePeriods * snapshot.periodMs * 1_000_000L,
                ),
                view = snapshot.view,
                observation = observation,
                cells = snapshot.cells,
                phoneHeight = snapshot.phoneHeight,
            )
        }
    }
}

/**
 * The geometry an observation was made with, at its view time: the analysis image's intrinsics (its size is theirs),
 * the unit down vector in camera axes, the label grid over the analysis image, and the phone the settings describe.
 * With it every cell's ray, and so its position, follows from the cell alone.
 */
data class DepthViewGeometry(
    val intrinsics: CameraIntrinsics,
    val gravityDown: Vec3,
    val gridWidth: Int,
    val gridHeight: Int,
    /** The phone height the settings assume and where it came from (preset, calibration); a relative reading's unit. */
    val phone: PhoneGeometrySettings,
    val timeBasis: TimeBasis,
    val quality: GeometryQuality,
) {
    private val down = gravityDown.normalized()

    val analysisWidth: Int get() = intrinsics.width
    val analysisHeight: Int get() = intrinsics.height

    /** Cell (x, y)'s centre in analysis-image pixels, the point the ground fit labels it by. */
    fun cellCenterU(x: Int): Float = (x + 0.5f) * intrinsics.width / gridWidth
    fun cellCenterV(y: Int): Float = (y + 0.5f) * intrinsics.height / gridHeight

    /** The ray through cell (x, y)'s centre in camera axes (x right, y down, z forward), z = 1. */
    fun cellRay(x: Int, y: Int): Vec3 =
        Vec3((cellCenterU(x) - intrinsics.cx) / intrinsics.fx, (cellCenterV(y) - intrinsics.cy) / intrinsics.fy, 1f)

    /** `q = g·r` for cell (x, y)'s centre ray, as the ground fit computes it: on flat ground at height h, `Z = h / q`. */
    fun cellQ(x: Int, y: Int): Float = q(cellCenterU(x), cellCenterV(y))

    internal fun q(u: Float, v: Float): Float =
        down.x * (u - intrinsics.cx) / intrinsics.fx + down.y * (v - intrinsics.cy) / intrinsics.fy + down.z
}
