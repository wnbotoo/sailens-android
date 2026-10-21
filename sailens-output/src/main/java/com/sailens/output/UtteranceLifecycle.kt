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
 *   submitted in, never the current one; once that session has ended, nothing of it is reported.
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
 * Ending a session is a reconciliation, not a guess. Each session counts the events it produced
 * -- decided under the lock, whether or not the observer has had them yet -- and [endSession]
 * returns that count once the session is closed: from then on nothing more of that session is
 * reported, so a recorder that compares it with what it wrote knows exactly how many it lost, each
 * at most once. Before closing, it waits a bounded time for two things that would otherwise turn
 * into losses: submissions registered but not yet confirmed or refused by the engine, and events on
 * their way to the observer. A submission still unconfirmed then is abandoned: counted as one lost
 * event, and nothing of that attempt is reported afterwards.
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

    // Per-session accounting, all guarded by [delivery]. Lock order: `this`, then [delivery].
    private val delivery = Object()

    /** Events each session produced: decided, and handed (or about to be handed) to the observer. */
    private val produced = HashMap<String, Int>()

    /** Of those, the ones not yet handed over. */
    private val inFlight = HashMap<String, Int>()

    /** Submissions registered and not yet confirmed or refused, per session. */
    private val pending = HashMap<String, MutableSet<String>>()

    /**
     * Ended sessions, and what each produced: nothing more of theirs is reported, and ending one again
     * returns the same count. Never trimmed -- one small entry per session, and forgetting one would
     * let a surviving attempt of it be reported again.
     */
    private val closed = HashMap<String, Int>()

    /** Serializes [endSession]: a second ending of a session waits for the first and shares its result. */
    private val ending = Object()

    fun beginSession(token: String) = synchronized(this) { session = token }

    /**
     * Ends [token]'s session. Waits up to [drainTimeoutMs] for its unconfirmed submissions; reports
     * every attempt of it still without an outcome as unknown; closes it; then waits for what is on
     * its way to the observer, within the same time limit. Ending it again changes nothing and
     * returns the same count, waiting for a first ending still in progress.
     *
     * @return how many events the session produced, delivered or not (an abandoned submission counts
     *   as one): a recorder that wrote fewer lost the difference. 0 when nothing was observed.
     */
    fun endSession(token: String, drainTimeoutMs: Long = END_DRAIN_TIMEOUT_MS): Int = synchronized(ending) {
        synchronized(delivery) { closed[token] }?.let { return it }
        val deadline = System.nanoTime() + drainTimeoutMs * 1_000_000
        awaitDrained(deadline) { pending[token].isNullOrEmpty() }
        val target = observer
        val events = synchronized(this) {
            if (session == token) session = null
            val unknown = if (target == null) emptyList() else settleOpen(UtteranceReasons.SESSION_ENDED) { it.session == token }
            synchronized(delivery) {
                // Still unconfirmed: abandoned. Each counts as one lost event, and being of the
                // closed session, nothing of it is reported afterwards.
                val stuck = pending.remove(token).orEmpty()
                if (stuck.isNotEmpty()) produced[token] = (produced[token] ?: 0) + stuck.size
                val kept = registerLocked(unknown)
                // The count is final from here: nothing more of the session is registered.
                closed[token] = produced[token] ?: 0
                kept
            }
        }
        if (target != null) deliver(target, events)
        awaitDrained(deadline) { (inFlight[token] ?: 0) == 0 }
        synchronized(delivery) {
            inFlight.remove(token)
            produced.remove(token)
            closed.getValue(token)
        }
    }

    /** Waits on [delivery] until [done], at most until [deadline] (System.nanoTime). */
    private inline fun awaitDrained(deadline: Long, done: () -> Boolean) = synchronized(delivery) {
        while (!done()) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            delivery.wait(remainingMs)
        }
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
        ).also { attempt ->
            attempt.session?.let { s -> synchronized(delivery) { if (s !in closed) pending.getOrPut(s) { LinkedHashSet() } += entry.id } }
        }
        trim()
        emptyList()
    }

    /** The engine has answered for [attempt]: it is no longer awaited by [endSession]. */
    private fun confirmed(attemptId: String, attempt: Attempt) {
        val s = attempt.session ?: return
        synchronized(delivery) {
            pending[s]?.remove(attemptId)
            delivery.notifyAll()
        }
    }

    /** The engine took the attempt [submitting] registered. */
    fun submitted(attemptId: String) = emit {
        val attempt = attempts[attemptId] ?: return@emit emptyList()
        confirmed(attemptId, attempt)
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
        confirmed(attemptId, attempt)
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
     * Each event is counted for its session the moment it is decided -- under the same lock that
     * settles its attempt -- so [endSession] cannot slip between deciding and delivering.
     */
    private inline fun emit(block: () -> List<UtteranceEvent>) {
        val target = observer ?: return
        val events = try {
            synchronized(this) {
                val decided = block()
                if (decided.isEmpty()) decided else synchronized(delivery) { registerLocked(decided) }
            }
        } catch (e: RuntimeException) {
            return
        }
        deliver(target, events)
    }

    /**
     * Holding [delivery]: drops what belongs to a closed session (abandoned attempts included), and
     * counts the rest for their sessions as produced and in flight. An attempt forgotten after
     * [MAX_ATTEMPTS] newer ones has no session any more: it is reported with none, so no session's
     * record takes it, and counted for none.
     */
    private fun registerLocked(events: List<UtteranceEvent>): List<UtteranceEvent> =
        events.filter { e -> e.session == null || e.session !in closed }.onEach { e ->
            val s = e.session ?: return@onEach
            produced[s] = (produced[s] ?: 0) + 1
            inFlight[s] = (inFlight[s] ?: 0) + 1
        }

    private fun deliver(target: UtteranceLifecycleObserver, events: List<UtteranceEvent>) {
        for (event in events) {
            try {
                target.onUtteranceEvent(event)
            } catch (e: RuntimeException) {
                // Observation must never reach the speech path.
            } finally {
                event.session?.let { s ->
                    synchronized(delivery) {
                        inFlight[s]?.let { inFlight[s] = it - 1 }
                        delivery.notifyAll()
                    }
                }
            }
        }
    }


    companion object {
        const val MAX_ATTEMPTS: Int = 512

        /** Long enough for a descheduled thread, short enough never to stall a session's end. */
        const val END_DRAIN_TIMEOUT_MS: Long = 500
    }
}
