package com.sailens.guidance.processor.decision

import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.perception.ObstacleSource
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys

/**
 * The number a Guidance sentence competes with on the speaker and the motor: its band, then its rank within
 * the band. Any band stands strictly above every rank of the band below, so with every rank 0 two sentences
 * compare exactly as their priorities do.
 */
object SpeechScale {
    const val FACTOR: Int = 8
    const val MAX_RANK: Int = FACTOR - 1

    fun of(band: EventPriority, rank: Int = 0): Int {
        require(rank in 0..MAX_RANK) { "rank $rank is outside 0..$MAX_RANK" }
        return band.value * FACTOR + rank
    }

    fun of(event: SceneEvent): Int = of(event.speechBand, event.rank)
}

/** The band a sentence competes in: its priority, except that a metric-only prompt is always MEDIUM. Cooldown and credit still use its priority. */
val SceneEvent.speechBand: EventPriority get() = if (isMetricOnly) EventPriority.MEDIUM else priority

/** Which sentences go first within a priority band. With [DetFirstConfig.speechRank] off, every rank is 0. */
object SpeechRanks {
    /**
     * The sentence saying path-complex prompts are off, whatever [DetFirstConfig.speechRank] says: below anything
     * in a higher band, which cuts it off, and above any MEDIUM obstacle prompt, which does not. So both ways round
     * it stands the same with the ranks on and off.
     */
    val MODE_NOTICE_SCALE: Int = SpeechScale.of(EventPriority.MEDIUM, SpeechScale.MAX_RANK)

    private const val INPUT_QUALITY = 7
    private const val STALLED = 6
    private const val OBSTACLE_SAYING_NEAR = 5
    private const val OBSTACLE_OR_BLOCKED = 4
    private const val METRIC_ONLY = 3

    fun of(event: SceneEvent, config: DetFirstConfig): Int = if (!config.speechRank) 0 else when (event.category) {
        EventCategory.SENSOR_QUALITY -> INPUT_QUALITY
        EventCategory.BLOCKED -> OBSTACLE_OR_BLOCKED
        EventCategory.OBSTACLE -> when {
            event.isMetricOnly -> METRIC_ONLY
            saysNearOfADetectedHazard(event) -> OBSTACLE_SAYING_NEAR
            else -> OBSTACLE_OR_BLOCKED
        }
        else -> 0
    }

    /** A semantic-source obstacle keeps today's standing level with BLOCKED: both come from one mask. */
    private fun saysNearOfADetectedHazard(event: SceneEvent): Boolean {
        val detected = event.components.flatMap { it.hazards }.filter { it.source == ObstacleSource.DETECTION }.map { it.trackId }.toSet()
        return PromptCredit.credits(event).any { it.stage == PromptCredit.Stage.NEAR && it.counts && it.trackId in detected }
    }

    /** A vision-fault notice: the covered lens and low light ahead of their band; the rest, recoveries too, 0. */
    fun ofNotice(messageKey: String, config: DetFirstConfig): Int = when {
        !config.speechRank -> 0
        messageKey == SceneEventMessageKeys.CAMERA_BLOCKED || messageKey == SceneEventMessageKeys.LOW_LIGHT -> INPUT_QUALITY
        else -> 0
    }

    /** A vision-fault rhythm, which goes by its fault's priority: the unrecognised floor is the LOW one. */
    fun ofVisionFaultRhythm(priority: EventPriority, config: DetFirstConfig): Int =
        if (config.speechRank && priority > EventPriority.LOW) INPUT_QUALITY else 0

    fun ofStalledNotice(config: DetFirstConfig): Int = if (config.speechRank) STALLED else 0
}
