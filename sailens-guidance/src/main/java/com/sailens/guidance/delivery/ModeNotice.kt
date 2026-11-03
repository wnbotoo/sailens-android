package com.sailens.guidance.delivery

import com.sailens.guidance.model.scene.SceneEventMessageKeys

/**
 * Says once per run, heard to the end, which prompts this run gives: with path-complex prompts off, no prompt
 * about an unclear path does not mean a clear path, and the user has to know.
 *
 * Driven by frames, with no timer, so a closed-loop run does at the same moments what production does. It waits
 * for navigation prompts, not the other way round, and has no limit on attempts or on time: if prompts never stop
 * it goes on waiting. Refused, it is offered again at most every [REFUSED_RETRY_MS]. Cut off for the k-th time, it
 * waits min(2^k, 8) s from the cut. A screen reader takes it once and reports no end; that is told enough. With
 * speech off it is not said: a user with only vibration hears nothing of it, and the settings say so.
 */
internal class ModeNotice(
    private val offer: (id: String, messageKey: String, atMs: Long, expiresAtMs: Long, onOutcome: (SpeechOutcome) -> Unit) -> Offer,
) {
    enum class Offer { ACCEPTED, REFUSED, PUBLISHED }

    private var heard = false
    private var saying = false
    private var cuts = 0
    private var nextOfferAt = Long.MIN_VALUE
    private var lastFrameAt = Long.MIN_VALUE

    fun act(now: Long) {
        lastFrameAt = now
        if (heard || saying || now < nextOfferAt) return
        when (offer("mode-notice-$now", SceneEventMessageKeys.MODE_OBSTACLES_AND_BLOCKED, now, now + DEADLINE_MS, ::onOutcome)) {
            Offer.ACCEPTED -> saying = true
            Offer.REFUSED -> nextOfferAt = now + REFUSED_RETRY_MS
            Offer.PUBLISHED -> heard = true
        }
    }

    private fun onOutcome(outcome: SpeechOutcome) {
        when (outcome) {
            SpeechOutcome.SPOKEN,
            SpeechOutcome.HEARD_AFTER_CUT -> {
                heard = true
                saying = false
            }
            SpeechOutcome.CUT -> {
                saying = false
                cuts++
                nextOfferAt = lastFrameAt + minOf(1_000L shl cuts.coerceAtMost(MAX_BACKOFF_DOUBLINGS), MAX_BACKOFF_MS)
            }
        }
    }

    fun reset() {
        heard = false
        saying = false
        cuts = 0
        nextOfferAt = Long.MIN_VALUE
        lastFrameAt = Long.MIN_VALUE
    }

    /** Everything that decides what it does next. Once heard there is nothing more it will do. */
    internal data class State(val heard: Boolean, val saying: Boolean, val cuts: Int, val nextOfferAt: Long, val lastFrameAt: Long)

    internal fun state(): State = if (heard) State(true, false, 0, Long.MIN_VALUE, Long.MIN_VALUE) else State(false, saying, cuts, nextOfferAt, lastFrameAt)

    internal fun restore(state: State) {
        heard = state.heard
        saying = state.saying
        cuts = state.cuts
        nextOfferAt = state.nextOfferAt
        lastFrameAt = state.lastFrameAt
    }

    private companion object {
        const val REFUSED_RETRY_MS = 500L
        const val DEADLINE_MS = 5_000L
        const val MAX_BACKOFF_MS = 8_000L
        const val MAX_BACKOFF_DOUBLINGS = 3
    }
}
