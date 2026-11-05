package com.sailens.shell.guidance.safety

import com.sailens.core.log.LogService
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.shell.device.GuidanceHaptic

/**
 * The screen's side of the safety monitor while the state is only observed, built and fed by [GuidanceSystemAlerts]:
 * every change goes to the trace and the UI state, at each alert today's code gives the state is checked against it,
 * and the alert itself is traced.
 *
 * Nothing here decides anything. A disagreement is a warning in the log, never a change to an alert; a fault in an
 * observation call is an error in the log, never an exception into the caller.
 *
 * While [timed] (diagnostics on), the main-thread time of every call through here from [startOfSession] to
 * [endOfSession] is kept, and [endOfSession] logs its count, p50, p95 and maximum. A call outside a session (the speech
 * engine coming up as the app starts, say) belongs to none and is not timed.
 *
 * @param recordState the trace's `safety_state` record.
 * @param recordAlert the trace's `guidance_alert` record.
 * @param publishState the state for the UI, on each change of it.
 * @param elapsedClock the view model's clock, for when an alert was given.
 */
internal class GuidanceSafetyObservation(
    stallCondition: (nowMs: Long) -> StallCondition,
    nowMs: Long,
    initialOutput: OutputFacts,
    private val recordState: (SafetyStateTrace) -> Unit,
    private val recordAlert: (GuidanceAlertTrace) -> Unit,
    private val publishState: (GuidanceSafetyState) -> Unit,
    private val logger: LogService,
    private val timed: () -> Boolean,
    private val elapsedClock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val cost = ObservationCost()

    /** Between [startOfSession] and [endOfSession]: only then is a call timed. */
    private var inSession = false

    val monitor = GuidanceSafetyMonitor(stallCondition, nowMs, initialOutput, onChange = ::changed)

    private var published: GuidanceSafetyState = monitor.state

    init {
        publishState(published)
    }

    /** One input to the monitor. */
    fun observe(input: GuidanceSafetyMonitor.() -> Unit) = timedCall { monitor.input() }

    /** Today's code is giving [alert] now: the state must stand behind it. */
    fun checkGiven(alert: SystemAlert) = timedCall {
        if (!monitor.supports(alert)) disagree(alert, given = true)
    }

    /** At the app going to the background, today's code gives the interruption alert exactly when [given]. */
    fun checkInterruption(given: Boolean) = timedCall {
        if (monitor.supports(SystemAlert.INTERRUPTED) != given) disagree(SystemAlert.INTERRUPTED, given)
    }

    /**
     * [alert]'s rhythm was given. The motor is claimed unconditionally, so it starts at once when [vibrated], the
     * vibrator having been asked to play it; on a phone without a vibrator nothing took it.
     */
    fun rhythmGiven(alert: SystemAlert, haptic: GuidanceHaptic, vibrated: Boolean) = timedCall {
        val result = if (vibrated) GuidanceAlertResults.PLAYED else GuidanceAlertResults.REFUSED
        recordAlert(alertTrace(alert, haptic = haptic.name.lowercase(), channel = GuidanceAlertChannels.HAPTICS, result = result))
    }

    /** [alert]'s sentence went to the screen reader, which reports no completion. */
    fun noticePublished(alert: SystemAlert) = timedCall {
        recordAlert(alertTrace(alert, messageKey = alert.sentenceKey(), channel = GuidanceAlertChannels.SCREEN_READER, result = GuidanceAlertResults.PUBLISHED))
    }

    /** [alert]'s sentence was handed to own speech, which reports nothing back for a system notice. */
    fun noticeRequested(alert: SystemAlert) = timedCall {
        recordAlert(alertTrace(alert, messageKey = alert.sentenceKey(), channel = GuidanceAlertChannels.SPEECH, result = GuidanceAlertResults.REQUESTED))
    }

    private fun SystemAlert.sentenceKey(): String = checkNotNull(noticeKey) { "$this has no sentence" }

    /**
     * The user asked to start: main-thread time is kept from the next call on, the start request's own included. Asked
     * again before the session ended (the first request still waiting to start), it is the same session, and what was
     * kept stays.
     */
    fun startOfSession() {
        if (inSession) return
        cost.clear()
        inSession = true
    }

    /** A session ended: logs the main-thread time kept since it started, if any was, and keeps none until the next. */
    fun endOfSession() {
        inSession = false
        val summary = cost.summary()
        cost.clear()
        if (summary != null) logger.info(TAG, "Safety observation main-thread time", summary)
    }

    /**
     * The trace and the UI each on their own: a fault in one must not keep the other on an old state, and the monitor
     * tells of a change only once. A state the UI did not take is offered again at the next change.
     */
    private fun changed(snapshot: GuidanceSafetySnapshot) {
        contained { recordState(snapshot.toTrace(at = wallClock())) }
        if (snapshot.state != published) {
            contained {
                publishState(snapshot.state)
                published = snapshot.state
            }
        }
    }

    private fun alertTrace(alert: SystemAlert, messageKey: String? = null, haptic: String? = null, channel: String, result: String) =
        GuidanceAlertTrace(
            at = wallClock(),
            elapsedMs = elapsedClock(),
            source = GuidanceAlertSources.SYSTEM,
            kind = alert.traceKind,
            messageKey = messageKey,
            haptic = haptic,
            priority = alert.tracePriority,
            channel = channel,
            result = result,
        )

    private fun disagree(alert: SystemAlert, given: Boolean) {
        logger.warning(
            TAG,
            DISAGREEMENT,
            mapOf("alert" to alert.name, "given" to given, "state" to monitor.state.label, "phase" to monitor.axes.phase.name),
        )
    }

    /**
     * Runs one call, timed while a session runs and diagnostics are on. A fault in it is logged and goes no further:
     * observing must not change what the screen does next, and an exception would.
     */
    private inline fun timedCall(block: () -> Unit) {
        val started = if (inSession && timed()) nanoClock() else null
        try {
            contained(block)
        } finally {
            if (started != null) cost.add(nanoClock() - started)
        }
    }

    private inline fun contained(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            logger.error(TAG, "Safety observation failed", error)
        }
    }

    companion object {
        private const val TAG = "GuidanceSafety"

        /** The warning a disagreement writes; a smoke test counts these. */
        const val DISAGREEMENT = "Safety state disagrees with an alert"
    }
}

/**
 * Durations in a fixed histogram of one-microsecond buckets, so a long session costs no more memory than a short one.
 * Percentiles are given to the microsecond above (never under the true value); the maximum is exact.
 */
internal class ObservationCost(private val buckets: Int = 10_000) {
    private val counts = IntArray(buckets + 1)
    private var calls = 0L
    private var maxNs = 0L

    fun add(ns: Long) {
        val micros = (ns.coerceAtLeast(0L) / 1_000L).coerceAtMost(buckets.toLong()).toInt()
        counts[micros]++
        calls++
        if (ns > maxNs) maxNs = ns
    }

    /** Milliseconds below which a share [p] of the calls took, to the microsecond above; null with no calls. */
    fun percentileMs(p: Double): Double? {
        if (calls == 0L) return null
        val rank = kotlin.math.ceil(p * calls).toLong().coerceIn(1L, calls)
        var seen = 0L
        for (micros in counts.indices) {
            seen += counts[micros]
            if (seen >= rank) return if (micros == buckets) maxNs / 1_000_000.0 else (micros + 1) / 1_000.0
        }
        return maxNs / 1_000_000.0
    }

    fun summary(): Map<String, Any>? {
        if (calls == 0L) return null
        return mapOf(
            "calls" to calls,
            "p50Ms" to checkNotNull(percentileMs(0.50)),
            "p95Ms" to checkNotNull(percentileMs(0.95)),
            "maxMs" to maxNs / 1_000_000.0,
        )
    }

    fun clear() {
        counts.fill(0)
        calls = 0L
        maxNs = 0L
    }
}
