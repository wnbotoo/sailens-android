package com.sailens.guidance.world

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.TimeBasis
import kotlin.math.hypot
import kotlin.math.sin

internal data class CameraVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: CameraVector) = CameraVector(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: CameraVector) = CameraVector(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = CameraVector(x * scale, y * scale, z * scale)
    operator fun unaryMinus() = CameraVector(-x, -y, -z)

    fun dot(other: CameraVector): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: CameraVector) = CameraVector(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )
}

internal data class LocalGroundPoint(val forward: Double, val right: Double, val up: Double)

/** Axes and nominal ground origin in upright analysis camera coordinates. */
internal class LocalGroundFrame private constructor(
    val frameTimeNanos: Long,
    val geometry: CameraGeometry,
    val origin: CameraVector,
    val forward: CameraVector,
    val right: CameraVector,
    val up: CameraVector,
) {
    fun locate(point: CameraVector): LocalGroundPoint {
        val offset = point - origin
        return LocalGroundPoint(offset.dot(forward), offset.dot(right), offset.dot(up))
    }

    fun pointOnPlane(forwardMeters: Double, rightMeters: Double): CameraVector =
        origin + forward * forwardMeters + right * rightMeters

    enum class Issue {
        FRAME_TIME,
        TIME_BASIS,
        INTRINSICS,
        GRAVITY,
        HEIGHT,
        HEIGHT_SOURCE,
        GEOMETRY_QUALITY,
        VERTICAL_VIEW,
    }

    sealed interface Result {
        data class Resolved(val frame: LocalGroundFrame) : Result
        data class Unavailable(val geometry: CameraGeometry, val issue: Issue) : Result
    }

    companion object {
        private val minForwardLength = sin(Math.toRadians(10.0))

        /** Resolves nominal geometry. The spatial admission checks for the analysis image belong at the builder. */
        fun resolve(geometry: CameraGeometry): Result {
            val time = geometry.frameTimeNanos?.takeIf { it > 0 }
                ?: return Result.Unavailable(geometry, Issue.FRAME_TIME)
            if (geometry.timeBasis != TimeBasis.CAMERA_REALTIME) {
                return Result.Unavailable(geometry, Issue.TIME_BASIS)
            }

            val lens = geometry.intrinsics
            if (lens == null || lens.width <= 0 || lens.height <= 0 ||
                !lens.fx.isFinite() || !lens.fy.isFinite() || lens.fx <= 0 || lens.fy <= 0 ||
                !lens.cx.isFinite() || !lens.cy.isFinite() ||
                lens.source != IntrinsicsSource.CALIBRATION || lens.bufferMappingAssumed
            ) {
                return Result.Unavailable(geometry, Issue.INTRINSICS)
            }

            val gravity = geometry.gravityInCamera ?: return Result.Unavailable(geometry, Issue.GRAVITY)
            val horizontal = hypot(gravity.x.toDouble(), gravity.y.toDouble())
            val magnitude = hypot(horizontal, gravity.z.toDouble())
            if (!magnitude.isFinite() || magnitude == 0.0) {
                return Result.Unavailable(geometry, Issue.GRAVITY)
            }

            val height = geometry.phone.heightMeters.toDouble()
            if (!height.isFinite() || height <= 0.0) {
                return Result.Unavailable(geometry, Issue.HEIGHT)
            }
            if (geometry.phone.source == HeightSource.DEFAULT_SEED) {
                return Result.Unavailable(geometry, Issue.HEIGHT_SOURCE)
            }
            if (geometry.quality != GeometryQuality.Ok) {
                return Result.Unavailable(geometry, Issue.GEOMETRY_QUALITY)
            }

            val forwardLength = horizontal / magnitude
            if (forwardLength < minForwardLength) {
                return Result.Unavailable(geometry, Issue.VERTICAL_VIEW)
            }
            val down = CameraVector(gravity.x / magnitude, gravity.y / magnitude, gravity.z / magnitude)
            val up = -down
            // Normalize the optical axis projected onto the plane orthogonal to gravity.
            val forward = CameraVector(-down.z * down.x / forwardLength, -down.z * down.y / forwardLength, forwardLength)
            return Result.Resolved(LocalGroundFrame(time, geometry, down * height, forward, forward.cross(up), up))
        }
    }
}
