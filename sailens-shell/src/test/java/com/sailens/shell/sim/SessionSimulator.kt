package com.sailens.shell.sim

import com.sailens.core.log.LogService
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.sim.FrameDelivery
import com.sailens.guidance.sim.SimulatedDepthModel
import com.sailens.guidance.sim.SimulatedPipeline
import com.sailens.guidance.usecase.decision.AcceptedPromptUseCase
import com.sailens.guidance.usecase.decision.ConfirmDeliveredEventUseCase
import com.sailens.guidance.usecase.decision.ReportCutPromptUseCase
import com.sailens.guidance.usecase.decision.ReportHeardAfterCutUseCase
import com.sailens.guidance.usecase.decision.RevokeUndeliveredEventUseCase
import com.sailens.output.SpeechEngineState
import com.sailens.shell.device.SharedEngineOwner
import com.sailens.shell.guidance.safety.GuidanceSafetyObservation
import com.sailens.shell.guidance.safety.GuidanceSystemAlerts
import com.sailens.shell.guidance.screen.GuidanceOutputWiring
import com.sailens.shell.guidance.screen.SceneAnalysisUiState
import com.sailens.shell.guidance.screen.StatusCardContent
import com.sailens.shell.guidance.screen.statusCardContent
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update

/** One warning or error the session's shell side logged. */
internal data class LoggedLine(val level: String, val tag: String, val message: String)

/** What a simulated session produced. */
internal class SessionRun(
    val script: SessionScript,
    val entries: List<TimelineEntry>,
    val lines: List<String>,
    val logged: List<LoggedLine>,
    val directorLog: List<String>,
    /** The frames sent to a run, by index. */
    val delivered: List<Int>,
    /** The screen's state when the session ended. */
    val finalUi: SceneAnalysisUiState,
    /** The director's ledger when the session ended: each run's record. */
    val ledger: Map<Int, String>,
    /** The status card after each result was handled. */
    val cards: List<CardSample> = emptyList(),
    /** Depth runs that reached the simulated model, over every session of the script; 0 without a lane. */
    val depthInferences: Int = 0,
    /** The status card's content each time the session settled, from its start. */
    val screens: List<ScreenSample> = emptyList(),
) {
    /** Warnings that the screen's facts and the safety monitor disagreed, and observation faults: none is expected. */
    val observationFaults: List<LoggedLine>
        get() = logged.filter { it.message == GuidanceSafetyObservation.DISAGREEMENT || it.message == "Safety observation failed" }
}

/**
 * Runs a [SessionScript] (`docs/geometry.md` §7): the real Guidance pipeline ([SimulatedPipeline], as the app wires
 * it), the view model's mirror ([ViewModelMirror]) with the real system alerts, output wiring, run sequencer, UI
 * state, cooldown receipts and shared-engine owner it builds, and doubles only at the devices: own speech
 * ([SimSpeech]), the vibrator behind the motor mirror ([SimHaptics]), the description ([SimDescription]), the
 * screen reader ([SimScreenReader]), the words ([SimAnnouncements]). The [Director] drives time.
 *
 * The pipeline's clock is moved so that a frame's camera timestamp is the clock's reading when it arrives, as on a
 * phone (the scenario must align its frames to the clock for that).
 */
internal object SessionSimulator {

    /**
     * @param depth runs the depth lane beside the pipeline with this model (`SimulatedPipeline.depth`); null: no lane.
     */
    fun run(
        script: SessionScript,
        reverseCompletion: Boolean = false,
        handshakeTimeoutMs: Long = 30_000,
        depth: SimulatedDepthModel? = null,
    ): SessionRun {
        val frames = script.scenario.frames
        val director = Director(
            predict = script.predict ?: { index -> !frames[index].semFails && !frames[index].detFails },
            reverseCompletion = reverseCompletion,
            handshakeTimeoutMs = handshakeTimeoutMs,
        )
        val gates = script.gates.mapValues { (name, cancellable) -> director.gate(name, cancellable) }
        val pipeline = SimulatedPipeline(
            script.scenario,
            analysisConfig = AnalysisConfig(detFirst = script.detFirst),
            semanticInit = gates[ScriptAction.SEM]?.gate,
            detectionInit = gates[ScriptAction.DET]?.gate,
            depth = depth,
        )
        if (script.scenario.alignClockToFrames) pipeline.clock.advanceBy(script.scenario.stepMs - script.firstFrameAtMs)
        val clock = SessionClock(advancePipeline = pipeline.clock::advanceBy, pipelineNow = pipeline.clock)
        director.clock = clock
        val origin = clock.origin
        val now = { clock.nowMs }
        val timeline = Timeline(now, origin)
        val log = SessionLog()

        val speech = SimSpeech(
            clock = pipeline.clock,
            scheduleEnd = { atMs, end -> director.schedule(atMs - origin, EventKind.SPEECH_END, end) },
            record = { timeline.add { _ -> it } },
            now = now,
        )
        val haptics = SimHaptics(
            clock = pipeline.clock,
            schedule = { atMs, action -> director.schedule(atMs - origin, EventKind.MOTOR, action) },
            record = { timeline.add { _ -> it } },
            now = now,
        )
        val description = SimDescription(now = pipeline.clock, record = timeline::description)
        val screenReader = SimScreenReader(timeline::screenReader)
        // ShellModule.kt:84-95: the last lease closing stops what is said, then releases the engine. The description
        // model's release is not simulated.
        val sharedEngineOwner = SharedEngineOwner(
            releaseEngines = {
                speech.stop()
                speech.release()
            },
        )
        val otherScreen = if (script.otherScreenHoldsEngine) sharedEngineOwner.acquire() else null

        val cooldown = object : CooldownReceipts {
            private val confirmDelivered = ConfirmDeliveredEventUseCase(pipeline.cooldownManager)
            private val revokeUndelivered = RevokeUndeliveredEventUseCase(pipeline.cooldownManager)
            private val reportCutPrompt = ReportCutPromptUseCase(pipeline.cooldownManager)
            private val reportHeard = ReportHeardAfterCutUseCase(pipeline.cooldownManager)
            private val acceptedPrompt = AcceptedPromptUseCase(pipeline.cooldownManager)

            override fun confirm(event: SceneEvent) {
                timeline.receipt("confirm", event)
                confirmDelivered(event)
            }

            override fun revoke(event: SceneEvent, reason: String) {
                timeline.receipt("revoke", event, reason)
                revokeUndelivered(event, reason)
            }

            override fun reportCut(event: SceneEvent) {
                timeline.receipt("cut", event)
                reportCutPrompt(event)
            }

            override fun reportHeardAfterCut(event: SceneEvent) {
                timeline.receipt("heard_after_cut", event)
                reportHeard(event)
            }

            override fun accepted(event: SceneEvent): Boolean = acceptedPrompt(event).also { timeline.receipt("accepted", event, "-> $it") }
        }

        var lastTraced: SafetyStateTrace? = null
        val record = object : SessionRecord {
            override fun recordSafetyState(trace: SafetyStateTrace) {
                lastTraced = trace
                timeline.state(trace)
            }
            override fun recordGuidanceAlert(trace: GuidanceAlertTrace) = timeline.alert(trace)
            override fun recordPromptOutcome(trace: PromptOutcomeTrace) = timeline.outcome(trace)
            override fun ui(what: String, detail: String?) = timeline.ui(what, detail)
        }

        val cards = mutableListOf<CardSample>()
        var screen: () -> SceneAnalysisUiState = { error("no screen yet") }
        val observer = object : MirrorObserver by director.observer {
            override fun resultArrived(run: Int, result: SceneResult) {
                director.observer.resultArrived(run, result)
                timeline.frame(result.sequenceNumber, result.qualification.baseline, result.events, result.obstacles.map { it.category }, result.frameReceivedAtMs)
            }

            override fun resultHandled(run: Int, sequenceNumber: Long) {
                director.observer.resultHandled(run, sequenceNumber)
                val ui = screen()
                cards += CardSample(sequenceNumber, ui.activeStatusEvent?.id?.toString(), held = ui.promptHold != null)
            }
        }

        val settings = MutableStateFlow(script.settings)
        val screenReaderActive = MutableStateFlow(script.screenReader)
        val context = director.dispatcher + CoroutineExceptionHandler { _, error -> director.uncaught(error) }
        val mirror = ViewModelMirror(
            frames = director::frames,
            depthFrames = { minIntervalMs -> pipeline.depthFrames?.invoke(minIntervalMs) ?: emptyFlow() },
            analysis = { frameFlow, releaseFrame, semanticMaskSnapshot, depthFrames ->
                pipeline.start(frameFlow, releaseFrame, semanticMaskSnapshot, depthFrames)
            },
            resetProcessors = {
                director.log += "reset"
                pipeline.stop()
            },
            releaseModels = { pipeline.stop.release() },
            description = description,
            announceToScreenReader = screenReader::announce,
            sharedEngineOwner = sharedEngineOwner,
            haptics = haptics,
            speech = speech,
            logger = log,
            record = record,
            settings = settings,
            screenReaderActive = screenReaderActive,
            textResolver = SimAnnouncements::text,
            announcements = SimAnnouncements::announcementFor,
            cooldown = cooldown,
            noticeText = { notice -> "notice_guidance_${notice.name.lowercase()}" },
            detFirst = script.detFirst,
            clock = pipeline.clock,
            context = context,
            observer = observer,
            seams = RealSeams,
        )
        screen = { mirror.uiState.value }
        val screens = mutableListOf<ScreenSample>()
        director.settled = { screens += ScreenSample(now(), mirror.uiState.value.statusCardContent(), lastTraced) }

        for (action in script.actions) {
            director.schedule(action.atMs, EventKind.SCRIPT) {
                timeline.script(action.label)
                perform(action, mirror, director, gates, speech, settings, screenReaderActive, description, origin)
            }
        }
        val delivered = mutableListOf<Int>()
        frames.forEachIndexed { index, frame ->
            if (frame.delivery == FrameDelivery.Lost) return@forEachIndexed
            director.schedule(script.frameAtMs(index), EventKind.FRAME) {
                pipeline.stationary.value = frame.stationary
                pipeline.currentIndex = index
                if (director.deliver(index, pipeline.cameraFrame(index))) delivered += index
            }
        }

        try {
            director.runUntil(script.endAtMs)
        } finally {
            mirror.viewModelScope.cancel()
            mirror.releaseScope.cancel()
        }
        // The session is over: what the other screen does with the engine when the test lets go of it is not part of it.
        val entries = timeline.entries.toList()
        otherScreen?.close()
        val lines = TimelineWriter(origin).write(header(script), entries)
        return SessionRun(
            script, entries, lines, log.lines, director.log.toList(), delivered, mirror.uiState.value,
            director.ledger.mapValues { it.value.toString() }, cards.toList(),
            depthInferences = synchronized(pipeline.depthRunners) { pipeline.depthRunners.sumOf { it.inferences.get() } },
            screens = screens.toList(),
        )
    }

    private fun perform(
        action: ScriptAction,
        mirror: ViewModelMirror,
        director: Director,
        gates: Map<String, Director.Gate>,
        speech: SimSpeech,
        settings: MutableStateFlow<GuidanceFeedbackSettings>,
        screenReaderActive: MutableStateFlow<Boolean>,
        description: SimDescription,
        origin: Long,
    ) {
        when (action) {
            is ScriptAction.Start -> {
                check(!mirror.uiState.value.isRunning) { "start pressed while running at ${action.atMs}" }
                mirror.toggleAnalysis()
            }
            is ScriptAction.Stop -> {
                check(mirror.uiState.value.isRunning) { "stop pressed while not running at ${action.atMs}" }
                mirror.toggleAnalysis()
            }
            is ScriptAction.Background -> {
                // LiveAnalysisScreen: ON_STOP hands the screen's resolved sentence to the view model.
                mirror.onGuidanceInterrupted("notice_guidance_interrupted")
                director.cameraPaused = true
            }
            is ScriptAction.Foreground -> mirror.acknowledgeInterruption()
            is ScriptAction.CameraResume -> director.cameraPaused = false
            is ScriptAction.ReleaseInit -> director.release(gates.getValue(action.gate))
            is ScriptAction.FailInit -> director.fail(gates.getValue(action.gate), ScriptedInitFailure(action.message))
            is ScriptAction.Engine -> speech.engine(action.state, action.ready)
            is ScriptAction.SpeechSetting -> settings.update { it.copy(speechEnabled = action.on) }
            is ScriptAction.HapticsSetting -> settings.update { it.copy(hapticsEnabled = action.on) }
            is ScriptAction.FaultHapticsSetting -> settings.update { it.copy(faultHapticsEnabled = action.on) }
            is ScriptAction.ScreenReader -> screenReaderActive.value = action.on
            is ScriptAction.Describe -> {
                description.begin(action.durationMs)
                val until = origin + action.atMs + action.durationMs
                director.schedule(action.atMs + action.durationMs, EventKind.SCRIPT) { description.endIfHeld(until) }
            }
            is ScriptAction.EndFrames -> director.endFrameSource()
            is ScriptAction.LeaveScreen -> mirror.onCleared()
        }
    }

    private fun header(script: SessionScript): List<String> {
        val detFirst = when (script.detFirst) {
            DetFirstConfig() -> "off"
            DetFirstConfig.LAUNCH -> "launch"
            else -> error("only the default and the launch switches are named")
        }
        fun onOff(on: Boolean) = if (on) "on" else "off"
        val s = script.settings
        return listOf(
            "# ${script.name}: ${script.description}",
            "# config detfirst=$detFirst step=${script.scenario.stepMs} speech=${onOff(s.speechEnabled)} screenReader=${onOff(script.screenReader)} " +
                "haptics=${onOff(s.hapticsEnabled)} faultHaptics=${onOff(s.faultHapticsEnabled)}",
        )
    }

    /** Reaches the real alerts and wiring the mirror builds. */
    private object RealSeams : MirrorSeams {
        override fun alerts(build: () -> GuidanceSystemAlerts): MirrorAlerts {
            val alerts = build()
            return object : MirrorAlerts {
                override fun onStartRequested() = alerts.onStartRequested()
                override fun onStallWatchStarted() = alerts.onStallWatchStarted()
                override fun onStallWatchStopped() = alerts.onStallWatchStopped()
                override fun onFlowStarted() = alerts.onFlowStarted()
                override fun onResult(baseline: BaselineQualification, sequenceNumber: Long, receivedAtMs: Long?) =
                    alerts.onResult(baseline, sequenceNumber, receivedAtMs)
                override fun onFlowFailed(error: Throwable) = alerts.onFlowFailed(error)
                override fun onStopped() = alerts.onStopped()
                override fun endOfSession() = alerts.endOfSession()
                override fun onLifecycleStop(noticeText: String) = alerts.onLifecycleStop(noticeText)
                override fun onLifecycleStart() = alerts.onLifecycleStart()
                override fun onOutput(engineState: SpeechEngineState, speechEnabled: Boolean, screenReaderActive: Boolean, hapticsEnabled: Boolean) =
                    alerts.onOutput(engineState, speechEnabled, screenReaderActive, hapticsEnabled)
                override fun poll() = alerts.poll()
                override val holdsOrdinaryPrompts: Boolean get() = alerts.holdsOrdinaryPrompts
            }
        }

        override fun output(build: () -> GuidanceOutputWiring): MirrorOutput {
            val wiring = build()
            return object : MirrorOutput {
                override fun stop(beforeOutputsStop: () -> Unit) = wiring.stop(beforeOutputsStop)
                override fun onFrame(events: List<SceneEvent>, sequenceNumber: Long, visionFaults: VisionFaults, holdOrdinary: Boolean) =
                    wiring.protocol.onFrame(events, sequenceNumber, visionFaults, holdOrdinary)
                override fun holdBegan() = wiring.protocol.holdBegan()
                override fun settingsChanged() = wiring.protocol.settingsChanged()
                override fun reset() = wiring.protocol.reset()
            }
        }
    }
}

/** The status card after a result: the event it shows ([eventId], null for none) and whether prompts were held. */
internal data class CardSample(val seq: Long, val eventId: String?, val held: Boolean)

/**
 * What the status card says once the session settled at [atMs] ([Director.settled]), with the safety state the trace
 * last recorded then: the trace's own record, not the screen's copy of the state. Null before the first record.
 */
internal data class ScreenSample(val atMs: Long, val card: StatusCardContent, val traced: SafetyStateTrace?)

/** A model's initialisation failing, as a script made it. */
internal class ScriptedInitFailure(message: String) : RuntimeException(message)

/** The shell side's log: warnings and errors kept, in order. */
internal class SessionLog : LogService {
    val lines = mutableListOf<LoggedLine>()

    override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
    override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
    override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) {
        lines += LoggedLine("warning", tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        lines += LoggedLine("error", tag, message)
    }
}
