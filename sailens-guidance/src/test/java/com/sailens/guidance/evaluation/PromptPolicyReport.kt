package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.PromptPolicyEvaluation.NearEntry
import java.util.Locale

/**
 * Text report of one or more [PromptPolicyEvaluation.Result]s (numbers only: no frame, no place).
 *
 * Every rate is given with its full denominator, failures included in the latency statistics
 * (a stage never reached counts as infinitely late: a quantile that falls on it reads "not
 * reached"), and distance metrics are withheld where too few hazards could be bracketed.
 */
internal object PromptPolicyReport {

    /** D1.5-5: below this share of bracketed crossings, distance metrics are not given. */
    const val MIN_COVERAGE: Double = 0.30

    fun render(results: List<PromptPolicyEvaluation.Result>): String = buildString {
        val evidence = results.map { it.evidence }.distinct()
        val completionKnown = results.isNotEmpty() && results.all { it.evidence.completionKnown }
        appendLine("  lifecycle: ${evidence.joinToString { it.label }}" + if (completionKnown) "" else " -- completion is UNDETERMINABLE, not \"no failure\"")
        appendLine(
            "  tracks seen ${results.sumOf { it.tracksSeen }}, never announced ${results.sumOf { it.tracksNeverAnnounced }}; " +
                "births ${results.sumOf { it.births }}, rebuilt after missed detections ${results.sumOf { it.rebuilds }} (each a new hazard)",
        )
        val offers = results.sumOf { it.offers }
        appendLine(
            "  offered prompts $offers, received ${results.sumOf { it.offersReceived }} (${pct(results.sumOf { it.offersReceived }, offers)})" +
                if (completionKnown) {
                    ", spoken to the end ${results.sumOf { it.offersCompleted }} (${pct(results.sumOf { it.offersCompleted }, offers)}); evidence conflicts ${results.sumOf { it.evidenceConflicts }}"
                } else {
                    ""
                },
        )

        appendLine("  stages (D1.2; latency from first appearance before the cooldown, failures counted as never):")
        appendLine("    %-28s %-8s %5s %9s %9s %9s %22s %22s".format(Locale.ROOT, "hazard", "stage", "n", "received", "amb.only", "completed", "appear->receive p50/p90", "appear->complete p50/p90"))
        val stages = results.flatMap { it.stages }
        for ((key, rows) in stages.groupBy { it.hazardClass to it.stage }.toSortedMap(compareBy({ it.first }, { it.second }))) {
            val counted = rows.filterNot { it.ambiguousOnly }
            val received = counted.count { it.receivedAt != null }
            val completed = counted.count { it.completedAtNanos != null }
            val receiveLatency = counted.map { r -> r.receivedAt?.let { (it - r.appearedAt).toDouble() } }
            val completeLatency = counted.map { r -> r.completedAtNanos?.let { it / 1e6 - r.appearedAt } }
            appendLine(
                "    %-28s %-8s %5d %9s %9d %9s %22s %22s".format(
                    Locale.ROOT, key.first, key.second.name, counted.size, "$received", rows.count { it.ambiguousOnly },
                    if (completionKnown) "$completed" else "?", quantiles(receiveLatency), if (completionKnown) quantiles(completeLatency) else "?",
                ),
            )
        }

        val leads = results.flatMap { it.leads }
        val basis = if (completionKnown) "completion" else "RECEIPT PROXY (no lifecycle: when the prompt was accepted, not when it was heard)"
        if (leads.isEmpty()) {
            appendLine("  lead time: none (camera clock not realtime, or a screen-reader session)")
        } else {
            appendLine("  lead time (D1.5) vs the point estimate, basis: $basis")
            for ((key, rows) in leads.groupBy { Triple(it.hazardClass, it.stage, it.thresholdM) }.toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))) {
                val by = rows.groupBy { it.result.outcome }
                fun n(o: LeadTime.Outcome) = by[o].orEmpty().size
                val bracketed = rows.size - n(LeadTime.Outcome.UNKNOWABLE)
                val coverage = if (rows.isEmpty()) 0.0 else bracketed.toDouble() / rows.size
                val decided = by[LeadTime.Outcome.BEFORE].orEmpty() + by[LeadTime.Outcome.AFTER].orEmpty()
                val leadText = if (coverage < MIN_COVERAGE) {
                    "distance metrics UNDETERMINABLE (coverage below ${(MIN_COVERAGE * 100).toInt()}%)"
                } else if (decided.isEmpty()) {
                    "no decided lead"
                } else {
                    "lead median [${fmt(median(decided.map { it.result.leadMinMs!! }))}, ${fmt(median(decided.map { it.result.leadMaxMs!! }))}] ms"
                }
                val reasons = by[LeadTime.Outcome.UNKNOWABLE].orEmpty().groupingBy { it.result.reason ?: "?" }.eachCount()
                appendLine(
                    "    ${key.first} ${key.second.name} <=${String.format(Locale.ROOT, "%.1f", key.third)}m: n ${rows.size}, bracketed ${pct(bracketed, rows.size)}; " +
                        "before ${n(LeadTime.Outcome.BEFORE)}, after ${n(LeadTime.Outcome.AFTER)}, clearly missed ${n(LeadTime.Outcome.CLEARLY_MISSED)}, " +
                        "order undetermined ${n(LeadTime.Outcome.ORDER_UNDETERMINED)}, unknowable ${n(LeadTime.Outcome.UNKNOWABLE)}" +
                        (if (reasons.isEmpty()) "" else " (${reasons.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }})") +
                        "; $leadText",
                )
            }
        }

        val near = results.flatMap { it.nearEntries }
        val by = near.groupBy { it.entry }
        fun n(e: NearEntry) = by[e].orEmpty().size
        val same = by[NearEntry.HELD_SAME_PRIORITY].orEmpty()
        val sameNever = same.count { if (completionKnown) it.completedLater == false else !it.receivedLater }
        appendLine(
            "  NEAR entries (D1.6, first per hazard) ${near.size}: offered ${n(NearEntry.OFFERED)}, passed but outranked ${n(NearEntry.PASSED_NOT_OFFERED)}, " +
                "suppressed by conflict resolution ${n(NearEntry.SUPPRESSED)}, held by a same-priority cooldown ${same.size} " +
                "(NEAR never ${if (completionKnown) "spoken to the end" else "received"} afterwards: $sameNever), held otherwise ${n(NearEntry.HELD_OTHER)}",
        )
        appendLine("    (held-by-cooldown attribution approximates the cooldown record by the latest received prompt on the same keys)")
    }

    /** Quantiles with failures (null) as +infinity. */
    private fun quantiles(values: List<Double?>): String {
        if (values.isEmpty()) return "-"
        val sorted = values.map { it ?: Double.POSITIVE_INFINITY }.sorted()
        fun q(f: Double): String = sorted[((sorted.size - 1) * f).toInt()].let { if (it.isInfinite()) "never" else fmt(it) }
        return "${q(0.5)}/${q(0.9)}"
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
    private fun pct(n: Int, of: Int) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * n / of)
    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.0f", v).let { if (it == "-0") "0" else it }
}
