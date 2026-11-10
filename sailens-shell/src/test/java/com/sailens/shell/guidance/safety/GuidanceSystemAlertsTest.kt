package com.sailens.shell.guidance.safety

import com.sailens.core.log.LogService
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.usecase.scene.GuidancePipelineFailedException
import com.sailens.output.SpeechEngineState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four alerts about Guidance itself, call by call, as the guidance screen's view model gives them.
 *
 * Every port is a fake that writes each call and its arguments into one record, in order: the outputs, the trace's
 * `safety_state` and `guidance_alert` records, the state published, and the log's warnings, errors and the session's
 * main-thread time. The fact ports read what the fake screen holds, which the tests write where the view model writes
 * it (running from the flow's start, cleared by a stop). The clock does not move within an entry.
 *
 * The expected records are written by hand from `SceneAnalysisViewModel.kt`, not from the class under test: the
 * outputs (logs, the stall loop, the outputs' stop, the result cleared, the description preempted, the alarm, the
 * sentence, the interruption and failure flags) from the file at `1a99f6e`, the observation (trace records, the state
 * published, the session's time) from the file at `9f2deb3`. Each step names its line there, as `1a99f6e:N` or
 * `9f2deb3:N`.
 *
 * Only the entries drive these tests, and nothing here counts the monitor's inputs: what is pinned is what the screen
 * does, not how the class reaches it.
 */
class GuidanceSystemAlertsTest {

    private data class Call(val atMs: Long, val what: String)

    private var now = 0L
    private var nextPollAtMs = Long.MAX_VALUE
    private val record = mutableListOf<Call>()

    // What the screen holds, as the view model writes it and the fact ports read it.
    private var running = false
    private var interrupted = false
    private var speechEnabled = true
    private var screenReader = false
    private var hapticsEnabled = true
    private var engine = SpeechEngineState.READY

    /** The engine's `isReady`, apart from its state: a rebuilt engine takes speech while its state still says unavailable. */
    private var ready = true
    private var vibrates = true

    private fun call(what: String) {
        record += Call(now, what)
    }

    private val logger = object : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = call("log info $tag: $message")
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) =
            call("log warning $tag: $message" + (data?.let { " $it" } ?: ""))
        override fun error(tag: String, message: String, throwable: Throwable?) =
            call("log error $tag: $message" + (throwable?.let { " (${it.message})" } ?: ""))
    }

    /** The output facts the view model builds the alerts with. */
    private fun facts() = OutputFacts(
        engineState = engine,
        speechEnabled = speechEnabled,
        screenReaderActive = screenReader,
        hapticsEnabled = hapticsEnabled,
        speechReady = ready,
    )

    /** The engine gives up: unavailable, and nothing takes speech. */
    private fun engineFails() {
        engine = SpeechEngineState.UNAVAILABLE
        ready = false
    }

    /** The engine is back and has spoken: ready by both readings. */
    private fun engineRecovers() {
        engine = SpeechEngineState.READY
        ready = true
    }

    private fun alerts(detFirst: DetFirstConfig = DetFirstConfig()) = GuidanceSystemAlerts(
        cancelStallLoop = { call("cancel stall loop") },
        stopGuidanceOutput = { call("stop guidance output") },
        clearLatestResult = { call("clear latest result") },
        preemptDescription = { call("preempt description: $it") },
        holdOrdinaryPrompts = { call("hold ordinary prompts") },
        playAlarm = { haptic ->
            call("alarm ${haptic.name}")
            vibrates
        },
        speakSystemNotice = { text, priority -> call("speak '$text' at $priority") },
        announceToScreenReader = { call("screen reader '$it'") },
        noticeText = { notice ->
            call("notice text ${notice.name}")
            "text of ${notice.name}"
        },
        markInterrupted = {
            interrupted = true
            call("mark interrupted")
        },
        clearInterrupted = {
            interrupted = false
            call("clear interrupted")
        },
        markFailed = { message ->
            running = false
            call("mark failed: $message")
        },
        isRunning = { running },
        wasInterrupted = { interrupted },
        outputSettings = { GuidanceOutputSettings(speechEnabled = speechEnabled, screenReaderActive = screenReader, hapticsEnabled = hapticsEnabled) },
        speechReady = { ready },
        initialOutput = facts(),
        recordState = { call("safety_state ${it.described()}") },
        recordAlert = { call("guidance_alert ${it.described()}") },
        publishState = { state, hold -> call("publish ${state.label}" + (hold?.let { " hold=${it.cause.traceName},${if (it.stopping) "stopping" else "recovering"}" } ?: "")) },
        timed = { true },
        logger = logger,
        clock = { now },
        detFirst = detFirst,
        wallClock = { WALL_CLOCK_BASE + now },
        nanoClock = { 0L },
    )

    // The view model's call sites, with the screen state it writes around each.

    /** Start pressed (`9f2deb3:311-312`), then `runs.start` launches the stall loop (`9f2deb3:406`, `:510-514`). */
    private fun GuidanceSystemAlerts.startPressed() {
        onStartRequested()
        onStallWatchStarted()
        nextPollAtMs = now + POLL_MS
    }

    /** `onStart` (`9f2deb3:416`): the monitor first, then the UI state says running. */
    private fun GuidanceSystemAlerts.flowStarted() {
        onFlowStarted()
        running = true
    }

    /** A result in `collectLatest` (`9f2deb3:444-445`). */
    private fun GuidanceSystemAlerts.result() = onResult(BaselineQualification.Qualified, 1, null)

    /** `checkSpeechUnavailable` (`9f2deb3:289-296`), from any of its three callers: the output as the screen holds it. */
    private fun GuidanceSystemAlerts.output() = onOutput(engine, speechEnabled, screenReader, hapticsEnabled)

    /**
     * The user's stop (`9f2deb3:625-637`): the stall loop goes first (`:626`, `:530-534`); after the output's stop,
     * `stopped {}` tells the monitor before the UI state stops running and clears the interruption (`:635`), and the
     * session ends after that (`:636`).
     */
    private fun GuidanceSystemAlerts.userStopped() {
        onStallWatchStopped()
        nextPollAtMs = Long.MAX_VALUE
        onStopped()
        running = false
        interrupted = false
        endOfSession()
    }

    /** The stall loop's polls, every 500 ms from the watch's start, up to [toMs]; the clock is then at [toMs]. */
    private fun GuidanceSystemAlerts.pollUntil(toMs: Long) {
        while (nextPollAtMs <= toMs) {
            now = nextPollAtMs
            poll()
            nextPollAtMs += POLL_MS
        }
        now = toMs
    }

    /** Started at 0, the flow collected, a first result at 1 000 ms; the record is then cleared. */
    private fun GuidanceSystemAlerts.guiding(): GuidanceSystemAlerts {
        now = 0L
        startPressed()
        flowStarted()
        pollUntil(1_000L)
        result()
        record.clear()
        return this
    }

    private fun recorded(): List<String> = record.map { it.what }

    private fun recordedAt(atMs: Long): List<String> = record.filter { it.atMs == atMs }.map { it.what }

    /** When a stall alarm was given: its warning (`1a99f6e:453`) is there whatever the output settings. */
    private fun stallAlarmTimes(): List<Long> = record.filter { it.what == STALLED_WARNING }.map { it.atMs }

    /** In every case the real monitor agrees with every alert given, and no observation call fails. */
    @After
    fun `the monitor agrees with every alert`() {
        assertTrue(recorded().joinToString("\n"), recorded().none { GuidanceSafetyObservation.DISAGREEMENT in it || "Safety observation failed" in it })
    }

    @Test
    fun `a session's start reaches the monitor at each step`() {
        val alerts = alerts()
        assertEquals(listOf("publish idle"), recorded()) // 9f2deb3:142-152, the state when the screen is built

        alerts.startPressed()
        alerts.flowStarted()
        now = 1_000L
        alerts.result()

        assertEquals(
            listOf(
                "publish idle",
                "safety_state initializing phase=starting channel=speech <- start_requested", // 9f2deb3:312
                "publish initializing",
                // 9f2deb3:513: the stall watch starting changes no axis.
                "safety_state initializing phase=awaiting_first_result channel=speech <- flow_started", // 9f2deb3:416
                "safety_state guiding phase=running channel=speech <- result", // 9f2deb3:444-445
                "publish guiding",
            ),
            recorded(),
        )
    }

    // FAILED: 1a99f6e:388-408 and :452-465; 9f2deb3:418-441 and :486-502.

    private fun failedWithOwnSpeech(detFirst: DetFirstConfig) {
        record.clear()
        speechEnabled = true
        screenReader = false
        val alerts = alerts(detFirst).guiding()
        now = 1_200L

        alerts.onFlowFailed(IllegalStateException("model lost"))

        assertEquals(
            listOf(
                "safety_state failed(error) phase=failed channel=speech <- flow_failed", // 9f2deb3:419
                "publish failed(error)",
                "log error $SCREEN: Error in scene analysis (model lost)", // 1a99f6e:389
                "cancel stall loop", // 1a99f6e:390, :483-484
                "stop guidance output", // 1a99f6e:391
                "clear latest result", // 1a99f6e:392
                // 9f2deb3:425: the state stands behind FAILED, so no warning.
                FAILED_WARNING, // 1a99f6e:394, :453
                "preempt description: guidance failed", // 1a99f6e:454
                "alarm INTERRUPTED", // 1a99f6e:455
                "guidance_alert failed haptics played interrupted max", // 9f2deb3:489-490
                "notice text FAILED", // 1a99f6e:459
                "speak 'text of FAILED' at $MAX", // 1a99f6e:463: nothing may cut it, ranks or not
                "guidance_alert failed speech requested notice_guidance_failed max", // 9f2deb3:499-500
                "mark failed: model lost", // 1a99f6e:399-407
                "log info $SAFETY: $SESSION_TIME", // 9f2deb3:441, after the UI state's update
            ),
            recorded(),
        )
        assertFalse(running)
    }

    @Test
    fun `a failure stops the outputs, then alerts, then shows the failure, with the ranks off`() =
        failedWithOwnSpeech(DetFirstConfig())

    @Test
    fun `a failure stops the outputs, then alerts, then shows the failure, under the launch policy`() =
        failedWithOwnSpeech(DetFirstConfig.LAUNCH)

    @Test
    fun `under the screen reader a failure's sentence is the reader's`() {
        screenReader = true
        val alerts = alerts().guiding()

        alerts.onFlowFailed(IllegalStateException("model lost"))

        assertEquals(
            listOf(
                "safety_state failed(error) phase=failed channel=screen_reader <- flow_failed", // 9f2deb3:419
                "publish failed(error)",
                "log error $SCREEN: Error in scene analysis (model lost)", // 1a99f6e:389
                "cancel stall loop", // 1a99f6e:390
                "stop guidance output", // 1a99f6e:391
                "clear latest result", // 1a99f6e:392
                FAILED_WARNING, // 1a99f6e:453
                "preempt description: guidance failed", // 1a99f6e:454
                "alarm INTERRUPTED", // 1a99f6e:455
                "guidance_alert failed haptics played interrupted max", // 9f2deb3:490
                "notice text FAILED", // 1a99f6e:459
                "screen reader 'text of FAILED'", // 1a99f6e:460-461
                "guidance_alert failed screen_reader published notice_guidance_failed max", // 9f2deb3:497
                "mark failed: model lost", // 1a99f6e:399-407
                "log info $SAFETY: $SESSION_TIME", // 9f2deb3:441
            ),
            recorded(),
        )
    }

    @Test
    fun `with speech off a failure is felt and not said`() {
        speechEnabled = false
        val alerts = alerts().guiding()

        alerts.onFlowFailed(IllegalStateException("model lost"))

        assertEquals(
            listOf(
                "safety_state failed(error) phase=failed channel=haptics_only <- flow_failed", // 9f2deb3:419
                "publish failed(error)",
                "log error $SCREEN: Error in scene analysis (model lost)", // 1a99f6e:389
                "cancel stall loop", // 1a99f6e:390
                "stop guidance output", // 1a99f6e:391
                "clear latest result", // 1a99f6e:392
                FAILED_WARNING, // 1a99f6e:453
                "preempt description: guidance failed", // 1a99f6e:454
                "alarm INTERRUPTED", // 1a99f6e:455
                "guidance_alert failed haptics played interrupted max", // 9f2deb3:490
                // 1a99f6e:458: speech off, nothing said and no words looked up.
                "mark failed: model lost", // 1a99f6e:399-407
                "log info $SAFETY: $SESSION_TIME", // 9f2deb3:441
            ),
            recorded(),
        )
    }

    @Test
    fun `a failure in the background alerts the same and keeps the interruption`() {
        val alerts = alerts().guiding()
        now = 1_200L
        alerts.onLifecycleStop("interrupted words")
        record.clear()
        now = 1_400L

        alerts.onFlowFailed(GuidancePipelineFailedException(consecutiveFailures = 30, cause = RuntimeException("sem")))

        assertEquals(
            listOf(
                // 9f2deb3:419: the failure stands above the interruption, which is kept.
                "safety_state failed(pipeline_failed) phase=failed interrupted channel=speech <- flow_failed",
                "publish failed(pipeline_failed)",
                "log error $SCREEN: Error in scene analysis (Guidance failed on 30 consecutive frames)", // 1a99f6e:389
                "cancel stall loop", // 1a99f6e:390
                "stop guidance output", // 1a99f6e:391
                "clear latest result", // 1a99f6e:392
                FAILED_WARNING, // 1a99f6e:453
                "preempt description: guidance failed", // 1a99f6e:454
                "alarm INTERRUPTED", // 1a99f6e:455
                "guidance_alert failed haptics played interrupted max", // 9f2deb3:490
                "notice text FAILED", // 1a99f6e:459
                "speak 'text of FAILED' at $MAX", // 1a99f6e:463
                "guidance_alert failed speech requested notice_guidance_failed max", // 9f2deb3:500
                // 1a99f6e:399-407: the failure's update leaves wasInterrupted as it was.
                "mark failed: Guidance failed on 30 consecutive frames",
                "log info $SAFETY: $SESSION_TIME", // 9f2deb3:441
            ),
            recorded(),
        )
        assertTrue(interrupted)
    }

    // INTERRUPTED: 1a99f6e:494-514; 9f2deb3:542-567.

    @Test
    fun `going to the background while not running alerts nothing`() {
        // Idle.
        val idle = alerts()
        record.clear()
        idle.onLifecycleStop("interrupted words")
        assertEquals(emptyList<String>(), recorded()) // 1a99f6e:495
        assertFalse(interrupted)

        // Start pressed, the flow not collected yet: the screen does not say running.
        val starting = alerts()
        starting.startPressed()
        record.clear()
        starting.onLifecycleStop("interrupted words")
        assertEquals(emptyList<String>(), recorded()) // 1a99f6e:495
        assertFalse(interrupted)

        // Ended on an error.
        val failed = alerts().guiding()
        failed.onFlowFailed(IllegalStateException("model lost"))
        record.clear()
        failed.onLifecycleStop("interrupted words")
        assertEquals(emptyList<String>(), recorded()) // 1a99f6e:495
        assertFalse(interrupted)
    }

    @Test
    fun `going to the background while running marks it, preempts, vibrates, then says it`() {
        val alerts = alerts().guiding()
        now = 1_200L

        alerts.onLifecycleStop("interrupted words")

        assertEquals(
            listOf(
                "safety_state interrupted(lifecycle) phase=running interrupted channel=speech <- lifecycle_stop", // 9f2deb3:543
                "publish interrupted(lifecycle) hold=lifecycle,stopping",
                "hold ordinary prompts", // before the alert, so a rhythm waiting for the motor is gone before the alarm
                // 9f2deb3:544: the state stands behind it, so no warning.
                "log warning $SCREEN: Guidance interrupted while running", // 1a99f6e:497
                "mark interrupted", // 1a99f6e:498
                "preempt description: guidance interrupted", // 1a99f6e:501
                "alarm INTERRUPTED", // 1a99f6e:504
                "guidance_alert interrupted haptics played interrupted max", // 9f2deb3:554-555
                "speak 'interrupted words' at $MAX", // 1a99f6e:512
                "guidance_alert interrupted speech requested notice_guidance_interrupted max", // 9f2deb3:564-565
            ),
            recorded(),
        )
        assertTrue(interrupted)
    }

    @Test
    fun `under the screen reader the interruption's sentence is the reader's`() {
        screenReader = true
        val alerts = alerts().guiding()

        alerts.onLifecycleStop("interrupted words")

        assertEquals(
            listOf(
                "safety_state interrupted(lifecycle) phase=running interrupted channel=screen_reader <- lifecycle_stop", // 9f2deb3:543
                "publish interrupted(lifecycle) hold=lifecycle,stopping",
                "hold ordinary prompts", // before the alert, so a rhythm waiting for the motor is gone before the alarm
                "log warning $SCREEN: Guidance interrupted while running", // 1a99f6e:497
                "mark interrupted", // 1a99f6e:498
                "preempt description: guidance interrupted", // 1a99f6e:501
                "alarm INTERRUPTED", // 1a99f6e:504
                "guidance_alert interrupted haptics played interrupted max", // 9f2deb3:555
                "screen reader 'interrupted words'", // 1a99f6e:508-509
                "guidance_alert interrupted screen_reader published notice_guidance_interrupted max", // 9f2deb3:561
            ),
            recorded(),
        )
    }

    @Test
    fun `with speech off the interruption is felt and not said`() {
        speechEnabled = false
        val alerts = alerts().guiding()

        alerts.onLifecycleStop("interrupted words")

        assertEquals(
            listOf(
                "safety_state interrupted(lifecycle) phase=running interrupted channel=haptics_only <- lifecycle_stop", // 9f2deb3:543
                "publish interrupted(lifecycle) hold=lifecycle,stopping",
                "hold ordinary prompts", // before the alert, so a rhythm waiting for the motor is gone before the alarm
                "log warning $SCREEN: Guidance interrupted while running", // 1a99f6e:497
                "mark interrupted", // 1a99f6e:498
                "preempt description: guidance interrupted", // 1a99f6e:501
                "alarm INTERRUPTED", // 1a99f6e:504
                "guidance_alert interrupted haptics played interrupted max", // 9f2deb3:555
                // 1a99f6e:507: speech off, nothing said.
            ),
            recorded(),
        )
    }

    @Test
    fun `on a phone without a vibrator the alarm's rhythm is traced as refused`() {
        vibrates = false
        val alerts = alerts().guiding()

        alerts.onLifecycleStop("interrupted words")

        assertEquals(
            listOf(
                "safety_state interrupted(lifecycle) phase=running interrupted channel=speech <- lifecycle_stop",
                "publish interrupted(lifecycle) hold=lifecycle,stopping",
                "hold ordinary prompts", // before the alert, so a rhythm waiting for the motor is gone before the alarm
                "log warning $SCREEN: Guidance interrupted while running", // 1a99f6e:497
                "mark interrupted", // 1a99f6e:498
                "preempt description: guidance interrupted", // 1a99f6e:501
                "alarm INTERRUPTED", // 1a99f6e:504
                "guidance_alert interrupted haptics refused interrupted max", // 9f2deb3:554-555, nothing took it
                "speak 'interrupted words' at $MAX", // 1a99f6e:512
                "guidance_alert interrupted speech requested notice_guidance_interrupted max", // 9f2deb3:565
            ),
            recorded(),
        )
    }

    // STALLED: 1a99f6e:467-480 and :452-465; 9f2deb3:510-528.

    private fun stalledAlarmRepeats(detFirst: DetFirstConfig, priority: Int) {
        record.clear()
        val alerts = alerts(detFirst).guiding()

        alerts.pollUntil(24_000L)

        // 1a99f6e:475 with the detector's limits: 2.5 s after the last result, then every 10 s.
        assertEquals(listOf(3_500L, 13_500L, 23_500L), stallAlarmTimes())
        assertEquals(
            listOf(
                "safety_state stop_required(perception_stalled) phase=running stall=no_results channel=speech <- poll", // 9f2deb3:517
                "publish stop_required(perception_stalled) hold=perception_stalled,stopping",
                "hold ordinary prompts",
                // 9f2deb3:522-523: the second poll finds nothing new, and the state stands behind STALLED.
                STALLED_WARNING, // 1a99f6e:476, :453
                "preempt description: guidance stalled", // 1a99f6e:454
                "alarm INTERRUPTED", // 1a99f6e:455
                "guidance_alert stalled haptics played interrupted high", // 9f2deb3:489-490
                "notice text STALLED", // 1a99f6e:459
                "speak 'text of STALLED' at $priority", // 1a99f6e:463
                "guidance_alert stalled speech requested notice_guidance_stalled high", // 9f2deb3:499-500
            ),
            recordedAt(3_500L),
        )
        assertEquals(
            listOf(
                STALLED_WARNING,
                "preempt description: guidance stalled",
                "alarm INTERRUPTED",
                "guidance_alert stalled haptics played interrupted high",
                "notice text STALLED",
                "speak 'text of STALLED' at $priority",
                "guidance_alert stalled speech requested notice_guidance_stalled high",
            ),
            recordedAt(13_500L),
        )
    }

    // A stall notice is in the HIGH band (2 × 8); with the speech ranks on it stands 6 within it.
    @Test
    fun `the stall alarm comes 2_5 s after the last result and then every 10 s, with the ranks off`() =
        stalledAlarmRepeats(DetFirstConfig(), priority = 16)

    @Test
    fun `the stall alarm comes 2_5 s after the last result and then every 10 s, under the launch policy`() =
        stalledAlarmRepeats(DetFirstConfig.LAUNCH, priority = 22)

    @Test
    fun `with no first result the stall alarm comes after the startup grace`() {
        val alerts = alerts()
        alerts.startPressed()
        alerts.flowStarted()

        alerts.pollUntil(31_000L)

        assertEquals(listOf(20_000L, 30_000L), stallAlarmTimes()) // 1a99f6e:475 with the 20 s startup limit
        assertEquals(
            listOf(
                "safety_state stop_required(startup_stalled) phase=awaiting_first_result stall=startup_timeout channel=speech <- poll", // 9f2deb3:517
                "publish stop_required(startup_stalled) hold=startup_stalled,stopping",
                "hold ordinary prompts",
                STALLED_WARNING, // 1a99f6e:453
                "preempt description: guidance stalled", // 1a99f6e:454
                "alarm INTERRUPTED", // 1a99f6e:455
                "guidance_alert stalled haptics played interrupted high", // 9f2deb3:490
                "notice text STALLED", // 1a99f6e:459
                "speak 'text of STALLED' at 16", // 1a99f6e:463
                "guidance_alert stalled speech requested notice_guidance_stalled high", // 9f2deb3:500
            ),
            recordedAt(20_000L),
        )
    }

    @Test
    fun `while interrupted no stall alarm is given, and back in the foreground the startup grace starts again`() {
        val alerts = alerts().guiding()
        alerts.pollUntil(5_200L)
        alerts.onLifecycleStop("interrupted words")
        alerts.pollUntil(30_200L)
        alerts.onLifecycleStart()
        alerts.pollUntil(51_000L)

        // 1a99f6e:474: every poll while interrupted skips the alarm; 1a99f6e:518 restarts the 20 s grace at 30.2 s.
        assertEquals(listOf(3_500L, 50_500L), stallAlarmTimes())
        assertEquals(emptyList<String>(), record.filter { it.atMs in 5_201L..30_199L }.map { it.what })
        assertEquals(
            listOf(
                "safety_state initializing phase=awaiting_first_result channel=speech <- lifecycle_start", // 9f2deb3:573
                "publish initializing hold=lifecycle,recovering",
                "clear interrupted", // 1a99f6e:519
            ),
            recordedAt(30_200L),
        )
        assertEquals(
            listOf(
                "safety_state stop_required(startup_stalled) phase=awaiting_first_result stall=startup_timeout channel=speech <- poll", // 9f2deb3:517
                "publish stop_required(startup_stalled) hold=startup_stalled,stopping",
                "hold ordinary prompts",
                STALLED_WARNING, // 1a99f6e:476, :453
                "preempt description: guidance stalled",
                "alarm INTERRUPTED",
                "guidance_alert stalled haptics played interrupted high",
                "notice text STALLED",
                "speak 'text of STALLED' at 16",
                "guidance_alert stalled speech requested notice_guidance_stalled high",
            ),
            recordedAt(50_500L),
        )
    }

    @Test
    fun `a result clears the stall alarm's repeat`() {
        val alerts = alerts().guiding()
        alerts.pollUntil(8_200L)
        alerts.result()
        alerts.pollUntil(22_000L)

        // 1a99f6e:411: the result is progress, so the next alarm is 2.5 s after it, then every 10 s.
        assertEquals(listOf(3_500L, 11_000L, 21_000L), stallAlarmTimes())
        // The stall is over, but the stop holds ordinary prompts until fresh results release it: the state stays a
        // stall's stop, and only the card hears that the cause has gone.
        assertEquals(
            listOf(
                "safety_state stop_required(perception_stalled) phase=running channel=speech <- result",
                "publish stop_required(perception_stalled) hold=perception_stalled,recovering",
            ),
            recordedAt(8_200L),
        )
    }

    // ON_START: 1a99f6e:516-520; 9f2deb3:569-575.

    @Test
    fun `back in the foreground while running and watched, the startup grace starts again`() {
        val alerts = alerts().guiding()
        now = 1_200L
        alerts.onLifecycleStop("interrupted words")
        alerts.pollUntil(2_200L)
        record.clear()

        alerts.onLifecycleStart()

        assertEquals(
            listOf(
                "safety_state initializing phase=awaiting_first_result channel=speech <- lifecycle_start", // 9f2deb3:573
                "publish initializing hold=lifecycle,recovering",
                "clear interrupted", // 1a99f6e:519
            ),
            recorded(),
        )
        assertFalse(interrupted)
        // Not 2.5 s after the result before the background, but 20 s after the return (1a99f6e:518).
        alerts.pollUntil(23_000L)
        assertEquals(listOf(22_500L), stallAlarmTimes())
    }

    @Test
    fun `back in the foreground after a failure, only the interruption is cleared`() {
        val alerts = alerts().guiding()
        alerts.onLifecycleStop("interrupted words")
        alerts.onFlowFailed(IllegalStateException("model lost"))
        record.clear()
        now = 5_000L

        alerts.onLifecycleStart()

        assertEquals(
            listOf(
                "safety_state failed(error) phase=failed channel=speech <- lifecycle_start", // 9f2deb3:573
                "clear interrupted", // 1a99f6e:518 restarts nothing; :519
            ),
            recorded(),
        )
        assertFalse(interrupted)
    }

    @Test
    fun `back in the foreground when never started, only the interruption flag is written`() {
        val alerts = alerts()
        record.clear()

        alerts.onLifecycleStart()

        assertEquals(listOf("clear interrupted"), recorded()) // 1a99f6e:518-519; 9f2deb3:573 changes no axis
    }

    @Test
    fun `back in the foreground with the stall loop already cancelled, the stall detector is not restarted`() {
        // The screen still says running while the loop is gone: a stop has cancelled the loop (1a99f6e:570) and not
        // yet written the UI state (:579). 1a99f6e:518 restarts the grace only while the loop exists.
        val alerts = alerts().guiding()
        now = 1_200L
        alerts.onLifecycleStop("interrupted words")
        alerts.onStallWatchStopped()
        record.clear()
        now = 2_000L

        alerts.onLifecycleStart()
        // A poll long after the grace would have run out finds the detector still stopped.
        now = 30_000L
        alerts.poll()

        assertEquals(
            listOf(
                "safety_state initializing phase=awaiting_first_result channel=speech <- lifecycle_start", // 9f2deb3:573
                "publish initializing hold=lifecycle,recovering",
                "clear interrupted", // 1a99f6e:519
            ),
            recorded(),
        )
    }

    // SPEECH_UNAVAILABLE: 1a99f6e:219-229, :268-274, :532-541; 9f2deb3:289-296, :587-597.

    private val speechUnavailableAlert = listOf(
        "log error $SCREEN: Speech engine unavailable; alerting through haptics", // 1a99f6e:539
        "alarm SPEECH_UNAVAILABLE", // 1a99f6e:540
        "guidance_alert speech_unavailable haptics played speech_unavailable max", // 9f2deb3:595-596
    )

    @Test
    fun `a stop and a new start within one speech outage alert once`() {
        val alerts = alerts().guiding()
        now = 2_000L
        engineFails()
        alerts.output()
        now = 3_000L
        alerts.userStopped()
        now = 4_000L
        alerts.startPressed()
        alerts.flowStarted()
        now = 5_000L
        alerts.result()
        now = 6_000L
        // A setting changed with the engine still unavailable (1a99f6e:321).
        alerts.output()

        assertEquals(
            listOf(
                "safety_state degraded(speech_unavailable) phase=running faults=speech_unavailable channel=haptics_only <- output", // 9f2deb3:291
                "publish degraded(speech_unavailable)",
                // 9f2deb3:293: own speech unavailable stands behind it, so no warning.
            ) + speechUnavailableAlert + listOf(
                "safety_state idle phase=not_started faults=speech_unavailable channel=haptics_only <- stopped", // 9f2deb3:635
                "publish idle",
                "log info $SAFETY: $SESSION_TIME", // 9f2deb3:636
                "safety_state initializing phase=starting faults=speech_unavailable channel=haptics_only <- start_requested", // 9f2deb3:312
                "publish initializing",
                "safety_state initializing phase=awaiting_first_result faults=speech_unavailable channel=haptics_only <- flow_started", // 9f2deb3:416
                "safety_state degraded(speech_unavailable) phase=running faults=speech_unavailable channel=haptics_only <- result", // 9f2deb3:445
                "publish degraded(speech_unavailable)",
                // 1a99f6e:271: the same outage, already told; 9f2deb3:291 changes no axis.
            ),
            recorded(),
        )
    }

    @Test
    fun `an engine that recovers and fails again is a new outage, with or without Guidance running`() {
        val alerts = alerts()
        record.clear()

        engineFails()
        alerts.output()
        engineRecovers()
        alerts.output()
        engineFails()
        alerts.output()

        val unavailable = listOf(
            "safety_state idle phase=not_started faults=speech_unavailable channel=haptics_only <- output", // 9f2deb3:291
        ) + speechUnavailableAlert
        assertEquals(
            unavailable +
                "safety_state idle phase=not_started channel=speech <- output" + // 9f2deb3:291
                unavailable,
            recorded(),
        )
    }

    @Test
    fun `an outage that began under the screen reader is told when own speech is back`() {
        screenReader = true
        val alerts = alerts().guiding()
        engineFails()
        alerts.output()
        // 1a99f6e:271: the reader speaks, so nothing; 9f2deb3:291: no fault while the user does not rely on own speech.
        assertEquals(emptyList<String>(), recorded())

        screenReader = false
        alerts.output()

        assertEquals(
            listOf(
                "safety_state degraded(speech_unavailable) phase=running faults=speech_unavailable channel=haptics_only <- output", // 9f2deb3:291
                "publish degraded(speech_unavailable)",
            ) + speechUnavailableAlert, // 1a99f6e:265, :271-272
            recorded(),
        )
    }

    @Test
    fun `an outage that began with speech off is told when speech is switched on`() {
        speechEnabled = false
        val alerts = alerts().guiding()
        engineFails()
        alerts.output()
        assertEquals(emptyList<String>(), recorded()) // 1a99f6e:271; 9f2deb3:291 changes no axis

        speechEnabled = true
        alerts.output()

        assertEquals(
            listOf(
                "safety_state degraded(speech_unavailable) phase=running faults=speech_unavailable channel=haptics_only <- output", // 9f2deb3:291
                "publish degraded(speech_unavailable)",
            ) + speechUnavailableAlert, // 1a99f6e:321, :271-272
            recorded(),
        )
    }

    // An engine rebuilt after a failure takes speech (`isReady`) while its state still says unavailable, until it accepts
    // its first sentence: SpeechManager.kt:743-747 at 9f2deb3. The monitor reads `isReady` where 9f2deb3:291 and :445 do.

    @Test
    fun `an outage told while a rebuilt engine already takes speech still alerts, and prompts are expected on speech`() {
        val alerts = alerts().guiding()
        now = 2_000L
        engine = SpeechEngineState.UNAVAILABLE
        ready = true

        alerts.output()

        assertEquals(
            listOf(
                // 9f2deb3:291: own speech is unavailable by the engine's state, while a prompt would still go to speech.
                "safety_state degraded(speech_unavailable) phase=running faults=speech_unavailable channel=speech <- output",
                "publish degraded(speech_unavailable)",
            ) + speechUnavailableAlert, // 1a99f6e:271-272: the engine's state, not `isReady`, decides the alert
            recorded(),
        )
    }

    @Test
    fun `a rebuilt engine takes speech before it reports ready, and the outage it ends is not told again`() {
        val alerts = alerts().guiding()
        now = 2_000L
        engineFails()
        alerts.output()
        record.clear()

        // Rebuilt: `isReady` again, the state unchanged, so nothing reaches the screen until the next result.
        ready = true
        now = 3_000L
        alerts.result()
        now = 3_500L
        alerts.output() // a setting changed
        now = 4_000L
        engineRecovers() // the first sentence was accepted
        alerts.output()

        assertEquals(
            listOf(
                "safety_state degraded(speech_unavailable) phase=running faults=speech_unavailable channel=speech <- result", // 9f2deb3:445
                // 3.5 s: 1a99f6e:271 the outage was told; 9f2deb3:291 changes no axis.
                "safety_state guiding phase=running channel=speech <- output", // 9f2deb3:291
                "publish guiding",
            ),
            recorded(),
        )
    }

    @Test
    fun `a user's stop ends the session's main-thread time once the stop is on the screen`() {
        val alerts = alerts().guiding()
        now = 2_000L

        // 9f2deb3:635: `stopped {}` tells the monitor, then writes the UI state; the session has not ended yet.
        alerts.onStallWatchStopped()
        alerts.onStopped()
        assertEquals(
            listOf(
                "safety_state idle phase=not_started channel=speech <- stopped",
                "publish idle",
            ),
            recorded(),
        )

        running = false
        interrupted = false
        alerts.endOfSession() // 9f2deb3:636, after the UI state's update; in onCleared right after the monitor (:640-641)

        assertEquals(
            listOf(
                "safety_state idle phase=not_started channel=speech <- stopped",
                "publish idle",
                "log info $SAFETY: $SESSION_TIME",
            ),
            recorded(),
        )
    }

    private fun SafetyStateTrace.described(): String = buildString {
        append(state)
        if (reasons.isNotEmpty()) append(reasons.joinToString(",", "(", ")"))
        append(" phase=").append(phase)
        if (interrupted) append(" interrupted")
        if (stall != "none") append(" stall=").append(stall)
        if (outputFaults.isNotEmpty()) append(" faults=").append(outputFaults.joinToString(","))
        append(" channel=").append(expectedNavigationChannel)
        append(" <- ").append(cause)
    }

    private fun GuidanceAlertTrace.described(): String = "$kind $channel $result ${messageKey ?: haptic} $priority"

    private companion object {
        const val POLL_MS = 500L
        const val WALL_CLOCK_BASE = 1_700_000_000_000L
        const val MAX = Int.MAX_VALUE

        /** The view model's log tag, which its alerts' lines carry. */
        const val SCREEN = "SceneAnalysisViewModel"
        const val SAFETY = "GuidanceSafety"
        const val SESSION_TIME = "Safety observation main-thread time"
        const val FAILED_WARNING = "log warning $SCREEN: Guidance unavailable while running {notice=FAILED}"
        const val STALLED_WARNING = "log warning $SCREEN: Guidance unavailable while running {notice=STALLED}"
    }
}
