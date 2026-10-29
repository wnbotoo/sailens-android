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
 * Rotation-only motion (docs/geometry.md §3): translation is unknown here.
 *
 * @param headingRad the camera's horizontal heading in the game-rotation-vector world frame, whose
 *   zero is arbitrary per session. It is where the **phone** points, not where the user walks.
 *   Increases when the phone turns right.
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
 * It keeps the last [capacity] samples and answers for a given time -- a camera frame's, which by
 * the time the pipeline asks is tens of milliseconds old -- not just with the newest sample: the
 * heading is interpolated between the samples either side of that time.
 *
 * @param maxSampleAgeNanos the state is UNAVAILABLE when no sample is this close to the time asked
 *   for, on either side.
 */
class MotionTracker(
    private val maxSampleAgeNanos: Long = 200_000_000L,
    private val capacity: Int = 128,
) {
    /**
     * One rotation sample. [headingRad] is carried over from the last measured sample when
     * [measured] is false (the camera pointed nearly straight up or down). [yawRateRadPerS] is from
     * the previous sample, and 0 across a degenerate stretch: the turn there was not observed.
     */
    private class Sample(val timestampNanos: Long, val headingRad: Double, val measured: Boolean, val yawRateRadPerS: Double)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun onGameRotationVector(timestampNanos: Long, values: FloatArray) {
        val forward = cameraForwardInWorld(values) ?: return
        val previous = samples.lastOrNull()
        val horizontal = sqrt(forward.x * forward.x + forward.y * forward.y)
        val sample = if (horizontal < MIN_HORIZONTAL) {
            // Pointing (nearly) straight up or down: the projected axis is noise. Keep the last
            // heading rather than inventing one, and claim no rate.
            previous?.let { Sample(timestampNanos, it.headingRad, measured = false, yawRateRadPerS = 0.0) } ?: return
        } else {
            // atan2(east, north): clockwise seen from above, so turning right increases it.
            val measured = atan2(forward.x.toDouble(), forward.y.toDouble())
            val rate = if (previous != null && previous.measured && timestampNanos > previous.timestampNanos) {
                wrap(measured - previous.headingRad) / ((timestampNanos - previous.timestampNanos) / 1e9)
            } else {
                0.0
            }
            Sample(timestampNanos, measured, measured = true, yawRateRadPerS = rate)
        }
        samples.addLast(sample)
        while (samples.size > capacity) samples.removeFirst()
    }

    /** The motion at [atNanos] (elapsed realtime, the sensors' base): a frame's time, not "now". */
    @Synchronized
    fun state(atNanos: Long, isStationary: Boolean): MotionState {
        // The newest sample at or before the time, and the first one after it.
        var before: Sample? = null
        var after: Sample? = null
        for (sample in samples) {
            if (sample.timestampNanos <= atNanos) before = sample else if (after == null) after = sample
        }
        val nearestGap = minOf(
            before?.let { atNanos - it.timestampNanos } ?: Long.MAX_VALUE,
            after?.let { it.timestampNanos - atNanos } ?: Long.MAX_VALUE,
        )
        if (nearestGap > maxSampleAgeNanos) {
            return MotionState(atNanos, 0f, 0f, isStationary, OrientationQuality.UNAVAILABLE)
        }
        val (heading, rate, measured) = when {
            before != null && after != null && before.measured && after.measured -> {
                val span = after.timestampNanos - before.timestampNanos
                val turn = wrap(after.headingRad - before.headingRad)
                val f = (atNanos - before.timestampNanos).toDouble() / span
                Triple(wrap(before.headingRad + turn * f), turn / (span / 1e9), true)
            }
            before != null -> Triple(before.headingRad, before.yawRateRadPerS, before.measured)
            else -> Triple(after!!.headingRad, 0.0, after.measured)
        }
        return MotionState(
            timestampNanos = atNanos,
            headingRad = heading.toFloat(),
            yawRateRadPerS = rate.toFloat(),
            isStationary = isStationary,
            orientationQuality = if (measured) OrientationQuality.OK else OrientationQuality.DEGRADED,
        )
    }

    @Synchronized
    fun reset() = samples.clear()

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
