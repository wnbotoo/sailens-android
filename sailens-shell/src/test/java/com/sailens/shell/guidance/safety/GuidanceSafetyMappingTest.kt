package com.sailens.shell.guidance.safety

import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.DegradeReason
import com.sailens.guidance.safety.UnqualifiedReason
import com.sailens.output.SpeechEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The axes-to-state table, row by row, and the invariants over every combination of the axes: the output alone never
 * moves the state into or out of a stop, an interruption, a failure, initialising or idle.
 */
class GuidanceSafetyMappingTest {

    private val running = GuidanceRunPhase.RUNNING
    private val ground = setOf(DegradeReason.GROUND_UNRECOGNIZED)
    private val qualified = BaselineQualification.Qualified
    private val obstructed = BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, emptySet())

    /** The axes the view model's facts give: health and channel derived as the monitor derives them. */
    private fun axes(
        phase: GuidanceRunPhase = running,
        failure: FailureReason? = if (phase == GuidanceRunPhase.FAILED) FailureReason.PIPELINE_FAILED else null,
        interrupted: Boolean = false,
        stall: StallCondition = StallCondition.NONE,
        judgement: BaselineQualification? = qualified,
        engine: SpeechEngineState = SpeechEngineState.READY,
        speechReady: Boolean = true,
        speech: Boolean = true,
        screenReader: Boolean = false,
        haptics: Boolean = true,
    ) = GuidanceSafetyAxes(
        phase = phase,
        failure = failure,
        interrupted = interrupted,
        stall = stall,
        judgement = judgement,
        outputFaults = outputFaults(engine, speech, screenReader),
        expectedNavigationChannel = expectedNavigationChannel(speech, screenReader, haptics, speechReady),
    )

    private class Row(val name: String, val axes: GuidanceSafetyAxes, val state: GuidanceSafetyState)

    private val rows = listOf(
        Row("1 failed: pipeline", axes(phase = GuidanceRunPhase.FAILED), GuidanceSafetyState.Failed(FailureReason.PIPELINE_FAILED)),
        Row("1 failed: error", axes(phase = GuidanceRunPhase.FAILED, failure = FailureReason.ERROR), GuidanceSafetyState.Failed(FailureReason.ERROR)),
        Row(
            "1 before 3, 4: failed in the background, stalled",
            axes(phase = GuidanceRunPhase.FAILED, interrupted = true, stall = StallCondition.NO_RESULTS),
            GuidanceSafetyState.Failed(FailureReason.PIPELINE_FAILED),
        ),
        Row("2 idle", axes(phase = GuidanceRunPhase.NOT_STARTED, judgement = null), GuidanceSafetyState.Idle),
        Row(
            "2 idle with speech unavailable: the output axis has a value while idle",
            axes(phase = GuidanceRunPhase.NOT_STARTED, judgement = null, engine = SpeechEngineState.UNAVAILABLE),
            GuidanceSafetyState.Idle,
        ),
        Row("3 interrupted", axes(interrupted = true), GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE)),
        Row(
            "3 before 4: interrupted and stalled",
            axes(interrupted = true, stall = StallCondition.NO_RESULTS),
            GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE),
        ),
        Row(
            "3 before 5: interrupted while awaiting the first result",
            axes(phase = GuidanceRunPhase.AWAITING_FIRST_RESULT, interrupted = true, judgement = null),
            GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE),
        ),
        Row(
            "4 startup stalled",
            axes(phase = GuidanceRunPhase.AWAITING_FIRST_RESULT, stall = StallCondition.STARTUP_TIMEOUT, judgement = null),
            GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED),
        ),
        Row("4 perception stalled", axes(stall = StallCondition.NO_RESULTS), GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED)),
        Row(
            "4 before 6: stalled with a stale unqualified judgement",
            axes(stall = StallCondition.NO_RESULTS, judgement = obstructed),
            GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED),
        ),
        Row(
            "4 before 7: startup stalled with an output fault",
            axes(phase = GuidanceRunPhase.AWAITING_FIRST_RESULT, stall = StallCondition.STARTUP_TIMEOUT, engine = SpeechEngineState.INITIALIZING),
            GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED),
        ),
        Row("5 starting", axes(phase = GuidanceRunPhase.STARTING, judgement = null), GuidanceSafetyState.Initializing),
        Row(
            "5 before 7: starting with an output fault",
            axes(phase = GuidanceRunPhase.STARTING, judgement = null, engine = SpeechEngineState.INITIALIZING, speechReady = false),
            GuidanceSafetyState.Initializing,
        ),
        Row(
            "5 before 6: back from the background with the judgement from before it",
            axes(phase = GuidanceRunPhase.AWAITING_FIRST_RESULT, judgement = obstructed),
            GuidanceSafetyState.Initializing,
        ),
        Row("6 obstructed", axes(judgement = obstructed), GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED)),
        Row(
            "6 too dark, the floor unrecognised too",
            axes(judgement = BaselineQualification.Unqualified(UnqualifiedReason.TOO_DARK, ground)),
            GuidanceSafetyState.StopRequired(StopReason.TOO_DARK),
        ),
        Row(
            "6 before 7: obstructed with speech unavailable",
            axes(judgement = obstructed, engine = SpeechEngineState.UNAVAILABLE),
            GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED),
        ),
        Row("7 floor unrecognised", axes(judgement = BaselineQualification.Degraded(ground)), GuidanceSafetyState.Degraded(ground, emptySet())),
        Row(
            "7 floor unrecognised and speech unavailable",
            axes(judgement = BaselineQualification.Degraded(ground), engine = SpeechEngineState.UNAVAILABLE),
            GuidanceSafetyState.Degraded(ground, setOf(OutputFault.SPEECH_UNAVAILABLE)),
        ),
        Row(
            "7 speech not ready",
            axes(engine = SpeechEngineState.INITIALIZING, speechReady = false),
            GuidanceSafetyState.Degraded(emptySet(), setOf(OutputFault.SPEECH_NOT_READY)),
        ),
        Row(
            "7 speech unavailable, isReady already true again: the health keeps the engine's state",
            axes(engine = SpeechEngineState.UNAVAILABLE, speechReady = true),
            GuidanceSafetyState.Degraded(emptySet(), setOf(OutputFault.SPEECH_UNAVAILABLE)),
        ),
        Row("8 guiding", axes(), GuidanceSafetyState.Guiding),
        Row(
            "8 engine READY, isReady false, speech on, no screen reader, navigation vibration off: channel NONE alone is guiding",
            axes(engine = SpeechEngineState.READY, speechReady = false, speech = true, screenReader = false, haptics = false),
            GuidanceSafetyState.Guiding,
        ),
        Row("8 speech and vibration off: the status card only", axes(speech = false, haptics = false), GuidanceSafetyState.Guiding),
        Row(
            "8 the screen reader speaks: own speech is not relied on",
            axes(screenReader = true, engine = SpeechEngineState.UNAVAILABLE, speechReady = false),
            GuidanceSafetyState.Guiding,
        ),
        Row("8 running with no judgement", axes(judgement = null), GuidanceSafetyState.Guiding),
    )

    @Test
    fun `every row of the table`() {
        for (row in rows) assertEquals(row.name, row.state, row.axes.state())
        assertTrue("about 25 rows", rows.size >= 25)
    }

    @Test
    fun `channel NONE with a ready engine and no fault is guiding`() {
        val axes = axes(engine = SpeechEngineState.READY, speechReady = false, speech = true, screenReader = false, haptics = false)
        assertEquals(NavigationChannel.NONE, axes.expectedNavigationChannel)
        assertEquals(emptySet<OutputFault>(), axes.outputFaults)
        assertEquals(GuidanceSafetyState.Guiding, axes.state())
    }

    @Test
    fun `a failure goes with the failed phase and only with it`() {
        for (phase in GuidanceRunPhase.entries) {
            for (failure in listOf(null) + FailureReason.entries) {
                val legal = (phase == GuidanceRunPhase.FAILED) == (failure != null)
                val built = runCatching { axes(phase = phase, failure = failure) }
                assertEquals("$phase $failure", legal, built.isSuccess)
            }
        }
    }

    // ---- the invariants, over every combination

    private val judgements: List<BaselineQualification?> = listOf(
        null,
        qualified,
        BaselineQualification.Degraded(ground),
        BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, emptySet()),
        BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, ground),
        BaselineQualification.Unqualified(UnqualifiedReason.TOO_DARK, emptySet()),
        BaselineQualification.Unqualified(UnqualifiedReason.TOO_DARK, ground),
    )

    private data class Output(val engine: SpeechEngineState, val ready: Boolean, val speech: Boolean, val reader: Boolean, val haptics: Boolean)

    private val outputs = SpeechEngineState.entries.flatMap { engine ->
        listOf(false, true).flatMap { ready ->
            listOf(false, true).flatMap { speech ->
                listOf(false, true).flatMap { reader -> listOf(false, true).map { haptics -> Output(engine, ready, speech, reader, haptics) } }
            }
        }
    }

    private data class Judging(val phase: GuidanceRunPhase, val interrupted: Boolean, val stall: StallCondition, val judgement: BaselineQualification?)

    private val judgings = GuidanceRunPhase.entries.flatMap { phase ->
        listOf(false, true).flatMap { interrupted ->
            StallCondition.entries.flatMap { stall -> judgements.map { judgement -> Judging(phase, interrupted, stall, judgement) } }
        }
    }

    private fun axesOf(judging: Judging, output: Output) = axes(
        phase = judging.phase,
        interrupted = judging.interrupted,
        stall = judging.stall,
        judgement = judging.judgement,
        engine = output.engine,
        speechReady = output.ready,
        speech = output.speech,
        screenReader = output.reader,
        haptics = output.haptics,
    )

    private val sweep: List<Triple<Judging, Output, GuidanceSafetyState>> =
        judgings.flatMap { judging -> outputs.map { output -> Triple(judging, output, axesOf(judging, output).state()) } }

    private val GuidanceSafetyState.followsTheOutput: Boolean
        get() = this == GuidanceSafetyState.Guiding || this is GuidanceSafetyState.Degraded

    private val GuidanceSafetyState.perception: Set<DegradeReason>
        get() = (this as? GuidanceSafetyState.Degraded)?.perception.orEmpty()

    @Test
    fun `the sweep covers every combination of the axes`() {
        // phase 5, interrupted 2, stall 3, judgement 7, engine 4, isReady 2, speech 2, screen reader 2, vibration 2
        assertEquals(13_440, sweep.size)
    }

    @Test
    fun `I1 - the output alone moves the state only between guiding and degraded, and never its perception`() {
        for ((judging, group) in sweep.groupBy { it.first }) {
            val states = group.map { it.third }.toSet()
            if (states.any { !it.followsTheOutput }) {
                assertEquals("$judging: the output moved a state that does not follow it: $states", 1, states.size)
            } else {
                assertEquals("$judging: the output changed the perception: $states", 1, states.map { it.perception }.toSet().size)
            }
        }
    }

    @Test
    fun `I2 - running, not interrupted, not stalled and unqualified is always a stop for that reason`() {
        for ((judging, _, state) in sweep) {
            val judgement = judging.judgement
            if (judging.phase != GuidanceRunPhase.RUNNING || judging.interrupted || judging.stall != StallCondition.NONE) continue
            if (judgement !is BaselineQualification.Unqualified) continue
            val reason = when (judgement.reason) {
                UnqualifiedReason.CAMERA_OBSTRUCTED -> StopReason.CAMERA_OBSTRUCTED
                UnqualifiedReason.TOO_DARK -> StopReason.TOO_DARK
            }
            assertEquals("$judging", GuidanceSafetyState.StopRequired(reason), state)
        }
    }

    @Test
    fun `I3 - failed exactly when the phase failed, idle exactly when not started`() {
        for ((judging, output, state) in sweep) {
            assertEquals("$judging $output", judging.phase == GuidanceRunPhase.FAILED, state is GuidanceSafetyState.Failed)
            assertEquals("$judging $output", judging.phase == GuidanceRunPhase.NOT_STARTED, state == GuidanceSafetyState.Idle)
        }
    }

    @Test
    fun `I4 - while interrupted, the stall condition changes nothing`() {
        val interrupted = sweep.filter { it.first.interrupted }
        for ((_, group) in interrupted.groupBy { Triple(it.first.phase, it.first.judgement, it.second) }) {
            assertEquals("$group", 1, group.map { it.third }.toSet().size)
        }
    }

    @Test
    fun `I5 - the state is a function of the axes alone`() {
        val again = judgings.reversed().flatMap { judging -> outputs.reversed().map { output -> (judging to output) to axesOf(judging, output).state() } }.toMap()
        for ((judging, output, state) in sweep) {
            assertEquals(state, again.getValue(judging to output))
            assertEquals(state, axesOf(judging, output).copy().state())
        }
    }

    // ---- the names the trace writes

    @Test
    fun `every state has its own trace name, from the closed set`() {
        val states = listOf(
            GuidanceSafetyState.Idle,
            GuidanceSafetyState.Initializing,
            GuidanceSafetyState.Guiding,
            GuidanceSafetyState.Degraded(ground, emptySet()),
            GuidanceSafetyState.StopRequired(StopReason.TOO_DARK),
            GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE),
            GuidanceSafetyState.Failed(FailureReason.ERROR),
        )
        assertEquals(SafetyTraceNames.States.all, states.map { it.traceName }.toSet())
        assertEquals(states.size, states.map { it.traceName }.distinct().size)
        assertEquals(
            "every state of the sweep is one of these kinds",
            sweep.map { it.third.traceName }.toSet(),
            SafetyTraceNames.States.all,
        )
    }

    @Test
    fun `every phase, condition, channel, input and alert has its own name, from the closed sets`() {
        assertEquals(SafetyTraceNames.Phases.all, GuidanceRunPhase.entries.map { it.traceName }.toSet())
        assertEquals(GuidanceRunPhase.entries.size, SafetyTraceNames.Phases.all.size)
        assertEquals(SafetyTraceNames.Stalls.all, StallCondition.entries.map { it.traceName }.toSet())
        assertEquals(StallCondition.entries.size, SafetyTraceNames.Stalls.all.size)
        assertEquals(SafetyTraceNames.Channels.all, NavigationChannel.entries.map { it.traceName }.toSet())
        assertEquals(NavigationChannel.entries.size, SafetyTraceNames.Channels.all.size)
        assertEquals(SafetyTraceNames.Causes.all - SafetyTraceNames.Causes.SESSION_START, SafetyInput.entries.map { it.traceName }.toSet())
        assertEquals(SafetyInput.entries.size + 1, SafetyTraceNames.Causes.all.size)
        assertEquals(SystemAlert.entries.size, SystemAlert.entries.map { it.traceKind }.distinct().size)
        assertTrue(SystemAlert.entries.all { it.traceKind in GuidanceAlertKinds.all })
    }

    @Test
    fun `the label is the state with its reasons, as the trace's state label`() {
        assertEquals("guiding", GuidanceSafetyState.Guiding.label)
        assertEquals("stop_required(camera_obstructed)", GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED).label)
        assertEquals(
            "degraded(ground_unrecognized,speech_unavailable)",
            GuidanceSafetyState.Degraded(ground, setOf(OutputFault.SPEECH_UNAVAILABLE)).label,
        )
    }
}
