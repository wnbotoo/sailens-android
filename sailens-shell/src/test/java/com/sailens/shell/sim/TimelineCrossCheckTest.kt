package com.sailens.shell.sim

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.HoldTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.UnqualifiedReason
import com.sailens.shell.device.GuidanceHaptic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cross-check on its own, with entries written by hand: what it lets through, and what it must not. */
class TimelineCrossCheckTest {

    /** A state record; a stop holds prompts from its own moment, as the monitor's does. */
    private fun state(atMs: Long, state: String, judgement: String?, cause: String, reasons: List<String> = emptyList(), judgementReasons: List<String> = emptyList()) =
        TimelineEntry.State(
            atMs,
            SafetyStateTrace(
                at = 0, elapsedMs = atMs, stateSinceElapsedMs = atMs, state = state, reasons = reasons, phase = "running",
                interrupted = false, stall = "none", judgement = judgement, judgementReasons = judgementReasons,
                expectedNavigationChannel = "speech", cause = cause,
                hold = if (state == SafetyTraceNames.States.STOP_REQUIRED) HoldTrace(reasons.single(), atMs, stopping = true, freshResults = 0, freshSpanMs = 0) else null,
            ),
        )

    private fun frame(atMs: Long, seq: Long, baseline: BaselineQualification = BaselineQualification.Qualified, events: List<SceneEvent> = emptyList()) =
        TimelineEntry.Frame(atMs, seq, baseline, events)

    private fun systemAlert(atMs: Long, kind: String, channel: String, messageKey: String? = null, haptic: String? = null) = TimelineEntry.Alert(
        atMs,
        GuidanceAlertTrace(at = 0, elapsedMs = atMs, source = "system", kind = kind, messageKey = messageKey, haptic = haptic, priority = "max", channel = channel, result = "played"),
    )

    private val event = SceneEvent(timestamp = 0, category = EventCategory.OBSTACLE, priority = EventPriority.HIGH, messageKey = "event_obstacle_center_person", expiresAt = 1, dedupeKey = "d")

    private fun outcome(atMs: Long, src: Long) = TimelineEntry.Outcome(
        atMs,
        PromptOutcomeTrace(
            eventId = event.id.toString(), sourceSequenceNumber = src, messageKey = event.messageKey, category = "obstacle",
            priority = "high", deliveredAt = 1, deliveredVia = listOf("speech"), speechEnabled = true, screenReaderActive = false, hapticsEnabled = true,
        ),
    )

    @Test
    fun `the reference qualification is cleared by a start, set by each result, and kept by a stop`() {
        val session = listOf(
            TimelineEntry.Script(0, "start"),
            state(0, "initializing", null, "start_requested"),
            frame(1050, 1),
            state(1050, "guiding", "qualified", "result"),
            TimelineEntry.Script(2000, "stop"),
            state(2000, "idle", "qualified", "stopped"),
            TimelineEntry.Script(3000, "start"),
            state(3000, "initializing", null, "start_requested"),
        )
        assertEquals("qualified, stopped, started again, no result yet: none on either side", emptyList<String>(), TimelineCrossCheck.check(session))
        val keptAfterStart = session.dropLast(1) + state(3000, "initializing", "qualified", "start_requested")
        assertEquals(1, TimelineCrossCheck.check(keptAfterStart).size)
        val clearedByStop = session.take(5) + state(2000, "idle", null, "stopped")
        assertEquals(1, TimelineCrossCheck.check(clearedByStop).size)
    }

    @Test
    fun `a stop for the camera comes only after an unqualified frame`() {
        val unqualified = BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, emptySet())
        val stop = state(1150, "stop_required", "unqualified", "result", reasons = listOf("camera_obstructed"), judgementReasons = listOf("camera_obstructed"))
        assertEquals(emptyList<String>(), TimelineCrossCheck.check(listOf(TimelineEntry.Script(0, "start"), frame(1150, 2, unqualified), stop)))
        val problems = TimelineCrossCheck.check(listOf(TimelineEntry.Script(0, "start"), frame(1150, 2), stop))
        assertTrue(problems.toString(), problems.any { "stop_required(camera_obstructed) after a frame of this start judged" in it })
        val tooDark = state(1150, "stop_required", "unqualified", "result", reasons = listOf("too_dark"), judgementReasons = listOf("camera_obstructed"))
        assertTrue("for another reason than the frame's", TimelineCrossCheck.check(listOf(TimelineEntry.Script(0, "start"), frame(1150, 2, unqualified), tooDark)).any { "stop_required(too_dark)" in it })
    }

    @Test
    fun `a stop for the camera without a hold answers to the latest frame, and one held after its cause went to the frame that began it`() {
        val unqualified = BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, emptySet())
        val stop = state(1150, "stop_required", "unqualified", "result", reasons = listOf("camera_obstructed"), judgementReasons = listOf("camera_obstructed"))
        val unheld = stop.copy(trace = stop.trace.copy(hold = null))
        val unheldProblems = TimelineCrossCheck.check(listOf(TimelineEntry.Script(0, "start"), frame(1150, 2), unheld))
        assertTrue(unheldProblems.toString(), unheldProblems.any { "1150: stop_required(camera_obstructed) after a frame of this start judged" in it })

        fun recovering(sinceMs: Long) = state(1450, "stop_required", "qualified", "result", reasons = listOf("camera_obstructed")).let {
            it.copy(trace = it.trace.copy(hold = HoldTrace("camera_obstructed", sinceMs, stopping = false, freshResults = 1, freshSpanMs = 0, firstFreshReceivedElapsedMs = 1450, lastFreshReceivedElapsedMs = 1450, lastFreshSequenceNumber = 3)))
        }
        val begun = listOf(TimelineEntry.Script(0, "start"), frame(1150, 2, unqualified), stop, frame(1450, 3))
        assertEquals("held by the stop its frame began", emptyList<String>(), TimelineCrossCheck.check(begun + recovering(1150)))
        val notBegun = TimelineCrossCheck.check(begun + recovering(1300))
        assertTrue(notBegun.toString(), notBegun.any { "1450: stop_required(camera_obstructed) held by camera_obstructed since 1300, no frame of this start began it" in it })
        val restarted = listOf(TimelineEntry.Script(0, "start"), frame(1150, 2, unqualified), stop, TimelineEntry.Script(1300, "start"), frame(1450, 3))
        assertTrue("a frame of an earlier start began no stop of this one", TimelineCrossCheck.check(restarted + recovering(1150)).any { "since 1150, no frame of this start began it" in it })
    }

    @Test
    fun `a frame from before a start does not account for a stop for the camera after it`() {
        for ((reason, judged) in listOf("camera_obstructed" to UnqualifiedReason.CAMERA_OBSTRUCTED, "too_dark" to UnqualifiedReason.TOO_DARK)) {
            val unqualified = BaselineQualification.Unqualified(judged, emptySet())
            val earlier = listOf(
                TimelineEntry.Script(0, "start"),
                frame(1150, 2, unqualified),
                state(1150, "stop_required", "unqualified", "result", reasons = listOf(reason), judgementReasons = listOf(reason)),
                TimelineEntry.Script(2000, "stop"),
                state(2000, "idle", "unqualified", "stopped", judgementReasons = listOf(reason)),
                TimelineEntry.Script(3000, "start"),
            )
            // Started again, no frame yet: a stop for the camera has nothing of this start to come from.
            val stale = earlier + state(3000, "stop_required", null, "start_requested", reasons = listOf(reason))
            val problems = TimelineCrossCheck.check(stale)
            assertTrue("$reason: $problems", problems.any { "3000: stop_required($reason) after a frame of this start judged null" in it })
            val judgedAgain = earlier + frame(3050, 3, unqualified) +
                state(3050, "stop_required", "unqualified", "result", reasons = listOf(reason), judgementReasons = listOf(reason))
            assertEquals("$reason: a frame of the new start does", emptyList<String>(), TimelineCrossCheck.check(judgedAgain))
        }
    }

    @Test
    fun `each system alert is its device call, just before it, and each alarm and notice is an alert's`() {
        val alarm = TimelineEntry.Motor(8000, "alarm", GuidanceHaptic.INTERRUPTED, "played")
        val rhythm = systemAlert(8000, "interrupted", "haptics", haptic = "interrupted")
        val notice = TimelineEntry.Speech(8000, "notice", "system_notice_1", "notice_guidance_interrupted", Int.MAX_VALUE, 23_000, "accepted")
        val sentence = systemAlert(8000, "interrupted", "speech", messageKey = "notice_guidance_interrupted")
        assertEquals(emptyList<String>(), TimelineCrossCheck.check(listOf(alarm, rhythm, notice, sentence)))
        assertEquals("an alert with no call", 1, TimelineCrossCheck.check(listOf(rhythm)).size)
        assertEquals("a call with no alert", 1, TimelineCrossCheck.check(listOf(alarm)).size)
        assertEquals("a call of another rhythm", 2, TimelineCrossCheck.check(listOf(alarm.copy(rhythm = GuidanceHaptic.SPEECH_UNAVAILABLE), rhythm)).size)
        assertEquals("at another time", 2, TimelineCrossCheck.check(listOf(alarm.copy(atMs = 7000), rhythm)).size)
        assertEquals("not right after it", 1, TimelineCrossCheck.check(listOf(notice, TimelineEntry.Speech(8000, "stop"), sentence)).size)
        val reader = TimelineEntry.ScreenReader(8000, "notice_guidance_interrupted")
        assertEquals("the screen reader's", emptyList<String>(), TimelineCrossCheck.check(listOf(reader, systemAlert(8000, "interrupted", "screen_reader", messageKey = "notice_guidance_interrupted"))))
    }

    @Test
    fun `outcomes and receipts are of an event a frame decided, and an outcome names that frame`() {
        val decided = frame(1050, 1, events = listOf(event))
        assertEquals(emptyList<String>(), TimelineCrossCheck.check(listOf(decided, TimelineEntry.Receipt(1050, "confirm", event.id.toString(), null), outcome(1050, 1))))
        assertEquals("another frame", 1, TimelineCrossCheck.check(listOf(decided, outcome(1050, 2))).size)
        assertEquals("no frame", 1, TimelineCrossCheck.check(listOf(outcome(1050, 1))).size)
        assertEquals("a receipt of nothing decided", 1, TimelineCrossCheck.check(listOf(TimelineEntry.Receipt(1050, "confirm", event.id.toString(), null))).size)
    }

    @Test
    fun `a session's own entries pass, and a judgement changed in one is found`() {
        val run = SessionSimulator.run(SessionScenarios.speechOutage)
        assertEquals(emptyList<String>(), TimelineCrossCheck.check(run.entries))
        val restart = run.entries.indexOfFirst { it is TimelineEntry.State && it.trace.cause == "start_requested" && it.atMs > 0 }
        val tampered = run.entries.toMutableList().also {
            val entry = it[restart] as TimelineEntry.State
            it[restart] = entry.copy(trace = entry.trace.copy(judgement = "qualified"))
        }
        assertEquals(1, TimelineCrossCheck.check(tampered).size)
    }
}
