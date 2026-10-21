package com.sailens.guidance.evaluation

import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents as E

/**
 * Whether each logical utterance was spoken to the end, from the recorded speech lifecycle
 * (det-first plan D1.3-3). Kept apart from "the current attempt": the words can be handed to the
 * engine several times, and what counts is whether any of those submissions finished.
 *
 * Rules:
 * - **Valid done**: an attempt the engine accepted (`submitted`, or `requeued` into it) receives
 *   `done` from the engine instance it was given to. Android's contract is that `onDone` means the
 *   audible output finished. A `done` is not discounted because the bookkeeping had already dropped
 *   the attempt (`superseded`, `cancelled`) or because it arrived after a flush.
 * - **Completion** of a logical utterance is the earliest valid done over all its attempts. Once
 *   earned it is never taken back by a later stop, failed hand-back or unknown outcome.
 * - **Failure** is counted per attempt and at most once, whatever evidence piles up after the first
 *   (`superseded` and the later `stopped` of the same attempt are one failure).
 * - **Evidence conflict**: one attempt with contradictory engine evidence -- more than one terminal
 *   callback (`done`/`stopped`/`error`), or a `done` from another engine instance. All of it is kept
 *   and flagged; the attempt counts neither as completed nor as failed, and the logical utterance
 *   is flagged so an acceptance can exclude it rather than pick the favourable reading.
 */
internal class CompletionCredit private constructor(val byLogicalId: Map<String, Logical>) {

    enum class AttemptOutcome { COMPLETED, FAILED, CONFLICT, OPEN }

    class Attempt(
        val attemptId: String,
        val logicalId: String,
        /** The engine instance it was given to; null if it never reached one. */
        val generation: Long?,
        val accepted: Boolean,
        val submittedAtNanos: Long?,
        val startedAtNanos: Long?,
        /** The valid done, if any. */
        val doneAtNanos: Long?,
        val outcome: AttemptOutcome,
        /** The evidence that failed it, the first of it: an event name, with its reason if any. */
        val failure: String?,
        val conflict: String?,
        val records: List<RegressionUtteranceRecord>,
    )

    class Logical(val logicalId: String, val attempts: List<Attempt>) {
        /** Earliest valid done over every attempt; never revoked. */
        val completedAtNanos: Long? = attempts.mapNotNull { it.doneAtNanos }.minOrNull()
        val completed: Boolean get() = completedAtNanos != null
        val firstSubmittedAtNanos: Long? = attempts.mapNotNull { it.submittedAtNanos }.minOrNull()
        val firstStartedAtNanos: Long? = attempts.mapNotNull { it.startedAtNanos }.minOrNull()
        val failedAttempts: Int = attempts.count { it.outcome == AttemptOutcome.FAILED }
        val evidenceConflict: Boolean = attempts.any { it.outcome == AttemptOutcome.CONFLICT }

        /** Refused before ever reaching the engine (no attempt accepted). */
        val neverAccepted: Boolean = attempts.none { it.accepted }

        /** Why it was not completed: each failed attempt's first failure, in order. */
        val failures: List<String> = attempts.mapNotNull { it.failure }
    }

    operator fun get(logicalId: String): Logical? = byLogicalId[logicalId]

    val conflicts: Int get() = byLogicalId.values.count { it.evidenceConflict }

    companion object {
        private val ENGINE_TERMINALS = setOf(E.DONE, E.STOPPED, E.ERROR)

        fun of(records: List<RegressionUtteranceRecord>): CompletionCredit {
            // An attempt's logical id is fixed by the record that registered it; a callback the
            // speech side could not place (logicalId null) is attributed through its attempt id.
            val logicalOf = HashMap<String, String>()
            for (r in records) {
                val logical = r.logicalId ?: continue
                logicalOf.putIfAbsent(r.attemptId, logical)
            }
            val byAttempt = records
                .filter { it.event != E.SCREEN_READER_PUBLISHED }
                .groupBy { it.attemptId }
            val attempts = byAttempt.mapNotNull { (attemptId, rs) ->
                val logical = logicalOf[attemptId] ?: return@mapNotNull null
                attempt(attemptId, logical, rs.sortedBy { it.elapsedRealtimeNanos })
            }
            return CompletionCredit(
                attempts.groupBy { it.logicalId }.mapValues { (id, list) -> Logical(id, list.sortedBy { it.submittedAtNanos ?: Long.MAX_VALUE }) },
            )
        }

        private fun attempt(attemptId: String, logicalId: String, rs: List<RegressionUtteranceRecord>): Attempt {
            val acceptance = rs.firstOrNull { it.event == E.SUBMITTED || it.event == E.REQUEUED }
            val generation = acceptance?.engineGeneration
            val terminals = rs.filter { it.event in ENGINE_TERMINALS }
            val dones = terminals.filter { it.event == E.DONE }
            val foreignDone = dones.any { it.engineGeneration != generation }
            val conflict = when {
                acceptance == null && dones.isNotEmpty() -> "done for an attempt the engine never accepted"
                foreignDone -> "done from engine ${dones.first { it.engineGeneration != generation }.engineGeneration}, submitted to $generation"
                terminals.size > 1 -> "terminal callbacks ${terminals.joinToString("+") { it.event }}"
                else -> null
            }
            val validDone = if (conflict == null && acceptance != null) dones.firstOrNull() else null
            val failure = if (validDone != null || conflict != null) {
                null
            } else {
                rs.firstOrNull { it.event in FAILURE_EVENTS }?.let { r -> r.event + (r.reason?.let { "($it)" } ?: "") }
            }
            // OPEN: accepted, and nothing recorded since -- handed back without a callback, or the
            // record ended first. Not completed, and not counted as a failure either.
            val outcome = when {
                conflict != null -> AttemptOutcome.CONFLICT
                validDone != null -> AttemptOutcome.COMPLETED
                failure != null -> AttemptOutcome.FAILED
                else -> AttemptOutcome.OPEN
            }
            return Attempt(
                attemptId = attemptId,
                logicalId = logicalId,
                generation = generation,
                accepted = acceptance != null,
                submittedAtNanos = acceptance?.elapsedRealtimeNanos,
                startedAtNanos = rs.firstOrNull { it.event == E.STARTED && it.engineGeneration == generation }?.elapsedRealtimeNanos,
                doneAtNanos = validDone?.elapsedRealtimeNanos,
                outcome = outcome,
                failure = failure,
                conflict = conflict,
                records = rs,
            )
        }

        /** Evidence that an attempt will not finish. A requeue_failed record is the would-be attempt's. */
        private val FAILURE_EVENTS = setOf(
            E.REFUSED, E.STOPPED, E.ERROR, E.SUPERSEDED, E.CANCELLED, E.REQUEUE_FAILED, E.UNKNOWN_TERMINAL,
        )
    }
}
