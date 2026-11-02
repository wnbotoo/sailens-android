package com.sailens.guidance.config

import kotlinx.serialization.Serializable

/**
 * The det-first switches. All off is today's policy: given the same delivery results, every frame decides
 * the same events and leaves the same cooldown. A capture records this as part of its analysis config;
 * one recorded before it existed reads as all off.
 *
 * @param proximityEscalation a hazard's own escalation passes the cooldown, by what was said about that hazard.
 * @param speechRank a prompt's standing within its priority band ([com.sailens.guidance.processor.decision.SpeechRanks]).
 * @param retryInterrupted a prompt is confirmed once heard, and one cut off is offered again.
 * @param keepBlockedWithObstacle a blocked path no longer drops a detected obstacle ahead.
 */
@Serializable
data class DetFirstConfig(
    val proximityEscalation: Boolean = false,
    val speechRank: Boolean = false,
    val retryInterrupted: Boolean = false,
    val keepBlockedWithObstacle: Boolean = false,
)
