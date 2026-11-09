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
 * - every prompt's outcome and every receipt is of an event a frame decided, and an outcome's source is that frame;
 * - a stop or an interruption always holds ordinary prompts, and a hold shows only a stop, an interruption or
 *   initializing;
 * - every hold released was released by a fresh result: the frames counted since its stop began were received after it,
 *   there were at least three of them, their receipts span at least 500 ms, and the release names the last of them.
 *
 * A stop for the camera that is still held while its cause has gone is checked against the frame that began it.
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
        holdsCoverStops(entries, problems)
        releases(entries, problems)
        return problems
    }

    /**
     * The ordinary prompts that reached the user while prompts were held: delivered outcomes of events the vision-fault
     * alerts do not tell, and navigation rhythms that started. One line each.
     */
    fun heldDeliveries(entries: List<TimelineEntry>): List<String> {
        val found = mutableListOf<String>()
        var held = false
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.State -> held = entry.trace.hold != null
                is TimelineEntry.Outcome -> if (held && entry.trace.deliveredAt != null && entry.trace.category !in VISION_CATEGORIES) {
                    found += "${entry.atMs}: outcome ${entry.trace.messageKey} delivered=${entry.trace.deliveredVia.joinToString(",")}"
                }
                is TimelineEntry.Motor -> if (held && entry.kind == "start" && entry.rhythm?.isNavigation == true) {
                    found += "${entry.atMs}: motor start ${entry.rhythm.name.lowercase()}"
                }
                else -> Unit
            }
        }
        return found
    }

    private val VISION_CATEGORIES = setOf("sensor_quality", "ground_unrecognized")

    private val com.sailens.shell.device.GuidanceHaptic.isNavigation: Boolean
        get() = this != com.sailens.shell.device.GuidanceHaptic.VISION_UNRELIABLE &&
            this != com.sailens.shell.device.GuidanceHaptic.INTERRUPTED &&
            this != com.sailens.shell.device.GuidanceHaptic.SPEECH_UNAVAILABLE

    private fun holdsCoverStops(entries: List<TimelineEntry>, problems: MutableList<String>) {
        for (entry in entries) {
            if (entry !is TimelineEntry.State) continue
            val trace = entry.trace
            val stopClass = trace.state == SafetyTraceNames.States.STOP_REQUIRED || trace.state == SafetyTraceNames.States.INTERRUPTED
            if (stopClass && trace.hold == null) problems += "${entry.atMs}: ${trace.stateLabel} holds no prompts"
            if (trace.hold != null && !stopClass && trace.state != SafetyTraceNames.States.INITIALIZING) {
                problems += "${entry.atMs}: a hold shows ${trace.stateLabel}"
            }
        }
    }

    private fun releases(entries: List<TimelineEntry>, problems: MutableList<String>) {
        // Per stop: when it began (relative), and the usable frames that arrived from the moment it stopped stopping.
        var since: Long? = null
        var recovering = false
        val counted = mutableListOf<TimelineEntry.Frame>()
        var pending: TimelineEntry.Frame? = null
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Script -> if (entry.action == "start") { since = null; recovering = false; counted.clear() }
                is TimelineEntry.Frame -> pending = entry
                is TimelineEntry.State -> {
                    val trace = entry.trace
                    val offset = trace.elapsedMs - entry.atMs
                    val hold = trace.hold
                    val arrived = pending?.takeIf { it.atMs == entry.atMs && trace.cause == SafetyTraceNames.Causes.RESULT }
                    pending = null
                    if (hold != null && hold.stopping) {
                        since = hold.sinceElapsedMs - offset
                        recovering = false
                        counted.clear()
                    } else if (hold != null || trace.holdReleased != null) {
                        val stopSince = since
                        if (stopSince == null) { problems += "${entry.atMs}: a hold recovers with no stop seen"; continue }
                        recovering = true
                        if (arrived != null && arrived.baseline !is BaselineQualification.Unqualified) {
                            val received = arrived.receivedAtMs ?: arrived.atMs
                            if (received > stopSince) counted += arrived
                        }
                        trace.holdReleased?.let { release ->
                            val first = counted.firstOrNull()
                            val last = counted.lastOrNull()
                            val span = if (first == null || last == null) 0 else (last.receivedAtMs ?: last.atMs) - (first.receivedAtMs ?: first.atMs)
                            if (counted.size != release.freshResults || span != release.freshSpanMs || last?.seq != release.sourceSequenceNumber ||
                                release.sinceElapsedMs - offset != stopSince || release.freshResults < 3 || release.freshSpanMs < 500
                            ) {
                                problems += "${entry.atMs}: released by ${release.sourceSequenceNumber} with ${release.freshResults} in ${release.freshSpanMs} ms, " +
                                    "the frames say ${counted.map { it.seq }} in $span ms since $stopSince"
                            }
                            since = null
                            recovering = false
                            counted.clear()
                        }
                    } else if (recovering) {
                        // The hold went without a release: the session ended or failed.
                        since = null
                        recovering = false
                        counted.clear()
                    }
                }
                else -> Unit
            }
        }
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
        val unqualifiedAt = HashMap<String, MutableSet<Long>>()
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Script -> if (entry.action == "start") {
                    latest = null
                    unqualifiedAt.clear()
                }
                is TimelineEntry.Frame -> {
                    latest = entry.baseline
                    (entry.baseline as? BaselineQualification.Unqualified)?.let { unqualifiedAt.getOrPut(it.reason.name.lowercase()) { HashSet() } += entry.atMs }
                }
                is TimelineEntry.State -> {
                    if (entry.trace.state != SafetyTraceNames.States.STOP_REQUIRED) continue
                    val reason = entry.trace.reasons.singleOrNull { it in CAMERA_STOPS } ?: continue
                    val hold = entry.trace.hold
                    if (hold == null || hold.stopping) {
                        val judged = (latest as? BaselineQualification.Unqualified)?.reason?.name?.lowercase()
                        if (judged != reason) problems += "${entry.atMs}: ${entry.trace.stateLabel} after a frame of this start judged $latest"
                    } else {
                        // Its cause has gone and the hold keeps the stop: the hold is that stop's, begun by a frame of this start.
                        val since = hold.sinceElapsedMs - (entry.trace.elapsedMs - entry.atMs)
                        if (hold.cause != reason || since !in unqualifiedAt[reason].orEmpty()) {
                            problems += "${entry.atMs}: ${entry.trace.stateLabel} held by ${hold.cause} since $since, no frame of this start began it"
                        }
                    }
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
