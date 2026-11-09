package com.sailens.shell.sim

import com.sailens.guidance.sim.FrameDelivery
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.Scenario
import com.sailens.guidance.sim.ScenarioFrame
import com.sailens.guidance.sim.Scenarios.HEIGHT
import com.sailens.guidance.sim.Scenarios.PITCH
import com.sailens.guidance.sim.Scenarios.personAt
import com.sailens.guidance.sim.Scenarios.street
import com.sailens.guidance.sim.World
import com.sailens.output.SpeechEngineState

/**
 * The session timelines (`docs/geometry.md` §7): each a whole Guidance session through the real pipeline, alerts
 * and delivery, with the default session's timing unless it says otherwise (start at 0, the segmentation model
 * ready at 1000, frames from 1050). Each timeline records today's behaviour, not an endorsement of it.
 */
internal object SessionScenarios {
    private val sidewalk = World(street())

    /** Frame [index] of a walk down the sidewalk at 1.2 m/s, 100 ms apart. */
    private fun walking(index: Int, world: World = sidewalk) = ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = index * 0.12f), world)

    private fun scenario(name: String, frames: List<ScenarioFrame>, stepMs: Long = 100) =
        Scenario(name, "", frames, stepMs = stepMs, alignClockToFrames = true)

    /** The index of the frame that comes at [atMs] in a default session, [stepMs] apart. */
    private fun indexAt(atMs: Long, stepMs: Long = 100) = ((atMs - SessionScript.DEFAULT_FIRST_FRAME_MS) / stepMs).toInt()

    val sessionClearWalk = SessionScript(
        name = "session_clear_walk",
        description = "Walking an empty sidewalk for 10 s, then stopping. The reference session: starting, the first " +
            "result, guiding, stopped; nothing to alert.",
        scenario = scenario("session_clear_walk", List(100) { walking(it) }),
        actions = SessionScript.defaultActions(ScriptAction.Stop(11_000)),
        endAtMs = 12_000,
    )

    /** The covered frames of [lensCoveredShort]: from 5050, for 10 s. */
    val LENS_COVERED = indexAt(5_050) until indexAt(15_050)

    /** The frames of [lensCoveredShort] with a person ahead: 3 s within the covered stretch. */
    val PERSON_UNDER_COVER = indexAt(8_050) until indexAt(11_050)

    val lensCoveredShort = SessionScript(
        name = "lens_covered_short",
        description = "Walking a sidewalk with the lens covered for 10 s from 5050. The sixth covered frame stops " +
            "guidance, said and felt at once and again each 8 s; the sixth clear frame judges again, and the lens is " +
            "said clear once it has stayed clear for 2 s. Under the cover a person walks 1.5 m ahead for 3 s: the models " +
            "still see the scene here, and the person is tracked, but no prompt but the covered lens's is given.",
        scenario = scenario(
            "lens_covered_short",
            List(190) { i ->
                val walked = i * 0.12
                val world = if (i in PERSON_UNDER_COVER) World(street(), listOf(personAt(0.0, walked + 1.5))) else sidewalk
                walking(i, world).copy(lensCovered = i in LENS_COVERED)
            },
        ),
        actions = SessionScript.defaultActions(ScriptAction.Stop(20_000)),
        endAtMs = 21_000,
    )

    val backgroundAndBack = SessionScript(
        name = "background_and_back",
        description = "Walking an empty sidewalk; at 8000 the app goes to the background and the camera stops, at " +
            "13000 it is back in the foreground, and the camera delivers again from 13300. The interruption is felt " +
            "and said whatever the vibration setting; the stall condition comes while in the background but is not " +
            "alarmed, and the foreground restarts the startup grace.",
        scenario = scenario("background_and_back", List(150) { walking(it) }),
        actions = SessionScript.defaultActions(
            ScriptAction.Background(8_000),
            ScriptAction.Foreground(13_000),
            ScriptAction.CameraResume(13_300),
            ScriptAction.Stop(16_000),
        ),
        endAtMs = 17_000,
    )

    private val personAhead = World(street(), listOf(personAt(0.0, 1.5)))
    private val personLeft = World(street(), listOf(personAt(-0.8, 2.0)))
    private val personRight = World(street(), listOf(personAt(0.8, 2.0)))

    /**
     * When each person of [speechOutage] stands in front of the user, for 2 s. Ahead and to a side take turns: the
     * prompts ahead share one cooldown and those to either side another (8 s at most for a user standing still), so
     * each person comes after the cooldown of the one before on that side and is offered.
     */
    private val OUTAGE_PEOPLE = listOf(
        2_050L to personAhead,
        7_050L to personLeft,
        12_550L to personAhead,
        22_050L to personAhead,
        27_050L to personRight,
        32_050L to personAhead,
        37_050L to personLeft,
        43_050L to personAhead,
        47_050L to personRight,
    )

    private fun outageFrame(index: Int): ScenarioFrame {
        val atMs = SessionScript.DEFAULT_FIRST_FRAME_MS + index * 100L
        val world = OUTAGE_PEOPLE.firstOrNull { (from, _) -> atMs >= from && atMs < from + 2_000 }?.second ?: sidewalk
        return ScenarioFrame(Pose(HEIGHT, PITCH), world, stationary = true)
    }

    val speechOutage = SessionScript(
        name = "speech_outage",
        description = "Standing on a sidewalk while people step in front of the user now and then, own speech fails " +
            "three times. At 6000 the engine says it is unavailable and takes nothing: alerted once, by vibration, " +
            "and prompts go to vibration. At 12000 it is rebuilt and takes speech again while its state still says " +
            "unavailable, which the screen does not hear; the first sentence it takes makes it ready. A stop and a " +
            "start; the second outage, alerted once, with a stop and a start inside it, not alerted again; it " +
            "recovers; the screen reader turned on and off inside it does not alert it again either. Under the screen " +
            "reader a third outage begins, which is alerted only once own speech is back.",
        scenario = scenario("speech_outage", List(indexAt(50_000) + 1) { outageFrame(it) }),
        actions = SessionScript.defaultActions(
            ScriptAction.Engine(6_000, SpeechEngineState.UNAVAILABLE, ready = false),
            ScriptAction.Engine(12_000, ready = true),
            ScriptAction.Stop(20_000),
            ScriptAction.Start(21_000),
            ScriptAction.Engine(26_000, SpeechEngineState.UNAVAILABLE, ready = false),
            ScriptAction.Stop(30_000),
            ScriptAction.Start(31_000),
            ScriptAction.ScreenReader(33_000, on = true),
            ScriptAction.ScreenReader(34_000, on = false),
            ScriptAction.Engine(36_000, SpeechEngineState.READY, ready = true),
            ScriptAction.ScreenReader(40_000, on = true),
            ScriptAction.Engine(42_000, SpeechEngineState.UNAVAILABLE, ready = false),
            ScriptAction.ScreenReader(46_000, on = false),
            ScriptAction.Stop(50_000),
        ),
        endAtMs = 51_000,
    )

    /** The frames of [resultsStop] the camera never delivers: everything after 6950 until 25050. */
    val RESULTS_LOST = (indexAt(6_950) + 1) until indexAt(25_050)

    private val resultsStopScenario = scenario(
        "results_stop",
        List(indexAt(27_000)) { i -> walking(i).copy(delivery = if (i in RESULTS_LOST) FrameDelivery.Lost else FrameDelivery.Delivered) },
    )

    val resultsStop = SessionScript(
        name = "results_stop",
        description = "Walking an empty sidewalk while the camera delivers nothing after 6950 until 25050. 2.5 s " +
            "without a result is a stall: guidance stops and the stall is alarmed, felt and said, and again every " +
            "10 s while it lasts; the first result back guides again.",
        scenario = resultsStopScenario,
        actions = SessionScript.defaultActions(ScriptAction.Stop(27_000)),
        endAtMs = 28_000,
    )

    val resultsStopBackground = SessionScript(
        name = "results_stop_background",
        description = "The stall of results_stop, with the app in the background from 12000, after the first stall " +
            "alarm, to 22000. In the background the stall is not alarmed again; back in the foreground the startup " +
            "grace starts over, so the stall is not alarmed before the results come back at 25050.",
        scenario = resultsStopScenario,
        actions = SessionScript.defaultActions(
            ScriptAction.Background(12_000),
            ScriptAction.Foreground(22_000),
            ScriptAction.CameraResume(22_300),
            ScriptAction.Stop(27_000),
        ),
        endAtMs = 28_000,
    )

    /** The first frame of [pipelineFailureSlow] and [pipelineFailureFast] the segmentation model fails on, and every later one. */
    const val FAILING_FROM_MS = 5_050L

    private fun failing(name: String, stepMs: Long, count: Int) = scenario(
        name,
        List(count) { i ->
            val atMs = SessionScript.DEFAULT_FIRST_FRAME_MS + i * stepMs
            ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = atMs * 0.0012f - 1.26f), sidewalk, semFails = atMs >= FAILING_FROM_MS)
        },
        stepMs = stepMs,
    )

    val pipelineFailureSlow = SessionScript(
        name = "pipeline_failure_slow",
        description = "Walking an empty sidewalk at 10 frames a second when the segmentation model starts failing on " +
            "every frame at 5050. The stall comes first, 2.5 s after the last result; the thirtieth failure in a row, " +
            "2.9 s after the first, ends the session as failed: guidance's outputs stop before the failure is felt " +
            "and said, and the screen shows it last.",
        scenario = failing("pipeline_failure_slow", stepMs = 100, count = 80),
        actions = SessionScript.defaultActions(),
        endAtMs = 10_000,
    )

    val pipelineFailureFast = SessionScript(
        name = "pipeline_failure_fast",
        description = "As pipeline_failure_slow at 30 frames a second: the thirtieth failure in a row comes about 1 s " +
            "after the first, before a stall could, so the failure is the only alert.",
        scenario = failing("pipeline_failure_fast", stepMs = 33, count = 170),
        actions = SessionScript.defaultActions(),
        endAtMs = 8_000,
    )

    val all = listOf(
        sessionClearWalk,
        lensCoveredShort,
        backgroundAndBack,
        speechOutage,
        resultsStop,
        resultsStopBackground,
        pipelineFailureSlow,
        pipelineFailureFast,
    )
}
