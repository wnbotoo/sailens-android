package com.sailens.guidance.config

import kotlinx.serialization.Serializable

/**
 * Policy switches for a detection-led Guidance. All off is today's policy: given the same delivery results, every frame decides
 * the same events and leaves the same cooldown. A capture records this as part of its analysis config;
 * one recorded before it existed reads as all off.
 *
 * @param proximityEscalation a hazard's own escalation passes the cooldown, by what was said about that hazard.
 * @param speechRank a prompt's standing within its priority band ([com.sailens.guidance.processor.decision.SpeechRanks]).
 * @param retryInterrupted a prompt is confirmed once heard, and one cut off is offered again.
 * @param keepBlockedWithObstacle a blocked path no longer drops a detected obstacle ahead.
 * @param metricGate a detected obstacle is also announced by its measured distance. Needs [speechRank]: such a
 *   prompt must stand behind every prompt today's rules admit.
 * @param nearMeters the NEAR a prompt says goes out to this far by measured distance. Needs [metricGate]. It changes
 *   what is said of what is admitted, never what is admitted.
 * @param pathComplexOff PATH_COMPLEX is no longer made; a hard block still is. Each run says so once, heard to the end.
 */
@Serializable
data class DetFirstConfig(
    val proximityEscalation: Boolean = false,
    val speechRank: Boolean = false,
    val retryInterrupted: Boolean = false,
    val keepBlockedWithObstacle: Boolean = false,
    val metricGate: MetricGateConfig? = null,
    val nearMeters: Float? = null,
    val pathComplexOff: Boolean = false,
) {
    init {
        require(metricGate == null || speechRank) { "metricGate needs speechRank" }
        require(nearMeters == null || metricGate != null) { "nearMeters needs metricGate" }
    }

    companion object {
        /**
         * The combination evaluated for shipping. It did not pass its acceptance on the sealed recordings
         * (docs/experiments/detfirst-acceptance-2026-11.md), so only a debug build asks for it. A switch alone is for diagnosis.
         */
        val LAUNCH: DetFirstConfig = DetFirstConfig(
            proximityEscalation = true,
            speechRank = true,
            retryInterrupted = true,
            keepBlockedWithObstacle = true,
            metricGate = MetricGateConfig(),
            nearMeters = 2.0f,
            pathComplexOff = true,
        )
    }
}

/**
 * How near, by the lower bound of its measured distance, a detected obstacle has to be to be announced when
 * today's image-position gates do not yet admit it.
 *
 * @param roadsideStaticM for [com.sailens.guidance.processor.decision.EventGenerator]'s roadside classes, wherever they stand.
 * @param metricOnlyRepeatMs how long a hazard said this way stays said, before scaling. Scaled, it is capped at 8 s.
 */
@Serializable
data class MetricGateConfig(
    val vehicleAheadM: Float = 5.0f,
    val vehicleSideM: Float = 2.5f,
    val personAheadM: Float = 4.0f,
    val personSideM: Float = 3.0f,
    val staticM: Float = 2.5f,
    val roadsideStaticM: Float = 2.0f,
    val metricOnlyRepeatMs: Long = 6_000,
    /**
     * How long a track must have lasted before its measured distance alone speaks. A track shorter than a sentence is
     * gone before the sentence ends. On the calibration captures (SM8850, sem + det at their recorded rate), at 3 frames
     * 35% of these prompts were about tracks under 1 s; at 20, none, with the first prompts ahead over before 2.0 m going
     * from 13/41 to 11/41 for persons and 9/38 to 7/38 for vehicles. It counts frames, so a slower device waits longer.
     */
    val minStableFrames: Int = 20,
)
