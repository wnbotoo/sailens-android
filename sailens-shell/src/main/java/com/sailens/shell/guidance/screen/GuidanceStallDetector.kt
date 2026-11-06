package com.sailens.shell.guidance.screen

import com.sailens.shell.guidance.safety.StallCondition

/**
 * Notices when a running Guidance session has stopped producing results.
 *
 * A frame the pipeline fails on is dropped, and a camera that stops delivering frames produces
 * nothing at all. Either way the session still looks like it is running -- and for a blind user,
 * "no prompt" is indistinguishable from "nothing ahead". So a session that has gone quiet for
 * longer than any real frame takes must be reported on a channel the user can perceive.
 *
 * Pure and clock-driven so the rule is testable on the JVM. Not thread-safe: it is called from the
 * main thread only, by [com.sailens.shell.guidance.safety.GuidanceSystemAlerts] and the safety
 * monitor it feeds, which reads [condition] for the state and asks [alarmDue] for the alarm.
 *
 * @param startupTimeoutMs how long the first result may take. Loading and compiling the models
 *   happens before it, and takes seconds on a cold GPU delegate.
 * @param stallAfterMs how long a session that has produced results may then go without one.
 * @param repeatEveryMs how often to repeat the alarm while the stall lasts. Once is easy to miss
 *   in traffic; continuously would drown everything else out.
 */
internal class GuidanceStallDetector(
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val stallAfterMs: Long = DEFAULT_STALL_AFTER_MS,
    private val repeatEveryMs: Long = DEFAULT_REPEAT_EVERY_MS,
) {
    private var watching = false
    private var hasResult = false
    private var lastProgressAtMs = 0L
    private var lastAlarmAtMs: Long? = null

    fun start(nowMs: Long) {
        watching = true
        hasResult = false
        lastProgressAtMs = nowMs
        lastAlarmAtMs = null
    }

    fun stop() {
        watching = false
        lastAlarmAtMs = null
    }

    fun onResult(nowMs: Long) {
        hasResult = true
        lastProgressAtMs = nowMs
        lastAlarmAtMs = null
    }

    /**
     * Whether the session is stalled at [nowMs]: the safety state's stall condition, and the test [alarmDue] starts
     * with. It reads only; the alarm's repeat is left to [alarmDue].
     */
    fun condition(nowMs: Long): StallCondition {
        if (!watching) return StallCondition.NONE
        val limit = if (hasResult) stallAfterMs else startupTimeoutMs
        if (nowMs - lastProgressAtMs < limit) return StallCondition.NONE
        return if (hasResult) StallCondition.NO_RESULTS else StallCondition.STARTUP_TIMEOUT
    }

    /**
     * True when the session is stalled at [nowMs] ([condition]) and an alarm is due again ([repeatDue]). Records the
     * alarm when it is.
     */
    fun alarmDue(nowMs: Long): Boolean = condition(nowMs) != StallCondition.NONE && repeatDue(nowMs)

    /**
     * The repeat, for a stall that holds at [nowMs]: the first alarm of a stall at once, then one every
     * [repeatEveryMs]. Records the alarm when one is due.
     */
    private fun repeatDue(nowMs: Long): Boolean {
        val lastAlarm = lastAlarmAtMs
        if (lastAlarm != null && nowMs - lastAlarm < repeatEveryMs) return false
        lastAlarmAtMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MS = 20_000L
        const val DEFAULT_STALL_AFTER_MS = 2_500L
        const val DEFAULT_REPEAT_EVERY_MS = 10_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
