package com.sailens.shell.sim

import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.output.Announcement
import com.sailens.output.SpeechEngineState
import com.sailens.output.UtteranceOutcome
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.NavigationClaim
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * What the view model's mirror and what it builds call on own speech: `SpeechManager`'s calls, each with the
 * signature the view model and `GuidanceOutputWiring` use.
 */
internal interface SpeechDevice {
    val state: StateFlow<SpeechEngineState>
    val isReady: Boolean
    fun initialize()
    fun setSpeechRate(rate: Float)
    fun speak(announcement: Announcement, onSpoken: (() -> Unit)?, onOutcome: ((UtteranceOutcome) -> Unit)?, atMs: Long?): Boolean
    fun settleFrame(): Long
    fun speakSystemNotice(text: String, priority: Int?)
    fun stop()
    fun release()
}

/** `HapticManager`'s seven calls. */
internal interface HapticDevice {
    fun trigger(event: SceneEvent): Boolean
    fun triggerOrQueue(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit): NavigationClaim
    fun playOrReserve(haptic: GuidanceHaptic, speechPriority: Int, amplitude: Int, onPlayed: () -> Unit)
    fun cancelReservation()
    fun play(haptic: GuidanceHaptic): Boolean
    fun cancelNavigation()
    fun cancel()
}

/** What Guidance asks of `SceneDescriptionCoordinator`. */
internal interface DescriptionDevice {
    val holdsTheFloor: Boolean
    fun preemptForGuidance(reason: String)
}

/** The five cooldown use cases the view model hands receipts to. */
internal interface CooldownReceipts {
    fun confirm(event: SceneEvent)
    fun revoke(event: SceneEvent, reason: String)
    fun reportCut(event: SceneEvent)
    fun reportHeardAfterCut(event: SceneEvent)
    fun accepted(event: SceneEvent): Boolean
}

/** `TraceService`'s three records the view model writes, and the system alerts' four ports onto the screen's state. */
internal interface SessionRecord {
    fun recordSafetyState(trace: SafetyStateTrace)
    fun recordGuidanceAlert(trace: GuidanceAlertTrace)
    fun recordPromptOutcome(trace: PromptOutcomeTrace)
    fun ui(what: String, detail: String? = null)
}

/** The entries of `GuidanceSystemAlerts` the view model calls. */
internal interface MirrorAlerts {
    fun onStartRequested()
    fun onStallWatchStarted()
    fun onStallWatchStopped()
    fun onFlowStarted()
    fun onResult(baseline: BaselineQualification)
    fun onFlowFailed(error: Throwable)
    fun onStopped()
    fun endOfSession()
    fun onLifecycleStop(noticeText: String)
    fun onLifecycleStart()
    fun onOutput(engineState: SpeechEngineState, speechEnabled: Boolean, screenReaderActive: Boolean, hapticsEnabled: Boolean)
    fun poll()
}

/** `GuidanceOutputWiring` as the view model uses it: its stop, and its protocol's three entries. */
internal interface MirrorOutput {
    fun stop(beforeOutputsStop: () -> Unit)
    fun onFrame(events: List<SceneEvent>, sequenceNumber: Long, visionFaults: VisionFaults)
    fun settingsChanged()
    fun reset()
}

/**
 * How the mirror reaches the system alerts and the output: the real classes it builds, as the view model builds
 * them, for a session; fakes that record each call, for the mirror's own order test.
 */
internal interface MirrorSeams {
    fun alerts(build: () -> com.sailens.shell.guidance.safety.GuidanceSystemAlerts): MirrorAlerts
    fun output(build: () -> com.sailens.shell.guidance.screen.GuidanceOutputWiring): MirrorOutput
}

/** The test's view of the mirror's runs: none of it is a call the view model makes. */
internal interface MirrorObserver {
    /** Start was pressed and a run is asked for: its number. */
    fun runRequested(): Int

    /** The run's block began, in [job], the job the run sequencer launched. */
    fun blockStarted(run: Int, job: Job)

    /** A result of [run] reached the collector, before anything is done with it. */
    fun resultArrived(run: Int, result: SceneResult)

    /** The collector is done with the result of frame [sequenceNumber]. */
    fun resultHandled(run: Int, sequenceNumber: Long)

    /** A stop asked the run sequencer for a reset, which completes [resetJob]. */
    fun stopIssued(resetJob: Job)
}
