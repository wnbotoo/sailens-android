package com.sailens.shell.sim

import com.sailens.guidance.delivery.isOrdinaryPrompt
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.PromptRevokeReasons
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.traceLevel
import com.sailens.guidance.safety.traceReasons
import com.sailens.shell.guidance.screen.StatusTitle

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
 *   there were at least three of them, their receipts span at least 500 ms, and the release names the last of them;
 * - while a hold stands no ordinary prompt reaches the user ([ordinaryDeliveriesDuringHolds]): what a channel took
 *   before the hold began is not a delivery during it;
 * - the frame that releases a hold offers its own prompt, which is not held;
 * - no prompt decided while a hold stands is confirmed to the cooldown while it stands.
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
        problems += ordinaryDeliveriesDuringHolds(entries)
        releasesOffered(entries, problems)
        confirmsDuringHolds(entries, problems)
        return problems
    }

    /**
     * The ordinary prompts that reached the user while a hold stood: a delivered outcome, own speech accepting one, the
     * screen reader handed one, or the motor asked for or starting a navigation rhythm. An ordinary prompt is a frame's
     * event the vision-fault alerts do not tell ([com.sailens.guidance.delivery.isOrdinaryPrompt]). One line each.
     */
    fun ordinaryDeliveriesDuringHolds(entries: List<TimelineEntry>): List<String> {
        val ordinary = HashMap<String, String>()
        val found = mutableListOf<String>()
        var held = false
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Script -> if (entry.action == "start") held = false
                is TimelineEntry.Frame -> entry.events.filter { it.isOrdinaryPrompt }.forEach { ordinary[it.id.toString()] = it.messageKey }
                is TimelineEntry.State -> held = entry.trace.hold != null
                is TimelineEntry.Outcome -> if (held && entry.trace.deliveredAt != null && entry.trace.eventId in ordinary) {
                    found += "${entry.atMs}: held, outcome ${entry.trace.messageKey} delivered=${entry.trace.deliveredVia.joinToString(",")}"
                }
                is TimelineEntry.Speech -> if (held && entry.kind == "speak" && entry.result == "accepted" && entry.id in ordinary) {
                    found += "${entry.atMs}: held, speech accepted ${ordinary[entry.id]}"
                }
                is TimelineEntry.ScreenReader -> if (held && entry.text.substringBefore('{') in ordinary.values) {
                    found += "${entry.atMs}: held, screen reader ${entry.text}"
                }
                is TimelineEntry.Motor -> if (held && entry.rhythm?.isNavigation == true && entry.kind in NAVIGATION_STARTS) {
                    found += "${entry.atMs}: held, motor ${entry.kind} ${entry.rhythm.name.lowercase()}"
                }
                else -> Unit
            }
        }
        return found
    }

    /** The motor lines that ask for or start a navigation rhythm; `drop` and `drop_queued` withdraw one. */
    private val NAVIGATION_STARTS = setOf("trigger", "queue", "start")

    /** The frame whose result released a hold offers its primary prompt as ever: its outcome is not a hold's. */
    private fun releasesOffered(entries: List<TimelineEntry>, problems: MutableList<String>) {
        var lastFrame: TimelineEntry.Frame? = null
        var awaited: String? = null
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Frame -> {
                    awaited?.let { problems += "${entry.atMs}: the frame that released the hold never offered its prompt" }
                    awaited = null
                    lastFrame = entry
                }
                is TimelineEntry.State -> if (entry.trace.holdReleased != null) {
                    val primary = lastFrame?.events?.firstOrNull()
                    if (primary != null && primary.isOrdinaryPrompt) awaited = primary.id.toString()
                }
                is TimelineEntry.Outcome -> if (entry.trace.eventId == awaited) {
                    if (entry.trace.revokeReason == PromptRevokeReasons.SAFETY_HOLD) {
                        problems += "${entry.atMs}: the frame that released the hold held its own prompt ${entry.trace.messageKey}"
                    }
                    awaited = null
                }
                else -> Unit
            }
        }
    }

    /** A prompt decided while a hold stands is not offered, so nothing confirms it to the cooldown while the hold stands. */
    private fun confirmsDuringHolds(entries: List<TimelineEntry>, problems: MutableList<String>) {
        val decidedHeld = HashSet<String>()
        var held = false
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Script -> if (entry.action == "start") held = false
                is TimelineEntry.State -> {
                    held = entry.trace.hold != null
                    if (!held) decidedHeld.clear()
                }
                is TimelineEntry.Frame -> if (held) entry.events.filter { it.isOrdinaryPrompt }.forEach { decidedHeld += it.id.toString() }
                is TimelineEntry.Receipt -> if (held && entry.kind == "confirm" && entry.eventId in decidedHeld) {
                    problems += "${entry.atMs}: held, a prompt decided during the hold was confirmed"
                }
                else -> Unit
            }
        }
    }

    /**
     * The status card across holds, from the screen's real state after each result ([cards]): no event while prompts
     * are held, and after a release no event decided before the frame that released it.
     */
    fun cardsAcrossHolds(entries: List<TimelineEntry>, cards: List<CardSample>): List<String> {
        val decidedBy = HashMap<String, Long>()
        entries.filterIsInstance<TimelineEntry.Frame>().forEach { frame -> frame.events.forEach { decidedBy[it.id.toString()] = frame.seq } }
        val problems = mutableListOf<String>()
        var releasedAt: Long? = null
        for (card in cards) {
            if (card.held) {
                if (card.eventId != null) problems += "frame ${card.seq}: held, the card shows an event"
                releasedAt = -1
                continue
            }
            if (releasedAt == -1L) releasedAt = card.seq
            val shown = card.eventId ?: continue
            val since = releasedAt ?: continue
            val decided = decidedBy[shown]
            if (decided == null || decided < since) problems += "frame ${card.seq}: the card shows an event from before the release at frame $since"
        }
        return problems
    }

    /**
     * What the status card claims, each time the session settled ([screens]), against the safety state the trace last
     * recorded: it says the way looks clear only while that state is guiding, or degraded by the output alone; it names
     * an event only while Guidance judges; and while a judgement is paused it names that judgement, unless an ordinary
     * prompt or speech gone has the card. Before the first record it claims nothing.
     */
    fun cardClaims(screens: List<ScreenSample>): List<String> {
        val problems = mutableListOf<String>()
        for (screen in screens) {
            val title = screen.card.title
            val traced = screen.traced
            val label = traced?.stateLabel ?: "no state recorded"
            val judges = traced?.state == SafetyTraceNames.States.GUIDING || traced?.state == SafetyTraceNames.States.DEGRADED
            val paused = if (traced?.state == SafetyTraceNames.States.DEGRADED) traced.reasons - traced.outputFaults.toSet() else emptyList()
            if (title == StatusTitle.Clear && !(judges && paused.isEmpty())) problems += "${screen.atMs}: the card says the way looks clear while $label"
            if (title is StatusTitle.Event && !judges) problems += "${screen.atMs}: the card names ${title.event.messageKey} while $label"
            paused.filter { it !in PAUSED_SENTENCES }.forEach { problems += "${screen.atMs}: $it paused, a judgement this check does not know" }
            if (title is StatusTitle.Paused && title.messageKey !in paused.mapNotNull(PAUSED_SENTENCES::get)) {
                problems += "${screen.atMs}: the card names ${title.messageKey} while $label"
            }
            val namesOrdinaryPrompt = title is StatusTitle.Event && title.event.isOrdinaryPrompt
            if (paused.isNotEmpty() && title !is StatusTitle.Paused && !namesOrdinaryPrompt && title != StatusTitle.SpeechUnavailable) {
                problems += "${screen.atMs}: $label, the card shows $title"
            }
        }
        return problems
    }

    /** Each paused judgement as the trace names it, and the sentence its notice is spoken with. */
    private val PAUSED_SENTENCES = mapOf("ground_unrecognized" to SceneEventMessageKeys.GROUND_UNRECOGNIZED)

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
