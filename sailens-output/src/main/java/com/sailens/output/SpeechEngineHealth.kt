package com.sailens.output

/**
 * Whether a TTS engine that once came up is still speaking, and what [SpeechManager] reports
 * meanwhile.
 *
 * An engine can stop working long after it was initialized: on 2026-10-28 an app process that had
 * run for about 30 hours got `TextToSpeech.ERROR` from every `speak()` (448 in a row), while the
 * engine still counted as ready. Nothing was said, nothing was reported, and the user walked on
 * believing the way was clear. `speak()` fails that way when the engine service is no longer
 * connected; the remedy is a new engine instance.
 *
 * So a failed submission of speakable text is an engine loss: the state turns
 * [SpeechEngineState.UNAVAILABLE] at once (the UI alerts once, through vibration) and stays so
 * through the rebuild, until a submission is accepted again. A rebuilt engine that also fails does
 * not alert a second time, and after [maxRebuilds] losses with nothing accepted in between, the
 * rebuilding stops until someone asks for the engine again.
 */
internal class SpeechEngineHealth(private val maxRebuilds: Int = MAX_REBUILDS) {

    /** What to do about a failed submission. */
    internal enum class Loss {
        /** The text itself was the problem (too long for the engine): the engine is fine. */
        NONE,
        /** The engine is lost: drop it and build a new one. */
        REBUILD,
        /** Lost again after [maxRebuilds] rebuilds that never spoke: stop rebuilding. */
        GIVE_UP,
    }

    /** True from a loss until a submission is accepted again. */
    var outage: Boolean = false
        private set

    private var losses = 0

    fun onSubmitFailed(textLength: Int, maxInputLength: Int): Loss {
        if (textLength > maxInputLength) return Loss.NONE
        outage = true
        losses++
        return if (losses > maxRebuilds) Loss.GIVE_UP else Loss.REBUILD
    }

    /** @return true when this ends an outage. */
    fun onSubmitAccepted(): Boolean {
        losses = 0
        val ended = outage
        outage = false
        return ended
    }

    /** The state to report for [state]: during an outage, unavailable until speech is accepted. */
    fun reported(state: SpeechEngineState): SpeechEngineState =
        if (outage && state != SpeechEngineState.IDLE) SpeechEngineState.UNAVAILABLE else state

    fun reset() {
        outage = false
        losses = 0
    }

    companion object {
        const val MAX_REBUILDS: Int = 3
    }
}
