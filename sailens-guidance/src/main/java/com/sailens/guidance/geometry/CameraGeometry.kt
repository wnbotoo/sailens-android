package com.sailens.guidance.geometry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 = length.let { Vec3(x / it, y / it, z / it) }
}

/**
 * Converts Android device-frame gravity into the camera frame of the upright analysis image
 * (docs/geometry.md §1):
 * x right, y down, z forward. Back camera only.
 */
object GravityInCamera {

    /**
     * @param deviceGravity `TYPE_GRAVITY` values in device axes (x right, y up, z out of the screen
     *   in the natural orientation). The sensor reports the reaction to gravity, which points up.
     * @param sensorOrientationDegrees `SENSOR_ORIENTATION`.
     * @param rotationDegrees the frame's rotation to upright.
     * @return the unit "down" vector in camera axes, or null for a zero vector or a rotation that is
     *   not a multiple of 90.
     */
    fun resolve(deviceGravity: Vec3, sensorOrientationDegrees: Int, rotationDegrees: Int): Vec3? {
        if (deviceGravity.length < 1e-3f) return null
        val down = deviceGravity.normalized().let { Vec3(-it.x, -it.y, -it.z) }
        // How far the upright image is turned from the device's natural orientation: the back
        // camera's sensor is mounted at sensorOrientation, and the frame is rotated by rotation.
        val turn = ((sensorOrientationDegrees - rotationDegrees) % 360 + 360) % 360
        // Image right and image down, in device axes, for each turn of the device.
        val (right, imageDown) = when (turn) {
            0 -> Vec3(1f, 0f, 0f) to Vec3(0f, -1f, 0f)
            90 -> Vec3(0f, -1f, 0f) to Vec3(-1f, 0f, 0f)
            180 -> Vec3(-1f, 0f, 0f) to Vec3(0f, 1f, 0f)
            270 -> Vec3(0f, 1f, 0f) to Vec3(1f, 0f, 0f)
            else -> return null
        }
        val forward = Vec3(0f, 0f, -1f)
        return Vec3(dot(down, right), dot(down, imageDown), dot(down, forward))
    }

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
}

/** Which clock the frame time was taken from (docs/geometry.md §1). */
enum class TimeBasis {
    /** The camera timestamp, which shares the sensors' elapsed-realtime base. */
    CAMERA_REALTIME,

    /** The camera's base is unknown; the source-side receipt time stands in. Alignment unmeasured. */
    SOURCE_RECEIPT,
}

enum class GeometryIssue {
    /** No usable gravity sample near the frame. */
    NO_GRAVITY,

    /** The nearest gravity sample is older than the bound. */
    STALE_GRAVITY,

    /** The frame time comes from receipt, so its alignment with the sensors is unmeasured. */
    UNMEASURED_ALIGNMENT,

    /** No frame time to align with. */
    NO_FRAME_TIME,

    /** Neither calibration nor focal length and sensor size were reported. */
    NO_INTRINSICS,

    /** The focal length is an estimate, or the buffer mapping was assumed. */
    ESTIMATED_INTRINSICS,

    /** The sensor orientation was not reported. */
    NO_SENSOR_ORIENTATION,
}

sealed interface GeometryQuality {
    data object Ok : GeometryQuality

    /** Usable, with a known weakness a consumer may weigh. */
    data class Degraded(val issues: Set<GeometryIssue>) : GeometryQuality

    /** Not usable: consumers keep today's behaviour. */
    data class Unavailable(val issues: Set<GeometryIssue>) : GeometryQuality
}

/**
 * The camera's geometry for one frame (docs/geometry.md §3). [gravityInCamera] and [intrinsics] are null exactly
 * when the quality is [GeometryQuality.Unavailable] for that reason.
 */
data class CameraGeometry(
    val frameTimeNanos: Long?,
    val timeBasis: TimeBasis,
    val intrinsics: CameraIntrinsics?,
    val gravityInCamera: Vec3?,
    /** Frame time minus the gravity sample's time; negative when the sample is newer. */
    val gravityAgeNanos: Long?,
    val phone: PhoneGeometrySettings,
    val quality: GeometryQuality,
) {
    /** Camera pitch below the horizon, degrees: positive when the camera looks down. */
    val pitchDownDegrees: Float?
        get() = gravityInCamera?.let { Math.toDegrees(asin(it.z.coerceIn(-1f, 1f).toDouble())).toFloat() }
}

/** A timestamped sensor vector, elapsed-realtime nanoseconds. */
data class SensorSample(val timestampNanos: Long, val values: Vec3)

/**
 * Assembles [CameraGeometry] for a frame from the raw facts, the nearest gravity sample and the
 * phone settings, and rates it.
 *
 * @param maxGravityAgeNanos beyond this distance between frame and sample the gravity is stale.
 *   Gravity changes slowly while walking, so 100 ms is generous for direction yet still rejects a
 *   sensor that has stopped.
 */
class CameraGeometryResolver(
    private val maxGravityAgeNanos: Long = 100_000_000L,
) {
    fun resolve(
        camera: CameraFacts?,
        frame: FrameFacts,
        nearestGravity: (timeNanos: Long) -> SensorSample?,
        phone: PhoneGeometrySettings,
    ): CameraGeometry {
        val fatal = mutableSetOf<GeometryIssue>()
        val weak = mutableSetOf<GeometryIssue>()

        val basis = if (camera?.clock == CameraClock.REALTIME) TimeBasis.CAMERA_REALTIME else TimeBasis.SOURCE_RECEIPT
        val frameTime = when (basis) {
            TimeBasis.CAMERA_REALTIME -> frame.sensorTimestampNanos
            TimeBasis.SOURCE_RECEIPT -> frame.receivedElapsedRealtimeNanos.takeIf { it > 0 }
        }
        if (basis == TimeBasis.SOURCE_RECEIPT) weak += GeometryIssue.UNMEASURED_ALIGNMENT
        if (frameTime == null) fatal += GeometryIssue.NO_FRAME_TIME

        val intrinsics = camera?.let { CameraIntrinsicsResolver.resolve(it, frame) }
        when {
            intrinsics == null -> fatal += GeometryIssue.NO_INTRINSICS
            intrinsics.source != IntrinsicsSource.CALIBRATION || intrinsics.bufferMappingAssumed ->
                weak += GeometryIssue.ESTIMATED_INTRINSICS
        }

        val orientation = camera?.sensorOrientationDegrees
        if (orientation == null) fatal += GeometryIssue.NO_SENSOR_ORIENTATION

        val sample = frameTime?.let(nearestGravity)
        val age = sample?.let { frameTime - it.timestampNanos }
        val gravity = if (sample != null && orientation != null) {
            GravityInCamera.resolve(sample.values, orientation, frame.rotationDegrees)
        } else {
            null
        }
        when {
            gravity == null -> if (frameTime != null) fatal += GeometryIssue.NO_GRAVITY
            abs(age ?: 0) > maxGravityAgeNanos -> weak += GeometryIssue.STALE_GRAVITY
        }

        val quality = when {
            fatal.isNotEmpty() -> GeometryQuality.Unavailable(fatal + weak)
            weak.isNotEmpty() -> GeometryQuality.Degraded(weak)
            else -> GeometryQuality.Ok
        }
        return CameraGeometry(frameTime, basis, intrinsics, gravity, age, phone, quality)
    }
}
