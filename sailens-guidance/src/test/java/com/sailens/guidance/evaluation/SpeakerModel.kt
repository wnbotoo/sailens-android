package com.sailens.guidance.evaluation

import java.util.Locale
import kotlin.math.abs

/**
 * A model of the speaker for counterfactual runs (det-first plan D1.4): given what would have been
 * offered to speech, which offers the engine takes, and when each starts and ends.
 *
 * It mirrors SpeechManager's arbitration -- a guidance announcement is refused when expired, when the
 * engine is not ready, or when speech of equal or higher priority is still live (queued or speaking
 * and not past its deadline); accepted, it flushes the queue and starts now -- and its queueing of
 * system notices and descriptions (QUEUE_ADD, behind what is queued). What it cannot know comes in
 * as inputs: each sentence's duration ([SpeakerDurations]: from other captures or the TTS duration
 * table, never from the capture being scored), the engine's readiness, notices and descriptions.
 *
 * **Calibration is a gate, not a report.** Until thresholds are frozen in `docs/experiments/` before
 * the sealed acceptance set is looked at, its output is descriptive only and accepts nothing
 * ([Calibration.render] says so). If calibration fails, D3 is judged on device data only.
 */
internal class SpeakerModel(private val startLatencyMs: Long = 0) {

    sealed interface Input {
        val atMs: Long
    }

    /** A guidance announcement offered to `speak()`. */
    data class Offer(
        override val atMs: Long,
        val id: String,
        val priority: Int,
        val expiresAtMs: Long,
        val durationMs: Long,
    ) : Input

    /** `speakSystemNotice()` or a description clause: queued behind everything, optionally prioritized. */
    data class Queued(
        override val atMs: Long,
        val id: String,
        val priority: Int?,
        val durationMs: Long,
        val owner: String? = null,
    ) : Input

    data class EngineReady(override val atMs: Long, val ready: Boolean) : Input

    data class Prediction(
        val id: String,
        val accepted: Boolean,
        val reason: String? = null,
        val startAtMs: Long? = null,
        /** When it would end: finished, or cut off by a flush. */
        val endAtMs: Long? = null,
        val completed: Boolean = false,
    )

    private class Playing(
        val id: String,
        val priority: Int?,
        val expiresAtMs: Long?,
        var startAtMs: Long,
        var endAtMs: Long,
    )

    fun run(inputs: List<Input>): List<Prediction> {
        val queue = ArrayDeque<Playing>()
        val out = LinkedHashMap<String, Prediction>()
        var ready = true

        fun advance(t: Long) {
            while (queue.isNotEmpty() && queue.first().endAtMs <= t) {
                val done = queue.removeFirst()
                out[done.id] = out.getValue(done.id).copy(endAtMs = done.endAtMs, completed = true)
            }
        }

        for (input in inputs.sortedBy { it.atMs }) {
            val t = input.atMs
            advance(t)
            when (input) {
                is EngineReady -> ready = input.ready
                is Offer -> {
                    val highest = queue.filter { it.priority != null && (it.expiresAtMs == null || t <= it.expiresAtMs) }
                        .maxOfOrNull { it.priority!! }
                    val refusal = when {
                        t > input.expiresAtMs -> "expired"
                        !ready -> "engine_not_ready"
                        highest != null && input.priority <= highest -> "outranked"
                        else -> null
                    }
                    if (refusal != null) {
                        out[input.id] = Prediction(input.id, accepted = false, reason = refusal)
                        continue
                    }
                    // QUEUE_FLUSH: whatever was queued or playing ends now.
                    for (cut in queue) out[cut.id] = out.getValue(cut.id).copy(endAtMs = maxOf(t, cut.startAtMs), completed = false)
                    queue.clear()
                    val start = t + startLatencyMs
                    queue.addLast(Playing(input.id, input.priority, input.expiresAtMs, start, start + input.durationMs))
                    out[input.id] = Prediction(input.id, accepted = true, startAtMs = start)
                }
                is Queued -> {
                    if (!ready) {
                        out[input.id] = Prediction(input.id, accepted = false, reason = "engine_not_ready")
                        continue
                    }
                    val start = maxOf(t + startLatencyMs, queue.lastOrNull()?.endAtMs ?: Long.MIN_VALUE)
                    val expires = input.priority?.let { t + PROTECTED_NOTICE_MAX_MS }
                    queue.addLast(Playing(input.id, input.priority, expires, start, start + input.durationMs))
                    out[input.id] = Prediction(input.id, accepted = true, startAtMs = start)
                }
            }
        }
        advance(Long.MAX_VALUE)
        return out.values.toList()
    }

    /** What actually happened to one offer, from the device. */
    data class Actual(
        val accepted: Boolean,
        val startedAtMs: Double? = null,
        val doneAtMs: Double? = null,
        val critical: Boolean = false,
        /** The device's refusal reason, when it refused. */
        val refusal: String? = null,
    )

    /**
     * Agreement of [predictions] with [actual] (descriptive until the D1.4 thresholds are frozen):
     * accept/refuse and spoken-to-the-end precision and recall, start/done timing errors, CRITICAL
     * misjudgements by direction, and the reasons behind each disagreement.
     */
    class Calibration private constructor(
        private val pairs: List<Pair<Prediction, Actual>>,
        /** Where each offer's duration came from ("c table", "d1.7 table", "default"), counted. */
        val durationSources: Map<String, Int>,
        /** Offers left out by an exclusion named in advance (X2, X3, X5 of the threshold note), by exclusion. */
        val excluded: Map<String, Int>,
    ) {
        /** Offers in [excluded] (id to exclusion) are run through the model but not scored. */
        constructor(
            predictions: List<Prediction>,
            actual: Map<String, Actual>,
            durationSources: Map<String, Int> = emptyMap(),
            excluded: Map<String, String> = emptyMap(),
        ) : this(
            predictions.mapNotNull { p -> if (p.id in excluded) null else actual[p.id]?.let { p to it } },
            durationSources,
            excluded.filterKeys { it in actual }.values.groupingBy { it }.eachCount().toSortedMap(),
        )

        val n = pairs.size
        val acceptedBoth = pairs.count { (p, a) -> p.accepted && a.accepted }
        val acceptedPredictedOnly = pairs.count { (p, a) -> p.accepted && !a.accepted }
        val refusedPredictedOnly = pairs.count { (p, a) -> !p.accepted && a.accepted }
        val refusedBoth = pairs.count { (p, a) -> !p.accepted && !a.accepted }
        val criticalMisjudged = pairs.count { (p, a) -> a.critical && p.accepted != a.accepted }
        val criticalOffers = pairs.count { (_, a) -> a.critical }

        /** CRITICAL offers the model accepts although the device refused them: the direction that hides a loss. */
        val criticalAcceptOverclaimed = pairs.count { (p, a) -> a.critical && p.accepted && !a.accepted }

        // Spoken to the end: what M2 and M5 count, so agreement on completion, not only on acceptance.
        val completedBoth = pairs.count { (p, a) -> p.completed && a.doneAtMs != null }
        val completedPredictedOnly = pairs.count { (p, a) -> p.completed && a.doneAtMs == null }
        val completedActualOnly = pairs.count { (p, a) -> !p.completed && a.doneAtMs != null }
        val criticalCompletionOverclaimed = pairs.count { (p, a) -> a.critical && p.completed && a.doneAtMs == null }
        val startErrorsMs = pairs.mapNotNull { (p, a) -> if (p.startAtMs != null && a.startedAtMs != null) p.startAtMs - a.startedAtMs else null }
        val doneErrorsMs = pairs.mapNotNull { (p, a) -> if (p.completed && p.endAtMs != null && a.doneAtMs != null) p.endAtMs - a.doneAtMs else null }

        /** Each disagreement by the refusing side's reason ("model accepts/device refused(<why>)" and the converse). */
        val disagreements: Map<String, Int> = pairs.filter { (p, a) -> p.accepted != a.accepted }
            .groupingBy { (p, a) -> if (p.accepted) "device refused(${a.refusal ?: "?"})" else "model refused(${p.reason ?: "?"})" }
            .eachCount().toSortedMap()

        fun render(label: String): String = buildString {
            appendLine("  speaker model ($label) -- DESCRIPTIVE ONLY: no D1.4 threshold is frozen, nothing is accepted on it")
            if (n == 0) {
                appendLine("    no offer to compare")
                return@buildString
            }
            appendLine(
                "    offers $n: accepted by both $acceptedBoth, refused by both $refusedBoth, " +
                    "model accepts/device refused $acceptedPredictedOnly, model refuses/device accepted $refusedPredictedOnly; " +
                    "CRITICAL misjudged $criticalMisjudged",
            )
            appendLine(
                "    accept precision ${ratio(acceptedBoth, acceptedBoth + acceptedPredictedOnly)} recall ${ratio(acceptedBoth, acceptedBoth + refusedPredictedOnly)}; " +
                    "refuse precision ${ratio(refusedBoth, refusedBoth + refusedPredictedOnly)} recall ${ratio(refusedBoth, refusedBoth + acceptedPredictedOnly)}",
            )
            appendLine(
                "    spoken to the end: by both $completedBoth, model only $completedPredictedOnly, device only $completedActualOnly; " +
                    "completion precision ${ratio(completedBoth, completedBoth + completedPredictedOnly)} recall ${ratio(completedBoth, completedBoth + completedActualOnly)}",
            )
            appendLine(
                "    CRITICAL offers $criticalOffers: model accepts/device refused $criticalAcceptOverclaimed, " +
                    "model completes/device did not $criticalCompletionOverclaimed",
            )
            if (disagreements.isNotEmpty()) appendLine("    disagreements: ${disagreements.entries.joinToString { "${it.key} ${it.value}" }}")
            if (excluded.isNotEmpty()) appendLine("    excluded, not scored: ${excluded.entries.joinToString { "${it.key} ${it.value}" }}")
            if (durationSources.isNotEmpty()) {
                appendLine("    offer durations by wording: ${durationSources.entries.joinToString { "${it.key} ${it.value}" }} (default ${DEFAULT_DURATION_MS} ms)")
            }
            if (startErrorsMs.isNotEmpty() || doneErrorsMs.isNotEmpty()) {
                appendLine("    timing error |start| p50/p95 ${quantiles(startErrorsMs)} ms; |done| p50/p95 ${quantiles(doneErrorsMs)} ms")
            } else {
                appendLine("    timing error: no lifecycle timing to compare")
            }
        }

        /** This and [other] as one set (offer ids are unique across captures). */
        operator fun plus(other: Calibration) = Calibration(pairs + other.pairs, sum(durationSources, other.durationSources), sum(excluded, other.excluded))

        private fun sum(a: Map<String, Int>, b: Map<String, Int>) = (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }.toSortedMap()

        private fun ratio(a: Int, b: Int) = if (b == 0) "-" else String.format(Locale.ROOT, "%.3f", a.toDouble() / b)
        private fun quantiles(errors: List<Double>): String {
            if (errors.isEmpty()) return "-"
            val sorted = errors.map(::abs).sorted()
            fun q(f: Double) = sorted[((sorted.size - 1) * f).toInt()]
            return String.format(Locale.ROOT, "%.0f/%.0f", q(0.5), q(0.95))
        }
    }

    companion object {
        /** SpeechManager.PROTECTED_NOTICE_MAX_MS (sailens-output is not a dependency of this module). */
        const val PROTECTED_NOTICE_MAX_MS: Long = 15_000

        /** A sentence's duration when nothing better is known; the D1.7 table replaces it. */
        const val DEFAULT_DURATION_MS: Long = 1_400
    }
}
