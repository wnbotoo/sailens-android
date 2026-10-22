package com.sailens.guidance.evaluation

import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.model.perception.DetectedObstacle
import kotlin.math.abs

/**
 * Lead time of one hazard stage against a distance threshold (det-first plan D1.5).
 *
 * `lead = crossing − completion`, where the crossing is when the hazard's ground contact first came
 * within [thresholdM]. Only **fresh** measurements count: a track the latest detection did not
 * update still reports its old box and distance, stamped with the current time, and so does every
 * predicted frame. Freshness is the track's measurement frame sequence equal to the frame's own --
 * never a time comparison, since two updates can share a millisecond.
 *
 * The crossing is an interval on the camera clock: the last fresh valid measurement beyond the
 * threshold at t1, the first within it at t2. Processing-completion times would shift the interval
 * late (median ~117 ms on the M0b captures) and can flip "early" into "late".
 */
internal object LeadTime {

    enum class Outcome {
        /** Completed before the crossing interval: the lead is positive. */
        BEFORE,

        /** Completed after it: the lead is negative. */
        AFTER,

        /** The crossing is bracketed, and the stage was never completed while the track lasted: a failure. */
        CLEARLY_MISSED,

        /** No bracketed crossing: censored, not a failure. [Result.reason] says why. */
        UNKNOWABLE,

        /** Completion falls inside the crossing interval: neither early nor late can be claimed. */
        ORDER_UNDETERMINED,
    }

    /** One fresh measurement of a track: the camera time of its frame, the contact estimate, the pitch. */
    data class Sample(val cameraNanos: Long, val validPointM: Float?, val validLowerM: Float?, val pitchDownDegrees: Float?)

    data class Crossing(val t1Nanos: Long, val t2Nanos: Long, val lowerBoundT2Nanos: Long?)

    data class Result(
        val outcome: Outcome,
        /** Lead interval in ms (`t1 − completion`, `t2 − completion`); null unless BEFORE / AFTER / ORDER_UNDETERMINED. */
        val leadMinMs: Double? = null,
        val leadMaxMs: Double? = null,
        val reason: String? = null,
        val crossing: Crossing? = null,
    )

    const val MAX_BRACKET_NANOS: Long = 1_000_000_000
    const val MAX_PITCH_CHANGE_DEGREES: Float = 10f

    /** The sample of [obstacle] on frame [frameSequence], or null when it was not measured there. */
    fun freshSample(obstacle: DetectedObstacle, frameSequence: Long, pitchDownDegrees: Float?): Sample? {
        val measurement = obstacle.measurement ?: return null
        if (measurement.frameSequence != frameSequence) return null
        val contact = obstacle.distanceEstimate?.contact
        val valid = contact?.validity == ContactValidity.VALID
        return Sample(
            cameraNanos = measurement.frameTimestampNanos,
            validPointM = contact?.pointM?.takeIf { valid },
            validLowerM = contact?.lowerM?.takeIf { valid },
            pitchDownDegrees = pitchDownDegrees,
        )
    }

    /** The bracketed crossing of [thresholdM] in [samples] (time order), or why there is none. */
    fun crossing(samples: List<Sample>, thresholdM: Float): Pair<Crossing?, String?> {
        val valid = samples.filter { it.validPointM != null }
        val j = valid.indexOfFirst { it.validPointM!! <= thresholdM }
        if (j < 0) return null to (if (valid.isEmpty()) "no valid contact" else "never within ${thresholdM}m")
        if (j == 0) return null to "first valid contact already within ${thresholdM}m"
        val before = valid[j - 1]
        val after = valid[j]
        if (after.cameraNanos - before.cameraNanos > MAX_BRACKET_NANOS) return null to "bracket wider than 1 s"
        val p1 = before.pitchDownDegrees
        val p2 = after.pitchDownDegrees
        if (p1 == null || p2 == null) return null to "no pitch"
        if (abs(p2 - p1) > MAX_PITCH_CHANGE_DEGREES) return null to "pitch changed by more than 10°"
        // The lower bound's own first time within the threshold, reported beside the point estimate.
        val lowerT2 = valid.firstOrNull { it.validLowerM != null && it.validLowerM <= thresholdM }?.cameraNanos
        return Crossing(before.cameraNanos, after.cameraNanos, lowerT2) to null
    }

    /**
     * @param completedAtNanos when the stage was first completed (elapsed-realtime clock, which a
     *   realtime camera clock shares); null when it never was while the track lasted.
     */
    fun classify(samples: List<Sample>, thresholdM: Float, completedAtNanos: Long?): Result {
        val (crossing, why) = crossing(samples, thresholdM)
        crossing ?: return Result(Outcome.UNKNOWABLE, reason = why)
        val tc = completedAtNanos ?: return Result(Outcome.CLEARLY_MISSED, crossing = crossing)
        val min = (crossing.t1Nanos - tc) / 1e6
        val max = (crossing.t2Nanos - tc) / 1e6
        val outcome = when {
            tc < crossing.t1Nanos -> Outcome.BEFORE
            tc > crossing.t2Nanos -> Outcome.AFTER
            else -> Outcome.ORDER_UNDETERMINED
        }
        return Result(outcome, min, max, crossing = crossing)
    }
}
