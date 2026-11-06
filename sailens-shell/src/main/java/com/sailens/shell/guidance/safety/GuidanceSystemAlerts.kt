package com.sailens.shell.guidance.safety

import com.sailens.core.log.LogService
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.output.SpeechEngineState
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.GuidanceNotice
import com.sailens.shell.guidance.screen.GuidanceStallDetector

/**
 * The four alerts about Guidance itself — the session failed, it stalled, the app was sent to the background, own
 * speech is gone — together with what the guidance screen does around each of them.
 *
 * The view model hands every event it handles to the entry of the same name and does nothing more for it here. Each
 * entry feeds the safety monitor (through [GuidanceSafetyObservation], which it builds) once, at the point the event is
 * handled, and gives the alert the monitor returns for that input, in the order the outputs must see it: the state
 * decides when an alert is due, this class how it is given. Holds the stall detector, whose condition the state reads
 * and whose repeat paces the stall alarm.
 *
 * Takes plain functions, as [com.sailens.shell.guidance.screen.GuidanceOutputWiring] does, so the order of every call
 * an alert makes can be checked on the JVM. The fact ports ([isRunning], [wasInterrupted], [outputSettings],
 * [speechReady]) read the view model's own state and the engine, never a copy kept here. Whether the session runs and
 * was interrupted are the monitor's to decide on; the screen's own two are only compared with it at the start of each
 * input. The one fact kept here is whether the view model's stall loop exists ([onStallWatchStarted] /
 * [onStallWatchStopped], told where the loop is launched and cancelled).
 *
 * Every time an entry needs is read from [clock] at the point it is used, a monitor input's inside its observation
 * call: a time read earlier can fall on the other side of a limit from the one the monitor would read.
 *
 * Main thread only, like the stall detector and the monitor.
 *
 * @param cancelStallLoop cancels the view model's 500 ms stall loop and drops it.
 * @param stopGuidanceOutput stops Guidance's outputs: the delivery protocol reset, then speech, then the motor.
 * @param clearLatestResult forgets the last result the screen holds.
 * @param preemptDescription cuts off a scene description that holds the floor, for the reason given.
 * @param playAlarm claims the motor unconditionally for the rhythm, whatever the vibration setting; true when the
 *   vibrator was asked to play it.
 * @param speakSystemNotice own speech, for a sentence about Guidance itself, at that priority (on
 *   [com.sailens.guidance.processor.decision.SpeechScale] or above it).
 * @param announceToScreenReader the shell's one route to the screen reader.
 * @param noticeText the words for a failed or stalled session.
 * @param markInterrupted sets the screen's interruption flag, which [wasInterrupted] reads.
 * @param clearInterrupted clears it.
 * @param markFailed shows the session as ended on an error, with its message.
 * @param isRunning whether the screen says the session runs; compared with the monitor, decided on by nothing.
 * @param wasInterrupted whether the screen says the session was interrupted; likewise.
 * @param outputSettings speech, screen reader and navigation vibration as the screen holds them now.
 * @param speechReady the speech engine's `isReady`, the reading delivery goes by. It can be true while the engine's
 *   state still says unavailable: a rebuilt engine takes speech before it reports ready.
 * @param initialOutput the output facts when the screen is built, for the monitor.
 * @param recordState the trace's `safety_state` record.
 * @param recordAlert the trace's `guidance_alert` record.
 * @param publishState the safety state for the UI, on each change of it.
 * @param timed whether main-thread time is kept (diagnostics on).
 * @param clock the same monotonic clock Guidance stamps events with.
 * @param detFirst the policy switches Guidance decides with: a stall notice's rank depends on them.
 * @param wrapMonitor what the inputs reach the monitor through: the monitor itself, but for a test that counts them.
 */
internal class GuidanceSystemAlerts(
    private val cancelStallLoop: () -> Unit,
    private val stopGuidanceOutput: () -> Unit,
    private val clearLatestResult: () -> Unit,
    private val preemptDescription: (reason: String) -> Unit,
    private val playAlarm: (GuidanceHaptic) -> Boolean,
    private val speakSystemNotice: (text: String, priority: Int) -> Unit,
    private val announceToScreenReader: (String) -> Unit,
    private val noticeText: (GuidanceNotice) -> String,
    private val markInterrupted: () -> Unit,
    private val clearInterrupted: () -> Unit,
    private val markFailed: (message: String) -> Unit,
    private val isRunning: () -> Boolean,
    private val wasInterrupted: () -> Boolean,
    private val outputSettings: () -> GuidanceOutputSettings,
    private val speechReady: () -> Boolean,
    initialOutput: OutputFacts,
    recordState: (SafetyStateTrace) -> Unit,
    recordAlert: (GuidanceAlertTrace) -> Unit,
    publishState: (GuidanceSafetyState) -> Unit,
    timed: () -> Boolean,
    private val logger: LogService,
    private val clock: () -> Long,
    private val detFirst: DetFirstConfig,
    wallClock: () -> Long = System::currentTimeMillis,
    nanoClock: () -> Long = System::nanoTime,
    wrapMonitor: (SafetyMonitor) -> SafetyMonitor = { it },
) {
    private val stallDetector = GuidanceStallDetector()

    /**
     * The view model's stall loop exists: set once it is launched, cleared once it is cancelled. Not whether the loop
     * is still active — a loop cancelled a moment ago and not yet dropped still counts, as its reference would.
     */
    private var stallWatchRunning = false

    private val observation = GuidanceSafetyObservation(
        stallCondition = stallDetector::condition,
        stallAlarmDue = stallDetector::alarmDue,
        nowMs = clock(),
        initialOutput = initialOutput,
        recordState = recordState,
        recordAlert = recordAlert,
        publishState = publishState,
        logger = logger,
        timed = timed,
        elapsedClock = clock,
        wallClock = wallClock,
        nanoClock = nanoClock,
        wrapMonitor = wrapMonitor,
    )

    /** Start pressed. Main-thread time is kept from here on, this call's own included. */
    fun onStartRequested() {
        observation.startOfSession()
        checkFacts(SafetyInput.START_REQUESTED)
        observation.observe { onStartRequested(clock()) }
    }

    /** The view model is launching its stall loop: a new watch, with the startup grace from now. */
    fun onStallWatchStarted() {
        checkFacts(SafetyInput.STALL_WATCH_STARTED)
        stallDetector.start(clock())
        observation.observe { onStallWatchStarted(clock()) }
        stallWatchRunning = true
    }

    /**
     * The view model cancelled its stall loop. The monitor hears nothing of it: the watch stops there with the input
     * that stopped the loop (the flow's failure, the user's stop). Not an input, so the screen's facts are not compared
     * here: when the screen goes away the loop is cancelled after the monitor heard of the stop, which the UI state
     * never hears of.
     */
    fun onStallWatchStopped() {
        stallWatchRunning = false
        stallDetector.stop()
    }

    /** The analysis flow is collected. */
    fun onFlowStarted() {
        checkFacts(SafetyInput.FLOW_STARTED)
        observation.observe { onFlowStarted(clock()) }
    }

    /**
     * A result reached the view model, [baseline] its qualification: the stall detector hears of it, then the monitor,
     * with `isReady` as it reads then.
     */
    fun onResult(baseline: BaselineQualification) {
        checkFacts(SafetyInput.RESULT)
        stallDetector.onResult(clock())
        observation.observe { onResult(clock(), baseline, speechReady = speechReady()) }
    }

    /**
     * The analysis flow threw. Guidance's outputs stop before the alert is given, so nothing left over from the
     * session can cut it; the screen shows the failure last.
     */
    fun onFlowFailed(error: Throwable) {
        checkFacts(SafetyInput.FLOW_FAILED)
        val alert = observation.observe { onFlowFailed(clock(), error) }
        logger.error(TAG, "Error in scene analysis", error)
        stopStallWatch()
        stopGuidanceOutput()
        clearLatestResult()
        // 卡片对盲人不存在：这条失效必须出声/出震，而不只是把状态改成错误。
        if (alert == SystemAlert.FAILED) alertGuidanceUnavailable(GuidanceNotice.FAILED)
        // Keep the raw cause in state + logs (logger.error above), but surface only a
        // friendly, localized message to the user via the status card
        // (error_analysis_start_failed). No raw-exception toast: it leaks internals and is
        // not useful to a screen-reader user; the persistent error card already communicates it.
        markFailed(error.message ?: "Unknown error")
        observation.endOfSession()
    }

    /** The user stopped Guidance, or the screen went away. The session ends with [endOfSession]. */
    fun onStopped() {
        checkFacts(SafetyInput.STOPPED)
        observation.observe { onStopped(clock()) }
    }

    /**
     * The stopped session is over: its main-thread time is logged. After a user's stop the view model calls this once
     * the stop is in its UI state, as [onFlowFailed] ends its session once the failure is; on the screen going away,
     * right after [onStopped], with the UI state left as it was. Not an input, so the screen's facts are not compared.
     */
    fun endOfSession() {
        observation.endOfSession()
    }

    /**
     * 分析在用户没有主动停止的情况下断了（息屏、来电、被系统回收）。
     *
     * 这是整条链路最危险的失效：用户以为辅助还在跑，实际上已经什么都不检测了，而"没有提示"
     * 恰好和"前方安全"是同一种体验。所以这里必须主动出声 + 震动，绝不能只写日志。
     *
     * 只有监视器说会话在运行时才告知（它返回 INTERRUPTED）。
     *
     * @param noticeText the words for it, resolved by the screen.
     */
    fun onLifecycleStop(noticeText: String) {
        checkFacts(SafetyInput.LIFECYCLE_STOP)
        val alert = observation.observe { onLifecycleStop(clock()) }
        if (alert != SystemAlert.INTERRUPTED) return

        logger.warning(TAG, "Guidance interrupted while running")
        markInterrupted()

        // 中断告知是这条链路上最该听见的一句，不能被半句场景描述盖住或接在后面。
        preemptDescription("guidance interrupted")

        // 震动先行：它不依赖 TTS 引擎，是最后一道能穿透的通知手段。
        val vibrated = playAlarm(GuidanceHaptic.INTERRUPTED)
        observation.rhythmGiven(SystemAlert.INTERRUPTED, GuidanceHaptic.INTERRUPTED, vibrated)

        val settings = outputSettings()
        if (!settings.speechEnabled) return
        if (settings.screenReaderActive) {
            announceToScreenReader(noticeText)
            observation.noticePublished(SystemAlert.INTERRUPTED)
        } else {
            // 必须说完：一条普通导航提示不能把"辅助已中断"冲掉。
            speakSystemNotice(noticeText, GuidanceNotice.INTERRUPTED_PRIORITY)
            observation.noticeRequested(SystemAlert.INTERRUPTED)
        }
    }

    /** Back in the foreground. */
    fun onLifecycleStart() {
        checkFacts(SafetyInput.LIFECYCLE_START)
        // 回到前台时相机要重新绑定，第一帧之前的空档不是卡死：给它重新计启动宽限。
        // Running is the monitor's to say; whether the stall loop exists is this class's, not the monitor's watch, which
        // never hears of the loop's cancelling.
        if (observation.monitor.axes.phase.isRunning && stallWatchRunning) stallDetector.start(clock())
        // After the detector's restart, so the monitor reads the new grace; before the UI state clears the interruption.
        observation.observe { onLifecycleStart(clock()) }
        clearInterrupted()
    }

    /**
     * The output as the screen now holds it: the engine's state, a setting or the screen reader changed. The monitor
     * hears it with `isReady` as it reads then, and says when own speech being gone is due to be told: once per
     * outage, as soon as the user relies on own speech.
     */
    fun onOutput(engineState: SpeechEngineState, speechEnabled: Boolean, screenReaderActive: Boolean, hapticsEnabled: Boolean) {
        checkFacts(SafetyInput.OUTPUT)
        val alert = observation.observe {
            onOutput(clock(), OutputFacts(engineState, speechEnabled, screenReaderActive, hapticsEnabled, speechReady = speechReady()))
        }
        if (alert == SystemAlert.SPEECH_UNAVAILABLE) speechEngineUnavailable()
    }

    /**
     * The stall loop's poll. One input, one time: the monitor reads the stall condition for the state and, unless the
     * session is interrupted, asks the detector whether an alarm is due, both at the same moment.
     */
    fun poll() {
        checkFacts(SafetyInput.POLL)
        // 进后台时 onLifecycleStop 已经告知过；那时相机停帧是必然的，不重复报：中断期间监视器不问检测器。
        val alert = observation.observe { poll(clock()) }
        if (alert == SystemAlert.STALLED) alertGuidanceUnavailable(GuidanceNotice.STALLED)
    }

    /** The screen's own facts against the monitor's, before the input [next] reaches it. */
    private fun checkFacts(next: SafetyInput) {
        observation.checkFacts(next, running = isRunning(), interrupted = wasInterrupted())
    }

    private fun stopStallWatch() {
        cancelStallLoop()
        onStallWatchStopped()
    }

    /**
     * Guidance 在运行中失效：分析出错而停止，或长时间没有产出结果。
     *
     * 与 [onLifecycleStop] 同一个道理：卡片对盲人不存在，"收不到提示"与"前方安全"是同一种
     * 体验。震动不看 hapticsEnabled 开关，理由同 [speechEngineUnavailable]——这是"应用无法工作"
     * 的告知，不是导航提示。
     */
    private fun alertGuidanceUnavailable(notice: GuidanceNotice) {
        logger.warning(TAG, "Guidance unavailable while running", mapOf("notice" to notice.name))
        preemptDescription("guidance ${notice.name.lowercase()}")
        val vibrated = playAlarm(GuidanceHaptic.INTERRUPTED)
        observation.rhythmGiven(notice.systemAlert, GuidanceHaptic.INTERRUPTED, vibrated)

        val settings = outputSettings()
        if (!settings.speechEnabled) return
        val text = noticeText(notice)
        if (settings.screenReaderActive) {
            announceToScreenReader(text)
            observation.noticePublished(notice.systemAlert)
        } else {
            speakSystemNotice(text, notice.speechPriority(detFirst))
            observation.noticeRequested(notice.systemAlert)
        }
    }

    /**
     * 语音引擎彻底失效（多次重试后放弃）。
     *
     * 这条失效必须走非视觉通道，否则等于没告知：TTS 已经死了、说不出话；视觉卡片对盲人不存在；
     * 而"收不到任何提示"和"前方没有危险"在用户体验上是同一件事。
     *
     * 震动**不看 hapticsEnabled 开关**。理由：用户当初选的输出通道是语音，现在那条通道坏了，
     * 这一下震动不是导航提示，而是"应用无法工作"的一次性告知——它是仅剩的可达通道。
     * 只震一次，不会变成持续打扰。
     */
    private fun speechEngineUnavailable() {
        val settings = outputSettings()
        if (!settings.speechEnabled) return
        // 读屏在工作时我们根本不会初始化自带引擎，所以这个状态到不了这里；留一道判断只是
        // 为了让"读屏用户不会收到与他无关的告警"这件事显式成立。
        if (settings.screenReaderActive) return

        logger.error(TAG, "Speech engine unavailable; alerting through haptics")
        val vibrated = playAlarm(GuidanceHaptic.SPEECH_UNAVAILABLE)
        observation.rhythmGiven(SystemAlert.SPEECH_UNAVAILABLE, GuidanceHaptic.SPEECH_UNAVAILABLE, vibrated)
    }

    private val GuidanceNotice.systemAlert: SystemAlert
        get() = when (this) {
            GuidanceNotice.FAILED -> SystemAlert.FAILED
            GuidanceNotice.STALLED -> SystemAlert.STALLED
        }

    private companion object {
        /** The guidance screen's log tag, the one its view model logs under: these lines are the screen's. */
        const val TAG = "SceneAnalysisViewModel"
    }
}
