package com.sailens.shell.sim

import com.sailens.output.Announcement
import com.sailens.output.SpeechEngineState
import com.sailens.output.SpeechManager
import com.sailens.output.UtteranceOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Own speech, as a double that keeps `SpeechManager`'s rules and `UtteranceSettlement`'s (sailens-output): what
 * the engine accepts, what it cuts, what holds the speaker, and what each caller is told. Not the real engine's
 * timing, its late completions, audio focus, or how it finds out it has failed: a failure is the script's.
 *
 * - The ledger holds the sentence being said and the ones queued behind it. One plays at a time, for
 *   [durationMs] of its key (the delivery fingerprints' figure: a longer wording holds the speaker longer).
 * - [speak]: refused when expired at the frame's time, when not [isReady], or when the ledger holds a live
 *   sentence of equal or higher priority (only strictly higher interrupts). Accepted, it flushes the ledger
 *   (`QUEUE_FLUSH`): every sentence there that has not ended is cut, and one that was asked about is told
 *   [UtteranceOutcome.CUT] after the new sentence is on the ledger; their ends are taken back.
 * - [speakSystemNotice]: dropped when not [isReady]; otherwise queued behind what is there (`QUEUE_ADD`), told
 *   nothing; with a priority, it holds the speaker for at most [SpeechManager.PROTECTED_NOTICE_MAX_MS] from when
 *   it was queued, playing or not.
 * - A sentence's end is an event in the director's queue at the time it ends. It is heard: `onSpoken`, then
 *   [UtteranceOutcome.SPOKEN], then it leaves the ledger, and the next one starts. So by the time a frame settles,
 *   every end at or before its time is done, and [settleFrame] only reads the clock.
 * - [stop]: every sentence not ended is cut and its end taken back; the ledger is emptied. [release]: a stop,
 *   then nothing is taken any more.
 * - [state] is a real state flow; [isReady] is apart from it, so a script can rebuild the engine (ready again)
 *   without the state changing. Unavailable but ready, the first submission it accepts makes the state READY.
 *
 * @param clock the session clock (the pipeline's).
 * @param scheduleEnd puts a sentence's end in the director's queue at a time; the function it returns takes it out.
 */
internal class SimSpeech(
    private val clock: () -> Long,
    private val scheduleEnd: (atMs: Long, end: () -> Unit) -> (() -> Unit),
    private val record: (TimelineEntry.Speech) -> Unit,
    private val now: () -> Long,
) : SpeechDevice {
    private val _state = MutableStateFlow(SpeechEngineState.READY)
    override val state: StateFlow<SpeechEngineState> = _state.asStateFlow()

    override var isReady: Boolean = true
        private set

    private var released = false
    private var nextSystemNotice = 1

    private class Utterance(
        val id: String,
        val text: String,
        val key: String,
        val priority: Int?,
        val expiresAtMs: Long?,
        val onSpoken: (() -> Unit)?,
        val onOutcome: ((UtteranceOutcome) -> Unit)?,
    ) {
        var startedAtMs: Long? = null
        var takeEndBack: (() -> Unit)? = null

        fun isAliveAt(nowMs: Long) = expiresAtMs == null || nowMs <= expiresAtMs
    }

    private val ledger = ArrayDeque<Utterance>()

    /** The utterances on the ledger, oldest first, as ids: for the contract test. */
    val ledgerIds: List<String> get() = ledger.map { it.id }

    override fun initialize() = Unit

    override fun setSpeechRate(rate: Float) = Unit

    override fun settleFrame(): Long = clock()

    override fun speak(
        announcement: Announcement,
        onSpoken: (() -> Unit)?,
        onOutcome: ((UtteranceOutcome) -> Unit)?,
        atMs: Long?,
    ): Boolean {
        val at = atMs ?: clock()
        val refusal = when {
            announcement.isExpired(at) -> "expired"
            !isReady -> "not_ready"
            else -> ledger.filter { it.priority != null && it.isAliveAt(at) }.maxOfOrNull { it.priority!! }
                ?.takeIf { announcement.priority <= it }?.let { "outranked" }
        }
        val line = TimelineEntry.Speech(
            now(), "speak", announcement.id, announcement.text, announcement.priority, announcement.expiresAtMs,
            result = refusal?.let { "refused($it)" } ?: "accepted",
        )
        record(line)
        if (refusal != null) return false
        accepted()
        val utterance = Utterance(
            announcement.id, announcement.text, announcement.key, announcement.priority, announcement.expiresAtMs,
            onSpoken, onOutcome,
        )
        // QUEUE_FLUSH: the new one is on the ledger before any flushed one is told it was cut.
        val flushed = ledger.toList()
        ledger.clear()
        ledger.addLast(utterance)
        start(utterance)
        flushed.forEach(::cut)
        return true
    }

    override fun speakSystemNotice(text: String, priority: Int?) {
        val id = "$SYSTEM_NOTICE_PREFIX${nextSystemNotice++}"
        if (!isReady) {
            record(TimelineEntry.Speech(now(), "notice", id, text, priority, result = "refused(not_ready)"))
            return
        }
        // The protected time starts when the notice is queued, not when it starts playing.
        val expiresAtMs = priority?.let { clock() + SpeechManager.PROTECTED_NOTICE_MAX_MS }
        record(TimelineEntry.Speech(now(), "notice", id, text, priority, expiresAtMs, result = "accepted"))
        accepted()
        val utterance = Utterance(id, text, text, priority, expiresAtMs, onSpoken = null, onOutcome = null)
        val idle = ledger.isEmpty()
        ledger.addLast(utterance)
        if (idle) start(utterance)
    }

    override fun stop() {
        record(TimelineEntry.Speech(now(), "stop"))
        val dropped = ledger.toList()
        ledger.clear()
        dropped.forEach(::cut)
    }

    override fun release() {
        record(TimelineEntry.Speech(now(), "release"))
        val dropped = ledger.toList()
        ledger.clear()
        dropped.forEach(::cut)
        released = true
        isReady = false
        _state.value = SpeechEngineState.IDLE
    }

    /**
     * The script changes the engine: its [state] (which the view model's collector sees), whether it [isReady] (which
     * it does not), or both. An engine that stops being ready drops what it was saying: no end will come for it.
     */
    fun engine(state: SpeechEngineState? = null, ready: Boolean? = null) {
        check(!released) { "the engine was released" }
        if (ready != null) {
            if (isReady && !ready) {
                val dropped = ledger.toList()
                ledger.clear()
                dropped.forEach(::cut)
            }
            isReady = ready
        }
        if (state != null) _state.value = state
    }

    /** `SpeechManager.onSubmitAccepted`: an unavailable engine that takes a submission is speaking again. */
    private fun accepted() {
        if (_state.value == SpeechEngineState.UNAVAILABLE) _state.value = SpeechEngineState.READY
    }

    private fun start(utterance: Utterance) {
        val startedAt = clock()
        utterance.startedAtMs = startedAt
        utterance.takeEndBack = scheduleEnd(startedAt + durationMs(utterance.key)) { ended(utterance) }
    }

    /** The engine finished [utterance]: heard, its callbacks, off the ledger, and the next one starts. */
    private fun ended(utterance: Utterance) {
        check(ledger.firstOrNull() === utterance) { "only the sentence being said can end" }
        record(TimelineEntry.Speech(now(), "done", utterance.id))
        utterance.takeEndBack = null
        utterance.onSpoken?.invoke()
        utterance.onOutcome?.invoke(UtteranceOutcome.SPOKEN)
        ledger.removeFirst()
        ledger.firstOrNull()?.let { next ->
            record(TimelineEntry.Speech(now(), "start", next.id, next.text))
            start(next)
        }
    }

    /** [utterance] left the ledger without ending: cut if it was playing, dropped if it was waiting. */
    private fun cut(utterance: Utterance) {
        utterance.takeEndBack?.invoke()
        utterance.takeEndBack = null
        record(TimelineEntry.Speech(now(), if (utterance.startedAtMs != null) "cut" else "drop", utterance.id))
        utterance.onOutcome?.invoke(UtteranceOutcome.CUT)
    }

    companion object {
        /** The double's own system-notice ids; a line numbers them `s1`, `s2`… */
        const val SYSTEM_NOTICE_PREFIX = "system_notice_"

        /** How long a sentence holds the speaker: by its key, as in the delivery fingerprints. */
        fun durationMs(key: String): Long = 600L + 40L * key.length
    }
}
