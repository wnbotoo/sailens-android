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
 * as inputs: each sentence's duration (measured from the lifecycle, or the TTS duration table), the
 * engine's readiness, notices and descriptions.
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
    data class Actual(val accepted: Boolean, val startedAtMs: Double? = null, val doneAtMs: Double? = null, val critical: Boolean = false)

    /**
     * Agreement of [predictions] with [actual] (descriptive until the D1.4 thresholds are frozen):
     * accept/refuse precision and recall, start/done timing errors, CRITICAL misjudgements.
     */
    class Calibration(predictions: List<Prediction>, actual: Map<String, Actual>) {
        private val pairs = predictions.mapNotNull { p -> actual[p.id]?.let { p to it } }
        val n = pairs.size
        val acceptedBoth = pairs.count { (p, a) -> p.accepted && a.accepted }
        val acceptedPredictedOnly = pairs.count { (p, a) -> p.accepted && !a.accepted }
        val refusedPredictedOnly = pairs.count { (p, a) -> !p.accepted && a.accepted }
        val refusedBoth = pairs.count { (p, a) -> !p.accepted && !a.accepted }
        val criticalMisjudged = pairs.count { (p, a) -> a.critical && p.accepted != a.accepted }
        val startErrorsMs = pairs.mapNotNull { (p, a) -> if (p.startAtMs != null && a.startedAtMs != null) p.startAtMs - a.startedAtMs else null }
        val doneErrorsMs = pairs.mapNotNull { (p, a) -> if (p.completed && p.endAtMs != null && a.doneAtMs != null) p.endAtMs - a.doneAtMs else null }

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
            if (startErrorsMs.isNotEmpty() || doneErrorsMs.isNotEmpty()) {
                appendLine("    timing error |start| p50/p95 ${quantiles(startErrorsMs)} ms; |done| p50/p95 ${quantiles(doneErrorsMs)} ms")
            } else {
                appendLine("    timing error: no lifecycle timing to compare")
            }
        }

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
