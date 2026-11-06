package com.sailens.shell.guidance.safety

import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.DegradeReason
import com.sailens.guidance.safety.UnqualifiedReason
import com.sailens.guidance.usecase.scene.GuidancePipelineFailedException
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.screen.GuidanceStallDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monitor through whole sessions, fed the way the view model feeds it: the stall detector first where the view
 * model updates it first, then the monitor. Each step checks the axes, the state, the change it reported, and the
 * alert the input called for.
 */
class GuidanceSafetyMonitorTest {

    private val output = OutputFacts(SpeechEngineState.READY, speechEnabled = true, screenReaderActive = false, hapticsEnabled = true, speechReady = true)

    /** The view model's events, in its order, against a real stall detector. */
    private inner class Session(initialOutput: OutputFacts = output) {
        val detector = GuidanceStallDetector()
        val changes = mutableListOf<GuidanceSafetySnapshot>()
        val monitor = GuidanceSafetyMonitor(
            detector::condition,
            detector::alarmDue,
            nowMs = 0L,
            initialOutput = initialOutput,
            onChange = { changes += it },
        )
        private var watching = false
        private var running = false

        fun startRequested(now: Long) = monitor.onStartRequested(now)

        fun stallWatchStarted(now: Long) {
            detector.start(now)
            watching = true
            monitor.onStallWatchStarted(now)
        }

        fun flowStarted(now: Long) {
            monitor.onFlowStarted(now)
            running = true
        }

        fun result(now: Long, baseline: BaselineQualification = BaselineQualification.Qualified, ready: Boolean = true) {
            detector.onResult(now)
            monitor.onResult(now, baseline, ready)
        }

        fun poll(now: Long) = monitor.poll(now)

        fun failed(now: Long, error: Throwable): SystemAlert? {
            val alert = monitor.onFlowFailed(now, error)
            detector.stop()
            watching = false
            running = false
            return alert
        }

        fun stopped(now: Long) {
            detector.stop()
            watching = false
            monitor.onStopped(now)
            running = false
        }

        /**
         * @return the alert the monitor calls for, checked against the rule the screen applied itself before the
         *   monitor decided it: the interruption is told exactly when the screen says the session runs.
         */
        fun background(now: Long): SystemAlert? {
            val alert = monitor.onLifecycleStop(now)
            assertEquals("told exactly when running", if (running) SystemAlert.INTERRUPTED else null, alert)
            return alert
        }

        fun foreground(now: Long) {
            if (running && watching) detector.start(now)
            monitor.onLifecycleStart(now)
        }

        fun output(now: Long, facts: OutputFacts) = monitor.onOutput(now, facts)

        /** The last change, and that it was the only one since [count] were seen. */
        fun lastChange(count: Int): GuidanceSafetySnapshot {
            assertEquals("changes reported", count, changes.size)
            return changes.last()
        }
    }

    private fun assertState(session: Session, state: GuidanceSafetyState, phase: GuidanceRunPhase, stall: StallCondition = StallCondition.NONE) {
        assertEquals(state, session.monitor.state)
        assertEquals(phase, session.monitor.axes.phase)
        assertEquals(stall, session.monitor.axes.stall)
    }

    private val obstructed = BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, emptySet())
    private val ground = BaselineQualification.Degraded(setOf(DegradeReason.GROUND_UNRECOGNIZED))

    @Test
    fun `a whole session - start, judge, stall, background, foreground, stop`() {
        val s = Session()
        assertState(s, GuidanceSafetyState.Idle, GuidanceRunPhase.NOT_STARTED)

        s.startRequested(0)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.STARTING)
        s.lastChange(1).let { assertEquals(SafetyInput.START_REQUESTED, it.cause); assertEquals(0L, it.stateSinceElapsedMs) }

        s.stallWatchStarted(100)
        s.flowStarted(100)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.AWAITING_FIRST_RESULT)
        // The phase changed, the state did not: it still holds since the start request.
        s.lastChange(2).let { assertEquals(SafetyInput.FLOW_STARTED, it.cause); assertEquals(0L, it.stateSinceElapsedMs); assertEquals(100L, it.elapsedMs) }

        s.result(3_000)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)
        s.lastChange(3).let { assertEquals(SafetyInput.RESULT, it.cause); assertEquals(3_000L, it.stateSinceElapsedMs) }

        s.result(3_100)
        assertEquals("a result that changes nothing reports nothing", 3, s.changes.size)

        s.result(4_000, obstructed)
        assertState(s, GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED), GuidanceRunPhase.RUNNING)
        assertEquals(obstructed, s.lastChange(4).axes.judgement)

        s.result(6_000)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)

        s.result(7_000, ground)
        assertState(s, GuidanceSafetyState.Degraded(setOf(DegradeReason.GROUND_UNRECOGNIZED), emptySet()), GuidanceRunPhase.RUNNING)
        assertEquals(6, s.changes.size)

        assertEquals(null, s.poll(9_000))
        assertEquals("before the stall limit nothing changes", 6, s.changes.size)
        assertEquals(SystemAlert.STALLED, s.poll(9_500))
        assertState(s, GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED), GuidanceRunPhase.RUNNING, StallCondition.NO_RESULTS)
        s.lastChange(7).let { assertEquals(SafetyInput.POLL, it.cause); assertEquals(9_500L, it.stateSinceElapsedMs) }

        s.result(11_000)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)

        assertEquals("the session runs: the interruption is told", SystemAlert.INTERRUPTED, s.background(12_000))
        assertState(s, GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE), GuidanceRunPhase.RUNNING)
        val interruptedSince = s.monitor.stateSinceElapsedMs

        // The camera gives no frames in the background: the condition is reached, the state is not touched, and no
        // alarm is given.
        assertEquals(null, s.poll(14_000))
        assertState(s, GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE), GuidanceRunPhase.RUNNING, StallCondition.NO_RESULTS)
        s.lastChange(10).let { assertEquals(SafetyInput.POLL, it.cause); assertEquals(interruptedSince, it.stateSinceElapsedMs) }

        s.foreground(20_000)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.AWAITING_FIRST_RESULT)
        assertEquals("a new startup grace, not a stall", null, s.poll(25_000))
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.AWAITING_FIRST_RESULT)

        s.result(26_000)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)

        s.stopped(30_000)
        assertState(s, GuidanceSafetyState.Idle, GuidanceRunPhase.NOT_STARTED)
        s.lastChange(13).let { assertEquals(SafetyInput.STOPPED, it.cause); assertEquals(30_000L, it.stateSinceElapsedMs) }

        // Every change written, in order: what caused it, the state, since when, and when it was observed.
        assertEquals(
            listOf(
                "START_REQUESTED initializing since=0 at=0",
                "FLOW_STARTED initializing since=0 at=100",
                "RESULT guiding since=3000 at=3000",
                "RESULT stop_required(camera_obstructed) since=4000 at=4000",
                "RESULT guiding since=6000 at=6000",
                "RESULT degraded(ground_unrecognized) since=7000 at=7000",
                "POLL stop_required(perception_stalled) since=9500 at=9500",
                "RESULT guiding since=11000 at=11000",
                "LIFECYCLE_STOP interrupted(lifecycle) since=12000 at=12000",
                "POLL interrupted(lifecycle) since=12000 at=14000",
                "LIFECYCLE_START initializing since=20000 at=20000",
                "RESULT guiding since=26000 at=26000",
                "STOPPED idle since=30000 at=30000",
            ),
            s.changes.map { "${it.cause} ${it.state.label} since=${it.stateSinceElapsedMs} at=${it.elapsedMs}" },
        )
    }

    @Test
    fun `a start that fails is an error, and the next start clears it`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(50)
        s.flowStarted(50)
        assertEquals(SystemAlert.FAILED, s.failed(900, IllegalStateException("detection model not ready")))
        assertState(s, GuidanceSafetyState.Failed(FailureReason.ERROR), GuidanceRunPhase.FAILED)

        s.startRequested(5_000)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.STARTING)
        assertEquals(null, s.monitor.axes.failure)
        assertEquals(null, s.monitor.axes.judgement)
    }

    @Test
    fun `thirty failed frames in a row are a pipeline failure`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(2_000)
        s.failed(4_000, GuidancePipelineFailedException(30, RuntimeException("sem")))
        assertState(s, GuidanceSafetyState.Failed(FailureReason.PIPELINE_FAILED), GuidanceRunPhase.FAILED)
        s.poll(10_000)
        assertState(s, GuidanceSafetyState.Failed(FailureReason.PIPELINE_FAILED), GuidanceRunPhase.FAILED)
    }

    @Test
    fun `a failure in the background is a failure, and the interruption it happened in is kept until the foreground`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(2_000)
        assertEquals(SystemAlert.INTERRUPTED, s.background(3_000))
        assertEquals("told in the background too", SystemAlert.FAILED, s.failed(4_000, GuidancePipelineFailedException(30, RuntimeException("sem"))))
        assertState(s, GuidanceSafetyState.Failed(FailureReason.PIPELINE_FAILED), GuidanceRunPhase.FAILED)
        assertTrue("the view model does not clear wasInterrupted on a failure", s.monitor.axes.interrupted)

        s.foreground(8_000)
        assertState(s, GuidanceSafetyState.Failed(FailureReason.PIPELINE_FAILED), GuidanceRunPhase.FAILED)
        assertFalse(s.monitor.axes.interrupted)
    }

    @Test
    fun `backgrounded before the flow started - no interruption, initialising throughout, however long the wait`() {
        val s = Session()
        s.startRequested(0)
        assertEquals("no alert: not running yet", null, s.background(10))
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.STARTING)

        // The previous run's barrier, then up to two seconds of resource release: no stall watch yet, so no poll.
        s.stallWatchStarted(4_500)
        s.flowStarted(4_500)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.AWAITING_FIRST_RESULT)
        assertFalse(s.monitor.axes.interrupted)

        s.foreground(9_000)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.AWAITING_FIRST_RESULT)
        s.poll(28_000)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.AWAITING_FIRST_RESULT)
        s.poll(29_000)
        assertState(s, GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED), GuidanceRunPhase.AWAITING_FIRST_RESULT, StallCondition.STARTUP_TIMEOUT)
    }

    @Test
    fun `a flow that ends after results stays running, and stalls once the stall limit passes`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(1_000)
        s.result(1_500)
        // The flow completes without an error: nothing in the view model handles that.
        s.poll(3_500)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)
        s.poll(4_000)
        assertState(s, GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED), GuidanceRunPhase.RUNNING, StallCondition.NO_RESULTS)
    }

    @Test
    fun `a flow that ends without a result stays awaiting it, and stalls at the startup limit`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.poll(19_500)
        assertState(s, GuidanceSafetyState.Initializing, GuidanceRunPhase.AWAITING_FIRST_RESULT)
        s.poll(20_000)
        assertState(s, GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED), GuidanceRunPhase.AWAITING_FIRST_RESULT, StallCondition.STARTUP_TIMEOUT)
    }

    @Test
    fun `the screen reader takes over and gives way - channel and health follow it`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(1_000)
        s.output(2_000, output.copy(screenReaderActive = true, engineState = SpeechEngineState.IDLE, speechReady = false))
        assertEquals(NavigationChannel.SCREEN_READER, s.monitor.axes.expectedNavigationChannel)
        assertEquals(emptySet<OutputFault>(), s.monitor.axes.outputFaults)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)

        s.result(2_500, ready = false)
        s.output(3_000, output.copy(engineState = SpeechEngineState.INITIALIZING, speechReady = false))
        assertEquals(NavigationChannel.HAPTICS_ONLY, s.monitor.axes.expectedNavigationChannel)
        assertState(s, GuidanceSafetyState.Degraded(emptySet(), setOf(OutputFault.SPEECH_NOT_READY)), GuidanceRunPhase.RUNNING)
        s.output(3_400, output)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)

        // Every input reads the stall condition at its own time: an output change long after the last result finds it.
        s.output(6_000, output.copy(hapticsEnabled = false))
        assertState(s, GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED), GuidanceRunPhase.RUNNING, StallCondition.NO_RESULTS)
    }

    @Test
    fun `speech going unavailable while idle is on the output axis, and the state stays idle`() {
        val s = Session()
        val alert = s.output(1_000, output.copy(engineState = SpeechEngineState.UNAVAILABLE, speechReady = false))
        assertState(s, GuidanceSafetyState.Idle, GuidanceRunPhase.NOT_STARTED)
        assertEquals(setOf(OutputFault.SPEECH_UNAVAILABLE), s.monitor.axes.outputFaults)
        assertEquals("told with Guidance not running", SystemAlert.SPEECH_UNAVAILABLE, alert)
        assertEquals(SafetyInput.OUTPUT, s.lastChange(1).cause)
    }

    @Test
    fun `speech going unavailable while running degrades the output, not the judgement`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(1_000)
        val alert = s.output(2_000, output.copy(engineState = SpeechEngineState.UNAVAILABLE, speechReady = false))
        assertState(s, GuidanceSafetyState.Degraded(emptySet(), setOf(OutputFault.SPEECH_UNAVAILABLE)), GuidanceRunPhase.RUNNING)
        assertEquals(NavigationChannel.HAPTICS_ONLY, s.monitor.axes.expectedNavigationChannel)
        assertEquals(SystemAlert.SPEECH_UNAVAILABLE, alert)
    }

    @Test
    fun `a rebuilt engine is ready before its state recovers - speech is expected while the health still says unavailable`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(1_000)
        s.output(2_000, output.copy(engineState = SpeechEngineState.UNAVAILABLE, speechReady = false))
        val degradedSince = s.monitor.stateSinceElapsedMs

        s.result(3_000, ready = true)
        assertEquals(NavigationChannel.SPEECH, s.monitor.axes.expectedNavigationChannel)
        assertEquals(setOf(OutputFault.SPEECH_UNAVAILABLE), s.monitor.axes.outputFaults)
        assertEquals(GuidanceSafetyState.Degraded(emptySet(), setOf(OutputFault.SPEECH_UNAVAILABLE)), s.monitor.state)
        s.lastChange(5).let { assertEquals("only a fact changed", degradedSince, it.stateSinceElapsedMs) }

        s.output(4_000, output)
        assertState(s, GuidanceSafetyState.Guiding, GuidanceRunPhase.RUNNING)
    }

    @Test
    fun `a stop clears an interruption, and the screen going away is a stop`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(1_000)
        s.background(2_000)
        s.stopped(2_500)
        assertState(s, GuidanceSafetyState.Idle, GuidanceRunPhase.NOT_STARTED)
        assertFalse(s.monitor.axes.interrupted)
        assertEquals("stopped: nothing to tell", null, s.background(3_000))
    }

    @Test
    fun `the stall alarm comes from the detector's repeat, and is not asked for while interrupted`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(1_000)
        val alarms = (3..48).map { it * 500L }.filter { s.poll(it) == SystemAlert.STALLED }
        // 2.5 s after the last result, then every 10 s, as the detector's own test has it.
        assertEquals(listOf(3_500L, 13_500L, 23_500L), alarms)

        s.background(24_200)
        assertEquals(emptyList<Long>(), (49..80).map { it * 500L }.filter { s.poll(it) == SystemAlert.STALLED })
        // Not asked while interrupted: had the polls in the background asked, the repeat would have been taken at
        // 33.5 s and this one would not be due.
        s.monitor.onLifecycleStart(40_000) // the detector left as it was, to see what the background did to it
        assertEquals(SystemAlert.STALLED, s.poll(40_500))
    }

    // Own speech being unavailable is told once per outage. Each case below is one the rule had while it stood on its
    // own in the screen, kept input for input: the engine's state, speech on or off, the screen reader on or off.

    private val speechRule = GuidanceSafetyMonitor({ StallCondition.NONE }, { false }, nowMs = 0L, initialOutput = output)

    private fun alertDue(
        state: SpeechEngineState,
        speech: Boolean = true,
        screenReader: Boolean = false,
    ): Boolean = speechRule.onOutput(
        nowMs = 0L,
        facts = OutputFacts(state, speechEnabled = speech, screenReaderActive = screenReader, hapticsEnabled = true, speechReady = false),
    ) == SystemAlert.SPEECH_UNAVAILABLE

    @Test
    fun `speech unavailable alert fires once on entering unavailable`() {
        assertFalse(alertDue(SpeechEngineState.INITIALIZING))
        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE))
        assertFalse("same outage", alertDue(SpeechEngineState.UNAVAILABLE))
    }

    @Test
    fun `speech unavailable alert is irrelevant when speech is disabled or screen reader owns output`() {
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, speech = false))
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, screenReader = true))
    }

    @Test
    fun `an outage that began under the screen reader is announced when own speech is back`() {
        // A failure under the screen reader raises no alert, and switching the screen reader off leaves
        // the engine state where it was: the alert must still come once own speech is back.
        assertFalse(alertDue(SpeechEngineState.READY, screenReader = true))
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, screenReader = true))

        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE, screenReader = false))
        assertFalse("still failing: announced once", alertDue(SpeechEngineState.UNAVAILABLE))
    }

    @Test
    fun `an outage that began with speech off is announced when speech is switched on`() {
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, speech = false))
        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE, speech = true))
    }

    @Test
    fun `a recovered engine that fails again is a new outage`() {
        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE))
        assertFalse(alertDue(SpeechEngineState.READY))

        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE))
    }

    @Test
    fun `an outage told is not told again when the screen reader or the speech setting comes and goes`() {
        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE))
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, screenReader = true))
        assertFalse("the same outage", alertDue(SpeechEngineState.UNAVAILABLE))
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, speech = false))
        assertFalse("the same outage", alertDue(SpeechEngineState.UNAVAILABLE))
    }

    @Test
    fun `the once-per-outage rule outlives a stop and a new start, and only an output input gives the alert`() {
        val s = Session()
        s.startRequested(0)
        s.stallWatchStarted(0)
        s.flowStarted(0)
        s.result(1_000)
        assertEquals(SystemAlert.SPEECH_UNAVAILABLE, s.output(2_000, output.copy(engineState = SpeechEngineState.UNAVAILABLE, speechReady = false)))
        s.stopped(3_000)
        s.startRequested(4_000)
        s.stallWatchStarted(4_000)
        s.flowStarted(4_000)
        s.result(5_000, ready = true)
        assertEquals(
            "the same outage",
            null,
            s.output(6_000, output.copy(engineState = SpeechEngineState.UNAVAILABLE, speechReady = true, hapticsEnabled = false)),
        )
        assertEquals(setOf(OutputFault.SPEECH_UNAVAILABLE), s.monitor.axes.outputFaults)
    }
}
