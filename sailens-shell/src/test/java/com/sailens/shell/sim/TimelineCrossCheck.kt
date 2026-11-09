package com.sailens.shell.sim

import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.traceLevel
import com.sailens.guidance.safety.traceReasons

/**
 * Checks a session's entries against each other, from records the safety monitor does not keep itself:
 *
 * - every state's judgement is the reference qualification: none from a start press until the next result, then
 *   each result's; a stop, the foreground or a failure keep it (the monitor clears it at a start request only);
 * - a stop for the camera (obstructed, too dark) comes only after a frame of the same start the baseline judged
 *   unqualified, for that reason: a frame from before a start says nothing of the run started;
 * - every system alert is the device call it says, at the same time and in the same place: its rhythm a
 *   `motor alarm` just before it, its sentence a `speech notice` or `screen_reader` line with its key; and every
 *   `motor alarm` and `speech notice` is one of theirs;
 * - every prompt's outcome and every receipt is of an event a frame decided, and an outcome's source is that frame.
 *
 * @return what does not hold, one line each; empty when everything does.
 */
internal object TimelineCrossCheck {

    fun check(entries: List<TimelineEntry>): List<String> {
        val problems = mutableListOf<String>()
        judgements(entries, problems)
        cameraStops(entries, problems)
        systemAlerts(entries, problems)
        sources(entries, problems)
        return problems
    }

    private fun judgements(entries: List<TimelineEntry>, problems: MutableList<String>) {
        var reference: BaselineQualification? = null
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Script -> if (entry.action == "start") reference = null
                is TimelineEntry.Frame -> reference = entry.baseline
                is TimelineEntry.State -> {
                    val expected = reference?.traceLevel to reference?.traceReasons.orEmpty()
                    val actual = entry.trace.judgement to entry.trace.judgementReasons
                    if (expected != actual) problems += "${entry.atMs}: state judgement $actual, the reference is $expected"
                }
                else -> Unit
            }
        }
    }

    private fun cameraStops(entries: List<TimelineEntry>, problems: MutableList<String>) {
        var latest: BaselineQualification? = null
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Script -> if (entry.action == "start") latest = null
                is TimelineEntry.Frame -> latest = entry.baseline
                is TimelineEntry.State -> {
                    if (entry.trace.state != SafetyTraceNames.States.STOP_REQUIRED) continue
                    val reason = entry.trace.reasons.singleOrNull { it in CAMERA_STOPS } ?: continue
                    val judged = (latest as? BaselineQualification.Unqualified)?.reason?.name?.lowercase()
                    if (judged != reason) problems += "${entry.atMs}: ${entry.trace.stateLabel} after a frame of this start judged $latest"
                }
                else -> Unit
            }
        }
    }

    private val CAMERA_STOPS = setOf("camera_obstructed", "too_dark")

    private fun systemAlerts(entries: List<TimelineEntry>, problems: MutableList<String>) {
        val matched = HashSet<Int>()
        entries.forEachIndexed { index, entry ->
            if (entry !is TimelineEntry.Alert || entry.trace.source != GuidanceAlertSources.SYSTEM) return@forEachIndexed
            val trace = entry.trace
            val call = (index - 1 downTo 0).firstOrNull { at ->
                val candidate = entries[at]
                at !in matched && candidate.atMs == entry.atMs && when (trace.channel) {
                    GuidanceAlertChannels.HAPTICS -> candidate is TimelineEntry.Motor && candidate.kind == "alarm" &&
                        candidate.rhythm?.name?.lowercase() == trace.haptic
                    GuidanceAlertChannels.SPEECH -> candidate is TimelineEntry.Speech && candidate.kind == "notice" &&
                        candidate.text == trace.messageKey
                    GuidanceAlertChannels.SCREEN_READER -> candidate is TimelineEntry.ScreenReader && candidate.text == trace.messageKey
                    else -> false
                }
            }
            if (call == null) {
                problems += "${entry.atMs}: system alert ${trace.kind} on ${trace.channel} has no device call before it"
            } else {
                matched += call
                val between = entries.subList(call + 1, index)
                if (between.any { it is TimelineEntry.Motor || it is TimelineEntry.Speech || it is TimelineEntry.ScreenReader }) {
                    problems += "${entry.atMs}: system alert ${trace.kind} on ${trace.channel} is not right after its device call"
                }
            }
        }
        entries.forEachIndexed { index, entry ->
            val ours = (entry is TimelineEntry.Motor && entry.kind == "alarm") || (entry is TimelineEntry.Speech && entry.kind == "notice")
            if (ours && index !in matched) problems += "${entry.atMs}: $entry is no system alert's"
        }
    }

    private fun sources(entries: List<TimelineEntry>, problems: MutableList<String>) {
        val decidedBy = HashMap<String, Long>()
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Frame -> entry.events.forEach { decidedBy[it.id.toString()] = entry.seq }
                is TimelineEntry.Outcome -> {
                    val seq = decidedBy[entry.trace.eventId]
                    if (seq != entry.trace.sourceSequenceNumber) {
                        problems += "${entry.atMs}: outcome of ${entry.trace.messageKey} names frame ${entry.trace.sourceSequenceNumber}, decided by $seq"
                    }
                }
                is TimelineEntry.Receipt -> if (entry.eventId !in decidedBy) problems += "${entry.atMs}: receipt ${entry.kind} of an event no frame decided"
                else -> Unit
            }
        }
    }
}
