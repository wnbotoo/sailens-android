package com.sailens.shell.guidance.safety

import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.DegradeReason
import com.sailens.guidance.safety.PromptHold
import com.sailens.guidance.safety.UnqualifiedReason
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.screen.GuidanceStallDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The prompt hold as the monitor keeps it: rule 6b of the mapping on its own, the stop cause it is stepped with, the
 * properties every reachable state has (I7, I10), and the sequences the hold has to get right.
 */
class GuidanceSafetyHoldTest {

    private val ready = OutputFacts(SpeechEngineState.READY, speechEnabled = true, screenReaderActive = false, hapticsEnabled = true, speechReady = true)
    private val obstructed = BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, emptySet())
    private val dark = BaselineQualification.Unqualified(UnqualifiedReason.TOO_DARK, emptySet())
    private val ground = BaselineQualification.Degraded(setOf(DegradeReason.GROUND_UNRECOGNIZED))

    private fun held(cause: HoldCause, stopping: Boolean, fresh: Int = 0) =
        PromptHold.Held(cause, 1_000L, stopping, fresh, if (fresh > 0) 1_100L else null, if (fresh > 0) 1_200L else null, 0, if (fresh > 0) 7L else null)

    private fun axes(phase: GuidanceRunPhase, interrupted: Boolean, stall: StallCondition, judgement: BaselineQualification?, faults: Set<OutputFault>, hold: PromptHold.Held<HoldCause>?) =
        GuidanceSafetyAxes(
            phase = phase,
            failure = if (phase == GuidanceRunPhase.FAILED) FailureReason.ERROR else null,
            interrupted = interrupted,
            stall = stall,
            judgement = judgement,
            outputFaults = faults,
            expectedNavigationChannel = NavigationChannel.SPEECH,
            hold = hold,
        )

    private val judgements = listOf(null, BaselineQualification.Qualified, ground, obstructed, dark)
    private val faultSets = listOf(emptySet(), setOf(OutputFault.SPEECH_UNAVAILABLE))
    private val holds: List<PromptHold.Held<HoldCause>?> =
        listOf(null) + HoldCause.entries.flatMap { cause -> listOf(held(cause, stopping = true), held(cause, stopping = false), held(cause, stopping = false, fresh = 2)) }

    @Test
    fun `rule 6b - a hold shows its stop only where the mapping without it would guide or degrade`() {
        var checked = 0
        for (phase in listOf(GuidanceRunPhase.AWAITING_FIRST_RESULT, GuidanceRunPhase.RUNNING)) {
            for (interrupted in listOf(false, true)) for (stall in StallCondition.entries) for (judgement in judgements) for (faults in faultSets) {
                val without = axes(phase, interrupted, stall, judgement, faults, hold = null).state()
                for (hold in holds.filterNotNull()) {
                    val state = axes(phase, interrupted, stall, judgement, faults, hold).state()
                    val expected = if (without == GuidanceSafetyState.Guiding || without is GuidanceSafetyState.Degraded) {
                        when (hold.cause) {
                            HoldCause.CAMERA_OBSTRUCTED -> GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED)
                            HoldCause.TOO_DARK -> GuidanceSafetyState.StopRequired(StopReason.TOO_DARK)
                            HoldCause.STARTUP_STALLED -> GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED)
                            HoldCause.PERCEPTION_STALLED -> GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED)
                            HoldCause.LIFECYCLE -> GuidanceSafetyState.Initializing
                        }
                    } else {
                        without
                    }
                    assertEquals("$phase $interrupted $stall $judgement $faults $hold", expected, state)
                    checked++
                }
            }
        }
        assertEquals(2 * 2 * 3 * 5 * 2 * 15, checked)
    }

    @Test
    fun `a hold is kept only while the session runs`() {
        for (phase in GuidanceRunPhase.entries) {
            val allowed = runCatching { axes(phase, false, StallCondition.NONE, null, emptySet(), held(HoldCause.LIFECYCLE, true)) }.isSuccess
            assertEquals(phase.toString(), phase.isRunning, allowed)
        }
    }

    @Test
    fun `the stop cause is the stop or the interruption the mapping without the hold gives`() {
        for (phase in GuidanceRunPhase.entries) {
            for (interrupted in listOf(false, true)) for (stall in StallCondition.entries) for (judgement in judgements) {
                val facts = axes(phase, interrupted, stall, judgement, emptySet(), hold = null)
                val expected = when (val state = facts.state()) {
                    is GuidanceSafetyState.StopRequired -> HoldCause.valueOf(state.reason.name)
                    is GuidanceSafetyState.Interrupted -> HoldCause.LIFECYCLE
                    else -> null
                }
                assertEquals("$phase $interrupted $stall $judgement", expected, facts.stopCause())
            }
        }
    }

    /** The view model's events against a real stall detector, as in [GuidanceSafetyMonitorTest]. */
    private inner class Session {
        val detector = GuidanceStallDetector()
        val changes = mutableListOf<GuidanceSafetySnapshot>()
        val monitor = GuidanceSafetyMonitor(detector::condition, detector::alarmDue, nowMs = 0L, initialOutput = ready, onChange = { changes += it })
        var running = false
        var watching = false
        var interrupted = false
        private var frame = 0L

        fun start(now: Long) {
            monitor.onStartRequested(now)
            detector.start(now)
            watching = true
            monitor.onStallWatchStarted(now)
            monitor.onFlowStarted(now)
            running = true
        }

        /** A result at [now], its frame received at [receivedAtMs] (default: as it arrives). */
        fun result(now: Long, baseline: BaselineQualification = BaselineQualification.Qualified, receivedAtMs: Long? = now) {
            detector.onResult(now)
            monitor.onResult(now, baseline, ++frame, receivedAtMs, true)
        }

        fun poll(now: Long) = monitor.poll(now)

        fun background(now: Long) {
            monitor.onLifecycleStop(now)
            if (running) interrupted = true
        }

        fun foreground(now: Long) {
            if (running && watching) detector.start(now)
            monitor.onLifecycleStart(now)
            interrupted = false
        }

        fun stop(now: Long) {
            detector.stop()
            watching = false
            monitor.onStopped(now)
            running = false
            interrupted = false
        }

        fun fail(now: Long) {
            monitor.onFlowFailed(now, IllegalStateException("x"))
            detector.stop()
            watching = false
            running = false
        }

        val hold get() = monitor.axes.hold
    }

    @Test
    fun `the monitor says which input began a stop, and only that one`() {
        val session = Session()
        val began = mutableListOf<String>()
        fun step(name: String, input: () -> Unit) {
            input()
            if (session.monitor.beganStop) began += name
        }
        step("start") { session.start(0) }
        step("result") { session.result(1_000) }
        step("covered") { session.result(1_100, obstructed) }
        step("covered again") { session.result(1_200, obstructed) }
        step("dark, another stop") { session.result(1_300, dark) }
        step("clear") { session.result(1_400) }
        step("poll while recovering") { session.poll(1_500) }
        step("background") { session.background(2_000) }
        step("background poll") { session.poll(2_500) }
        step("foreground") { session.foreground(3_000) }
        // Back in the foreground the detector's startup grace (20 s) starts again.
        step("stalled, seen by a poll") { session.poll(23_500) }
        step("stalled still") { session.poll(24_000) }
        step("stopped") { session.stop(25_000) }
        assertEquals(listOf("covered", "dark, another stop", "background", "stalled, seen by a poll"), began)
    }

    @Test
    fun `background twice with no result in between - a frame received between the two stops is stale`() {
        val s = Session()
        s.start(0)
        s.result(500)
        s.background(1_000)
        assertEquals(1_000L, s.hold?.sinceMs)
        s.foreground(2_000)
        assertEquals("the cause has gone: recovering, with no result", false, s.hold?.stopping)
        s.background(2_300)
        assertEquals("a new stop, with its own start", 2_300L, s.hold?.sinceMs)
        s.foreground(3_000)
        s.result(3_050, receivedAtMs = 2_100)
        assertEquals("received before the second stop: stale", 0, s.hold?.fresh)
        assertEquals(GuidanceSafetyState.Initializing, s.monitor.state)
    }

    @Test
    fun `after a stall a result from a picture taken before it neither counts nor ends the stop`() {
        val s = Session()
        s.start(0)
        s.result(1_000)
        assertEquals(SystemAlert.STALLED, s.poll(3_500))
        assertEquals(3_500L, s.hold?.sinceMs)
        s.result(4_000, receivedAtMs = 1_050)
        assertEquals(GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED), s.monitor.state)
        assertEquals(0, s.hold?.fresh)
        s.result(4_100)
        s.result(4_300)
        s.result(4_600)
        assertEquals(GuidanceSafetyState.Guiding, s.monitor.state)
        val release = checkNotNull(s.changes.last().release)
        assertEquals(PromptHold.Release(HoldCause.PERCEPTION_STALLED, 3_500L, 3, 4_100L, 4_600L, 0, 5L), release)
    }

    @Test
    fun `a result in the background is not counted, the hold recovers only after the foreground`() {
        val s = Session()
        s.start(0)
        s.result(500)
        s.background(1_000)
        s.result(1_200, receivedAtMs = 900)
        s.result(1_300)
        assertEquals("the stop still holds in the background", true, s.hold?.stopping)
        assertEquals(0, s.hold?.fresh)
        assertEquals(GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE), s.monitor.state)
    }

    @Test
    fun `a new start holds nothing over, and its first result guides at once`() {
        val s = Session()
        s.start(0)
        s.result(500, obstructed)
        s.stop(1_000)
        assertNull(s.hold)
        s.start(2_000)
        s.result(2_100)
        assertEquals(GuidanceSafetyState.Guiding, s.monitor.state)
    }

    @Test
    fun `a failure ends the hold with the session`() {
        val s = Session()
        s.start(0)
        s.result(500, dark)
        s.fail(800)
        assertNull(s.hold)
        assertEquals(GuidanceSafetyState.Failed(FailureReason.ERROR), s.monitor.state)
    }

    @Test
    fun `a stall during a camera recovery begins a new stop`() {
        val s = Session()
        s.start(0)
        s.result(500, obstructed)
        s.result(600)
        assertEquals(HoldCause.CAMERA_OBSTRUCTED, s.hold?.cause)
        s.poll(3_500)
        assertEquals(HoldCause.PERCEPTION_STALLED, s.hold?.cause)
        assertEquals(3_500L, s.hold?.sinceMs)
        assertEquals(0, s.hold?.fresh)
    }

    @Test
    fun `back in the foreground with no background before it, a camera recovery waits for a first result again`() {
        val s = Session()
        s.start(0)
        s.result(500, obstructed)
        s.result(600)
        s.foreground(700)
        assertEquals(GuidanceSafetyState.Initializing, s.monitor.state)
        assertEquals(HoldCause.CAMERA_OBSTRUCTED, s.hold?.cause)
        s.result(800)
        assertEquals(GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED), s.monitor.state)
    }

    @Test
    fun `degraded results count toward the release`() {
        val s = Session()
        s.start(0)
        s.result(500, obstructed)
        s.result(1_000, ground)
        s.result(1_200, ground)
        s.result(1_500, ground)
        assertEquals(GuidanceSafetyState.Degraded(setOf(DegradeReason.GROUND_UNRECOGNIZED), emptySet()), s.monitor.state)
    }

    /**
     * Over random sessions, in the orders the view model can produce: after every input, a stop or an interruption by
     * the mapping without the hold means a hold is kept (I7), a hold shows only a stop, an interruption or initializing
     * (I7), and an output change leaves the hold as it was (I10).
     */
    @Test
    fun `I7 and I10 on every reachable state`() {
        val random = Random(1109)
        repeat(1_500) {
            val s = Session()
            var now = 0L
            repeat(80) {
                now += random.nextLong(1, 1_200)
                when {
                    !s.running -> if (random.nextInt(3) > 0) s.start(now)
                    else -> when (random.nextInt(12)) {
                        in 0..5 -> if (!s.interrupted || random.nextBoolean()) {
                            val baseline = listOf(BaselineQualification.Qualified, ground, obstructed, dark)[random.nextInt(4)]
                            val receivedAtMs = when (random.nextInt(5)) {
                                0 -> null
                                1 -> now - random.nextLong(5_000)
                                else -> now - random.nextLong(100)
                            }
                            s.result(now, baseline, receivedAtMs)
                        }
                        6, 7 -> s.poll(now)
                        8 -> if (!s.interrupted) s.background(now)
                        9 -> if (s.interrupted || random.nextInt(4) == 0) s.foreground(now)
                        10 -> if (random.nextInt(4) == 0) s.stop(now) else s.poll(now)
                        else -> if (random.nextInt(8) == 0) s.fail(now) else {
                            // At one moment, so that only the output differs: time alone can bring a stall.
                            s.poll(now)
                            val before = s.hold
                            s.monitor.onOutput(now, ready.copy(engineState = SpeechEngineState.entries[random.nextInt(4)], speechReady = random.nextBoolean()))
                            assertEquals("I10: an output change leaves the hold", before, s.hold)
                        }
                    }
                }
                val axes = s.monitor.axes
                val without = axes.copy(hold = null).state()
                if (without is GuidanceSafetyState.StopRequired || without is GuidanceSafetyState.Interrupted) {
                    assertTrue("I7: a stop holds prompts: $axes", axes.hold != null)
                }
                if (axes.hold != null) {
                    val state = s.monitor.state
                    assertTrue(
                        "I7: a hold shows a stop, an interruption or initializing: $state",
                        state is GuidanceSafetyState.StopRequired || state is GuidanceSafetyState.Interrupted || state == GuidanceSafetyState.Initializing,
                    )
                }
            }
        }
    }
}
