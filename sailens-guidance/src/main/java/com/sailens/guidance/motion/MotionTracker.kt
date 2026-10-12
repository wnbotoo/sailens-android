package com.sailens.guidance.motion

import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The most recent sensor samples, for looking up the one nearest a frame. Written from the sensor
 * thread, read from the pipeline; a small fixed ring, no allocation per sample beyond the sample.
 */
class SensorSampleBuffer(private val capacity: Int = 64) {
    private val samples = arrayOfNulls<SensorSample>(capacity)
    private var next = 0
    private var size = 0

    @Synchronized
    fun add(sample: SensorSample) {
        samples[next] = sample
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** The sample whose timestamp is nearest [timeNanos], or null when empty. */
    @Synchronized
    fun nearest(timeNanos: Long): SensorSample? {
        var best: SensorSample? = null
        for (i in 0 until size) {
            val sample = samples[i] ?: continue
            if (best == null || abs(sample.timestampNanos - timeNanos) < abs(best.timestampNanos - timeNanos)) best = sample
        }
        return best
    }

    @Synchronized
    fun latest(): SensorSample? = if (size == 0) null else samples[(next - 1 + capacity) % capacity]

    @Synchronized
    fun clear() {
        samples.fill(null)
        next = 0
        size = 0
    }
}

enum class OrientationQuality {
    OK,

    /** The camera points nearly straight up or down, so heading is carried over, not measured. */
    DEGRADED,

    /** No rotation sample, or none recent. */
    UNAVAILABLE,
}

/**
 * Rotation-only motion (§3, M3o): translation is unknown here.
 *
 * @param headingRad the camera's horizontal heading in the game-rotation-vector world frame, whose
 *   zero is arbitrary per session. It is where the **phone** points, not where the user walks.
 *   Increases when the phone turns right (§2's yaw sign).
 * @param yawRateRadPerS rate of change of [headingRad], same sign.
 */
data class MotionState(
    val timestampNanos: Long,
    val headingRad: Float,
    val yawRateRadPerS: Float,
    val isStationary: Boolean,
    val orientationQuality: OrientationQuality,
)

/**
 * Turns `TYPE_GAME_ROTATION_VECTOR` samples into [MotionState]. Pure: the Android sensor source
 * feeds it, a recording or a simulator can feed it the same way.
 *
 * @param maxSampleAgeNanos a state asked for longer than this after the last sample is UNAVAILABLE.
 */
class MotionTracker(private val maxSampleAgeNanos: Long = 200_000_000L) {
    private var heading: Double? = null

    /** When [heading] was measured; a rate needs two consecutive measurements. */
    private var headingAt = 0L
    private var lastSampleAt = 0L
    private var yawRate = 0.0
    private var degenerate = false

    @Synchronized
    fun onGameRotationVector(timestampNanos: Long, values: FloatArray) {
        val forward = cameraForwardInWorld(values) ?: return
        lastSampleAt = timestampNanos
        val horizontal = sqrt(forward.x * forward.x + forward.y * forward.y)
        if (horizontal < MIN_HORIZONTAL) {
            // Pointing (nearly) straight up or down: the projected axis is noise. Keep the last
            // heading rather than inventing one (§2, degenerate case), and claim no rate.
            degenerate = true
            yawRate = 0.0
            return
        }
        // atan2(east, north): clockwise seen from above, so turning right increases it.
        val measured = atan2(forward.x.toDouble(), forward.y.toDouble())
        val previous = heading
        // No rate across a degenerate stretch: the turn there was not observed.
        yawRate = if (previous != null && !degenerate && timestampNanos > headingAt) {
            wrap(measured - previous) / ((timestampNanos - headingAt) / 1e9)
        } else {
            0.0
        }
        degenerate = false
        heading = measured
        headingAt = timestampNanos
    }

    @Synchronized
    fun state(nowNanos: Long, isStationary: Boolean): MotionState {
        val current = heading
        val quality = when {
            current == null || nowNanos - lastSampleAt > maxSampleAgeNanos -> OrientationQuality.UNAVAILABLE
            degenerate -> OrientationQuality.DEGRADED
            else -> OrientationQuality.OK
        }
        return MotionState(
            timestampNanos = nowNanos,
            headingRad = (current ?: 0.0).toFloat(),
            yawRateRadPerS = if (quality == OrientationQuality.UNAVAILABLE) 0f else yawRate.toFloat(),
            isStationary = isStationary,
            orientationQuality = quality,
        )
    }

    @Synchronized
    fun reset() {
        heading = null
        headingAt = 0L
        lastSampleAt = 0L
        yawRate = 0.0
        degenerate = false
    }

    companion object {
        /** Below this horizontal length (about 10 degrees from vertical) heading is not measured. */
        private const val MIN_HORIZONTAL = 0.17f

        /**
         * The back camera's optical axis (device -z) in the rotation vector's world frame (x east,
         * y north, z up; arbitrary zero for the game variant). Values are the quaternion's x, y, z
         * and, when reported, w.
         */
        fun cameraForwardInWorld(values: FloatArray): Vec3? {
            if (values.size < 3) return null
            val x = values[0].toDouble()
            val y = values[1].toDouble()
            val z = values[2].toDouble()
            val w = if (values.size >= 4) values[3].toDouble() else sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
            // Third column of the device-to-world rotation matrix, negated.
            return Vec3(
                (-(2 * x * z + 2 * y * w)).toFloat(),
                (-(2 * y * z - 2 * x * w)).toFloat(),
                (-(1 - 2 * x * x - 2 * y * y)).toFloat(),
            )
        }

        private fun wrap(angle: Double): Double {
            var a = angle
            while (a > PI) a -= 2 * PI
            while (a < -PI) a += 2 * PI
            return a
        }
    }
}
