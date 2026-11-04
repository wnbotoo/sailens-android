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

/** What the view model's side of the monitor writes: trace records, the UI's state, and warnings when they disagree. */
class GuidanceSafetyObservationTest {

    private var now = 0L
    private var nanos = 0L
    private var stall = StallCondition.NONE
    private var diagnostics = false
    private var failRecording = false
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
        stallCondition = { stall },
        nowMs = 0L,
        initialOutput = output,
        recordState = {
            check(!failRecording) { "disk full" }
            states += it
        },
        recordAlert = { alerts += it },
        publishState = { published += it },
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
    fun `an alert the state stands behind writes nothing, one it does not a warning`() {
        running()
        observation.checkGiven(SystemAlert.STALLED)
        observation.checkGiven(SystemAlert.FAILED)
        observation.checkGiven(SystemAlert.SPEECH_UNAVAILABLE)
        observation.checkInterruption(given = true)
        assertEquals(
            List(4) { "warning ${GuidanceSafetyObservation.DISAGREEMENT}" },
            log,
        )

        log.clear()
        stall = StallCondition.NO_RESULTS
        observation.observe { poll(4_000) }
        observation.checkGiven(SystemAlert.STALLED)
        observation.checkInterruption(given = false)
        assertEquals(emptyList<String>(), log)

        observation.observe { onLifecycleStop(5_000) }
        observation.checkInterruption(given = true)
        observation.checkInterruption(given = false)
        assertEquals(listOf("warning ${GuidanceSafetyObservation.DISAGREEMENT}"), log)
        assertEquals("INTERRUPTED", logData.last()!!["alert"])
        assertEquals(false, logData.last()!!["given"])
    }

    @Test
    fun `system alerts are traced as the shell's, with their kind, key, channel, result and priority`() {
        now = 7_000
        observation.rhythmPlayed(SystemAlert.STALLED, GuidanceHaptic.INTERRUPTED)
        observation.noticeRequested(SystemAlert.STALLED)
        observation.noticePublished(SystemAlert.INTERRUPTED)
        observation.rhythmPlayed(SystemAlert.SPEECH_UNAVAILABLE, GuidanceHaptic.SPEECH_UNAVAILABLE)
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
    fun `a fault in observing is logged and goes no further, and the monitor keeps its state`() {
        failRecording = true
        observation.observe { onStartRequested(0) }
        observation.noticeRequested(SystemAlert.SPEECH_UNAVAILABLE)

        assertEquals(listOf("error Safety observation failed", "error Safety observation failed"), log)
        assertEquals(GuidanceSafetyState.Initializing, observation.monitor.state)
        assertEquals(emptyList<GuidanceAlertTrace>(), alerts)
    }

    @Test
    fun `with diagnostics on, the session's main-thread time is logged at its end, then started afresh`() {
        diagnostics = true
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
        running()
        observation.endOfSession()
        assertEquals(emptyList<String>(), log)
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
