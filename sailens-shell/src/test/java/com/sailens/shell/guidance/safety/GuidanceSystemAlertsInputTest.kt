package com.sailens.shell.guidance.safety

import com.sailens.core.log.LogService
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.output.SpeechEngineState
import com.sailens.shell.device.GuidanceHaptic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How [GuidanceSystemAlerts] reaches the safety monitor: each entry is one input, the alert given is the one the
 * monitor returned for it, and before each input the screen's own facts are compared with the monitor.
 *
 * What the alerts do, call by call, is [GuidanceSystemAlertsTest]'s, which on purpose counts nothing of the monitor.
 * Here the real monitor sits behind a counting delegate, and the fake screen writes its facts where the view model
 * writes them: running from the flow's start, cleared by a stop.
 */
class GuidanceSystemAlertsInputTest {

    private var now = 0L
    private var running = false
    private var interrupted = false
    private var speechEnabled = true
    private var screenReader = false
    private var engine = SpeechEngineState.READY

    private val alarms = mutableListOf<GuidanceHaptic>()
    private val warnings = mutableListOf<Pair<String, Map<String, Any>?>>()
    private val errors = mutableListOf<String>()

    private val logger = object : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) {
            warnings += message to data
        }
        override fun error(tag: String, message: String, throwable: Throwable?) {
            errors += message
        }
    }

    /** The real monitor, with every input it is given written down first. */
    private class CountingMonitor(private val real: SafetyMonitor) : SafetyMonitor by real {
        val inputs = mutableListOf<SafetyInput>()

        override fun onStartRequested(nowMs: Long) {
            inputs += SafetyInput.START_REQUESTED
            real.onStartRequested(nowMs)
        }

        override fun onStallWatchStarted(nowMs: Long) {
            inputs += SafetyInput.STALL_WATCH_STARTED
            real.onStallWatchStarted(nowMs)
        }

        override fun onFlowStarted(nowMs: Long) {
            inputs += SafetyInput.FLOW_STARTED
            real.onFlowStarted(nowMs)
        }

        override fun onResult(nowMs: Long, baseline: BaselineQualification, speechReady: Boolean) {
            inputs += SafetyInput.RESULT
            real.onResult(nowMs, baseline, speechReady)
        }

        override fun onFlowFailed(nowMs: Long, error: Throwable): SystemAlert? {
            inputs += SafetyInput.FLOW_FAILED
            return real.onFlowFailed(nowMs, error)
        }

        override fun onStopped(nowMs: Long) {
            inputs += SafetyInput.STOPPED
            real.onStopped(nowMs)
        }

        override fun onLifecycleStop(nowMs: Long): SystemAlert? {
            inputs += SafetyInput.LIFECYCLE_STOP
            return real.onLifecycleStop(nowMs)
        }

        override fun onLifecycleStart(nowMs: Long) {
            inputs += SafetyInput.LIFECYCLE_START
            real.onLifecycleStart(nowMs)
        }

        override fun onOutput(nowMs: Long, facts: OutputFacts): SystemAlert? {
            inputs += SafetyInput.OUTPUT
            return real.onOutput(nowMs, facts)
        }

        override fun poll(nowMs: Long): SystemAlert? {
            inputs += SafetyInput.POLL
            return real.poll(nowMs)
        }
    }

    private var counting: CountingMonitor? = null
    private val inputs: List<SafetyInput> get() = checkNotNull(counting).inputs

    private fun alerts() = GuidanceSystemAlerts(
        cancelStallLoop = {},
        stopGuidanceOutput = {},
        clearLatestResult = {},
        preemptDescription = {},
        playAlarm = { haptic ->
            alarms += haptic
            true
        },
        speakSystemNotice = { _, _ -> },
        announceToScreenReader = {},
        noticeText = { it.name },
        markInterrupted = { interrupted = true },
        clearInterrupted = { interrupted = false },
        markFailed = { running = false },
        isRunning = { running },
        wasInterrupted = { interrupted },
        outputSettings = { GuidanceOutputSettings(speechEnabled = speechEnabled, screenReaderActive = screenReader, hapticsEnabled = true) },
        speechReady = { engine == SpeechEngineState.READY },
        initialOutput = OutputFacts(engine, speechEnabled, screenReader, hapticsEnabled = true, speechReady = engine == SpeechEngineState.READY),
        recordState = {},
        recordAlert = {},
        publishState = {},
        timed = { false },
        logger = logger,
        clock = { now },
        detFirst = DetFirstConfig(),
        wrapMonitor = { real -> CountingMonitor(real).also { counting = it } },
    )

    // The view model's call sites, with the screen state it writes around each.

    private fun GuidanceSystemAlerts.startPressed() {
        onStartRequested()
        onStallWatchStarted()
    }

    private fun GuidanceSystemAlerts.flowStarted() {
        onFlowStarted()
        running = true
    }

    private fun GuidanceSystemAlerts.output() = onOutput(engine, speechEnabled, screenReader, true)

    /** Started at 0, the flow collected, a first result at 1 000 ms. */
    private fun GuidanceSystemAlerts.guiding(): GuidanceSystemAlerts {
        now = 0L
        startPressed()
        flowStarted()
        now = 1_000L
        onResult(BaselineQualification.Qualified)
        return this
    }

    private fun disagreements() = warnings.filter { it.first == GuidanceSafetyObservation.DISAGREEMENT }

    @After
    fun `no observation call failed`() {
        assertEquals(emptyList<String>(), errors.filter { it == "Safety observation failed" })
    }

    @Test
    fun `every entry is one input to the monitor, and the two that are not inputs are none`() {
        val alerts = alerts()

        fun once(expected: SafetyInput?, entry: () -> Unit) {
            val before = inputs.size
            entry()
            assertEquals("$expected", listOfNotNull(expected), inputs.drop(before))
        }

        now = 0L
        once(SafetyInput.START_REQUESTED) { alerts.onStartRequested() }
        once(SafetyInput.STALL_WATCH_STARTED) { alerts.onStallWatchStarted() }
        once(SafetyInput.FLOW_STARTED) { alerts.onFlowStarted() }
        running = true
        now = 1_000L
        once(SafetyInput.RESULT) { alerts.onResult(BaselineQualification.Qualified) }
        now = 3_000L
        once(SafetyInput.POLL) { alerts.poll() }
        // The stall alarm is due: still one input, which gives the alarm.
        now = 3_500L
        once(SafetyInput.POLL) { alerts.poll() }
        assertEquals(listOf(GuidanceHaptic.INTERRUPTED), alarms)
        engine = SpeechEngineState.UNAVAILABLE
        once(SafetyInput.OUTPUT) { alerts.output() }
        assertEquals(GuidanceHaptic.SPEECH_UNAVAILABLE, alarms.last())
        once(SafetyInput.LIFECYCLE_STOP) { alerts.onLifecycleStop("interrupted words") }
        now = 4_000L
        once(SafetyInput.POLL) { alerts.poll() }
        once(SafetyInput.LIFECYCLE_START) { alerts.onLifecycleStart() }
        // The failure stops the stall watch inside the entry: still one input.
        once(SafetyInput.FLOW_FAILED) { alerts.onFlowFailed(IllegalStateException("model lost")) }
        once(null) { alerts.endOfSession() }

        now = 5_000L
        once(SafetyInput.START_REQUESTED) { alerts.onStartRequested() }
        once(SafetyInput.STALL_WATCH_STARTED) { alerts.onStallWatchStarted() }
        once(SafetyInput.FLOW_STARTED) { alerts.onFlowStarted() }
        running = true
        once(null) { alerts.onStallWatchStopped() }
        once(SafetyInput.STOPPED) { alerts.onStopped() }
        running = false
        interrupted = false
        once(null) { alerts.endOfSession() }

        assertEquals(
            "stalled, speech unavailable, interrupted, failed",
            listOf(GuidanceHaptic.INTERRUPTED, GuidanceHaptic.SPEECH_UNAVAILABLE, GuidanceHaptic.INTERRUPTED, GuidanceHaptic.INTERRUPTED),
            alarms,
        )
        assertEquals(emptyList<Pair<String, Map<String, Any>?>>(), disagreements())
    }

    @Test
    fun `one unavailable output gives one speech alarm, and the same output again gives none`() {
        val alerts = alerts()
        engine = SpeechEngineState.UNAVAILABLE

        alerts.output()
        assertEquals(listOf(GuidanceHaptic.SPEECH_UNAVAILABLE), alarms)
        alerts.output()
        assertEquals("the same outage", listOf(GuidanceHaptic.SPEECH_UNAVAILABLE), alarms)

        assertEquals(listOf(SafetyInput.OUTPUT, SafetyInput.OUTPUT), inputs)
    }

    /** One entry in a screen state where the view model calls it. */
    private class Case(val input: SafetyInput, val before: GuidanceSystemAlerts.() -> Unit, val entry: GuidanceSystemAlerts.() -> Unit)

    private val cases = listOf(
        Case(SafetyInput.START_REQUESTED, before = {}, entry = { onStartRequested() }),
        Case(SafetyInput.STALL_WATCH_STARTED, before = { onStartRequested() }, entry = { onStallWatchStarted() }),
        Case(SafetyInput.FLOW_STARTED, before = { startPressed() }, entry = { onFlowStarted() }),
        Case(SafetyInput.RESULT, before = { startPressed(); flowStarted() }, entry = { onResult(BaselineQualification.Qualified) }),
        Case(SafetyInput.FLOW_FAILED, before = { guiding() }, entry = { onFlowFailed(IllegalStateException("model lost")) }),
        Case(SafetyInput.STOPPED, before = { guiding(); onStallWatchStopped() }, entry = { onStopped() }),
        Case(SafetyInput.LIFECYCLE_STOP, before = { guiding() }, entry = { onLifecycleStop("interrupted words") }),
        Case(SafetyInput.LIFECYCLE_START, before = { guiding(); onLifecycleStop("interrupted words") }, entry = { onLifecycleStart() }),
        Case(SafetyInput.OUTPUT, before = { guiding() }, entry = { output() }),
        Case(SafetyInput.POLL, before = { guiding() }, entry = { poll() }),
    )

    private fun reset() {
        now = 0L
        running = false
        interrupted = false
        engine = SpeechEngineState.READY
        alarms.clear()
        warnings.clear()
    }

    @Test
    fun `at the start of every input the screen's facts agree with the monitor, and nothing is written`() {
        assertEquals("every input has a case", SafetyInput.entries.toSet(), cases.map { it.input }.toSet())
        for (case in cases) {
            reset()
            val alerts = alerts()
            alerts.(case.before)()
            alerts.(case.entry)()
            assertEquals("${case.input}", emptyList<Pair<String, Map<String, Any>?>>(), disagreements())
        }
    }

    @Test
    fun `at the start of every input a screen fact the monitor does not hold is one warning, naming the input`() {
        for (flip in listOf("running", "interrupted")) {
            for (case in cases) {
                reset()
                val alerts = alerts()
                alerts.(case.before)()
                when (flip) {
                    "running" -> running = !running
                    else -> interrupted = !interrupted
                }
                alerts.(case.entry)()
                val found = disagreements()
                assertEquals("${case.input}, $flip", 1, found.size)
                assertEquals("${case.input}, $flip", case.input.name, found.single().second!!["input"])
            }
        }
    }

    @Test
    fun `the interruption alert follows the monitor, whatever the screen's own flag says`() {
        // The screen says not running while the monitor holds a running session: the alert is the monitor's, and the
        // disagreement is a warning.
        val guided = alerts().guiding()
        running = false
        guided.onLifecycleStop("interrupted words")
        assertEquals(listOf(GuidanceHaptic.INTERRUPTED), alarms)
        assertTrue(interrupted)
        assertEquals(1, disagreements().size)

        // The other way: the screen says running while the monitor is idle. Nothing is told.
        reset()
        val idle = alerts()
        running = true
        idle.onLifecycleStop("interrupted words")
        assertEquals(emptyList<GuidanceHaptic>(), alarms)
        assertFalse(interrupted)
        assertEquals(1, disagreements().size)
    }
}
