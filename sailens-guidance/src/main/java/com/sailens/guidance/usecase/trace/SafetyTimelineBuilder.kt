package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.SafetyRecordCompleteness
import com.sailens.guidance.model.trace.SafetyStateTimeline
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.model.trace.TraceReplaySession

/**
 * Builds a trace's [SafetyStateTimeline]. The order is fixed, and it matters:
 *
 * 1. find every gap on the records as read, by `seq`, and the unknown time each leaves;
 * 2. only then join neighbouring records of the same state and count each state's time;
 * 3. let unknown time override known time, and clip all of it to the window.
 *
 * Joining first would join the two records either side of a gap whenever they hold the same state, and count the
 * time in between, which a missing record may have spent in another state, as that one.
 */
internal object SafetyTimelineBuilder {

    fun build(session: TraceReplaySession): SafetyStateTimeline {
        val records = session.safetyStates.sortedBy { it.seq }
        val count = session.summary?.safetyStateCount
        val recorded = session.metadata?.safetyTrace == true || count != null || records.isNotEmpty() ||
            session.guidanceAlerts.any { it.source == GuidanceAlertSources.SYSTEM }
        if (!recorded) return SafetyStateTimeline.NOT_RECORDED

        // The window. Without the start record, the session's first state record was written at the opening.
        val start = session.metadata?.openedElapsedMs
            ?: records.firstOrNull { it.seq == 1L && it.cause == SafetyTraceNames.Causes.SESSION_START }?.elapsedMs
        val end = session.summary?.closedElapsedMs
        val endedOnError = session.errors.any { it.stage == NAVIGATION_FLOW_STAGE }

        // 1. Gaps, on the records as read.
        val unknown = mutableListOf<Interval>()
        var missing = 0L
        val first = records.firstOrNull()
        val last = records.lastOrNull()
        if (first == null || last == null) {
            missing += count ?: 0L
        } else {
            if (first.seq > 1) {
                missing += first.seq - 1
                if (start != null) unknown += Interval(start, maxOf(start, first.stateSinceElapsedMs))
            }
            records.zipWithNext { a, b ->
                if (b.seq > a.seq + 1) {
                    missing += b.seq - a.seq - 1
                    unknown += Interval(a.elapsedMs, maxOf(a.elapsedMs, b.stateSinceElapsedMs))
                }
            }
            if (count != null && last.seq < count) {
                missing += count - last.seq
                if (end != null) unknown += Interval(last.elapsedMs, end)
            }
        }
        val completeness = when {
            missing > 0 -> SafetyRecordCompleteness.INCOMPLETE
            count == null -> SafetyRecordCompleteness.POSSIBLY_INCOMPLETE
            else -> SafetyRecordCompleteness.COMPLETE
        }

        // Where counting can start and stop: the window, or the records nearest a bound the trace lost.
        val from = start ?: first?.elapsedMs
        val to = end ?: last?.elapsedMs
        if (from == null || to == null || to <= from) {
            return SafetyStateTimeline(
                completeness = completeness,
                windowStartKnown = start != null,
                windowEndKnown = end != null,
                missingRecords = missing,
                endedOnError = endedOnError,
            )
        }
        val window = Interval(from, to)
        val unknownInWindow = union(unknown.mapNotNull { it.intersect(window) })

        // 2. Join neighbouring records of the same state; each run holds from its first record's state start to the next run's.
        val runs = mutableListOf<Pair<String, Long>>()
        for (record in records) {
            if (runs.lastOrNull()?.first != record.stateLabel) runs += record.stateLabel to record.stateSinceElapsedMs
        }
        val known = sortedMapOf<String, Long>()
        var cursor = Long.MIN_VALUE
        runs.forEachIndexed { index, (label, since) ->
            val runStart = maxOf(since, cursor)
            val runEnd = maxOf(runStart, if (index + 1 < runs.size) runs[index + 1].second else to)
            cursor = runEnd
            // 3. Clipped to the window, less what is unknown.
            val clipped = Interval(runStart, runEnd).intersect(window) ?: return@forEachIndexed
            val time = clipped.length - unknownInWindow.sumOf { clipped.intersect(it)?.length ?: 0L }
            if (time > 0) known[label] = (known[label] ?: 0L) + time
        }
        return SafetyStateTimeline(
            completeness = completeness,
            durationsMs = known,
            unknownMs = window.length - known.values.sum(),
            windowStartKnown = start != null,
            windowEndKnown = end != null,
            missingRecords = missing,
            endedOnError = endedOnError,
        )
    }

    /** `[from, to)`, elapsed-realtime milliseconds. */
    private data class Interval(val from: Long, val to: Long) {
        val length: Long get() = to - from

        fun intersect(other: Interval): Interval? {
            val a = maxOf(from, other.from)
            val b = minOf(to, other.to)
            return if (b > a) Interval(a, b) else null
        }
    }

    /** Disjoint intervals covering the same time as [intervals]. */
    private fun union(intervals: List<Interval>): List<Interval> {
        val merged = mutableListOf<Interval>()
        for (interval in intervals.sortedBy { it.from }) {
            val previous = merged.lastOrNull()
            if (previous != null && interval.from <= previous.to) {
                merged[merged.lastIndex] = Interval(previous.from, maxOf(previous.to, interval.to))
            } else {
                merged += interval
            }
        }
        return merged
    }

    private const val NAVIGATION_FLOW_STAGE = "navigation_flow"
}
