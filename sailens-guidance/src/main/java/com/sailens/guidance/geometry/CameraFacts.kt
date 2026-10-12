package com.sailens.guidance.geometry

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.trace.capture.CaptureCameraRecord
import com.sailens.guidance.trace.capture.FrameRecord

/*
 * The raw facts M3o resolves (local-navigation-implementation §3, §7), in the form both sources
 * share: a live camera binding and a recorded capture. Resolving a recording takes exactly the same
 * path as resolving the live camera, so old captures stay usable.
 */

/** `SENSOR_INFO_TIMESTAMP_SOURCE` as the camera reported it. */
enum class CameraClock { REALTIME, UNKNOWN, NOT_REPORTED }

/** A rectangle in sensor pixels, right/bottom exclusive, as Camera2 reports it. */
data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Per-binding camera facts. Only the back camera is supported: the axis conventions in
 * [GravityInCamera] assume it.
 *
 * @param lensIntrinsicCalibration fx, fy, cx, cy, s in pixels of the pre-correction active array.
 */
data class CameraFacts(
    val sensorOrientationDegrees: Int?,
    val clock: CameraClock,
    val lensIntrinsicCalibration: List<Float>?,
    val activeArray: SensorRect?,
    val preCorrectionActiveArray: SensorRect?,
    val pixelArrayWidth: Int?,
    val pixelArrayHeight: Int?,
    val physicalSizeWidthMm: Float?,
    val physicalSizeHeightMm: Float?,
    val availableFocalLengthsMm: List<Float>,
)

/**
 * The facts of the camera bound now, or null when none is bound. The host that owns the camera
 * provides it; a host that does not leaves Guidance without geometry, which is a legal state.
 */
fun interface CameraFactsSource {
    fun current(): CameraFacts?
}

/**
 * Per-frame facts: the buffer the models see (the whole buffer -- the pipeline does not apply the
 * source's crop rectangle) and how it maps to the sensor.
 *
 * @param sensorTimestampNanos the camera timestamp, in the base [CameraFacts.clock] names.
 * @param receivedElapsedRealtimeNanos taken at the source before any queueing; 0 when unknown.
 * @param sensorToBufferTransform 3×3 row-major, active-array coordinates → buffer pixels.
 */
data class FrameFacts(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val sensorTimestampNanos: Long,
    val receivedElapsedRealtimeNanos: Long,
    val sensorToBufferTransform: List<Float>?,
) {
    companion object {
        fun of(frame: ImageFrame) = FrameFacts(
            width = frame.width,
            height = frame.height,
            rotationDegrees = frame.rotationDegrees,
            sensorTimestampNanos = frame.timestamp,
            receivedElapsedRealtimeNanos = frame.receivedElapsedRealtimeNanos,
            sensorToBufferTransform = frame.sourceGeometry?.sensorToBufferTransform,
        )

        /** A recorded frame: its source size, not the size the capture stored it at. */
        fun of(record: FrameRecord) = FrameFacts(
            width = record.sourceWidth,
            height = record.sourceHeight,
            rotationDegrees = record.rotationDegrees,
            sensorTimestampNanos = record.sensorTimestampNanos,
            receivedElapsedRealtimeNanos = record.receivedElapsedRealtimeNanos,
            sensorToBufferTransform = record.sensorToBufferTransform,
        )
    }
}

/** The camera facts a capture recorded. */
fun CaptureCameraRecord.toCameraFacts() = CameraFacts(
    sensorOrientationDegrees = sensorOrientationDegrees,
    clock = when (timestampSource) {
        "realtime" -> CameraClock.REALTIME
        "unknown" -> CameraClock.UNKNOWN
        else -> CameraClock.NOT_REPORTED
    },
    lensIntrinsicCalibration = lensIntrinsicCalibration,
    activeArray = activeArray?.toSensorRect(),
    preCorrectionActiveArray = preCorrectionActiveArray?.toSensorRect(),
    pixelArrayWidth = pixelArrayWidth,
    pixelArrayHeight = pixelArrayHeight,
    physicalSizeWidthMm = physicalSizeWidthMm,
    physicalSizeHeightMm = physicalSizeHeightMm,
    availableFocalLengthsMm = availableFocalLengthsMm,
)

private fun List<Int>.toSensorRect(): SensorRect? = if (size == 4) SensorRect(this[0], this[1], this[2], this[3]) else null
