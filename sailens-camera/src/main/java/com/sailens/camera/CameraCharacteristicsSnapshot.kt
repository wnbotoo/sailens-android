package com.sailens.camera

/**
 * The camera facts that geometry and capture need, as plain values.
 *
 * Nothing here exposes Camera2 or CameraX types: the interop that reads them stays inside this
 * module, so a later CameraX API change (or another capture source) does not leak into callers.
 * Values are raw, in the units and coordinate systems Camera2 defines; mapping them into the
 * analysis image is the consumer's job (local-navigation-implementation §7, M3o).
 *
 * A snapshot belongs to one camera binding. Some logical cameras change `SENSOR_ORIENTATION` with
 * device state (API 32+), so it is read again on every bind and never cached across bindings.
 *
 * @param lensIntrinsicCalibration `LENS_INTRINSIC_CALIBRATION`: fx, fy, cx, cy, s in pixels of the
 *   pre-correction active array; null when the device does not report it.
 * @param lensDistortion `LENS_DISTORTION` coefficients; null when not reported.
 * @param availableFocalLengthsMm `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`; empty when not reported.
 */
public data class CameraCharacteristicsSnapshot(
    val cameraId: String,
    val capturedAtElapsedRealtimeNanos: Long,
    val sensorOrientationDegrees: Int?,
    val timestampSource: CameraTimestampSource,
    val lensIntrinsicCalibration: List<Float>?,
    val lensDistortion: List<Float>?,
    val lensPoseRotation: List<Float>?,
    val lensPoseTranslation: List<Float>?,
    val activeArray: PixelRect?,
    val preCorrectionActiveArray: PixelRect?,
    val pixelArrayWidth: Int?,
    val pixelArrayHeight: Int?,
    val physicalSizeWidthMm: Float?,
    val physicalSizeHeightMm: Float?,
    val availableFocalLengthsMm: List<Float>,
)

/**
 * `SENSOR_INFO_TIMESTAMP_SOURCE`. Only [REALTIME] frame timestamps can be compared with
 * `SystemClock.elapsedRealtimeNanos()` and sensor event timestamps; [UNKNOWN] timestamps are
 * monotonic but not guaranteed to align with the gyroscope.
 */
public enum class CameraTimestampSource { REALTIME, UNKNOWN, NOT_REPORTED }

/** A rectangle in sensor pixels, edges as Camera2 reports them (right/bottom exclusive). */
public data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The characteristics of the camera currently bound, or null when no camera is bound. */
public fun interface CameraCharacteristicsProvider {
    public fun currentSnapshot(): CameraCharacteristicsSnapshot?
}
