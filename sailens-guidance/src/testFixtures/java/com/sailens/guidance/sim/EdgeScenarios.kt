package com.sailens.guidance.sim

import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.sim.Scenarios.FIELD_PITCH
import com.sailens.guidance.sim.Scenarios.HEIGHT
import com.sailens.guidance.sim.Scenarios.PITCH
import com.sailens.guidance.sim.Scenarios.car
import com.sailens.guidance.sim.Scenarios.personAt
import com.sailens.guidance.sim.Scenarios.street

/**
 * Scenarios at the edges of what Guidance is given: obstacles in each zone, a narrowing and a blocked
 * path, a flickering class map, single-frame and vanishing detections, frames lost, frozen, late or
 * failed, a covered lens and the dark. Like [Scenarios], each golden records today's behaviour, not an
 * endorsement of it. They are kept apart from [Scenarios.all] because some frames here never produce a
 * result, and every scenario there must produce one per frame.
 *
 * A covered lens or the dark changes only the luma the frame-quality check reads: the models still
 * report the scene, as they do in [Scenarios.groundNoGround]. What a model makes of a covered lens is
 * not simulated.
 */
object EdgeScenarios {
    private val bench = DetectedAs(cocoId = 13, label = "bench", category = ObstacleCategory.STATIC_OBSTACLE)

    private fun standing(world: World, count: Int, pitch: Float = PITCH) =
        List(count) { ScenarioFrame(Pose(HEIGHT, pitch), world, stationary = true) }

    /** A person ahead, at the left and at the right, then a bench ahead, as the user stands still. */
    val obstaclesCenterLeftRight = Scenario(
        name = "obstacles_center_left_right",
        description = "Standing still while a person stands 3 m ahead, then 1.5 m ahead, then 2 m off at the left, then " +
            "at the right, then a bench (a static class, with the stricter roadside gate) 1.2 m ahead, with the " +
            "sidewalk empty for a while between them. A centre person must come closer than a side one before it is " +
            "announced, and every new position needs three detections before it is tracked. Left and right share a " +
            "side cooldown (6 s, capped at 8 s for a user standing still), so the person at the right, tracked 5 s " +
            "after the left one was announced, is announced only once that cooldown has run out.",
        frames = run {
            val bench = Box(SceneClasses.WALL, -0.6, 0.6, 1.2, 1.65, 0.5, detection = bench)
            val empty = World(street())
            standing(World(street(), listOf(personAt(0.0, 3.0))), 20) +
                standing(World(street(), listOf(personAt(0.0, 1.5))), 20) +
                standing(empty, 10) +
                standing(World(street(), listOf(personAt(-0.8, 2.0))), 20) +
                standing(empty, 50) +
                standing(World(street(), listOf(personAt(0.8, 2.0))), 20) +
                standing(empty, 50) +
                standing(World(street(), listOf(bench)), 25)
        },
    )

    /** Walls closing a 3 m sidewalk to 0.9 m over 6 m, walked into. */
    val corridorNarrowing = Scenario(
        name = "corridor_narrowing",
        description = "Walking between walls that close the 3 m sidewalk to 0.9 m over 6 m, and on into the 0.9 m " +
            "passage. Narrowing prompts are off by default, so nothing says it narrows. They would stay silent if " +
            "they were on: the retained width never falls under the 0.55 a narrowing needs, because the fallback " +
            "scales the expected width with perspective. What the walls do to the path judgement is in the width and " +
            "blockage figures, and connectivity figures are the Kotlin fallback's.",
        frames = run {
            // Steps of 1 m whose inner edges close from 1.5 m to 0.45 m off the centre line, then a 0.9 m passage.
            val walls = (0 until 6).flatMap { step ->
                val inner = 1.5 - (1.05 / 6) * (step + 1)
                listOf(
                    Box(SceneClasses.WALL, -3.0, -inner, step + 2.0, step + 3.0, 2.0),
                    Box(SceneClasses.WALL, inner, 3.0, step + 2.0, step + 3.0, 2.0),
                )
            } + listOf(
                Box(SceneClasses.WALL, -3.0, -0.45, 8.0, 20.0, 2.0),
                Box(SceneClasses.WALL, 0.45, 3.0, 8.0, 20.0, 2.0),
            )
            val world = World(street(), walls)
            (0 until 85).map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.12f), world) }
        },
    )

    /** The frames of [fullBlockage] that walk over the misread strip. */
    val STRIP_WALK = 0 until 45

    /** A strip of floor read as building walked over, then a wall across the sidewalk walked up to. */
    val fullBlockage = Scenario(
        name = "full_blockage",
        description = "Walking over a 1.5 m strip of floor 4 m ahead that the segmentation reads as building, with the " +
            "sidewalk beyond it; then, from the start again, toward a wall across the whole sidewalk 6 m ahead, " +
            "stopping 0.8 m short of it. With the strip at the user's feet the path is blocked at a confidence just " +
            "under the hard-blocked 0.75, so it is called complex, and the strip is gone before the floor could be " +
            "confirmed unrecognised; the wall drives the confidence straight to 1.0 and is critical blocked. " +
            "Connectivity figures are the Kotlin fallback's.",
        frames = run {
            val strip = World(listOf(GroundPatch(SceneClasses.BUILDING, -30.0, 30.0, 4.0, 5.5)) + street())
            val wall = World(street(), listOf(Box(SceneClasses.WALL, -30.0, 30.0, 6.0, 6.3, 3.0)))
            STRIP_WALK.map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.12f), strip) } +
                (0 until 44).map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.12f), wall) } +
                (0 until 10).map { ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = 5.2f), wall, stationary = true) }
        },
    )

    /** One frame in five where the segmentation reads the ground at the user's feet as building. */
    val segmentationFlicker = Scenario(
        name = "segmentation_flicker",
        description = "Walking an empty sidewalk while one frame in five the segmentation reads the ground at the " +
            "user's feet as building. Each such frame makes the ground uncertain, never unrecognised, which needs " +
            "1.5 s; blocked needs four frames in a row and the passable ratio is a moving average, so a single " +
            "frame does not make a blocked or complex-path prompt. Connectivity figures are the Kotlin fallback's.",
        frames = (0 until 40).map { i ->
            val walked = i * 0.12f
            val world = World(street())
            if (i % 5 == 4) {
                val misread = World(listOf(GroundPatch(SceneClasses.BUILDING, -30.0, 30.0, -5.0, walked + 2.0)) + street())
                ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = walked), world, semWorld = misread)
            } else {
                ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = walked), world)
            }
        },
    )

    /** Index of the frame with the single false detection in [singleFrameFalseDetection]. */
    const val FALSE_DETECTION_INDEX = 5

    /** Index of the first of the three detections in a row in [singleFrameFalseDetection]. */
    const val REPEATED_DETECTION_INDEX = 18

    /** A person the detector reports for one frame only, then, well after, for several frames in a row. */
    val singleFrameFalseDetection = Scenario(
        name = "single_frame_false_detection",
        description = "Standing on an empty sidewalk while the detector alone reports a person 2 m ahead for a single " +
            "frame; twelve frames later it reports the same person in the same place frame after frame. A track " +
            "is born at the first detection but is output only from its third, so the single frame is never " +
            "announced, and by the repeat its track has long been dropped for missed detections.",
        frames = run {
            val empty = World(street())
            val withPerson = World(street(), listOf(personAt(0.0, 2.0)))
            List(30) { i ->
                val seen = i == FALSE_DETECTION_INDEX || i in REPEATED_DETECTION_INDEX until REPEATED_DETECTION_INDEX + 6
                ScenarioFrame(Pose(HEIGHT, PITCH), empty, stationary = true, detWorld = if (seen) withPerson else null)
            }
        },
    )

    /** The first frame lost in the gap of [droppedFrames]. */
    const val GAP_START_INDEX = 10

    /** Frames lost in the gap of [droppedFrames]: 1.2 s. */
    const val GAP_FRAMES = 12

    /** The walk of [droppedFrames], with each frame's delivery: the gap, the bursts and the covered stretch. */
    private fun droppedDelivery(index: Int): FrameDelivery = when {
        index in GAP_START_INDEX until GAP_START_INDEX + GAP_FRAMES -> FrameDelivery.Lost
        index in 22 until 42 && (index - 22) % 5 in 1..3 -> FrameDelivery.Lost
        index in 67 until 87 && (index - 67) % 5 in 1..3 -> FrameDelivery.Lost
        else -> FrameDelivery.Delivered
    }

    /** The covered stretch of [droppedFrames]. */
    val DROPPED_COVERED = 67 until 87

    private fun droppedFramesOf(carAfterGap: (Int) -> World?) = run {
        val world = Scenarios.approachParkedCar.frames.first().world
        val walk = Scenarios.approachParkedCar.frames.mapIndexed { index, frame ->
            frame.copy(delivery = droppedDelivery(index), detWorld = carAfterGap(index))
        }
        val stand = (62 until 92).map { index ->
            ScenarioFrame(
                Pose(HEIGHT, FIELD_PITCH, walkedM = 7.4f), world, stationary = true,
                lensCovered = index in DROPPED_COVERED, delivery = droppedDelivery(index),
            )
        }
        walk + stand
    }

    /** The parked-car walk with a 1.2 s gap, bursts of lost frames, and a covered lens while frames are lost. */
    val droppedFrames = Scenario(
        name = "dropped_frames",
        description = "The walk toward the parked car with frames lost: a 1.2 s gap early on, then three frames in every " +
            "five; then, standing 0.6 m short of the car, the lens covered while three frames in every five are " +
            "lost. Detection runs on every frame that arrives, so after the gap the car's box matches its track " +
            "and the track carries on; the covered lens is confirmed on the sixth covered frame that arrives.",
        frames = droppedFramesOf { null },
    )

    /**
     * [droppedFrames] with the car's box moved 1.5 m sideways from the end of the gap on, for the detector only:
     * it no longer overlaps its track, so the track cannot carry on. Not a scenario of its own.
     */
    val droppedFramesMovedBox = droppedFrames.copy(
        name = "dropped_frames_moved_box",
        frames = droppedFramesOf { index ->
            if (index < GAP_START_INDEX + GAP_FRAMES) {
                null
            } else {
                World(street(), listOf(Box(SceneClasses.CAR, 0.5, 2.5, 8.0, 9.8, 1.5, detection = car)))
            }
        },
    )

    /** The last frame with the person in [rateLimitedDetTtl]. */
    const val TTL_LAST_HIT_INDEX = 12

    /**
     * What [rateLimitedDetTtl] runs with: the app's profile with detection limited to 3 runs a second, a knob
     * left for tuning that the app does not turn. 1000 / 3 = 333 ms, so on 100 ms frames detection runs every 400 ms.
     */
    val rateLimitedDetection: PerceptionConfig = PerceptionConfig.forProfile(
        profile = PerceptionProfile.DEFAULT,
        realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
    ).copy(detectionTargetFps = 3)

    /** A person seen by a detector limited to 3 runs a second, who then goes. Run it with [rateLimitedDetection]. */
    val rateLimitedDetTtl = Scenario(
        name = "rate_limited_det_ttl",
        description = "Detection limited to 3 runs a second, which is not the app's configuration: frames are 100 ms " +
            "apart, so it runs every fourth frame and the tracker predicts in between. A person stands 2 m ahead " +
            "long enough to be tracked and then goes. On predicted frames a track older than 500 ms since its last " +
            "detection is dropped; on a detection frame that misses it, it only counts a miss.",
        frames = run {
            val withPerson = World(street(), listOf(personAt(0.0, 2.0)))
            val empty = World(street())
            List(26) { i -> ScenarioFrame(Pose(HEIGHT, PITCH), if (i <= TTL_LAST_HIT_INDEX) withPerson else empty, stationary = true) }
        },
    )

    /** The frames of [staleFrames] that are received late in [staleFramesLate]. */
    val STALE_LATE = 20 until 50

    private fun staleWalk(lagMs: (Int) -> Long) = run {
        val world = World(street(), listOf(personAt(0.0, 6.0)))
        (0 until 56).map { i ->
            ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = minOf(i * 0.12f, 4.6f)), world, receiptLagMs = lagMs(i))
        }
    }

    /** A walk toward a person, every frame received as it is decided. */
    val staleFrames = Scenario(
        name = "stale_frames",
        description = "Walking toward a person 6 m ahead and stopping 1.4 m short, with the clock and the frames' receipt " +
            "times aligned and every frame decided as it is received. The same walk with thirty frames, the person's " +
            "prompt among them, received 2 s before they are decided must come out the same: no decision reads a " +
            "frame's age.",
        frames = staleWalk { 0 },
        alignClockToFrames = true,
    )

    /** [staleFrames] with thirty frames received 2 s before they are decided. Compared with it, not a golden of its own. */
    val staleFramesLate = staleFrames.copy(
        name = "stale_frames_late",
        frames = staleWalk { i -> if (i in STALE_LATE) 2_000 else 0 },
    )

    /** The frames of [frozenCamera] that repeat the picture of the frame before them. */
    val FROZEN = 30 until 80

    /** A walk toward a parked car with the camera stuck on one picture for 5 s. */
    val frozenCamera = Scenario(
        name = "frozen_camera",
        description = "Walking toward a car parked across the sidewalk 14 m ahead while for 5 s the camera keeps " +
            "delivering the same picture with fresh timestamps. Results keep coming, so nothing notices: the frozen " +
            "frames are judged like standing still. The first live frame's box jumps to the real distance, too far " +
            "from the old one to match it, so the car is tracked anew and needs three detections again.",
        frames = run {
            val world = World(street(), listOf(Box(SceneClasses.CAR, -1.0, 1.0, 14.0, 15.8, 1.5, detection = car)))
            (0 until 110).map { i ->
                ScenarioFrame(
                    Pose(HEIGHT, FIELD_PITCH, walkedM = minOf(i * 0.12f, 12.4f)), world,
                    delivery = if (i in FROZEN) FrameDelivery.Frozen(FROZEN.first - 1) else FrameDelivery.Delivered,
                )
            }
        },
    )

    /** The covered frames of [coverNearWall]. */
    val COVER_NEAR_WALL = 50 until 80

    /** A walk toward a wall with the lens covered 2 m short of it. */
    val coverNearWall = Scenario(
        name = "cover_near_wall",
        description = "Walking toward a wall across the sidewalk and stopping 2 m short of it, the lens covered there for " +
            "3 s and then uncovered. The covered frames before the sixth are still judged from the picture, and from " +
            "the sixth uncovered frame the path is judged again. At 2 m the wall is not judged blocked, so neither " +
            "kind of frame has a blocked prompt to give; connectivity figures are the Kotlin fallback's.",
        frames = run {
            val world = World(street(), listOf(Box(SceneClasses.WALL, -30.0, 30.0, 8.0, 8.3, 3.0)))
            (0 until 50).map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.12f), world) } +
                (50 until 100).map { i ->
                    ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = 6.0f), world, stationary = true, lensCovered = i in COVER_NEAR_WALL)
                }
        },
    )

    /** Dark, covered, dark again, then normal light, standing still. */
    val lowLightAndCover = Scenario(
        name = "low_light_and_cover",
        description = "Standing on a sidewalk in the dark for 3 s, the lens then covered for 2 s, dark again for 3 s, " +
            "then normal light. A covered lens wins over the dark, the dark is a high prompt and the covered lens a " +
            "critical one, and each change takes six frames to be believed.",
        frames = run {
            val world = World(street())
            fun stretch(count: Int, lighting: Lighting) = List(count) { ScenarioFrame(Pose(HEIGHT, PITCH), world, stationary = true, lighting = lighting) }
            stretch(30, Lighting.DARK) + stretch(20, Lighting.COVERED) + stretch(30, Lighting.DARK) + stretch(20, Lighting.NORMAL)
        },
    )

    /** The first frame at which the person in [obstacleVanishes] starts to step aside. */
    const val VANISH_START_INDEX = 17

    /** A walk toward a person who steps out of view. */
    val obstacleVanishes = Scenario(
        name = "obstacle_vanishes",
        description = "Walking toward a person 5 m ahead who, when 3 m away, steps sideways out of view and is gone " +
            "from detection and segmentation alike. Detection runs on every frame, so the track lives on for four " +
            "missed detections and is dropped at the fifth; nothing remembers the person after that.",
        frames = (0 until 40).map { i ->
            val step = i - VANISH_START_INDEX
            val boxes = when {
                step < 0 -> listOf(personAt(0.0, 5.0))
                step < 8 -> listOf(personAt(0.2 * (step + 1), 5.0))
                else -> emptyList()
            }
            ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = minOf(i * 0.12f, 2.6f)), World(street(), boxes))
        },
    )

    /** The frames of [perceptionFaults] on which a model fails. */
    private fun faultAt(index: Int): Pair<Boolean, Boolean> = when (index) {
        10 -> true to false
        20 -> false to true
        in 30 until 59 -> (index % 2 == 0) to (index % 2 == 1)
        in 70 until 100 -> (index % 2 == 0) to (index % 2 == 1)
        else -> false to false
    }

    /** Model failures, one at a time, 29 in a row, then 30 in a row. */
    val perceptionFaults = Scenario(
        name = "perception_faults",
        description = "Walking an empty sidewalk while the segmentation fails on one frame, the detector on another, " +
            "then the two fail by turns for 29 frames in a row and recover, then for 30 frames in a row. A failed " +
            "frame gives no result, a success clears the count, and the thirtieth failure in a row ends the run.",
        frames = (0 until 105).map { i ->
            val (sem, det) = faultAt(i)
            ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.12f), World(street()), semFails = sem, detFails = det)
        },
    )

    val all = listOf(
        obstaclesCenterLeftRight,
        corridorNarrowing,
        fullBlockage,
        segmentationFlicker,
        singleFrameFalseDetection,
        droppedFrames,
        rateLimitedDetTtl,
        staleFrames,
        frozenCamera,
        coverNearWall,
        lowLightAndCover,
        obstacleVanishes,
        perceptionFaults,
    )
}
