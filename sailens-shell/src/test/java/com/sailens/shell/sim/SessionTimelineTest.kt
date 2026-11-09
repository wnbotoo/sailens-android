package com.sailens.shell.sim

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every session timeline, run through the real pipeline, alerts and delivery, must produce its checked-in
 * `<name>.timeline.txt` line for line, and must reach what it is there to exercise: each test asserts that on top of
 * the timeline, so a session that drifts off its path fails even when its timeline is regenerated. Every session
 * also passes the cross-check ([TimelineCrossCheck]) and logs no disagreement between the screen's facts and the
 * safety monitor, and no observation fault. Regenerate with
 *
 * ```
 * gradlew :sailens-shell:testDebugUnitTest --tests "*SessionTimelineTest*" -PupdateGoldens
 * ```
 *
 * Each session's test is named after it.
 */
class SessionTimelineTest {

    @Test
    fun session_clear_walk() {
        val run = check(SessionScenarios.sessionClearWalk)
        val states = run.states()
        assertEquals("start pressed: initializing", "0 initializing start_requested", states.first { it.cause == "start_requested" }.describe())
        val flowStarted = states.single { it.cause == "flow_started" }
        assertEquals("the flow's start changes the phase only", "0 initializing awaiting_first_result", "${flowStarted.at} ${flowStarted.label} ${flowStarted.phase}")
        assertEquals("the first result guides", "1050 guiding result", states.first { it.cause == "result" }.describe())
        assertEquals("the stop", "11000 idle stopped", states.last().describe())
        assertTrue("nothing is alerted", run.entries.none { it is TimelineEntry.Alert })
    }

    @Test
    fun lens_covered_short() {
        val run = check(SessionScenarios.lensCoveredShort)
        val script = SessionScenarios.lensCoveredShort
        val confirmed = script.frameAtMs(SessionScenarios.LENS_COVERED.first + 5)
        val stop = run.states().first { it.label == "stop_required(camera_obstructed)" }
        assertEquals("the sixth covered frame stops guidance", confirmed, stop.at)
        assertEquals("on that frame the notice is taken and the rhythm requested and played", listOf("fault_notice accepted", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(confirmed))
        assertEquals("a cycle later, said and felt again", listOf("fault_notice accepted", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(confirmed + 8_000))
        val clear = script.frameAtMs(SessionScenarios.LENS_COVERED.last + 6)
        assertEquals("the sixth clear frame guides again", "$clear guiding result", run.states().first { it.at > confirmed && it.label == "guiding" }.describe())
        assertEquals("the lens is said clear once it has stayed clear for 2 s", listOf("fault_recovery accepted"), run.visionAlertsAt(clear + 2_000))
        val underCover = run.entries.filterIsInstance<TimelineEntry.Frame>().filter { it.atMs in confirmed until clear }
        assertTrue("a person is tracked ahead under the cover", underCover.any { ObstacleCategory.PERSON in it.tracked })
        assertTrue(
            "and nothing but the covered lens is decided while it holds",
            underCover.all { frame -> frame.events.all { it.category == EventCategory.SENSOR_QUALITY } },
        )
        assertTrue("no system alert", run.systemAlerts().isEmpty())
    }

    @Test
    fun background_and_back() {
        val run = check(SessionScenarios.backgroundAndBack)
        val interrupted = run.states().single { it.cause == "lifecycle_stop" }
        assertEquals("8000 interrupted(lifecycle) lifecycle_stop", interrupted.describe())
        assertEquals(
            "marked, then felt whatever the switch, then said at the highest priority",
            listOf("ui mark_interrupted", "motor alarm interrupted", "alert interrupted haptics played", "speech notice notice_guidance_interrupted max", "alert interrupted speech requested"),
            run.deviceAndAlertsAt(8_000),
        )
        val inBackground = run.states().filter { it.at in 8_001..12_999 }
        assertTrue("the stall condition comes in the background", inBackground.any { it.cause == "poll" && it.stall == "no_results" && it.label == "interrupted(lifecycle)" })
        assertEquals("the foreground starts over", "13000 initializing lifecycle_start", run.states().single { it.cause == "lifecycle_start" }.describe())
        assertTrue("the interruption is cleared", run.entries.any { it is TimelineEntry.Ui && it.atMs == 13_000L && it.what == "clear_interrupted" })
        assertEquals("the first frame back guides", "13350 guiding result", run.states().first { it.at > 13_000 && it.label == "guiding" }.describe())
        assertTrue("nothing stalls", run.systemAlerts().none { it.kind == GuidanceAlertKinds.STALLED })
    }

    @Test
    fun speech_outage() {
        val run = check(SessionScenarios.speechOutage)
        val unavailable = run.systemAlerts().filter { it.kind == GuidanceAlertKinds.SPEECH_UNAVAILABLE }
        assertEquals(
            "one alert per outage, by vibration: the first, the second, and the third once own speech is back",
            listOf(6_000L to GuidanceAlertChannels.HAPTICS, 26_000L to GuidanceAlertChannels.HAPTICS, 46_000L to GuidanceAlertChannels.HAPTICS),
            unavailable.map { it.at to it.channel },
        )
        assertEquals("the first outage", "6000 degraded(speech_unavailable) output", run.states().first { it.at == 6_000L }.describe())
        val outageOutcome = run.outcomes().single { it.at in 6_001..11_999 }
        assertEquals("a prompt in the outage goes to vibration", listOf(PromptDeliveryChannels.HAPTICS), outageOutcome.via)
        assertTrue("own speech refused it", run.speech().any { it.atMs == outageOutcome.at && it.kind == "speak" && it.result == "refused(not_ready)" })
        assertTrue("the rebuild at 12000 reaches no input: the state flow does not change", run.states().none { it.at == 12_000L })
        val nextResult = run.states().first { it.at > 12_000 }
        assertEquals("the next result reads the engine ready: speech, still faulty", "12050 degraded(speech_unavailable) result speech", "${nextResult.describe()} ${nextResult.channel}")
        val firstTaken = run.speech().first { it.atMs > 12_000 && it.result == "accepted" }
        val ready = run.states().first { it.at >= firstTaken.atMs && it.cause == "output" }
        assertEquals("the first sentence taken makes it ready", "${firstTaken.atMs} guiding output", ready.describe())
        assertTrue(
            "a stop and a start inside the second outage alert nothing more",
            run.entries.any { it is TimelineEntry.Script && it.atMs == 30_000L && it.action == "stop" } &&
                run.entries.any { it is TimelineEntry.Script && it.atMs == 31_000L && it.action == "start" } &&
                unavailable.none { it.at in 26_001..45_999 },
        )
        assertEquals(
            "the screen reader's turn inside the second outage reaches the monitor, and is not alerted",
            listOf("33000 guiding output", "34000 degraded(speech_unavailable) output"),
            run.states().filter { it.at in 33_000..34_000 }.map { it.describe() },
        )
        assertTrue("an outage under the screen reader changes nothing the monitor holds", run.states().none { it.at in 42_000..45_999 })
    }

    @Test
    fun results_stop() {
        val run = check(SessionScenarios.resultsStop)
        val stalls = run.systemAlerts().filter { it.kind == GuidanceAlertKinds.STALLED && it.channel == GuidanceAlertChannels.HAPTICS }
        assertEquals("the stall is alarmed twice", 2, stalls.size)
        val lastResult = run.lastFrameBefore(stalls[0].at)
        assertTrue("the first alarm comes 2.5 to 3 s after the last result (${stalls[0].at - lastResult})", stalls[0].at - lastResult in 2_500..3_000)
        assertEquals("and again 10 s later", stalls[0].at + 10_000, stalls[1].at)
        assertEquals("${stalls[0].at} stop_required(perception_stalled) poll", run.states().first { it.at == stalls[0].at }.describe())
        assertEquals(
            "the stall preempts, vibrates, then says it",
            listOf("motor alarm interrupted", "alert stalled haptics played", "speech notice notice_guidance_stalled 16", "alert stalled speech requested"),
            run.deviceAndAlertsAt(stalls[0].at),
        )
        assertEquals("the first result back guides", "25050 guiding result", run.states().first { it.at > stalls[1].at && it.label == "guiding" }.describe())
    }

    @Test
    fun results_stop_background() {
        val run = check(SessionScenarios.resultsStopBackground)
        val stalls = run.systemAlerts().filter { it.kind == GuidanceAlertKinds.STALLED && it.channel == GuidanceAlertChannels.HAPTICS }
        assertEquals("only the stall before the background is alarmed", listOf(9_500L), stalls.map { it.at })
        assertEquals("12000 interrupted(lifecycle) lifecycle_stop", run.states().single { it.cause == "lifecycle_stop" }.describe())
        assertTrue(
            "the background holds over the 10 s repeat",
            run.states().filter { it.at in 12_000..21_999 }.all { it.label == "interrupted(lifecycle)" },
        )
        assertEquals("the foreground starts over", "22000 initializing lifecycle_start", run.states().single { it.cause == "lifecycle_start" }.describe())
        assertEquals("the first result back guides", "25050 guiding result", run.states().first { it.at > 22_000 && it.label == "guiding" }.describe())
    }

    @Test
    fun pipeline_failure_slow() {
        val run = check(SessionScenarios.pipelineFailureSlow)
        val script = SessionScenarios.pipelineFailureSlow
        val failedAt = thirtiethFailure(script)
        val stall = run.systemAlerts().single { it.kind == GuidanceAlertKinds.STALLED && it.channel == GuidanceAlertChannels.HAPTICS }
        assertEquals("the stall is alarmed first, 2.5 s after the last result", run.lastFrameBefore(stall.at) + 2_550, stall.at)
        assertTrue("before the failure", stall.at < failedAt)
        assertFailure(run, failedAt)
    }

    @Test
    fun pipeline_failure_fast() {
        val run = check(SessionScenarios.pipelineFailureFast)
        val failedAt = thirtiethFailure(SessionScenarios.pipelineFailureFast)
        assertTrue("the failure comes within 1.1 s of the first failing frame", failedAt - SessionScenarios.FAILING_FROM_MS < 1_100)
        assertTrue("nothing stalls", run.systemAlerts().none { it.kind == GuidanceAlertKinds.STALLED })
        assertFailure(run, failedAt)
    }

    @Test
    fun `every session has a test named after it`() {
        val tests = javaClass.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.toSet()
        val untested = SessionScenarios.all.map { it.name }.filter { it !in tests }
        assertEquals("add a test named after each session", emptyList<String>(), untested)
        assertEquals("session names are unique", SessionScenarios.all.size, SessionScenarios.all.map { it.name }.toSet().size)
    }

    @Test
    fun `a run is deterministic`() {
        for (script in listOf(SessionScenarios.speechOutage, SessionScenarios.pipelineFailureSlow)) {
            assertEquals(script.name, SessionSimulator.run(script).lines, SessionSimulator.run(script).lines)
        }
    }

    // ---- helpers

    private fun check(script: SessionScript): SessionRun {
        val run = SessionSimulator.run(script)
        Timelines.check(script.name, run.lines)
        assertEquals("no disagreement and no observation fault", emptyList<LoggedLine>(), run.observationFaults)
        assertEquals("the cross-check", emptyList<String>(), TimelineCrossCheck.check(run.entries))
        return run
    }

    /** The time of the frame on which the segmentation model fails for the thirtieth time in a row. */
    private fun thirtiethFailure(script: SessionScript): Long =
        script.frameAtMs(script.scenario.frames.indexOfFirst { it.semFails } + 29)

    /**
     * The failure, in the order the outputs must see it: the state, the outputs stopped (speech, then the motor), the
     * last result forgotten, then felt and said, and the screen shows it last of all. No frame is sent after it.
     */
    private fun assertFailure(run: SessionRun, failedAt: Long) {
        assertEquals("$failedAt failed(pipeline_failed) flow_failed", run.states().single { it.cause == "flow_failed" }.describe())
        val order = run.deviceAndAlertsAt(failedAt)
        val stop = order.indexOf("speech stop")
        val motorStop = order.indexOf("motor cancel")
        val rhythm = order.indexOf("motor alarm interrupted")
        assertTrue("the outputs stop before the failure's rhythm: $order", stop in 0 until motorStop && motorStop < rhythm)
        assertEquals(
            "then the failure is felt and said, at the highest priority",
            listOf("ui clear_latest_result", "motor alarm interrupted", "alert failed haptics played", "speech notice notice_guidance_failed max", "alert failed speech requested", "ui mark_failed"),
            order.drop(order.indexOf("ui clear_latest_result")),
        )
        val last = run.entries.last { it.atMs == failedAt }
        assertTrue("the screen shows it last: $last", last is TimelineEntry.Ui && last.what == "mark_failed")
        assertTrue("no frame is sent after it", run.delivered.none { run.script.frameAtMs(it) > failedAt })
    }

    private data class StateLine(val at: Long, val label: String, val phase: String, val stall: String, val channel: String, val cause: String) {
        fun describe() = "$at $label $cause"
    }

    private data class AlertLine(val at: Long, val kind: String, val channel: String, val result: String)

    private data class OutcomeLine(val at: Long, val key: String, val via: List<String>)

    private fun SessionRun.states() = entries.filterIsInstance<TimelineEntry.State>().map {
        StateLine(it.atMs, it.trace.stateLabel, it.trace.phase, it.trace.stall, it.trace.expectedNavigationChannel, it.trace.cause)
    }

    private fun SessionRun.systemAlerts() = entries.filterIsInstance<TimelineEntry.Alert>()
        .filter { it.trace.source == GuidanceAlertSources.SYSTEM }
        .map { AlertLine(it.atMs, it.trace.kind, it.trace.channel, it.trace.result) }

    private fun SessionRun.visionAlertsAt(atMs: Long) = entries.filterIsInstance<TimelineEntry.Alert>()
        .filter { it.atMs == atMs && it.trace.source == GuidanceAlertSources.VISION }
        .map { "${it.trace.kind} ${it.trace.result}" }

    private fun SessionRun.outcomes() = entries.filterIsInstance<TimelineEntry.Outcome>().map {
        OutcomeLine(it.atMs, it.trace.messageKey, it.trace.deliveredVia)
    }

    private fun SessionRun.speech() = entries.filterIsInstance<TimelineEntry.Speech>()

    /** The device calls, the system alerts and the UI ports at [atMs], in order, without ids or times. */
    private fun SessionRun.deviceAndAlertsAt(atMs: Long): List<String> = entries.filter { it.atMs == atMs }.mapNotNull { entry ->
        when (entry) {
            is TimelineEntry.Motor -> "motor ${entry.kind}" + (entry.rhythm?.let { " ${it.name.lowercase()}" } ?: "")
            is TimelineEntry.Speech -> "speech ${entry.kind}" + (entry.text?.let { " $it" } ?: "") +
                (entry.scale?.let { " " + if (it == Int.MAX_VALUE) "max" else it.toString() } ?: "")
            is TimelineEntry.Alert -> if (entry.trace.source == GuidanceAlertSources.SYSTEM) "alert ${entry.trace.kind} ${entry.trace.channel} ${entry.trace.result}" else null
            is TimelineEntry.Ui -> "ui ${entry.what}"
            else -> null
        }
    }.filter { !it.startsWith("speech cut") && !it.startsWith("speech drop") }

    private fun SessionRun.lastFrameBefore(atMs: Long): Long =
        checkNotNull(entries.filterIsInstance<TimelineEntry.Frame>().lastOrNull { it.atMs < atMs }) { "no frame before $atMs" }.atMs
}
