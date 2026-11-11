package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.DepthRecordCompleteness
import com.sailens.guidance.model.trace.DepthRunReport
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.model.trace.TimingStats
import com.sailens.guidance.model.trace.TraceReplaySession
import kotlin.math.ceil

/** Builds [DepthRunReport] from a parsed trace; never from frame records, except the ages. */
internal object DepthRunReportBuilder {

    fun build(session: TraceReplaySession): DepthRunReport {
        val records = session.depthRuns.sortedBy { it.seq }
        val counted = session.summary?.depthRunCount
        val recorded = session.metadata?.depthTrace == true || records.isNotEmpty() || (counted ?: 0L) > 0L
        if (!recorded) return DepthRunReport.NOT_RECORDED

        val (own, closings) = records.partition { it.outcome != DepthRunTrace.CLOSED }
        val generations = records.groupBy { it.generation }
        val outcomes = own.groupingBy { it.outcome }.eachCount().toMutableMap()
        val cancelled = closings.count { it.cancelledRunId != null }
        if (cancelled > 0) outcomes[DepthRunReport.CANCELLED] = cancelled
        val published = own.filter { it.outcome == DepthRunTrace.PUBLISHED }
        val skips = HashMap<String, Int>()
        records.forEach { record -> record.skipsSinceLast.forEach { (reason, n) -> skips[reason] = (skips[reason] ?: 0) + n } }

        val missing = missingRecords(records, counted)
        val gaps = generations.values.sumOf { runIdGaps(it) }
        val unclosed = if (session.summary != null) generations.values.count { group -> group.none { it.outcome == DepthRunTrace.CLOSED } } else 0
        val completeness = when {
            missing > 0 || gaps > 0 || unclosed > 0 -> DepthRecordCompleteness.INCOMPLETE
            counted == null -> DepthRecordCompleteness.POSSIBLY_INCOMPLETE
            else -> DepthRecordCompleteness.COMPLETE
        }
        return DepthRunReport(
            completeness = completeness,
            missingRecords = missing,
            depthSessions = generations.size,
            attempts = own.size + cancelled,
            outcomeCounts = outcomes.toSortedMap(),
            validityCounts = published.groupingBy { it.validity ?: "none" }.eachCount().toSortedMap(),
            inverseOutputRatio = if (published.isEmpty()) 0.0 else published.count { it.validity == INVERSE_OUTPUT }.toDouble() / published.size,
            skipCounts = skips.toSortedMap(),
            prepareMs = stats(own.mapNotNull { it.prepareMs }),
            admitWaitMs = stats(own.mapNotNull { it.admitWaitMs }),
            inferMs = stats(own.mapNotNull { it.inferMs }),
            outputReadMs = stats(own.mapNotNull { it.outputReadMs }),
            gpuMs = stats(own.mapNotNull { run -> run.inferMs?.let { infer -> run.outputReadMs?.let { read -> infer + read } } }),
            fitMs = stats(own.mapNotNull { it.fitMs }),
            ageMs = stats(session.frames.mapNotNull { it.depthAgeMs?.let(Math::abs) }),
        )
    }

    /** `seq` runs from 1 to the session's count without a gap; without the count, up to the highest seen. */
    private fun missingRecords(records: List<DepthRunTrace>, counted: Long?): Long {
        val seqs = records.mapTo(HashSet()) { it.seq }
        val highest = maxOf(seqs.maxOrNull() ?: 0L, counted ?: 0L)
        return highest - seqs.count { it in 1..highest }
    }

    /** Within one depth session, the run ids settled (own records and the closing record's in-flight one) run from 1. */
    private fun runIdGaps(group: List<DepthRunTrace>): Int {
        val settled = HashSet<Long>()
        group.forEach { record ->
            if (record.outcome == DepthRunTrace.CLOSED) record.cancelledRunId?.let(settled::add) else settled += record.runId
        }
        val last = group.maxOf { it.runId }
        return (1..last).count { it !in settled }
    }

    private fun stats(values: List<Long>): TimingStats? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        return TimingStats(count = sorted.size, p50 = rank(0.5), p95 = rank(0.95), max = sorted.last())
    }

    private const val INVERSE_OUTPUT = "inverse_output"
}
