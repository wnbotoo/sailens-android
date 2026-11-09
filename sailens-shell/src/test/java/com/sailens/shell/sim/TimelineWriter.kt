package com.sailens.shell.sim

import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.traceLevel
import com.sailens.guidance.safety.traceReasons
import com.sailens.shell.device.GuidanceHaptic

/**
 * One thing that happened in a simulated session, at [atMs]: milliseconds since the session's start was pressed.
 * Kept as data, so the cross-check reads what happened rather than a line of text.
 */
internal sealed interface TimelineEntry {
    val atMs: Long

    /** An action of the session's script, as the script names it. */
    data class Script(override val atMs: Long, val action: String) : TimelineEntry

    /**
     * A result reached the collector: the frame's qualification and the events decided on it. [tracked], what the
     * tracker output on it, is kept for the tests and is not on the frame's line.
     */
    data class Frame(
        override val atMs: Long,
        val seq: Long,
        val baseline: BaselineQualification,
        val events: List<SceneEvent>,
        val tracked: List<ObstacleCategory> = emptyList(),
    ) : TimelineEntry

    /** What the safety observation handed to `recordState`. */
    data class State(override val atMs: Long, val trace: SafetyStateTrace) : TimelineEntry

    /** What `recordAlert` got: a system alert's, or a vision-fault notice's or rhythm's. */
    data class Alert(override val atMs: Long, val trace: GuidanceAlertTrace) : TimelineEntry

    /** What the delivery protocol handed to `recordOutcome` for an offered prompt. */
    data class Outcome(override val atMs: Long, val trace: PromptOutcomeTrace) : TimelineEntry

    /** A receipt going to the cooldown, through one of its five use cases. */
    data class Receipt(override val atMs: Long, val kind: String, val eventId: String, val detail: String?) : TimelineEntry

    /** A call the speech double got, and its answer; or an end, a cut, a start or a drop it made later. */
    data class Speech(
        override val atMs: Long,
        val kind: String,
        val id: String? = null,
        val text: String? = null,
        val scale: Int? = null,
        val expiresAtMs: Long? = null,
        val result: String? = null,
    ) : TimelineEntry

    /** A call the motor mirror got, and its answer; or a rhythm it started or dropped later. */
    data class Motor(override val atMs: Long, val kind: String, val rhythm: GuidanceHaptic?, val result: String?) : TimelineEntry

    data class ScreenReader(override val atMs: Long, val text: String) : TimelineEntry

    /** A description began holding the floor, ended, or was preempted. */
    data class Description(override val atMs: Long, val what: String) : TimelineEntry

    /** One of the four ports through which the system alerts write the screen's state. */
    data class Ui(override val atMs: Long, val what: String, val detail: String? = null) : TimelineEntry
}

/**
 * The record of a simulated session, as entries in the order they happened, and their lines
 * (`docs/geometry.md` §7). Times are kept relative to the moment start was pressed; [origin] is the
 * clock's reading then, so an absolute time handed in (an event's expiry, a state's since) is made
 * relative with [relative].
 */
internal class Timeline(private val now: () -> Long, val origin: Long) {
    private val _entries = mutableListOf<TimelineEntry>()
    val entries: List<TimelineEntry> get() = _entries

    fun relative(clockMs: Long): Long = clockMs - origin

    fun add(entry: (atMs: Long) -> TimelineEntry) {
        _entries += entry(now())
    }

    fun script(action: String) = add { TimelineEntry.Script(it, action) }

    fun frame(seq: Long, baseline: BaselineQualification, events: List<SceneEvent>, tracked: List<ObstacleCategory>) =
        add { TimelineEntry.Frame(it, seq, baseline, events, tracked) }

    fun state(trace: SafetyStateTrace) = add { TimelineEntry.State(it, trace) }

    fun alert(trace: GuidanceAlertTrace) = add { TimelineEntry.Alert(it, trace) }

    fun outcome(trace: PromptOutcomeTrace) = add { TimelineEntry.Outcome(it, trace) }

    fun receipt(kind: String, event: SceneEvent, detail: String? = null) =
        add { TimelineEntry.Receipt(it, kind, event.id.toString(), detail) }

    fun ui(what: String, detail: String? = null) = add { TimelineEntry.Ui(it, what, detail) }

    fun description(what: String) = add { TimelineEntry.Description(it, what) }

    fun screenReader(text: String) = add { TimelineEntry.ScreenReader(it, text) }
}

/**
 * Writes a session's entries as the lines of its `<name>.timeline.txt` (`docs/geometry.md` §7):
 *
 * - a `frame` line for a result whose frame decided events, or whose qualification differs from the
 *   frame before (the first frame always); the other, quiet results are merged into one `frames` line
 *   per run of them, written at the time of the run's last frame (`from` is its first), and written out
 *   before whatever line comes next, so times never go back;
 * - ids are numbered in the order they first appear: events `e1`, `e2`…, vision-fault notices `n1`…, the
 *   speech double's system notices `s1`…, anything else `m1`…; no random id reaches a line;
 * - times are relative, and no line carries a floating-point number or a wall-clock time.
 */
internal class TimelineWriter(private val origin: Long) {
    private val ids = LinkedHashMap<String, String>()
    private val counters = HashMap<Char, Int>()
    private val lines = mutableListOf<String>()

    private var previousBaseline: BaselineQualification? = null
    private var quiet: QuietRun? = null

    private class QuietRun(val fromMs: Long, val firstSeq: Long, val baseline: BaselineQualification) {
        var lastMs = fromMs
        var lastSeq = firstSeq
        var count = 1
    }

    fun write(header: List<String>, entries: List<TimelineEntry>): List<String> {
        lines += header
        entries.forEach(::entry)
        flushQuiet()
        return lines.toList()
    }

    private fun rel(clockMs: Long) = clockMs - origin

    private fun id(raw: String): String = ids.getOrPut(raw) {
        val kind = when {
            raw.startsWith(VisionFaultAlerts.NOTICE_ID_PREFIX) -> 'n'
            raw.startsWith(SimSpeech.SYSTEM_NOTICE_PREFIX) -> 's'
            UUID_PATTERN.matches(raw) -> 'e'
            else -> 'm'
        }
        val next = (counters[kind] ?: 0) + 1
        counters[kind] = next
        "$kind$next"
    }

    private fun line(atMs: Long, text: String) {
        flushQuiet()
        lines += "$atMs $text"
    }

    private fun flushQuiet() {
        val run = quiet ?: return
        quiet = null
        lines += "${run.lastMs} frames seq=${run.firstSeq}..${run.lastSeq} n=${run.count} from=${run.fromMs} q=${qualification(run.baseline)}"
    }

    private fun entry(entry: TimelineEntry) {
        when (entry) {
            is TimelineEntry.Frame -> frame(entry)
            is TimelineEntry.Script -> line(entry.atMs, "script ${entry.action}")
            is TimelineEntry.State -> line(entry.atMs, state(entry.trace))
            is TimelineEntry.Alert -> line(entry.atMs, alert(entry.trace))
            is TimelineEntry.Outcome -> line(entry.atMs, outcome(entry.trace))
            is TimelineEntry.Receipt -> line(entry.atMs, "receipt ${entry.kind} ${id(entry.eventId)}" + (entry.detail?.let { " $it" } ?: ""))
            is TimelineEntry.Speech -> line(entry.atMs, speech(entry))
            is TimelineEntry.Motor -> line(
                entry.atMs,
                "motor ${entry.kind}" + (entry.rhythm?.let { " ${it.name.lowercase()}" } ?: "") + (entry.result?.let { " -> $it" } ?: ""),
            )
            is TimelineEntry.ScreenReader -> line(entry.atMs, "screen_reader \"${entry.text}\"")
            is TimelineEntry.Description -> line(entry.atMs, "description ${entry.what}")
            is TimelineEntry.Ui -> line(entry.atMs, "ui ${entry.what}" + (entry.detail?.let { " \"$it\"" } ?: ""))
        }
    }

    private fun frame(frame: TimelineEntry.Frame) {
        val changed = previousBaseline != frame.baseline
        previousBaseline = frame.baseline
        if (frame.events.isEmpty() && !changed) {
            val run = quiet
            if (run != null) {
                run.lastMs = frame.atMs
                run.lastSeq = frame.seq
                run.count++
            } else {
                quiet = QuietRun(frame.atMs, frame.seq, frame.baseline)
            }
            return
        }
        val events = frame.events.joinToString(",") { event ->
            val params = event.messageParams.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
            "${id(event.id.toString())}:${event.messageKey}:${event.priority.name}" +
                (if (params.isEmpty()) "" else "{$params}") +
                "@${event.distance?.name ?: "-"}~${rel(event.expiresAt)}"
        }
        line(frame.atMs, "frame seq=${frame.seq} q=${qualification(frame.baseline)} events=[$events]")
    }

    private fun qualification(baseline: BaselineQualification): String = when (baseline) {
        BaselineQualification.Qualified -> "qualified"
        is BaselineQualification.Degraded -> "degraded(${baseline.traceReasons.joinToString(",")})"
        is BaselineQualification.Unqualified -> {
            val also = baseline.alsoDegraded.sorted().joinToString(",") { it.name.lowercase() }
            "unqualified(${baseline.reason.name.lowercase()}" + (if (also.isEmpty()) "" else ";also=$also") + ")"
        }
    }

    private fun state(trace: SafetyStateTrace): String = buildString {
        append("state ").append(trace.stateLabel)
        append(" since=").append(rel(trace.stateSinceElapsedMs))
        append(" phase=").append(trace.phase)
        append(" interrupted=").append(trace.interrupted)
        append(" stall=").append(trace.stall)
        append(" judgement=").append(trace.judgement ?: "none")
        if (trace.judgementReasons.isNotEmpty()) append('(').append(trace.judgementReasons.joinToString(",")).append(')')
        append(" faults=[").append(trace.outputFaults.joinToString(",")).append(']')
        append(" channel=").append(trace.expectedNavigationChannel)
        append(" cause=").append(trace.cause)
    }

    private fun alert(trace: GuidanceAlertTrace): String =
        "alert ${trace.source} ${trace.kind} ${trace.messageKey ?: trace.haptic} priority=${trace.priority} channel=${trace.channel} result=${trace.result}"

    private fun outcome(trace: PromptOutcomeTrace): String {
        val head = "outcome ${id(trace.eventId)} src=${trace.sourceSequenceNumber} ${trace.messageKey} ${trace.priority.uppercase()}"
        return if (trace.deliveredAt != null) "$head delivered=${trace.deliveredVia.joinToString(",")}" else "$head revoked=${trace.revokeReason}"
    }

    private fun speech(entry: TimelineEntry.Speech): String = buildString {
        append("speech ").append(entry.kind)
        entry.id?.let { append(' ').append(id(it)) }
        entry.text?.let { append(" \"").append(it).append('"') }
        entry.scale?.let { append(" scale=").append(if (it == Int.MAX_VALUE) "max" else it.toString()) }
        entry.expiresAtMs?.let { append(" exp=").append(rel(it)) }
        entry.result?.let { append(" -> ").append(it) }
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
