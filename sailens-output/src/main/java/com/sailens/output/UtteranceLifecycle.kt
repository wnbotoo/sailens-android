package com.sailens.output

/**
 * What became of one submission of an utterance to the engine, for measurement. Nothing in this
 * module reads these events back: observing them changes no decision about what is said.
 *
 * Two ids, because the same words can be handed to the engine more than once. [attemptId] is the
 * engine's utterance id of one submission; [logicalId] is the caller's id for what is being said
 * ([Announcement.id] for an announcement) and stays the same when [SpeechManager.withdraw] hands the
 * words back under a fresh attempt id.
 *
 * @param engineGeneration the engine instance the attempt was given to, fixed when it was given;
 *   a callback reports the generation of the instance it came from. Null for an attempt no engine
 *   ever saw.
 * @param session the lifecycle session ([SpeechManager.beginLifecycleSession]) the attempt was
 *   submitted in. A callback that arrives later still reports that session, not the current one.
 * @param elapsedRealtimeNanos `SystemClock.elapsedRealtimeNanos()`, read on the thread that observed
 *   the event: the engine's own thread for its callbacks.
 * @param logicalId null when the attempt is not known here (submitted before observation began, or
 *   forgotten after [UtteranceLifecycle.MAX_ATTEMPTS] newer ones).
 */
public data class UtteranceEvent(
    val kind: UtteranceEventKind,
    val attemptId: String,
    val logicalId: String?,
    val engineGeneration: Long?,
    val session: String?,
    val elapsedRealtimeNanos: Long,
    /** [UtteranceReasons]: why it was refused, cancelled, or left without an outcome. */
    val reason: String? = null,
    /** [UtteranceEventKind.STOPPED]: the engine's `interrupted` flag. */
    val interrupted: Boolean? = null,
    /** [UtteranceEventKind.ERROR]: the engine's error code; null from the deprecated callback. */
    val errorCode: Int? = null,
    /** [UtteranceEventKind.REQUEUED] / [UtteranceEventKind.REQUEUE_FAILED]: the attempt this one replaces. */
    val previousAttemptId: String? = null,
    /** [UtteranceEventKind.SUBMITTED]: the owner's diagnostic name, if any. */
    val owner: String? = null,
    /** [UtteranceEventKind.SUBMITTED] / [UtteranceEventKind.REFUSED]: the caller's priority, if any. */
    val priority: Int? = null,
    /** [UtteranceEventKind.SUBMITTED]: length of the text, in chars. */
    val textLength: Int? = null,
)

public enum class UtteranceEventKind(public val wireName: String) {
    /** The engine accepted it ([android.speech.tts.TextToSpeech.speak] did not return ERROR). */
    SUBMITTED("submitted"),

    /** Never given to the engine, or the engine refused it; [UtteranceEvent.reason] says which. */
    REFUSED("refused"),
    STARTED("started"),

    /** The engine's `onDone`: the audible output finished. */
    DONE("done"),

    /** The engine's `onStop`, with [UtteranceEvent.interrupted]. */
    STOPPED("stopped"),

    /** The engine's `onError`, with [UtteranceEvent.errorCode]. */
    ERROR("error"),

    /** Handed back to the engine under a new attempt id after a withdrawal; the event is the new attempt's. */
    REQUEUED("requeued"),

    /** Handing back failed; the event names the attempt that would have been. */
    REQUEUE_FAILED("requeue_failed"),

    /** Dropped from the queue by a newer utterance (QUEUE_FLUSH). Said by the bookkeeping, not the engine. */
    SUPERSEDED("superseded"),

    /** Dropped by a stop, a withdrawal or a failed flush; [UtteranceEvent.reason] says which. */
    CANCELLED("cancelled"),

    /** Still without any outcome when its session ended or its engine was shut down. */
    UNKNOWN_TERMINAL("unknown_terminal"),
}

/** Values of [UtteranceEvent.reason]. */
public object UtteranceReasons {
    public const val EXPIRED: String = "expired"

    /** Speech of equal or higher priority was still playing. */
    public const val OUTRANKED: String = "outranked"
    public const val ENGINE_NOT_READY: String = "engine_not_ready"

    /** `speak()` returned ERROR. */
    public const val SPEAK_ERROR: String = "speak_error"
    public const val STOP: String = "stop"

    /** A flush-mode `speak()` failed, so the queue was forgotten. */
    public const val SPEAK_FAILED: String = "speak_failed"

    /** Its owner withdrew it. */
    public const val WITHDRAWN: String = "withdrawn"

    /** Expired while another owner withdrew, so it was not handed back. */
    public const val EXPIRED_ON_WITHDRAW: String = "expired_on_withdraw"
    public const val SESSION_ENDED: String = "session_ended"
    public const val ENGINE_SHUT_DOWN: String = "engine_shut_down"
}

/** Receives [UtteranceEvent]s. Called on whatever thread observed the event; must not block or throw. */
public fun interface UtteranceLifecycleObserver {
    public fun onUtteranceEvent(event: UtteranceEvent)
}

/**
 * The attempt bookkeeping behind [UtteranceEvent]s: which logical utterance, engine generation and
 * session each attempt belongs to, and whether it has an outcome yet.
 *
 * Every fact about an attempt is fixed when it is submitted and read back from here, never from
 * "the current" engine or session: a callback that arrives after the engine was replaced or the
 * session ended still belongs to the one it was submitted to.
 *
 * Observation only. Without an observer it does nothing at all; with one, it never throws and
 * calls the observer outside its lock.
 *
 * Because the observer is called outside the lock, an event can be decided (its attempt settled)
 * and still be on its way to the observer. [endSession] therefore waits, for a bounded time, until
 * every event decided before it has been delivered: whoever closes the record after it has then
 * either received each one or been told how many it did not.
 */
internal class UtteranceLifecycle(private val nanoClock: () -> Long) {

    @Volatile
    var observer: UtteranceLifecycleObserver? = null

    private class Attempt(
        val logicalId: String,
        val generation: Long,
        val session: String?,
        val submittedAtNanos: Long,
        val owner: String?,
        val priority: Int?,
        val textLength: Int,
        val previousAttemptId: String?,
        /** False until the engine accepted it. */
        var submitted: Boolean = false,
        /** True once it has an outcome; later evidence is still reported, but it is never "unknown". */
        var settled: Boolean = false,
    )

    private val attempts = LinkedHashMap<String, Attempt>()
    private var session: String? = null

    /** Events decided under the lock and not yet handed to the observer; guarded by [delivery]. */
    private val delivery = Object()
    private var inFlight = 0

    fun beginSession(token: String) = synchronized(this) { session = token }

    /**
     * Ends [token]'s session: every attempt of it still without an outcome is reported as unknown,
     * and every event decided before this call is delivered before it returns.
     *
     * @return how many events were still on their way to the observer when it stopped waiting,
     *   after [drainTimeoutMs]; 0 when all arrived (or nothing is observed).
     */
    fun endSession(token: String, drainTimeoutMs: Long = END_DRAIN_TIMEOUT_MS): Int {
        emit {
            if (session == token) session = null
            settleOpen(UtteranceReasons.SESSION_ENDED) { it.session == token }
        }
        return awaitDelivered(drainTimeoutMs)
    }

    /** Waits until nothing is on its way to the observer, at most [timeoutMs]; returns what still is. */
    private fun awaitDelivered(timeoutMs: Long): Int = synchronized(delivery) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (inFlight > 0) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            delivery.wait(remainingMs)
        }
        inFlight
    }

    /** The engine of [generation] was shut down: its attempts without an outcome will not get one. */
    fun generationEnded(generation: Long) = emit {
        settleOpen(UtteranceReasons.ENGINE_SHUT_DOWN) { it.generation == generation }
    }

    /**
     * About to hand [entry] to the engine of [generation]. Registered before the call so that a
     * callback racing the call's return still finds it. [previousAttemptId] for a hand-back.
     */
    fun submitting(entry: UtteranceLedger.Entry, generation: Long, previousAttemptId: String? = null) = emit {
        val inherited = previousAttemptId?.let { attempts[it]?.session }
        attempts[entry.id] = Attempt(
            logicalId = entry.logicalId,
            generation = generation,
            session = if (previousAttemptId != null && inherited != null) inherited else session,
            submittedAtNanos = nanoClock(),
            owner = entry.owner?.name,
            priority = entry.priority,
            textLength = entry.text.length,
            previousAttemptId = previousAttemptId,
        )
        trim()
        emptyList()
    }

    /** The engine took the attempt [submitting] registered. */
    fun submitted(attemptId: String) = emit {
        val attempt = attempts[attemptId] ?: return@emit emptyList()
        attempt.submitted = true
        val previous = attempt.previousAttemptId
        if (previous == null) {
            listOf(
                event(UtteranceEventKind.SUBMITTED, attemptId, attempt, attempt.submittedAtNanos).copy(
                    owner = attempt.owner,
                    priority = attempt.priority,
                    textLength = attempt.textLength,
                ),
            )
        } else {
            attempts[previous]?.settled = true
            listOf(event(UtteranceEventKind.REQUEUED, attemptId, attempt, attempt.submittedAtNanos).copy(previousAttemptId = previous))
        }
    }

    /** The engine refused the attempt [submitting] registered. */
    fun submitFailed(attemptId: String) = emit {
        val attempt = attempts[attemptId] ?: return@emit emptyList()
        attempt.settled = true
        val previous = attempt.previousAttemptId
        if (previous == null) {
            listOf(
                event(UtteranceEventKind.REFUSED, attemptId, attempt, nanoClock())
                    .copy(reason = UtteranceReasons.SPEAK_ERROR, priority = attempt.priority),
            )
        } else {
            attempts[previous]?.settled = true
            listOf(event(UtteranceEventKind.REQUEUE_FAILED, attemptId, attempt, nanoClock()).copy(previousAttemptId = previous))
        }
    }

    /** Refused before reaching the engine. */
    fun refused(attemptId: String, logicalId: String, reason: String, priority: Int?) = emit {
        listOf(
            UtteranceEvent(
                kind = UtteranceEventKind.REFUSED,
                attemptId = attemptId,
                logicalId = logicalId,
                engineGeneration = null,
                session = session,
                elapsedRealtimeNanos = nanoClock(),
                reason = reason,
                priority = priority,
            ),
        )
    }

    /** The bookkeeping dropped [entries] ([UtteranceEventKind.SUPERSEDED] or [UtteranceEventKind.CANCELLED]). */
    fun removed(entries: List<UtteranceLedger.Entry>, kind: UtteranceEventKind, reason: String?) = emit {
        if (entries.isEmpty()) return@emit emptyList()
        val at = nanoClock()
        entries.map { entry ->
            val attempt = attempts[entry.id]
            attempt?.settled = true
            UtteranceEvent(
                kind = kind,
                attemptId = entry.id,
                logicalId = attempt?.logicalId ?: entry.logicalId,
                engineGeneration = attempt?.generation,
                session = attempt?.session,
                elapsedRealtimeNanos = at,
                reason = reason,
            )
        }
    }

    /**
     * An engine callback, from the listener of the engine of [generation], read at [atNanos] on the
     * engine's thread. Reported whatever the attempt's state: contradictory evidence is kept, not
     * resolved here.
     */
    fun engineCallback(
        kind: UtteranceEventKind,
        attemptId: String?,
        generation: Long,
        atNanos: Long,
        interrupted: Boolean? = null,
        errorCode: Int? = null,
    ) = emit {
        val id = attemptId ?: return@emit emptyList()
        val attempt = attempts[id]
        if (kind != UtteranceEventKind.STARTED) attempt?.settled = true
        listOf(
            UtteranceEvent(
                kind = kind,
                attemptId = id,
                logicalId = attempt?.logicalId,
                engineGeneration = generation,
                session = attempt?.session,
                elapsedRealtimeNanos = atNanos,
                interrupted = interrupted,
                errorCode = errorCode,
            ),
        )
    }

    private fun settleOpen(reason: String, which: (Attempt) -> Boolean): List<UtteranceEvent> {
        val at = nanoClock()
        return attempts.entries
            .filter { (_, attempt) -> attempt.submitted && !attempt.settled && which(attempt) }
            .map { (id, attempt) ->
                attempt.settled = true
                event(UtteranceEventKind.UNKNOWN_TERMINAL, id, attempt, at).copy(reason = reason)
            }
    }

    private fun event(kind: UtteranceEventKind, attemptId: String, attempt: Attempt, at: Long) = UtteranceEvent(
        kind = kind,
        attemptId = attemptId,
        logicalId = attempt.logicalId,
        engineGeneration = attempt.generation,
        session = attempt.session,
        elapsedRealtimeNanos = at,
    )

    /** Forgets the oldest attempts, settled ones first, beyond [MAX_ATTEMPTS]. */
    private fun trim() {
        while (attempts.size > MAX_ATTEMPTS) {
            val victim = attempts.entries.firstOrNull { it.value.settled }?.key ?: attempts.keys.first()
            attempts.remove(victim)
        }
    }

    /**
     * Runs [block] under the lock when observed, then hands its events to the observer outside it.
     * They count as in flight from the moment they are decided -- under the same lock that settles
     * their attempts -- until the observer has had them, so [endSession] cannot slip between.
     */
    private inline fun emit(block: () -> List<UtteranceEvent>) {
        val target = observer ?: return
        val events = try {
            synchronized(this) {
                block().also { if (it.isNotEmpty()) synchronized(delivery) { inFlight += it.size } }
            }
        } catch (e: RuntimeException) {
            return
        }
        for (event in events) {
            try {
                target.onUtteranceEvent(event)
            } catch (e: RuntimeException) {
                // Observation must never reach the speech path.
            } finally {
                synchronized(delivery) {
                    inFlight--
                    delivery.notifyAll()
                }
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 512

        /** Long enough for a descheduled callback thread, short enough never to stall a session's end. */
        const val END_DRAIN_TIMEOUT_MS: Long = 500
    }
}
