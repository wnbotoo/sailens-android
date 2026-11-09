package com.sailens.shell.sim

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptRevokeReasons
import com.sailens.shell.device.GuidanceHaptic
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
    fun lens_covered_long_near_wall() {
        val script = SessionScenarios.lensCoveredLongNearWall
        val run = check(script)
        val confirmed = script.frameAtMs(SessionScenarios.COVERED_NEAR_WALL.first + 5)
        assertEquals("the sixth covered frame stops guidance", "$confirmed stop_required(camera_obstructed) result", run.states().first { it.label == "stop_required(camera_obstructed)" }.describe())
        for (cycle in 0..2) {
            assertEquals("said and felt on entering and every 8 s (cycle $cycle)", listOf("fault_notice accepted", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(confirmed + cycle * 8_000L))
        }
        assertEquals("and at no other time", listOf(confirmed, confirmed + 8_000, confirmed + 16_000), run.visionAlerts().filter { it.kind == GuidanceAlertKinds.FAULT_NOTICE }.map { it.at })
        val clear = script.frameAtMs(SessionScenarios.COVERED_NEAR_WALL.last + 6)
        assertEquals("the sixth clear frame guides again", "$clear guiding result", run.states().first { it.at > confirmed && it.label == "guiding" }.describe())
        assertEquals("the lens is said clear 2 s later", listOf("fault_recovery accepted"), run.visionAlertsAt(clear + 2_000))
        assertTrue("2 m short of the wall, no blocked prompt either side of the cover", run.decided().none { it.messageKey == SceneEventMessageKeys.BLOCKED })
        assertTrue("no system alert", run.systemAlerts().isEmpty())
    }

    @Test
    fun low_light() {
        val script = SessionScenarios.lowLight
        val run = check(script)
        val dark = script.frameAtMs(SessionScenarios.LOW_LIGHT.first + 5)
        assertEquals("the sixth dark frame stops guidance", "$dark stop_required(too_dark) result", run.states().first { it.label == "stop_required(too_dark)" }.describe())
        assertEquals("said and felt on entering", listOf("fault_notice accepted", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(dark))
        assertEquals("said again 15 s later, without the rhythm", listOf("fault_notice accepted"), run.visionAlertsAt(dark + 15_000))
        assertEquals("and at no other time", listOf(dark, dark + 15_000), run.visionAlerts().filter { it.kind == GuidanceAlertKinds.FAULT_NOTICE }.map { it.at })
        val lit = script.frameAtMs(SessionScenarios.LOW_LIGHT.last + 6)
        assertEquals("the sixth lit frame guides again", "$lit guiding result", run.states().first { it.at > dark && it.label == "guiding" }.describe())
        assertEquals("the light is said back 2 s later", listOf("fault_recovery ${SceneEventMessageKeys.LIGHT_RESTORED} accepted"), run.visionAlerts().filter { it.at == lit + 2_000 }.map { "${it.kind} ${it.key} ${it.result}" })
        assertTrue("no system alert", run.systemAlerts().isEmpty())
    }

    @Test
    fun ground_unrecognized_long() {
        val script = SessionScenarios.groundUnrecognizedLong
        val run = check(script)
        val confirmed = SessionScenarios.FLOOR_CONFIRMED_MS
        val blocked = run.outcomes().first { it.key == SceneEventMessageKeys.BLOCKED }
        assertTrue("the path is judged blocked before the floor is confirmed (${blocked.at})", blocked.at in SessionScenarios.FLOOR_FROM_MS until confirmed)
        assertEquals("confirmed 1.5 s on the floor: degraded", "$confirmed degraded(ground_unrecognized) result", run.states().first { it.label == "degraded(ground_unrecognized)" }.describe())
        assertEquals("said and felt then", listOf("fault_notice accepted", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(confirmed))
        assertEquals("said again 60 s later", listOf("fault_notice accepted"), run.visionAlertsAt(confirmed + 60_000))
        assertEquals("and at no other time", listOf(confirmed, confirmed + 60_000), run.visionAlerts().filter { it.kind == GuidanceAlertKinds.FAULT_NOTICE }.map { it.at })
        val back = script.frameAtMs(SessionScenarios.GROUND_LONG.last + 1) + 1_000
        val onFloor = run.outcomes().filter { it.at in confirmed until back }
        assertTrue("no path prompt on the unrecognised floor", onFloor.none { it.key == SceneEventMessageKeys.BLOCKED || it.key == SceneEventMessageKeys.PATH_COMPLEX })
        assertEquals(
            "people are prompted as ever, and reach the user",
            listOf("event_obstacle_center_person CRITICAL [speech, haptics]", "event_obstacle_left_person HIGH [speech, haptics]"),
            onFloor.filter { it.key.startsWith("event_obstacle_") }.map { "${it.key} ${it.priority} ${it.via}" },
        )
        assertEquals("1 s back on the sidewalk the floor is recognised", "$back guiding result", run.states().first { it.at > confirmed && it.label == "guiding" }.describe())
        assertEquals("and said so", listOf("fault_recovery ${SceneEventMessageKeys.GROUND_RECOGNIZED} accepted"), run.visionAlerts().filter { it.at == back }.map { "${it.kind} ${it.key} ${it.result}" })
        assertTrue("no system alert", run.systemAlerts().isEmpty())
    }

    @Test
    fun ground_unrecognized_then_covered() {
        val script = SessionScenarios.groundUnrecognizedThenCovered
        val run = check(script)
        val covered = script.frameAtMs(SessionScenarios.COVERED_ON_GROUND.first + 5)
        val stop = run.states().first { it.label == "stop_required(camera_obstructed)" }
        assertEquals("the sixth covered frame stops guidance", covered, stop.at)
        assertEquals("the floor still in its judgement", "unqualified(camera_obstructed,ground_unrecognized)", stop.judgement)
        val clear = script.frameAtMs(SessionScenarios.COVERED_ON_GROUND.last + 6)
        assertEquals("the sixth clear frame: degraded by the floor again", "$clear degraded(ground_unrecognized) result", run.states().first { it.at > covered && it.label != stop.label }.describe())
        assertEquals("the lens is said clear 2 s later", listOf("fault_recovery ${SceneEventMessageKeys.CAMERA_RESTORED} accepted"), run.visionAlerts().filter { it.at == clear + 2_000 }.map { "${it.kind} ${it.key} ${it.result}" })
        val floor = run.visionAlerts().filter { it.key == SceneEventMessageKeys.GROUND_UNRECOGNIZED || (it.kind == GuidanceAlertKinds.FAULT_RHYTHM && it.priority == "low") }
        assertEquals(
            "the floor is said and felt once, on being confirmed: not while the lens is covered, nor once it is clear",
            listOf("fault_notice", "fault_rhythm", "fault_rhythm").map { "${SessionScenarios.FLOOR_CONFIRMED_MS} $it" },
            floor.map { "${it.at} ${it.kind}" },
        )
        val back = script.frameAtMs(SessionScenarios.GROUND_THEN_COVERED.last + 1) + 1_000
        assertEquals("back on the sidewalk, the floor is said recognised", listOf("fault_recovery ${SceneEventMessageKeys.GROUND_RECOGNIZED} accepted"), run.visionAlerts().filter { it.at == back }.map { "${it.kind} ${it.key} ${it.result}" })
    }

    @Test
    fun speech_off_lens_covered() {
        val run = check(SessionScenarios.speechOffLensCovered)
        val withSpeech = SessionSimulator.run(SessionScenarios.lensCoveredShort)
        assertEquals(
            "guidance's states are those of lens_covered_short",
            withSpeech.states().filter { it.cause != "output" }.map { it.describe() },
            run.states().filter { it.cause != "output" }.map { it.describe() },
        )
        val confirmed = SessionScenarios.lensCoveredShort.frameAtMs(SessionScenarios.LENS_COVERED.first + 5)
        for (at in listOf(confirmed, confirmed + 8_000)) {
            assertEquals("the rhythm is the notice, each cycle", listOf("fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(at))
        }
        assertTrue("no notice and no recovery is offered", run.visionAlerts().all { it.kind == GuidanceAlertKinds.FAULT_RHYTHM })
        assertEquals("nothing is said", listOf("stop"), run.speech().map { it.kind })
    }

    @Test
    fun screen_reader_lens_covered() {
        val script = SessionScenarios.screenReaderLensCovered
        val run = check(script)
        val on = SessionScenarios.SCREEN_READER_ON_MS
        assertTrue("the screen reader stops own speech", run.speech().any { it.atMs == on && it.kind == "stop" })
        assertEquals("and becomes the channel", "$on guiding output screen_reader", run.states().single { it.at == on }.let { "${it.describe()} ${it.channel}" })
        val confirmed = script.frameAtMs(SessionScenarios.LENS_COVERED.first + 5)
        val clear = script.frameAtMs(SessionScenarios.LENS_COVERED.last + 6)
        assertEquals(
            "every notice after it, the clear lens's included, is published to the screen reader",
            listOf(
                "$confirmed fault_notice ${SceneEventMessageKeys.CAMERA_BLOCKED}",
                "${confirmed + 8_000} fault_notice ${SceneEventMessageKeys.CAMERA_BLOCKED}",
                "${clear + 2_000} fault_recovery ${SceneEventMessageKeys.CAMERA_RESTORED}",
            ),
            run.visionAlerts().filter { it.kind != GuidanceAlertKinds.FAULT_RHYTHM }.map {
                assertEquals("${it.at} ${it.key}", "${GuidanceAlertChannels.SCREEN_READER} ${GuidanceAlertResults.PUBLISHED}", "${it.channel} ${it.result}")
                "${it.at} ${it.kind} ${it.key}"
            },
        )
        assertEquals("which takes each", listOf(SceneEventMessageKeys.CAMERA_BLOCKED, SceneEventMessageKeys.CAMERA_BLOCKED, SceneEventMessageKeys.CAMERA_RESTORED), run.entries.filterIsInstance<TimelineEntry.ScreenReader>().map { it.text })
        for (at in listOf(confirmed, confirmed + 8_000)) {
            assertEquals("the rhythms play as with own speech", listOf("fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(at).filter { it.startsWith("fault_rhythm") })
        }
        assertTrue("own speech says nothing after it", run.speech().none { it.atMs >= on && (it.kind == "speak" || it.kind == "notice") })
    }

    @Test
    fun description_holds_floor() {
        val run = check(SessionScenarios.descriptionHoldsFloor)
        val held = SessionScenarios.FLOOR_CONFIRMED_MS
        val atHeld = run.entries.filter { it.atMs == held }
        assertTrue(
            "the description begins before the frame that confirms the floor",
            atHeld.indexOfFirst { it is TimelineEntry.Description && it.what == "holds" } in 0 until atHeld.indexOfFirst { it is TimelineEntry.Frame },
        )
        assertTrue("the blocked prompt came before it", run.outcomes().first { it.key == SceneEventMessageKeys.BLOCKED }.at < held)
        val floorEvent = run.outcomes().single { it.key == SceneEventMessageKeys.GROUND_UNRECOGNIZED }
        assertEquals("the floor's event goes to the vision-fault alerts", "$held [${PromptDeliveryChannels.VISION_ALERTS}]", "${floorEvent.at} ${floorEvent.via}")
        assertTrue("its cooldown confirmed", run.receipts().any { it.atMs == held && it.kind == "confirm" })
        val preempted = run.entries.filterIsInstance<TimelineEntry.Description>().single { it.what.startsWith("preempted") }
        val ahead = run.outcomes().first { it.key == "event_obstacle_center_person" }
        assertEquals("the person ahead preempts the description", "${ahead.at} preempted(event_obstacle_center_person)", "${preempted.atMs} ${preempted.what}")
        assertTrue("and is said (${ahead.priority} ${ahead.via})", ahead.priority in setOf("HIGH", "CRITICAL") && PromptDeliveryChannels.SPEECH in ahead.via)
        val floorNotices = run.visionAlerts().filter { it.kind == GuidanceAlertKinds.FAULT_NOTICE }
        val whileHeld = floorNotices.filter { it.at < preempted.atMs }
        assertEquals("the floor's notice is first offered while the description holds", held, whileHeld.first().at)
        assertTrue(
            "and refused each time, to no channel, until the description is preempted: $whileHeld",
            whileHeld.size >= 2 && whileHeld.all { it.channel == GuidanceAlertChannels.NONE && it.result == GuidanceAlertResults.REFUSED },
        )
        val side = run.outcomes().filter { it.key == "event_obstacle_left_person" }
        assertTrue(
            "the person to the left, at medium urgency, waits for the description and is withdrawn: $side",
            side.isNotEmpty() && side.all { it.priority == "MEDIUM" && it.revoked == PromptRevokeReasons.WAITING_FOR_DESCRIPTION && it.at < preempted.atMs },
        )
        val taken = floorNotices.first { it.result == GuidanceAlertResults.ACCEPTED }
        assertTrue("the floor's notice is taken once nothing higher holds the speaker (${taken.at})", taken.at > preempted.atMs)
    }

    @Test
    fun stop_while_covered() {
        val run = check(SessionScenarios.stopWhileCovered)
        val stop = SessionScenarios.STOP_WHILE_COVERED_MS
        assertTrue("stopped while the lens is covered", run.states().last { it.at < stop }.label == "stop_required(camera_obstructed)")
        assertEquals("speech, then the motor, then the state", listOf("speech stop", "motor cancel", "state idle"), run.entries.filter { it.atMs == stop }.mapNotNull {
            when (it) {
                is TimelineEntry.Speech -> "speech ${it.kind}"
                is TimelineEntry.Motor -> "motor ${it.kind}"
                is TimelineEntry.State -> "state ${it.trace.stateLabel}"
                else -> null
            }
        })
        assertTrue("the protocol was reset: the clear lens is never said", run.visionAlerts().none { it.kind == GuidanceAlertKinds.FAULT_RECOVERY })
        assertTrue("nothing after the stop", run.entries.none { it.atMs > stop })
    }

    @Test
    fun lens_covered_short_launch() {
        val (run, off) = checkLaunch(SessionScenarios.lensCoveredShortLaunch, SessionScenarios.lensCoveredShort)
        val notice = { r: SessionRun -> r.speech().first { it.text == SceneEventMessageKeys.CAMERA_BLOCKED }.scale!! }
        assertTrue("the covered lens's notice ranks higher (${notice(off)} -> ${notice(run)})", notice(run) > notice(off))
    }

    @Test
    fun ground_unrecognized_long_launch() {
        val (run, off) = checkLaunch(SessionScenarios.groundUnrecognizedLongLaunch, SessionScenarios.groundUnrecognizedLong)
        val prompts = { r: SessionRun -> r.speech().filter { it.kind == "speak" && it.text!!.startsWith("event_obstacle_") }.map { it.scale!! } }
        assertTrue("the prompts rank higher (${prompts(off)} -> ${prompts(run)})", prompts(run).size == 2 && prompts(run).zip(prompts(off)).all { (on, before) -> on > before })
        assertTrue("and are accepted by the cooldown before their rhythm", run.receipts().count { it.kind == "accepted" } >= 2)
    }

    @Test
    fun background_and_back_launch() {
        checkLaunch(SessionScenarios.backgroundAndBackLaunch, SessionScenarios.backgroundAndBack)
    }

    @Test
    fun speech_outage_long_cover() {
        val script = SessionScenarios.speechOutageLongCover
        val run = check(script)
        val confirmed = script.frameAtMs(SessionScenarios.LONG_COVER.first + 5)
        assertEquals("$confirmed stop_required(camera_obstructed) result", run.states().first { it.label == "stop_required(camera_obstructed)" }.describe())
        assertTrue("the notice is refused, the engine not being ready", run.speech().any { it.atMs == confirmed && it.text == SceneEventMessageKeys.CAMERA_BLOCKED && it.result == "refused(not_ready)" })
        assertEquals("and the entry rhythm plays", listOf("fault_notice refused", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(confirmed))
        assertEquals("5 s later the fallback plays", listOf("fault_notice refused", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(confirmed + 5_000))
        assertEquals("no other rhythm", listOf(confirmed, confirmed + 5_000), run.rhythmRequests())
    }

    @Test
    fun speech_outage_short_then_long_cover() {
        val script = SessionScenarios.speechOutageShortThenLongCover
        val run = check(script)
        val short = script.frameAtMs(SessionScenarios.SHORT_COVER.first + 5)
        val long = script.frameAtMs(SessionScenarios.LONG_COVER.first + 5)
        assertEquals("the short cover is confirmed", "$short stop_required(camera_obstructed) result", run.states().first { it.label == "stop_required(camera_obstructed)" }.describe())
        assertTrue("and never told", run.visionAlerts().none { it.kind == GuidanceAlertKinds.FAULT_NOTICE && it.result != GuidanceAlertResults.REFUSED })
        assertEquals("the long cover is confirmed 5 s after it", 5_000L, long - short)
        assertEquals(
            "the short cover's entry rhythm, with no fallback; one rhythm when the long cover is confirmed; none 5 s later",
            listOf(short, long),
            run.rhythmRequests(),
        )
    }

    @Test
    fun speech_outage_short_then_long_cover_fault_haptics_off() {
        val script = SessionScenarios.speechOutageShortThenLongCoverFaultHapticsOff
        val run = check(script)
        val long = script.frameAtMs(SessionScenarios.LONG_COVER.first + 5)
        assertEquals("no entry rhythm; the one rhythm, when the long cover is confirmed, is the fallback", listOf(long), run.rhythmRequests())
        assertEquals(listOf("fault_notice refused", "fault_rhythm requested", "fault_rhythm played"), run.visionAlertsAt(long))
    }

    @Test
    fun speech_outage_haptics_off() {
        val run = check(SessionScenarios.speechOutageHapticsOff)
        assertEquals(
            "the outage is alerted once, by vibration",
            listOf("6000 haptics played"),
            run.systemAlerts().filter { it.kind == GuidanceAlertKinds.SPEECH_UNAVAILABLE }.map { "${it.at} ${it.channel} ${it.result}" },
        )
        val inOutage = run.outcomes().filter { it.at in 6_000 until 12_000 }
        assertTrue("a prompt is offered in the outage", inOutage.isNotEmpty())
        assertTrue("and withdrawn, no channel left: $inOutage", inOutage.all { it.revoked == PromptRevokeReasons.OUTPUT_REFUSED })
        assertEquals(
            "on every frame it is offered",
            run.entries.filterIsInstance<TimelineEntry.Frame>().filter { it.atMs in 6_000 until 12_000 && it.events.isNotEmpty() }.map { it.atMs },
            inOutage.map { it.at },
        )
    }

    @Test
    fun startup_stall() {
        val run = check(SessionScenarios.startupStall)
        val ready = SessionScenarios.SLOW_INIT_MS
        assertTrue("no frame is received before the model is ready", run.delivered.all { run.script.frameAtMs(it) > ready })
        assertEquals("20 s after start the startup stall stops guidance", "20000 stop_required(startup_stalled) poll", run.states().first { it.label == "stop_required(startup_stalled)" }.describe())
        assertEquals(
            "alarmed, felt and said, once",
            listOf("20000 stalled haptics played", "20000 stalled speech requested"),
            run.systemAlerts().map { "${it.at} ${it.kind} ${it.channel} ${it.result}" },
        )
        assertEquals("the first result guides", "${ready + 50} guiding result", run.states().first { it.label == "guiding" }.describe())
    }

    @Test
    fun init_failure() {
        val run = check(SessionScenarios.initFailure)
        assertFailure(run, SessionScenarios.INIT_FAILS_MS, "failed(error)", SessionScenarios.INIT_FAILURE)
        assertEquals("no frame is received", emptyList<Int>(), run.delivered)
    }

    @Test
    fun flow_ends() {
        val run = check(SessionScenarios.flowEnds)
        assertTrue("no frame is sent after the end", run.delivered.all { run.script.frameAtMs(it) < SessionScenarios.FLOW_ENDS_MS })
        val stalls = run.systemAlerts().filter { it.kind == GuidanceAlertKinds.STALLED && it.channel == GuidanceAlertChannels.HAPTICS }.map { it.at }
        val last = run.lastFrameBefore(SessionScenarios.FLOW_ENDS_MS)
        assertEquals("a stall, 2.5 s after the last result and every 10 s", listOf(9_500L, 19_500L, 29_500L), stalls)
        assertTrue("the first ${stalls[0] - last} ms after the last result", stalls[0] - last in 2_500..3_000)
        assertEquals("${stalls[0]} stop_required(perception_stalled) poll", run.states().first { it.at == stalls[0] }.describe())
        assertTrue("the session never fails", run.states().none { it.label.startsWith("failed") })
    }

    @Test
    fun stop_and_recovery_speech() {
        val run = check(SessionScenarios.stopAndRecoverySpeech)
        val confirmed = assertCoverCutsPrompt(run)
        assertTrue("the cut goes nowhere but the speaker: no receipt", run.receipts().none { it.kind == "cut" })
        assertRecovers(run, confirmed)
    }

    @Test
    fun stop_and_recovery_speech_launch() {
        val run = check(SessionScenarios.stopAndRecoverySpeechLaunch)
        val confirmed = assertCoverCutsPrompt(run)
        val cut = run.speech().single { it.kind == "cut" }
        assertEquals("the cut prompt is reported to the cooldown", listOf("$confirmed cut ${cut.id}"), run.receipts().filter { it.kind == "cut" }.map { "${it.atMs} ${it.kind} ${it.eventId}" })
        assertRecovers(run, confirmed)
    }

    @Test
    fun stop_and_recovery_haptics() {
        val run = check(SessionScenarios.stopAndRecoveryHaptics)
        val stop = run.states().first { it.label == "stop_required(camera_obstructed)" }.at
        val queued = run.motor().single { it.kind == "queue" && it.result == "queued" }
        assertTrue("a person's rhythm queued before the stop (${queued.atMs})", queued.atMs < stop)
        val rhythm = queued.rhythm!!
        assertTrue("and still waiting at the stop", run.motor().none { it.rhythm == rhythm && it.atMs in queued.atMs + 1..stop && (it.kind == "start" || it.kind == "drop") })
        val prompt = run.entries.filterIsInstance<TimelineEntry.Frame>().single { it.atMs == queued.atMs }.events.first()
        val expiresAt = queued.atMs + (prompt.expiresAt - prompt.timestamp)
        assertTrue("and not expired ($expiresAt)", expiresAt > stop)
        assertTrue("the cover's rhythm reserves the motor at the stop", run.motor().any { it.atMs == stop && it.kind == "reserve" && it.result == "reserved" })
        val reservedStarts = run.motor().first { it.kind == "start" && it.rhythm == GuidanceHaptic.VISION_UNRELIABLE && it.atMs > stop }.atMs
        val queuedStarts = run.motor().first { it.kind == "start" && it.rhythm == rhythm }.atMs
        assertTrue("the reserved rhythm goes first, then the queued one ($reservedStarts, $queuedStarts)", reservedStarts < queuedStarts)
    }

    @Test
    fun accompanying_rhythm_replaced() {
        val run = check(SessionScenarios.accompanyingRhythmReplaced)
        val queued = run.motor().filter { it.kind == "queue" && it.result == "queued" }
        assertTrue("two rhythms going with spoken prompts wait for the motor: $queued", queued.size >= 2)
        val first = queued.first()
        assertTrue(
            "the first goes with words speech took at the same moment",
            run.speech().any { it.atMs == first.atMs && it.kind == "speak" && it.result == "accepted" },
        )
        val dropped = run.motor().first { it.kind == "drop" && it.rhythm == first.rhythm }
        assertTrue("and is replaced by the next before it plays", dropped.atMs == queued[1].atMs)
        assertTrue("it never starts", run.motor().none { it.kind == "start" && it.rhythm == first.rhythm && it.atMs in first.atMs..dropped.atMs })
        val outcomes = run.entries.filterIsInstance<TimelineEntry.Outcome>().filter { it.trace.category == "obstacle" }
        assertTrue("both prompts have an outcome", outcomes.size >= 2)
        for (outcome in outcomes) {
            assertEquals("delivered on the channel that took it: $outcome", listOf(PromptDeliveryChannels.SPEECH), outcome.trace.deliveredVia)
        }
        assertEquals("one outcome per prompt", outcomes.size, outcomes.map { it.trace.eventId }.toSet().size)
        assertTrue("the cooldown is never told to take one back", run.receipts().none { it.kind == "revoke" })
    }

    @Test
    fun screen_leaves_other_lease() {
        val run = check(SessionScenarios.screenLeavesOtherLease)
        val said = assertLeavesWhileSaying(run)
        assertTrue("the sentence is said to the end", run.speech().any { it.kind == "done" && it.id == said && it.atMs > SessionScenarios.LEAVE_MS })
        assertTrue("the engine is neither stopped nor released", run.speech().none { it.kind == "stop" || it.kind == "release" || it.kind == "cut" })
        assertTrue("its failure after the screen went is scripted", run.entries.any { it is TimelineEntry.Script && it.atMs == 4_500L })
    }

    @Test
    fun screen_leaves_last_lease() {
        val run = check(SessionScenarios.screenLeavesLastLease)
        val said = assertLeavesWhileSaying(run)
        assertEquals(
            "the last lease closes: the engine is stopped, cutting the sentence off, then released",
            listOf("speech stop", "speech cut $said", "speech release"),
            run.speech().filter { it.atMs == SessionScenarios.LEAVE_MS }.map { "speech ${it.kind}" + (if (it.kind == "cut") " ${it.id}" else "") },
        )
    }

    @Test
    fun restart_while_previous_run_holds() {
        val run = check(SessionScenarios.restartWhilePreviousRunHolds)
        val held = SessionScenarios.HELD_INIT_ENDS_MS
        assertEquals("the restart is starting while the old run holds", "1500 initializing start_requested starting", run.states().single { it.at == 1_500L }.let { "${it.describe()} ${it.phase}" })
        assertTrue("nothing is alerted, the background included", run.entries.none { it is TimelineEntry.Alert })
        assertTrue("the background reaches no state", run.states().none { it.at in 2_000L..2_999L })
        assertEquals("the flow starts once a run begins", listOf(0L, held), run.states().filter { it.cause == "flow_started" }.map { it.at })
        val delays = run.directorLog.filter { it.startsWith("delay") }.map { it.removePrefix("delay ").substringBefore('+').toLong() }
        assertTrue("no stall watch between the stop and $held: $delays", delays.none { it in 1_000L until held })
        assertTrue("the new run is watched from $held", held in delays)
        val log = run.directorLog
        assertEquals("the old run's end is read once", 1, log.count { it == "run 1: ended" })
        assertTrue(
            "the old run ends, then its reset, then the new run begins and subscribes: $log",
            log.indexOf("job 1 completed") in 0 until log.indexOf("run 2: began") && log.indexOf("run 2: began") < log.indexOf("run 2: subscribed"),
        )
        assertTrue("the old run leaves the ledger", "run 1: removed" in log)
        assertEquals("the new run takes the next frame, and guides", "${held + 50} guiding result", run.states().first { it.label == "guiding" }.describe())
        assertEquals("its first frame", held + 50, run.script.frameAtMs(run.delivered.first()))
    }

    @Test
    fun failure_in_background() {
        val run = check(SessionScenarios.failureInBackground)
        val background = SessionScenarios.BACKGROUND_WHILE_LOADING_MS
        val fails = SessionScenarios.FAILS_IN_BACKGROUND_MS
        assertEquals("$background interrupted(lifecycle) lifecycle_stop", run.states().single { it.cause == "lifecycle_stop" }.describe())
        assertEquals(
            "the interruption, then the failure, are alerted",
            listOf("$background interrupted haptics", "$background interrupted speech", "$fails failed haptics", "$fails failed speech"),
            run.systemAlerts().map { "${it.at} ${it.kind} ${it.channel}" },
        )
        val failed = run.states().single { it.cause == "flow_failed" }
        assertEquals("failed outranks interrupted, still marked", "$fails failed(error) flow_failed interrupted=true", "${failed.describe()} interrupted=${failed.interrupted}")
        assertFailure(run, fails, "failed(error)", SessionScenarios.INIT_FAILURE)
        assertEquals("no frame is received", emptyList<Int>(), run.delivered)
    }

    @Test
    fun fault_haptics_off_while_reserved() {
        val run = check(SessionScenarios.faultHapticsOffWhileReserved)
        val confirmed = run.states().first { it.label == "stop_required(camera_obstructed)" }.at
        val prompt = run.outcomes().single { it.key == "event_obstacle_center_person" }
        assertEquals("a critical prompt 100 ms before the cover is confirmed", "${confirmed - 100} CRITICAL", "${prompt.at} ${prompt.priority}")
        assertTrue("with its rhythm", run.motor().any { it.atMs == prompt.at && it.rhythm == GuidanceHaptic.AHEAD && it.result == "started" })
        assertTrue("the cover's rhythm waits for it, reserved", run.motor().any { it.atMs == confirmed && it.kind == "reserve" && it.result == "reserved" })
        val off = SessionScenarios.FAULT_HAPTICS_OFF_MS
        assertEquals(
            "turned off between two frames, the reservation is withdrawn then",
            listOf("script fault_haptics off", "alert fault_rhythm cancelled", "motor cancel_reservation"),
            run.entries.filter { it.atMs == off }.mapNotNull {
                when (it) {
                    is TimelineEntry.Script -> "script ${it.action}"
                    is TimelineEntry.Alert -> "alert ${it.trace.kind} ${it.trace.result}"
                    is TimelineEntry.Motor -> "motor ${it.kind}"
                    else -> null
                }
            },
        )
        assertTrue("the rhythm never plays", run.visionAlerts().none { it.kind == GuidanceAlertKinds.FAULT_RHYTHM && it.result == GuidanceAlertResults.PLAYED })
        assertTrue("the covered lens is still said", run.visionAlerts().any { it.kind == GuidanceAlertKinds.FAULT_NOTICE && it.result == GuidanceAlertResults.ACCEPTED })
    }

    @Test
    fun `every session has a test named after it`() {
        val tests = javaClass.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.toSet()
        // A method name cannot hold a '.': the test of `<name>.launch` is `<name>_launch`.
        val untested = SessionScenarios.all.map { it.name }.filter { it.replace('.', '_') !in tests }
        assertEquals("add a test named after each session", emptyList<String>(), untested)
        assertEquals("session names are unique", SessionScenarios.all.size, SessionScenarios.all.map { it.name.replace('.', '_') }.toSet().size)
    }

    @Test
    fun `a run is deterministic`() {
        val scripts = listOf(
            SessionScenarios.speechOutage,
            SessionScenarios.pipelineFailureSlow,
            SessionScenarios.descriptionHoldsFloor,
            SessionScenarios.stopAndRecoveryHaptics,
            SessionScenarios.restartWhilePreviousRunHolds,
            SessionScenarios.screenLeavesOtherLease,
        )
        for (script in scripts) {
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
     * last result forgotten, then felt and said, and the screen shows it last of all, with [detail] when given. No
     * frame is sent after it.
     */
    private fun assertFailure(run: SessionRun, failedAt: Long, label: String = "failed(pipeline_failed)", detail: String? = null) {
        assertEquals("$failedAt $label flow_failed", run.states().single { it.cause == "flow_failed" }.describe())
        detail?.let { assertEquals("the screen shows why", it, run.entries.filterIsInstance<TimelineEntry.Ui>().single { ui -> ui.what == "mark_failed" }.detail) }
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

    /**
     * A session under DetFirstConfig.LAUNCH: its states and alerts must be those of [base] without the switches, and
     * the mode notice is said on the first frame, never without them. Returns the two runs.
     */
    private fun checkLaunch(launch: SessionScript, base: SessionScript): Pair<SessionRun, SessionRun> {
        val run = check(launch)
        val off = SessionSimulator.run(base)
        assertEquals("the states of ${base.name}", off.states(), run.states())
        assertEquals("its alerts", off.alertLines(), run.alertLines())
        val mode = run.speech().first()
        assertEquals(
            "the mode notice is said on the first frame",
            "${launch.firstFrameAtMs} speak ${SceneEventMessageKeys.MODE_OBSTACLES_AND_BLOCKED} accepted",
            "${mode.atMs} ${mode.kind} ${mode.text} ${mode.result}",
        )
        assertTrue("never without the switches", off.speech().none { it.text == SceneEventMessageKeys.MODE_OBSTACLES_AND_BLOCKED })
        return run to off
    }

    /**
     * The cover of [SessionScenarios.stopAndRecoverySpeech] is confirmed on its sixth frame while a prompt below
     * critical is being said, and the covered lens's notice cuts it off. Returns when.
     */
    private fun assertCoverCutsPrompt(run: SessionRun): Long {
        val confirmed = run.states().first { it.label == "stop_required(camera_obstructed)" }.at
        assertEquals("the sixth covered frame", SessionScenarios.RECOVERY_COVER_MS + 500, confirmed)
        val cut = run.speech().single { it.kind == "cut" }
        assertEquals("a prompt is cut off as the cover is confirmed", confirmed, cut.atMs)
        val prompt = run.decided().single { it.id.toString() == cut.id }
        assertTrue("one below critical (${prompt.priority})", prompt.priority < EventPriority.CRITICAL)
        assertTrue(
            "by the covered lens's notice",
            run.speech().any { it.atMs == confirmed && it.kind == "speak" && it.text == SceneEventMessageKeys.CAMERA_BLOCKED && it.result == "accepted" },
        )
        return confirmed
    }

    /** The brief uncover changes nothing; the sixth clear frame after the last uncover judges again and prompts the person at once. */
    private fun assertRecovers(run: SessionRun, confirmed: Long) {
        val recovered = SessionScenarios.RECOVERY_LAST_UNCOVER_MS + 500
        assertEquals(
            "the brief uncover changes nothing; the sixth clear frame after the last one guides",
            listOf("$confirmed stop_required(camera_obstructed) result", "$recovered guiding result"),
            run.states().filter { it.at >= confirmed }.take(2).map { it.describe() },
        )
        val again = run.outcomes().first { it.at > confirmed && it.key.startsWith("event_obstacle_") }
        assertEquals("the person is prompted on that frame, and said", "$recovered event_obstacle_left_person true", "${again.at} ${again.key} ${PromptDeliveryChannels.SPEECH in again.via}")
    }

    /** A prompt is being said when the screen goes; guidance stops, the motor after it, and nothing reaches the safety state after. Returns the prompt's id. */
    private fun assertLeavesWhileSaying(run: SessionRun): String {
        val leave = SessionScenarios.LEAVE_MS
        val said = run.speech().last { it.atMs < leave && it.kind == "speak" && it.result == "accepted" }
        assertTrue("a prompt is being said when the screen goes", run.speech().none { it.kind == "done" && it.id == said.id && it.atMs <= leave })
        assertEquals(
            "guidance stops, then the motor",
            listOf("state idle stopped", "motor cancel"),
            run.entries.filter { it.atMs == leave }.mapNotNull {
                when (it) {
                    is TimelineEntry.State -> "state ${it.trace.stateLabel} ${it.trace.cause}"
                    is TimelineEntry.Motor -> "motor ${it.kind}"
                    else -> null
                }
            },
        )
        assertTrue("no change of the engine reaches the safety state after it", run.states().none { it.at > leave })
        return checkNotNull(said.id)
    }

    private data class StateLine(
        val at: Long,
        val label: String,
        val phase: String,
        val stall: String,
        val channel: String,
        val cause: String,
        val judgement: String,
        val interrupted: Boolean,
    ) {
        fun describe() = "$at $label $cause"
    }

    private data class AlertLine(val at: Long, val kind: String, val channel: String, val result: String)

    private data class OutcomeLine(val at: Long, val key: String, val via: List<String>, val priority: String, val revoked: String?)

    /** A vision-fault alert: [key] is the sentence's, or the rhythm's for a rhythm; [priority] as traced. */
    private data class VisionLine(val at: Long, val kind: String, val key: String, val priority: String, val channel: String, val result: String)

    private fun SessionRun.states() = entries.filterIsInstance<TimelineEntry.State>().map {
        val judgement = (it.trace.judgement ?: "none") + if (it.trace.judgementReasons.isEmpty()) "" else it.trace.judgementReasons.joinToString(",", "(", ")")
        StateLine(it.atMs, it.trace.stateLabel, it.trace.phase, it.trace.stall, it.trace.expectedNavigationChannel, it.trace.cause, judgement, it.trace.interrupted)
    }

    private fun SessionRun.alertLines() = entries.filterIsInstance<TimelineEntry.Alert>().map {
        "${it.atMs} ${it.trace.source} ${it.trace.kind} ${it.trace.messageKey ?: it.trace.haptic} ${it.trace.channel} ${it.trace.result}"
    }

    private fun SessionRun.visionAlerts() = entries.filterIsInstance<TimelineEntry.Alert>()
        .filter { it.trace.source == GuidanceAlertSources.VISION }
        .map { VisionLine(it.atMs, it.trace.kind, checkNotNull(it.trace.messageKey ?: it.trace.haptic), it.trace.priority, it.trace.channel, it.trace.result) }

    /** When a vision-fault rhythm was asked of the motor. */
    private fun SessionRun.rhythmRequests() =
        visionAlerts().filter { it.kind == GuidanceAlertKinds.FAULT_RHYTHM && it.result == GuidanceAlertResults.REQUESTED }.map { it.at }

    /** Every event decided, on every frame. */
    private fun SessionRun.decided() = entries.filterIsInstance<TimelineEntry.Frame>().flatMap { it.events }

    private fun SessionRun.receipts() = entries.filterIsInstance<TimelineEntry.Receipt>()

    private fun SessionRun.motor() = entries.filterIsInstance<TimelineEntry.Motor>()

    private fun SessionRun.systemAlerts() = entries.filterIsInstance<TimelineEntry.Alert>()
        .filter { it.trace.source == GuidanceAlertSources.SYSTEM }
        .map { AlertLine(it.atMs, it.trace.kind, it.trace.channel, it.trace.result) }

    private fun SessionRun.visionAlertsAt(atMs: Long) = entries.filterIsInstance<TimelineEntry.Alert>()
        .filter { it.atMs == atMs && it.trace.source == GuidanceAlertSources.VISION }
        .map { "${it.trace.kind} ${it.trace.result}" }

    private fun SessionRun.outcomes() = entries.filterIsInstance<TimelineEntry.Outcome>().map {
        OutcomeLine(it.atMs, it.trace.messageKey, it.trace.deliveredVia, it.trace.priority.uppercase(), it.trace.revokeReason)
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
