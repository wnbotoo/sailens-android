package com.sailens.shell.describe

import com.sailens.core.log.LogService
import com.sailens.describe.DescribeSceneUseCase
import com.sailens.output.SpeechClauseBuffer
import com.sailens.shell.device.ScreenReaderAnnouncer
import com.sailens.shell.device.SpokenChannel
import com.sailens.vlm.SceneDescriptionChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the one description in flight — or the last one — looks like. */
data class SceneDescriptionState(
    val isDescribing: Boolean = false,
    /** Streamed so far, or the whole answer once [isComplete]. */
    val text: String = "",
    val isComplete: Boolean = false,
    /** Already-localised failure text, set when the last request failed. */
    val errorMessage: String? = null,
)

/**
 * The single owner of scene description for the whole shell: the job, the token that says whether
 * it may still speak, and the speech it has queued.
 *
 * There is exactly one of these, and that is the whole fix it exists for. When each screen kept its
 * own description session, a navigation warning cancelled the guidance screen's session — which was
 * never the one running — while the description the user was actually listening to, on the Describe
 * screen, kept decoding and kept talking after the warning. "Do not walk into that" outranks "what
 * is that" only if the thing Guidance cancels is the thing that is running.
 *
 * Three rules, all enforced here rather than by callers:
 *
 * - **Guidance preempts.** [preemptForGuidance] cancels the run, invalidates its token so a chunk
 *   already decoded cannot speak, and withdraws every clause Describe has queued — including the
 *   tail of an answer that finished decoding but is still being read out.
 * - **Describe only takes back its own speech.** [cancel] withdraws Describe's utterances and
 *   nothing else, so closing or cancelling a description can never cut off a warning.
 * - **One answer, one channel.** A request speaks through the channel that was current when it
 *   started. If the channel changes mid-way — speech turned off, a screen reader turned on — the
 *   request is cancelled rather than finished half in one voice and half in another, or into a
 *   channel that no longer exists. The next request uses the new channel.
 *
 * Policy, so it lives in the shell; sailens-output stays a mechanism that does not know Guidance or
 * Describe exist (architecture.md §6.6).
 */
class SceneDescriptionCoordinator(
    private val describeScene: DescribeSceneUseCase,
    private val voice: DescribeVoice,
    private val announcer: ScreenReaderAnnouncer,
    /** The non-speech failure signal, for when an answer could not be said at all. */
    private val signalFailureWithoutSpeech: () -> Unit,
    private val channel: StateFlow<SpokenChannel>,
    private val scope: CoroutineScope,
    private val logService: LogService,
) {
    private val session = SceneDescriptionSession()

    private val _state = MutableStateFlow(SceneDescriptionState())
    val state: StateFlow<SceneDescriptionState> = _state.asStateFlow()

    init {
        scope.launch {
            var current = channel.value
            channel.collect { next ->
                if (next == current) return@collect
                current = next
                stop("output channel changed to $next")
            }
        }
    }

    /**
     * Starts describing what the camera sees, unless a description is already running.
     *
     * @param failureNotice already-localised text for a failed request. The shell owns resources,
     *   this class does not (§6.11).
     * @return false when a description was already running and nothing new was started.
     */
    fun describe(failureNotice: String): Boolean {
        if (session.isDescribing) return false
        val requestChannel = channel.value
        session.start(scope) { token -> run(token, requestChannel, failureNotice) }
        return true
    }

    /** The user or the Describe screen is done with the description. Touches only Describe's speech. */
    fun cancel(reason: String) {
        stop(reason)
    }

    /**
     * Guidance is about to speak. Call this before, not after: by the time a warning is queued, no
     * clause of the description may be left in front of it or behind it.
     */
    fun preemptForGuidance(reason: String) {
        stop("preempted by guidance: $reason")
    }

    private fun stop(reason: String) {
        val wasRunning = session.cancel(reason)
        // Always withdraw, not only while a request is running: an answer that has finished
        // decoding can still be half-way through being read out.
        voice.withdraw()
        if (wasRunning) {
            logService.info(TAG, "Scene description stopped", mapOf("reason" to reason))
            _state.update { it.copy(isDescribing = false) }
        }
    }

    private suspend fun run(token: Long, requestChannel: SpokenChannel, failureNotice: String) {
        _state.value = SceneDescriptionState(isDescribing = true)
        val clauses = SpeechClauseBuffer()
        var spokeAnyClause = false
        try {
            describeScene().collect { chunk ->
                // Cancellation is asynchronous, and whether an already-decoded chunk can still
                // arrive here depends on how the engine hands chunks over. The token makes the rule
                // independent of that: a request that no longer owns the channel never speaks.
                if (!session.isCurrent(token)) return@collect
                when (chunk) {
                    is SceneDescriptionChunk.Delta -> {
                        _state.update { it.copy(text = it.text + chunk.text) }
                        if (requestChannel != SpokenChannel.OWN_TTS) return@collect
                        clauses.append(chunk.text)?.let { clause ->
                            if (speakOwned(token, clause)) spokeAnyClause = true
                        }
                    }

                    is SceneDescriptionChunk.Completed -> {
                        val description = chunk.description
                        _state.update { it.copy(text = description.text, isComplete = true) }
                        logService.info(
                            TAG,
                            "Scene description completed",
                            mapOf(
                                "timeToFirstTokenMs" to description.timeToFirstTokenMs,
                                "latencyMs" to description.latencyMs,
                                "backend" to description.backend,
                                "channel" to requestChannel.name,
                            ),
                        )
                        if (description.text.isBlank()) return@collect
                        deliverCompleted(token, requestChannel, description.text, clauses, spokeAnyClause)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (session.isCurrent(token)) {
                logService.error(TAG, "Scene description failed", e)
                _state.update { it.copy(errorMessage = failureNotice, isComplete = false) }
                reportFailure(token, requestChannel, failureNotice)
            }
        } finally {
            if (session.isCurrent(token)) {
                _state.update { it.copy(isDescribing = false) }
            }
        }
    }

    private suspend fun deliverCompleted(
        token: Long,
        requestChannel: SpokenChannel,
        text: String,
        clauses: SpeechClauseBuffer,
        spokeAnyClause: Boolean,
    ) {
        when (requestChannel) {
            // The screen reader interrupts its previous announcement with each new one, so a
            // streamed answer would arrive as a string of cut-off fragments. It gets the whole text.
            SpokenChannel.SCREEN_READER -> announcer.announce(text)

            SpokenChannel.OWN_TTS -> {
                var spoke = spokeAnyClause
                clauses.drain()?.let { tail -> if (speakOwned(token, tail)) spoke = true }
                // Nothing streamed: a runtime that cannot stream delivers the text only here.
                if (!spoke) spoke = speakOwned(token, text)
                // The engine never came up. The answer is on screen, but the person asked out loud.
                if (!spoke && session.isCurrent(token)) signalFailureWithoutSpeech()
            }

            // Speech is off and no screen reader runs: the answer is on screen, as the user chose.
            SpokenChannel.SILENT -> Unit
        }
    }

    private suspend fun reportFailure(token: Long, requestChannel: SpokenChannel, failureNotice: String) {
        when (requestChannel) {
            SpokenChannel.SCREEN_READER -> announcer.announce(failureNotice)
            SpokenChannel.OWN_TTS ->
                if (!speakOwned(token, failureNotice) && session.isCurrent(token)) signalFailureWithoutSpeech()
            // The user pressed a button and is waiting; silence would read as "nothing is there".
            SpokenChannel.SILENT -> signalFailureWithoutSpeech()
        }
    }

    /**
     * Speaks [text] as Describe's, if this request still owns the channel after waiting for the
     * engine. The check is repeated after the wait because the wait is a suspension point:
     * Guidance may have preempted while it was pending.
     */
    private suspend fun speakOwned(token: Long, text: String): Boolean {
        if (!voice.awaitReady()) return false
        if (!session.isCurrent(token)) return false
        voice.speak(text)
        return true
    }

    private companion object {
        const val TAG = "SceneDescription"
    }
}
