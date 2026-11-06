package com.sailens.shell.guidance.safety

import com.sailens.core.log.LogService
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.DegradeReason
import com.sailens.guidance.safety.UnqualifiedReason
import com.sailens.output.SpeechEngineState
import com.sailens.shell.device.GuidanceHaptic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the view model's side of the monitor writes and returns: trace records, the UI's state, the monitor's alerts,
 * and warnings when the screen's facts and the monitor disagree.
 */
class GuidanceSafetyObservationTest {

    private var now = 0L
    private var nanos = 0L
    private var stall = StallCondition.NONE
    private var alarmDue = false
    private var conditionFault: RuntimeException? = null
    private var diagnostics = false
    private var failRecording = false
    private var failPublishing = false
    private val states = mutableListOf<SafetyStateTrace>()
    private val alerts = mutableListOf<GuidanceAlertTrace>()
    private val published = mutableListOf<GuidanceSafetyState>()
    private val log = mutableListOf<String>()
    private val logData = mutableListOf<Map<String, Any>?>()

    private val logger = object : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) {
            log += "info $message"
            logData += data
        }
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) {
            log += "warning $message"
            logData += data
        }
        override fun error(tag: String, message: String, throwable: Throwable?) {
            log += "error $message"
            logData += null
        }
    }

    private val output = OutputFacts(SpeechEngineState.READY, speechEnabled = true, screenReaderActive = false, hapticsEnabled = true, speechReady = true)

    private val observation = GuidanceSafetyObservation(
        stallCondition = { conditionFault?.let { throw it } ?: stall },
        stallAlarmDue = { alarmDue },
        nowMs = 0L,
        initialOutput = output,
        recordState = {
            check(!failRecording) { "disk full" }
            states += it
        },
        recordAlert = { alerts += it },
        publishState = {
            check(!failPublishing) { "no UI" }
            published += it
        },
        logger = logger,
        timed = { diagnostics },
        elapsedClock = { now },
        wallClock = { 1_700_000_000_000L + now },
        nanoClock = { nanos },
    )

    private fun running() {
        observation.observe { onStartRequested(0) }
        observation.observe { onStallWatchStarted(0) }
        observation.observe { onFlowStarted(0) }
        observation.observe { onResult(1_000, BaselineQualification.Qualified, speechReady = true) }
    }

    @Test
    fun `the initial state is published once, and every change is a record`() {
        assertEquals(listOf<GuidanceSafetyState>(GuidanceSafetyState.Idle), published)
        running()

        assertEquals(
            listOf(
                SafetyTraceNames.Causes.START_REQUESTED to SafetyTraceNames.States.INITIALIZING,
                SafetyTraceNames.Causes.FLOW_STARTED to SafetyTraceNames.States.INITIALIZING,
                SafetyTraceNames.Causes.RESULT to SafetyTraceNames.States.GUIDING,
            ),
            states.map { it.cause to it.state },
        )
        assertEquals(
            "published on each change of state, not of a fact",
            listOf(GuidanceSafetyState.Idle, GuidanceSafetyState.Initializing, GuidanceSafetyState.Guiding),
            published,
        )
    }

    @Test
    fun `a record carries the axes by their trace names`() {
        running()
        now = 4_000
        observation.observe {
            onResult(4_000, BaselineQualification.Unqualified(UnqualifiedReason.TOO_DARK, setOf(DegradeReason.GROUND_UNRECOGNIZED)), speechReady = false)
        }
        observation.observe { onOutput(4_100, output.copy(engineState = SpeechEngineState.UNAVAILABLE, speechReady = false, hapticsEnabled = false)) }

        val record = states.last()
        assertEquals("", record.sessionId)
        assertEquals(0L, record.seq)
        assertEquals(1_700_000_004_000L, record.at)
        assertEquals(4_100L, record.elapsedMs)
        assertEquals(4_000L, record.stateSinceElapsedMs)
        assertEquals(SafetyTraceNames.States.STOP_REQUIRED, record.state)
        assertEquals(listOf("too_dark"), record.reasons)
        assertEquals(SafetyTraceNames.Phases.RUNNING, record.phase)
        assertEquals(false, record.interrupted)
        assertEquals(SafetyTraceNames.Stalls.NONE, record.stall)
        assertEquals("unqualified", record.judgement)
        assertEquals(listOf("too_dark", "ground_unrecognized"), record.judgementReasons)
        assertEquals(listOf("speech_unavailable"), record.outputFaults)
        assertEquals(SafetyTraceNames.Channels.NONE, record.expectedNavigationChannel)
        assertEquals(SafetyTraceNames.Causes.OUTPUT, record.cause)
        assertEquals("stop_required(too_dark)", record.stateLabel)
    }

    @Test
    fun `screen facts the monitor agrees with write nothing, ones it does not a warning`() {
        observation.checkFacts(SafetyInput.START_REQUESTED, running = false, interrupted = false)
        assertEquals("idle: not running, not interrupted", emptyList<String>(), log)

        running()
        observation.checkFacts(SafetyInput.POLL, running = true, interrupted = false)
        assertEquals(emptyList<String>(), log)
        observation.checkFacts(SafetyInput.POLL, running = false, interrupted = false)
        observation.checkFacts(SafetyInput.POLL, running = true, interrupted = true)
        assertEquals(List(2) { "warning ${GuidanceSafetyObservation.DISAGREEMENT}" }, log)

        log.clear()
        logData.clear()
        observation.observe { onLifecycleStop(5_000) }
        observation.checkFacts(SafetyInput.LIFECYCLE_START, running = true, interrupted = true)
        assertEquals(emptyList<String>(), log)
        observation.checkFacts(SafetyInput.LIFECYCLE_START, running = true, interrupted = false)
        assertEquals(listOf("warning ${GuidanceSafetyObservation.DISAGREEMENT}"), log)
        assertEquals(
            mapOf(
                "input" to "LIFECYCLE_START",
                "running" to true,
                "interrupted" to false,
                "failureNotShown" to false,
                "phase" to "RUNNING",
                "monitorInterrupted" to true,
                "state" to "interrupted(lifecycle)",
            ),
            logData.last(),
        )
    }

    @Test
    fun `inside a failure not yet shown, the screen still saying running is what agrees`() {
        running()
        observation.observe { onFlowFailed(2_000, IllegalStateException("lost")) }

        observation.checkFacts(SafetyInput.OUTPUT, running = true, interrupted = false, failureNotShown = true)
        assertEquals(emptyList<String>(), log)
        // Outside that window the failed monitor and a running screen disagree, and so do a stopped screen inside it.
        observation.checkFacts(SafetyInput.OUTPUT, running = true, interrupted = false)
        observation.checkFacts(SafetyInput.OUTPUT, running = false, interrupted = false, failureNotShown = true)
        observation.checkFacts(SafetyInput.OUTPUT, running = true, interrupted = true, failureNotShown = true)
        assertEquals(List(3) { "warning ${GuidanceSafetyObservation.DISAGREEMENT}" }, log)

        // A monitor that has not failed is not what the window expects.
        val fresh = GuidanceSafetyObservationTest()
        fresh.running()
        fresh.observation.checkFacts(SafetyInput.OUTPUT, running = true, interrupted = false, failureNotShown = true)
        assertEquals(listOf("warning ${GuidanceSafetyObservation.DISAGREEMENT}"), fresh.log)
    }

    @Test
    fun `an input returns the alert the monitor calls for`() {
        assertEquals("not running: nothing to tell", null, observation.observe { onLifecycleStop(0) })
        running()
        assertEquals(null, observation.observe { poll(2_000) })
        alarmDue = true
        assertEquals(SystemAlert.STALLED, observation.observe { poll(4_000) })
        assertEquals(SystemAlert.INTERRUPTED, observation.observe { onLifecycleStop(5_000) })
        assertEquals("interrupted: no stall alarm", null, observation.observe { poll(5_500) })
        assertEquals(SystemAlert.FAILED, observation.observe { onFlowFailed(6_000, IllegalStateException("lost")) })
        assertEquals(
            SystemAlert.SPEECH_UNAVAILABLE,
            observation.observe { onOutput(7_000, output.copy(engineState = SpeechEngineState.UNAVAILABLE, speechReady = false)) },
        )
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a fault in the monitor's own input reaches the caller, and is still timed`() {
        diagnostics = true
        observation.startOfSession()
        running()
        conditionFault = IllegalStateException("broken rule")
        nanos = 0L

        val thrown = runCatching {
            observation.observe {
                nanos += 7_000_000
                poll(2_000)
            }
        }.exceptionOrNull()

        assertEquals("broken rule", thrown?.message)
        assertEquals("not logged as a contained fault", emptyList<String>(), log)
        conditionFault = null
        observation.endOfSession()
        assertEquals(7.0, logData.single()!!["maxMs"] as Double, 1e-9)
    }

    @Test
    fun `system alerts are traced as the shell's, with their kind, key, channel, result and priority`() {
        now = 7_000
        observation.rhythmGiven(SystemAlert.STALLED, GuidanceHaptic.INTERRUPTED, vibrated = true)
        observation.noticeRequested(SystemAlert.STALLED)
        observation.noticePublished(SystemAlert.INTERRUPTED)
        observation.rhythmGiven(SystemAlert.SPEECH_UNAVAILABLE, GuidanceHaptic.SPEECH_UNAVAILABLE, vibrated = true)
        observation.noticeRequested(SystemAlert.FAILED)

        assertTrue(alerts.all { it.source == GuidanceAlertSources.SYSTEM && it.elapsedMs == 7_000L && it.at == 1_700_000_007_000L })
        assertEquals(
            listOf(
                listOf(GuidanceAlertKinds.STALLED, null, "interrupted", GuidanceAlertChannels.HAPTICS, GuidanceAlertResults.PLAYED, "high"),
                listOf(GuidanceAlertKinds.STALLED, "notice_guidance_stalled", null, GuidanceAlertChannels.SPEECH, GuidanceAlertResults.REQUESTED, "high"),
                listOf(GuidanceAlertKinds.INTERRUPTED, "notice_guidance_interrupted", null, GuidanceAlertChannels.SCREEN_READER, GuidanceAlertResults.PUBLISHED, "max"),
                listOf(GuidanceAlertKinds.SPEECH_UNAVAILABLE, null, "speech_unavailable", GuidanceAlertChannels.HAPTICS, GuidanceAlertResults.PLAYED, "max"),
                listOf(GuidanceAlertKinds.FAILED, "notice_guidance_failed", null, GuidanceAlertChannels.SPEECH, GuidanceAlertResults.REQUESTED, "max"),
            ),
            alerts.map { listOf(it.kind, it.messageKey, it.haptic, it.channel, it.result, it.priority) },
        )
    }

    @Test
    fun `a rhythm on a phone without a vibrator is traced as nothing having taken it`() {
        observation.rhythmGiven(SystemAlert.INTERRUPTED, GuidanceHaptic.INTERRUPTED, vibrated = false)

        assertEquals(
            listOf(listOf(GuidanceAlertKinds.INTERRUPTED, "interrupted", GuidanceAlertChannels.HAPTICS, GuidanceAlertResults.REFUSED)),
            alerts.map { listOf(it.kind, it.haptic, it.channel, it.result) },
        )
    }

    @Test
    fun `a fault in observing is logged and goes no further, and the monitor keeps its state`() {
        failRecording = true
        observation.observe { onStartRequested(0) }
        observation.noticeRequested(SystemAlert.SPEECH_UNAVAILABLE)

        assertEquals(listOf("error Safety observation failed", "error Safety observation failed"), log)
        assertEquals(GuidanceSafetyState.Initializing, observation.monitor.state)
        assertEquals(emptyList<GuidanceAlertTrace>(), alerts)
    }

    @Test
    fun `a fault in the trace still lets the UI have the new state`() {
        failRecording = true
        observation.observe { onStartRequested(0) }

        assertEquals(listOf("error Safety observation failed"), log)
        assertEquals(listOf<GuidanceSafetyState>(GuidanceSafetyState.Idle, GuidanceSafetyState.Initializing), published)
    }

    @Test
    fun `a fault in the UI still lets the trace have the change, and the state the UI missed is offered at the next`() {
        failPublishing = true
        observation.observe { onStartRequested(0) }

        assertEquals(listOf("error Safety observation failed"), log)
        assertEquals(listOf(SafetyTraceNames.States.INITIALIZING), states.map { it.state })
        assertEquals(listOf<GuidanceSafetyState>(GuidanceSafetyState.Idle), published)

        failPublishing = false
        // The flow starts: the phase changes, the state stays Initializing, which the UI has not had.
        observation.observe { onFlowStarted(0) }
        assertEquals(listOf<GuidanceSafetyState>(GuidanceSafetyState.Idle, GuidanceSafetyState.Initializing), published)
        assertEquals(2, states.size)
    }

    @Test
    fun `with diagnostics on, the session's main-thread time is logged at its end, then started afresh`() {
        diagnostics = true
        observation.startOfSession()
        for (micros in listOf(10L, 20L, 30L, 40L, 1_000L)) {
            observation.observe {
                nanos += micros * 1_000
                poll(0)
            }
        }
        observation.endOfSession()

        assertEquals(listOf("info Safety observation main-thread time"), log)
        val summary = logData.single()!!
        assertEquals(5L, summary["calls"])
        assertEquals(0.031, summary["p50Ms"] as Double, 1e-9)
        assertEquals(1.001, summary["p95Ms"] as Double, 1e-9)
        assertEquals(1.0, summary["maxMs"] as Double, 1e-9)

        log.clear()
        observation.endOfSession()
        assertEquals("nothing kept since", emptyList<String>(), log)
    }

    @Test
    fun `with diagnostics off, nothing is timed or logged`() {
        observation.startOfSession()
        running()
        observation.endOfSession()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a call outside a session is not timed - before the start is asked for, or after the session ended`() {
        diagnostics = true
        fun call(micros: Long) = observation.observe {
            nanos += micros * 1_000
            poll(0)
        }
        // The speech engine coming up as the app starts: no session yet.
        call(5_000)
        observation.startOfSession()
        repeat(3) { call(10) }
        observation.endOfSession()
        call(5_000)
        observation.endOfSession()

        assertEquals("one session, logged once", listOf("info Safety observation main-thread time"), log)
        val summary = logData.single()!!
        assertEquals(3L, summary["calls"])
        assertEquals(0.01, summary["maxMs"] as Double, 1e-9)
    }

    @Test
    fun `a start asked for again before the session ended keeps what the first one's calls took`() {
        diagnostics = true
        fun call(micros: Long) = observation.observe {
            nanos += micros * 1_000
            poll(0)
        }
        observation.startOfSession()
        call(5_000)
        // The first request is still waiting to start, and the user asks again.
        observation.startOfSession()
        call(10)
        observation.endOfSession()

        val summary = logData.single()!!
        assertEquals(2L, summary["calls"])
        assertEquals(5.0, summary["maxMs"] as Double, 1e-9)
    }

    @Test
    fun `percentiles are the microsecond above, the maximum exact, and a long call is not lost`() {
        val cost = ObservationCost(buckets = 1_000)
        assertNull(cost.percentileMs(0.95))
        assertNull(cost.summary())
        repeat(19) { cost.add(150_000L) } // 0.150 ms
        cost.add(5_000_000L) // 5 ms, beyond the buckets
        assertEquals(0.151, cost.percentileMs(0.95)!!, 1e-9)
        assertEquals(5.0, cost.percentileMs(1.0)!!, 1e-9)
        assertEquals(5.0, cost.summary()!!["maxMs"] as Double, 1e-9)
        cost.clear()
        assertNull(cost.summary())
    }
}
