package com.sailens.shell.sim

import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.sim.Scenario
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings

/** One thing the user, the phone or the test does to a session, at [atMs] after start was first pressed. */
internal sealed interface ScriptAction {
    val atMs: Long

    /** What the `script` line says. */
    val label: String

    /** Start pressed (the toggle, while not running). */
    data class Start(override val atMs: Long) : ScriptAction {
        override val label get() = "start"
    }

    /** Stop pressed (the toggle, while running). */
    data class Stop(override val atMs: Long) : ScriptAction {
        override val label get() = "stop"
    }

    /** The app goes to the background: the screen's `ON_STOP`, and the camera unbinds. */
    data class Background(override val atMs: Long) : ScriptAction {
        override val label get() = "background"
    }

    /** Back in the foreground: the screen's `ON_START`. The camera comes back with [CameraResume]. */
    data class Foreground(override val atMs: Long) : ScriptAction {
        override val label get() = "foreground"
    }

    data class CameraResume(override val atMs: Long) : ScriptAction {
        override val label get() = "camera_resume"
    }

    /** The model held at [gate] finishes initialising. */
    data class ReleaseInit(override val atMs: Long, val gate: String = SEM) : ScriptAction {
        override val label get() = "release_init $gate"
    }

    /** The model held at [gate] throws while initialising. */
    data class FailInit(override val atMs: Long, val message: String, val gate: String = SEM) : ScriptAction {
        override val label get() = "fail_init $gate \"$message\""
    }

    /** The speech engine changes: its state (which the screen collects), its readiness (which it does not), or both. */
    data class Engine(override val atMs: Long, val state: SpeechEngineState? = null, val ready: Boolean? = null) : ScriptAction {
        override val label get() = "engine" + (state?.let { " ${it.name.lowercase()}" } ?: "") + (ready?.let { " ready=$it" } ?: "")
    }

    data class SpeechSetting(override val atMs: Long, val on: Boolean) : ScriptAction {
        override val label get() = "speech ${onOff(on)}"
    }

    data class ScreenReader(override val atMs: Long, val on: Boolean) : ScriptAction {
        override val label get() = "screen_reader ${onOff(on)}"
    }

    data class HapticsSetting(override val atMs: Long, val on: Boolean) : ScriptAction {
        override val label get() = "haptics ${onOff(on)}"
    }

    data class FaultHapticsSetting(override val atMs: Long, val on: Boolean) : ScriptAction {
        override val label get() = "fault_haptics ${onOff(on)}"
    }

    /** A scene description begins holding the floor for [durationMs]. */
    data class Describe(override val atMs: Long, val durationMs: Long) : ScriptAction {
        override val label get() = "describe $durationMs"
    }

    /** The camera's frame flow completes normally. */
    data class EndFrames(override val atMs: Long) : ScriptAction {
        override val label get() = "end_frames"
    }

    /** The guidance screen goes away: its view model is cleared. */
    data class LeaveScreen(override val atMs: Long) : ScriptAction {
        override val label get() = "leave_screen"
    }

    companion object {
        const val SEM = "sem"
        const val DET = "det"
        private fun onOff(on: Boolean) = if (on) "on" else "off"
    }
}

/**
 * A simulated Guidance session: the frames of [scenario] (its world, its step between frames), when the camera
 * delivers them, the script, and the settings it starts with.
 *
 * The default session (`docs/geometry.md` §7): the screen is built and start pressed at 0, the segmentation model
 * finishes initialising at 1000, the first frame comes at 1050 and one every [Scenario.stepMs] after it. Frames
 * fall on x50 milliseconds and the stall loop's polls on multiples of 500, so the two never meet with the default
 * step.
 *
 * @param name the timeline's name, `<name>.timeline.txt`.
 * @param gates the models whose initialisation the script holds ([ScriptAction.ReleaseInit], [ScriptAction.FailInit]),
 *   each with whether its wait gives way to a stop: the segmentation model's, by default.
 * @param predict whether the frame at an index gives a result once sent: unless a model fails on it, by default.
 * @param otherScreenHoldsEngine whether another screen holds a lease on the shared engines as well.
 */
internal data class SessionScript(
    val name: String,
    val description: String,
    val scenario: Scenario,
    val actions: List<ScriptAction>,
    val endAtMs: Long,
    val firstFrameAtMs: Long = DEFAULT_FIRST_FRAME_MS,
    val settings: GuidanceFeedbackSettings = GuidanceFeedbackSettings(),
    val screenReader: Boolean = false,
    val detFirst: DetFirstConfig = DetFirstConfig(),
    val gates: Map<String, Boolean> = mapOf(ScriptAction.SEM to true),
    val predict: ((index: Int) -> Boolean)? = null,
    val otherScreenHoldsEngine: Boolean = false,
) {
    /** When frame [index] comes from the camera. */
    fun frameAtMs(index: Int): Long = firstFrameAtMs + index * scenario.stepMs

    companion object {
        const val DEFAULT_FIRST_FRAME_MS = 1_050L
        const val DEFAULT_INIT_RELEASE_MS = 1_000L

        /** The default session's start and initialisation, followed by [rest]. */
        fun defaultActions(vararg rest: ScriptAction): List<ScriptAction> =
            listOf(ScriptAction.Start(0), ScriptAction.ReleaseInit(DEFAULT_INIT_RELEASE_MS)) + rest
    }
}
