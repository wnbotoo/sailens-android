package com.sailens.shell.sim

import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.sim.Box
import com.sailens.guidance.sim.FrameDelivery
import com.sailens.guidance.sim.GroundPatch
import com.sailens.guidance.sim.Lighting
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.SceneClasses
import com.sailens.guidance.sim.Scenario
import com.sailens.guidance.sim.ScenarioFrame
import com.sailens.guidance.sim.Scenarios.HEIGHT
import com.sailens.guidance.sim.Scenarios.PITCH
import com.sailens.guidance.sim.Scenarios.personAt
import com.sailens.guidance.sim.Scenarios.street
import com.sailens.guidance.sim.World
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings

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

    // ---- the other sessions

    /** A user standing still on [world]. */
    private fun standing(world: World = sidewalk) = ScenarioFrame(Pose(HEIGHT, PITCH), world, stationary = true)

    /** When frame [index] of a default session comes, 100 ms apart. */
    private fun at(index: Int) = SessionScript.DEFAULT_FIRST_FRAME_MS + index * 100L

    private fun during(atMs: Long, fromMs: Long, lengthMs: Long) = atMs >= fromMs && atMs < fromMs + lengthMs

    private val wallAhead = World(street(), listOf(Box(SceneClasses.WALL, -30.0, 30.0, 8.0, 8.3, 3.0)))

    /** The covered frames of [lensCoveredLongNearWall]: from 6050, standing 2 m short of the wall, for 20 s. */
    val COVERED_NEAR_WALL = indexAt(6_050) until indexAt(26_050)

    val lensCoveredLongNearWall = SessionScript(
        name = "lens_covered_long_near_wall",
        description = "Walking toward a wall across the sidewalk and stopping 2 m short of it at 6050, the lens covered " +
            "there for 20 s. The sixth covered frame stops guidance, said and felt then and every 8 s; the sixth clear " +
            "frame judges again, and the lens is said clear 2 s later. At 2 m the wall is not judged blocked, on the " +
            "covered frames before the sixth or after the cover, so no blocked prompt comes either side of it; " +
            "connectivity figures are the Kotlin fallback's.",
        scenario = scenario(
            "lens_covered_long_near_wall",
            List(indexAt(32_000)) { i ->
                if (i < COVERED_NEAR_WALL.first) {
                    walking(i, wallAhead)
                } else {
                    ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = 6.0f), wallAhead, stationary = true, lensCovered = i in COVERED_NEAR_WALL)
                }
            },
        ),
        actions = SessionScript.defaultActions(ScriptAction.Stop(32_000)),
        endAtMs = 33_000,
    )

    /** The dark frames of [lowLight]: from 5050, for 20 s. */
    val LOW_LIGHT = indexAt(5_050) until indexAt(25_050)

    val lowLight = SessionScript(
        name = "low_light",
        description = "Standing on a sidewalk in the dark for 20 s from 5050. The sixth dark frame stops guidance, said " +
            "and felt on entering, and said again 15 s later without the rhythm; the sixth lit frame judges again, and " +
            "the light is said back 2 s later.",
        scenario = scenario("low_light", List(indexAt(30_000)) { i -> standing().copy(lighting = if (i in LOW_LIGHT) Lighting.DARK else Lighting.NORMAL) }),
        actions = SessionScript.defaultActions(ScriptAction.Stop(30_000)),
        endAtMs = 31_000,
    )

    /** A floor the segmentation model reads as building, with a wall 8 m ahead. */
    private fun indoor(vararg people: Box) = World(
        listOf(GroundPatch(SceneClasses.BUILDING, -30.0, 30.0, -5.0, 39.9)),
        listOf(Box(SceneClasses.WALL, -30.0, 30.0, 8.0, 8.3, 3.0)) + people,
    )

    /** When the user steps onto the unrecognised floor, in every session that has it. */
    const val FLOOR_FROM_MS = 5_050L

    /** When that floor is confirmed unrecognised: held for 1.5 s. */
    const val FLOOR_CONFIRMED_MS = FLOOR_FROM_MS + 1_500

    /** The frames of [groundUnrecognizedLong] on the unrecognised floor: from 5050, for 70 s. */
    val GROUND_LONG = indexAt(FLOOR_FROM_MS) until indexAt(75_050)

    private fun groundLongWorld(atMs: Long): World = when {
        during(atMs, 20_050, 2_000) -> indoor(personAt(0.0, 1.5))
        during(atMs, 40_050, 2_000) -> indoor(personAt(-0.8, 2.0))
        else -> indoor()
    }

    val groundUnrecognizedLong = SessionScript(
        name = "ground_unrecognized_long",
        description = "Standing on a floor the segmentation model reads as building from 5050 for 70 s, then back on the " +
            "sidewalk. Before the floor is confirmed unrecognised the path is judged blocked; 1.5 s on it is confirmed, " +
            "which degrades guidance and is said and felt, and said again 60 s later. People are prompted as ever " +
            "meanwhile: one ahead at 20050, one to the left at 40050. 1 s back on the sidewalk the floor is recognised " +
            "again, and said so.",
        scenario = scenario(
            "ground_unrecognized_long",
            List(indexAt(80_000)) { i -> standing(if (i in GROUND_LONG) groundLongWorld(at(i)) else sidewalk) },
        ),
        actions = SessionScript.defaultActions(ScriptAction.Stop(80_000)),
        endAtMs = 81_000,
    )

    /** The frames of [groundUnrecognizedThenCovered] on the unrecognised floor, and those with the lens covered. */
    val GROUND_THEN_COVERED = indexAt(FLOOR_FROM_MS) until indexAt(35_050)
    val COVERED_ON_GROUND = indexAt(15_050) until indexAt(20_050)

    val groundUnrecognizedThenCovered = SessionScript(
        name = "ground_unrecognized_then_covered",
        description = "On the unrecognised floor of ground_unrecognized_long from 5050 for 30 s, the lens covered for 5 s " +
            "from 15050. The covered lens stops guidance, the floor still in its judgement; nothing more is said or " +
            "felt about the floor meanwhile, and once the lens is clear the floor is not announced a second time. Back " +
            "on the sidewalk at 35050, the floor is said recognised.",
        scenario = scenario(
            "ground_unrecognized_then_covered",
            List(indexAt(40_000)) { i ->
                standing(if (i in GROUND_THEN_COVERED) indoor() else sidewalk).copy(lensCovered = i in COVERED_ON_GROUND)
            },
        ),
        actions = SessionScript.defaultActions(ScriptAction.Stop(40_000)),
        endAtMs = 41_000,
    )

    val speechOffLensCovered = lensCoveredShort.copy(
        name = "speech_off_lens_covered",
        description = "lens_covered_short with speech off. The rhythm is the notice, every cycle, and fault vibration " +
            "counts as on; nothing is said, the clear lens included. Guidance's states are those of lens_covered_short.",
        settings = GuidanceFeedbackSettings(speechEnabled = false),
    )

    /** When the screen reader comes on in [screenReaderLensCovered]. */
    const val SCREEN_READER_ON_MS = 3_000L

    val screenReaderLensCovered = lensCoveredShort.copy(
        name = "screen_reader_lens_covered",
        description = "lens_covered_short, with the screen reader turned on at 3000: own speech is stopped then, and " +
            "every notice after it, the clear lens's included, goes to the screen reader, which counts as told. The " +
            "rhythms play as with own speech.",
        actions = SessionScript.defaultActions(ScriptAction.ScreenReader(SCREEN_READER_ON_MS, on = true), ScriptAction.Stop(20_000)),
    )

    /** In [descriptionHoldsFloor], a person to the left at medium urgency, then one ahead. */
    const val SIDE_PERSON_FROM_MS = 7_050L
    const val PERSON_AHEAD_FROM_MS = 8_550L

    private fun descriptionWorld(atMs: Long): World = when {
        during(atMs, SIDE_PERSON_FROM_MS, 1_000) -> indoor(personAt(-1.0, 2.0))
        during(atMs, PERSON_AHEAD_FROM_MS, 2_000) -> indoor(personAt(0.0, 1.5))
        else -> indoor()
    }

    val descriptionHoldsFloor = SessionScript(
        name = "description_holds_floor",
        description = "Standing on the unrecognised floor of ground_unrecognized_long; a scene description begins to hold " +
            "the floor for 4 s at 6550, the moment the floor is confirmed (the script before the frame). The floor's " +
            "notice waits for it: refused each time it is offered, while its rhythm plays. A person to the left at " +
            "medium urgency waits for it as well and is withdrawn; a person ahead from 8550 preempts it and is said. " +
            "The floor's notice is taken once nothing higher holds the speaker.",
        scenario = scenario(
            "description_holds_floor",
            List(indexAt(14_000)) { i -> standing(if (at(i) >= FLOOR_FROM_MS) descriptionWorld(at(i)) else sidewalk) },
        ),
        actions = SessionScript.defaultActions(ScriptAction.Describe(FLOOR_CONFIRMED_MS, 4_000), ScriptAction.Stop(14_000)),
        endAtMs = 15_000,
    )

    /** When [stopWhileCovered] is stopped: the lens still covered. */
    const val STOP_WHILE_COVERED_MS = 10_000L

    val stopWhileCovered = lensCoveredShort.copy(
        name = "stop_while_covered",
        description = "lens_covered_short, stopped at 10000 while the lens is still covered: the delivery protocol is " +
            "reset, so the clear lens is never said, then speech and the motor stop.",
        actions = SessionScript.defaultActions(ScriptAction.Stop(STOP_WHILE_COVERED_MS)),
        endAtMs = 11_000,
    )

    /** [script] with the prompt-policy switches evaluated for shipping on ([DetFirstConfig.LAUNCH]), as `<name>.launch`. */
    private fun launch(script: SessionScript, effect: String) = script.copy(
        name = "${script.name}.launch",
        description = "${script.name} with DetFirstConfig.LAUNCH: $effect",
        detFirst = DetFirstConfig.LAUNCH,
    )

    val lensCoveredShortLaunch = launch(
        lensCoveredShort,
        "the same states and alerts. The covered lens's notice ranks higher, and the mode notice is said on the first frame.",
    )

    val groundUnrecognizedLongLaunch = launch(
        groundUnrecognizedLong,
        "the same states and alerts. The prompts rank higher and are confirmed once heard, a rhythm goes with them only " +
            "when the cooldown accepts them, and the mode notice is said on the first frame.",
    )

    val backgroundAndBackLaunch = launch(
        backgroundAndBack,
        "the same states and alerts; the mode notice is said on the first frame.",
    )

    /** The covers of the speech-outage sessions: a short one at 7050, the long one at 12050. */
    val SHORT_COVER = indexAt(7_050) until indexAt(8_050)
    val LONG_COVER = indexAt(12_050) until indexAt(19_050)

    /** Own speech is unavailable from 6000 to 22000 while the user stands on a sidewalk, the lens covered in [covers]. */
    private fun outageCover(name: String, description: String, covers: List<IntRange>, settings: GuidanceFeedbackSettings = GuidanceFeedbackSettings()) =
        SessionScript(
            name = name,
            description = description,
            scenario = scenario(name, List(indexAt(25_000)) { i -> standing().copy(lensCovered = covers.any { i in it }) }),
            actions = SessionScript.defaultActions(
                ScriptAction.Engine(6_000, SpeechEngineState.UNAVAILABLE, ready = false),
                ScriptAction.Engine(22_000, SpeechEngineState.READY, ready = true),
                ScriptAction.Stop(25_000),
            ),
            endAtMs = 26_000,
            settings = settings,
        )

    val speechOutageLongCover = outageCover(
        "speech_outage_long_cover",
        "Standing on a sidewalk while own speech is unavailable from 6000 to 22000, the lens covered for 7 s from 12050. " +
            "On the sixth covered frame the notice is refused, the engine not being ready, and the entry rhythm plays, " +
            "which does not count as telling; 5 s later the fallback rhythm plays, whatever the switches say.",
        listOf(LONG_COVER),
    )

    val speechOutageShortThenLongCover = outageCover(
        "speech_outage_short_then_long_cover",
        "speech_outage_long_cover after a 1 s cover at 7050 that the user is never told about. Its debt carries over, so " +
            "the long cover is 5 s overdue the moment it is confirmed: its fallback is asked for together with the entry " +
            "rhythm then, one rhythm, and not again 5 s later.",
        listOf(SHORT_COVER, LONG_COVER),
    )

    val speechOutageShortThenLongCoverFaultHapticsOff = outageCover(
        "speech_outage_short_then_long_cover_fault_haptics_off",
        "speech_outage_short_then_long_cover with fault vibration off: there is no entry rhythm, so the one rhythm when " +
            "the long cover is confirmed is the fallback, played whatever the switch says.",
        listOf(SHORT_COVER, LONG_COVER),
        GuidanceFeedbackSettings(faultHapticsEnabled = false),
    )

    val speechOutageHapticsOff = SessionScript(
        name = "speech_outage_haptics_off",
        description = "The first outage of speech_outage with navigation vibration off: a prompt in the outage has no " +
            "channel left and is withdrawn on every frame it is offered; the outage is still alerted once, by vibration.",
        scenario = scenario("speech_outage_haptics_off", List(indexAt(16_000)) { outageFrame(it) }),
        actions = SessionScript.defaultActions(
            ScriptAction.Engine(6_000, SpeechEngineState.UNAVAILABLE, ready = false),
            ScriptAction.Engine(12_000, ready = true),
            ScriptAction.Stop(16_000),
        ),
        endAtMs = 17_000,
        settings = GuidanceFeedbackSettings(hapticsEnabled = false),
    )

    /** When the segmentation model of [startupStall] is ready. */
    const val SLOW_INIT_MS = 25_000L

    val startupStall = SessionScript(
        name = "startup_stall",
        description = "The segmentation model takes until 25000 to initialise, and no frame is received until then. 20 s " +
            "after start the startup stall stops guidance and is alarmed, felt and said; the first result at 25050 " +
            "guides, before a repeat would come.",
        scenario = scenario("startup_stall", List(indexAt(28_000)) { standing() }),
        actions = listOf(ScriptAction.Start(0), ScriptAction.ReleaseInit(SLOW_INIT_MS), ScriptAction.Stop(28_000)),
        endAtMs = 29_000,
    )

    /** When the segmentation model fails to initialise in [initFailure], and why. */
    const val INIT_FAILS_MS = 1_000L
    const val INIT_FAILURE = "the model could not be loaded"

    val initFailure = SessionScript(
        name = "init_failure",
        description = "The segmentation model fails to initialise at 1000: the session fails at once, its outputs stopped " +
            "before the failure is felt and said, and the screen shows it last. No frame is received.",
        scenario = scenario("init_failure", List(indexAt(3_000)) { standing() }),
        actions = listOf(ScriptAction.Start(0), ScriptAction.FailInit(INIT_FAILS_MS, INIT_FAILURE)),
        endAtMs = 3_000,
    )

    /** When the frame flow of [flowEnds] completes. */
    const val FLOW_ENDS_MS = 7_000L

    val flowEnds = SessionScript(
        name = "flow_ends",
        description = "The camera's frame flow ends normally after 6950. Nothing handles the end: the screen still runs, " +
            "2.5 s without a result is a stall, alarmed and repeated every 10 s, and the session never fails.",
        scenario = scenario("flow_ends", List(indexAt(31_000)) { standing() }),
        actions = SessionScript.defaultActions(ScriptAction.EndFrames(FLOW_ENDS_MS), ScriptAction.Stop(31_000)),
        endAtMs = 32_000,
    )

    /** [stopAndRecoverySpeech]: the lens covered from 4350, uncovered at 11050, covered again for 0.3 s from 11350. */
    const val RECOVERY_COVER_MS = 4_350L
    const val RECOVERY_UNCOVER_MS = 11_050L
    const val RECOVERY_RECOVER_MS = 11_350L
    const val RECOVERY_LAST_UNCOVER_MS = 11_650L

    private fun recoveryFrame(index: Int): ScenarioFrame {
        val world = World(street(), listOf(personAt(-0.8, 6.0)))
        val atMs = at(index)
        if (atMs < RECOVERY_COVER_MS) return walking(index, world)
        val covered = during(atMs, RECOVERY_COVER_MS, RECOVERY_UNCOVER_MS - RECOVERY_COVER_MS) ||
            during(atMs, RECOVERY_RECOVER_MS, RECOVERY_LAST_UNCOVER_MS - RECOVERY_RECOVER_MS)
        return ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = indexAt(RECOVERY_COVER_MS) * 0.12f), world, stationary = true, lensCovered = covered)
    }

    val stopAndRecoverySpeech = SessionScript(
        name = "stop_and_recovery_speech",
        description = "Walking toward a person standing to the left 6 m ahead; the lens is covered at 4350 and the user " +
            "stands still. The sixth covered frame comes while the prompt about the person, below critical, is being " +
            "said: the covered lens's notice cuts it off, and the cooldown is told nothing of the cut. Uncovered at " +
            "11050, covered again for 0.3 s at 11350 and uncovered: the brief uncover changes nothing, and the sixth " +
            "clear frame after the last one judges again and prompts the person at once.",
        scenario = scenario("stop_and_recovery_speech", List(indexAt(16_000)) { recoveryFrame(it) }),
        actions = SessionScript.defaultActions(ScriptAction.Stop(16_000)),
        endAtMs = 17_000,
    )

    val stopAndRecoverySpeechLaunch = launch(
        stopAndRecoverySpeech,
        "interrupted prompts are retried, so the cut prompt is reported to the cooldown.",
    )

    /** In [stopAndRecoveryHaptics], a person ahead and the cover both from 6650. */
    const val HAPTICS_PERSON_FROM_MS = 6_650L

    val stopAndRecoveryHaptics = SessionScript(
        name = "stop_and_recovery_haptics",
        description = "With speech off, standing on the unrecognised floor of ground_unrecognized_long: its entry rhythm " +
            "holds the motor from 6550. A person stepping in ahead at 6650 is prompted by vibration alone and queues " +
            "behind it; the lens, covered from 6650, is confirmed covered at 7150, and its rhythm reserves the motor. " +
            "When the floor's rhythm ends the reserved one plays first, and the queued person's after it.",
        scenario = scenario(
            "stop_and_recovery_haptics",
            List(indexAt(14_000)) { i ->
                val world = when {
                    at(i) >= HAPTICS_PERSON_FROM_MS -> indoor(personAt(0.0, 1.5))
                    at(i) >= FLOOR_FROM_MS -> indoor()
                    else -> sidewalk
                }
                standing(world).copy(lensCovered = during(at(i), HAPTICS_PERSON_FROM_MS, 4_000))
            },
        ),
        actions = SessionScript.defaultActions(ScriptAction.Stop(14_000)),
        endAtMs = 15_000,
        settings = GuidanceFeedbackSettings(speechEnabled = false),
    )

    /** In [accompanyingRhythmReplaced], a person to the left from here, and one ahead from [ACCOMPANYING_AHEAD_FROM_MS]. */
    const val ACCOMPANYING_SIDE_FROM_MS = 6_550L
    const val ACCOMPANYING_AHEAD_FROM_MS = 6_850L

    val accompanyingRhythmReplaced = SessionScript(
        name = "accompanying_rhythm_replaced",
        description = "Speech and navigation vibration on, standing on the unrecognised floor of ground_unrecognized_long: " +
            "its entry rhythm holds the motor from 6550. A person to the left is said while it plays, and the rhythm " +
            "going with the words waits for the motor; a person ahead, more urgent, is said next and its rhythm takes " +
            "the place of the first. Each prompt was delivered when speech took it: what the motor does with their " +
            "rhythms afterwards changes neither the cooldown nor the record.",
        scenario = scenario(
            "accompanying_rhythm_replaced",
            List(indexAt(11_000)) { i ->
                val people = listOfNotNull(
                    personAt(-1.0, 2.0).takeIf { at(i) >= ACCOMPANYING_SIDE_FROM_MS },
                    personAt(0.0, 1.5).takeIf { at(i) >= ACCOMPANYING_AHEAD_FROM_MS },
                )
                standing(if (at(i) >= FLOOR_FROM_MS) indoor(*people.toTypedArray()) else sidewalk)
            },
        ),
        actions = SessionScript.defaultActions(ScriptAction.Stop(11_000)),
        endAtMs = 12_000,
    )

    /** When the guidance screen goes away in the two sessions that leave it. */
    const val LEAVE_MS = 3_000L

    val screenLeavesOtherLease = SessionScript(
        name = "screen_leaves_other_lease",
        description = "A prompt about a person ahead is being said when the guidance screen goes away at 3000, while " +
            "another screen holds the shared speech engine as well: the screen's scope ends first, then guidance stops, " +
            "and the sentence is said to the end. The engine failing at 4500 reaches nothing.",
        scenario = scenario("screen_leaves_other_lease", List(indexAt(5_000)) { outageFrame(it) }),
        actions = SessionScript.defaultActions(ScriptAction.LeaveScreen(LEAVE_MS), ScriptAction.Engine(4_500, SpeechEngineState.UNAVAILABLE)),
        endAtMs = 6_000,
        otherScreenHoldsEngine = true,
    )

    val screenLeavesLastLease = SessionScript(
        name = "screen_leaves_last_lease",
        description = "screen_leaves_other_lease with no other screen holding the engine: the screen's lease is the " +
            "last, so closing it stops the engine, cutting the sentence off, and releases it.",
        scenario = scenario("screen_leaves_last_lease", List(indexAt(5_000)) { outageFrame(it) }),
        actions = SessionScript.defaultActions(ScriptAction.LeaveScreen(LEAVE_MS)),
        endAtMs = 6_000,
    )

    /** When the held initialisation of [restartWhilePreviousRunHolds] gives way. */
    const val HELD_INIT_ENDS_MS = 4_000L

    val restartWhilePreviousRunHolds = SessionScript(
        name = "restart_while_previous_run_holds",
        description = "The segmentation model's initialisation does not give way to a stop until 4000. Stopped at 1000 " +
            "and started again at 1500, the new run waits for the old one: the screen is starting meanwhile, with no " +
            "stall watch, and the background from 2000 to 2500 alerts nothing, nothing running yet. At 4000 the old run " +
            "ends and the new one begins at once, on the model already initialised, and is watched from then.",
        scenario = scenario("restart_while_previous_run_holds", List(indexAt(8_000)) { standing() }),
        actions = listOf(
            ScriptAction.Start(0),
            ScriptAction.Stop(1_000),
            ScriptAction.Start(1_500),
            ScriptAction.Background(2_000),
            ScriptAction.Foreground(2_500),
            ScriptAction.CameraResume(2_800),
            ScriptAction.ReleaseInit(HELD_INIT_ENDS_MS),
            ScriptAction.Stop(8_000),
        ),
        endAtMs = 9_000,
        gates = mapOf(ScriptAction.SEM to false),
    )

    /** In [failureInBackground]: the background, then the model failing. */
    const val BACKGROUND_WHILE_LOADING_MS = 500L
    const val FAILS_IN_BACKGROUND_MS = 3_000L

    val failureInBackground = SessionScript(
        name = "failure_in_background",
        description = "The app goes to the background at 500, the segmentation model still loading, and the model fails " +
            "at 3000: the interruption is alerted, then the failure too, which ends the session; failed outranks " +
            "interrupted, and the interruption stays marked.",
        scenario = scenario("failure_in_background", List(indexAt(5_000)) { standing() }),
        actions = listOf(
            ScriptAction.Start(0),
            ScriptAction.Background(BACKGROUND_WHILE_LOADING_MS),
            ScriptAction.FailInit(FAILS_IN_BACKGROUND_MS, INIT_FAILURE),
        ),
        endAtMs = 5_000,
    )

    /** In [faultHapticsOffWhileReserved]: the cover from 5050, a person ahead from 5250, fault vibration off at 5600. */
    const val RESERVED_COVER_FROM_MS = 5_050L
    const val RESERVED_PERSON_FROM_MS = 5_250L
    const val FAULT_HAPTICS_OFF_MS = 5_600L

    val faultHapticsOffWhileReserved = SessionScript(
        name = "fault_haptics_off_while_reserved",
        description = "A person stepping in ahead is prompted at critical priority at 5450, 100 ms before the lens, covered " +
            "from 5050, is confirmed covered: the cover's rhythm waits for the person's, reserved. Fault vibration is " +
            "turned off at 5600, between two frames, and the reservation is withdrawn then: the rhythm never plays. " +
            "The covered lens is still said.",
        scenario = scenario(
            "fault_haptics_off_while_reserved",
            List(indexAt(14_000)) { i ->
                val world = if (during(at(i), RESERVED_PERSON_FROM_MS, 2_000)) World(street(), listOf(personAt(0.0, 1.5))) else sidewalk
                standing(world).copy(lensCovered = during(at(i), RESERVED_COVER_FROM_MS, 5_000))
            },
        ),
        actions = SessionScript.defaultActions(ScriptAction.FaultHapticsSetting(FAULT_HAPTICS_OFF_MS, on = false), ScriptAction.Stop(14_000)),
        endAtMs = 15_000,
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
        lensCoveredLongNearWall,
        lowLight,
        groundUnrecognizedLong,
        groundUnrecognizedThenCovered,
        speechOffLensCovered,
        screenReaderLensCovered,
        descriptionHoldsFloor,
        stopWhileCovered,
        lensCoveredShortLaunch,
        groundUnrecognizedLongLaunch,
        backgroundAndBackLaunch,
        speechOutageLongCover,
        speechOutageShortThenLongCover,
        speechOutageShortThenLongCoverFaultHapticsOff,
        speechOutageHapticsOff,
        startupStall,
        initFailure,
        flowEnds,
        stopAndRecoverySpeech,
        stopAndRecoverySpeechLaunch,
        stopAndRecoveryHaptics,
        accompanyingRhythmReplaced,
        screenLeavesOtherLease,
        screenLeavesLastLease,
        restartWhilePreviousRunHolds,
        failureInBackground,
        faultHapticsOffWhileReserved,
    )
}
