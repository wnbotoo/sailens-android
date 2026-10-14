package com.sailens.guidance.sim

import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.trace.capture.CaptureCameraRecord
import com.sailens.guidance.trace.capture.FrameEncoding
import com.sailens.guidance.trace.capture.FrameRecord
import com.sailens.guidance.trace.capture.SensorRecord

/** One simulated frame: where the phone is, what is in front of it, and what the device reports. */
data class ScenarioFrame(
    val pose: Pose,
    val world: World,
    val stationary: Boolean = false,
    val lensCovered: Boolean = false,
)

/** The M0a raw facts a capture of one scenario frame would hold. */
data class ScenarioFacts(val camera: CaptureCameraRecord, val frame: FrameRecord, val gravity: List<SensorRecord>)

/**
 * A scripted walk through a synthetic world (M2a). [frames] are [stepMs] apart; [description] says
 * what the scenario exercises and heads its golden file.
 */
data class Scenario(
    val name: String,
    val description: String,
    val frames: List<ScenarioFrame>,
    val camera: SyntheticCamera = SyntheticCamera(),
    val stepMs: Long = 100,
) {
    fun facts(index: Int): ScenarioFacts {
        val seq = index + 1L
        val timestamp = seq * stepMs * 1_000_000
        return ScenarioFacts(
            camera = camera.cameraRecord(),
            frame = FrameRecord(
                seq = seq,
                sensorTimestampNanos = timestamp,
                receivedElapsedRealtimeNanos = timestamp,
                sourceWidth = camera.bufferWidth,
                sourceHeight = camera.bufferHeight,
                rotationDegrees = camera.sensorOrientation,
                encoding = FrameEncoding.JPEG,
                file = "frames/%06d.jpg".format(seq),
                width = camera.bufferWidth / 2,
                height = camera.bufferHeight / 2,
                sensorToBufferTransform = camera.sensorToBufferTransform(),
            ),
            gravity = gravitySamples(frames[index].pose, timestamp),
        )
    }
}

/**
 * The M2a scenarios. Each pins what today's Guidance does with a geometry it gets wrong or must
 * keep getting right; the goldens record that behaviour, they do not endorse it. M3o and later
 * milestones change these goldens on purpose, and the diff is the review.
 */
object Scenarios {
    /** Camera height and pitch of the default carry (roadmap: 1.3 m, chest height). */
    private const val HEIGHT = 1.3f
    private const val PITCH = 25f

    private val person = DetectedAs(cocoId = 0, label = "person", category = ObstacleCategory.PERSON)

    /** Road on the left, a 3 m sidewalk ahead, verge on the right. */
    private fun street(sidewalkEndZ: Double = 39.9, beyond: List<GroundPatch> = emptyList()) = listOf(
        GroundPatch(SceneClasses.ROAD, -30.0, -1.5, -5.0, sidewalkEndZ),
        GroundPatch(SceneClasses.SIDEWALK, -1.5, 1.5, -5.0, sidewalkEndZ),
        GroundPatch(SceneClasses.TERRAIN, 1.5, 30.0, -5.0, sidewalkEndZ),
    ) + beyond

    private fun personAt(x: Double, z: Double, visibleAboveM: Double? = null) = Box(
        SceneClasses.PERSON, x - 0.22, x + 0.22, z, z + 0.3, 1.7,
        detection = person.copy(visibleAboveM = visibleAboveM),
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** [count] values from [from] to [to] inclusive. */
    private fun sweep(from: Float, to: Float, count: Int) = (0 until count).map { lerp(from, to, it / (count - 1f)) }

    val straightClearPath = Scenario(
        name = "straight_clear_path",
        description = "Walking 1.2 m/s down an empty sidewalk at the default carry. The reference: nothing to say.",
        frames = (0 until 40).map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.12f), World(street())) },
    )

    val pitchChangeFixedObstacle = Scenario(
        name = "pitch_change_fixed_obstacle",
        description = "Standing 3 m from a person, tilting the phone from 10 to 45 degrees down and back. " +
            "The person does not move, so any change in distance or wording is the pitch alone.",
        frames = run {
            val world = World(street(), listOf(personAt(0.0, 3.0)))
            val pitches = sweep(10f, 45f, 30) + List(10) { 45f } + sweep(45f, 10f, 30)
            pitches.map { ScenarioFrame(Pose(HEIGHT, it), world, stationary = true) }
        },
    )

    val phoneHeightChange = Scenario(
        name = "phone_height_change",
        description = "Standing 3 m from a person at 25 degrees down, the phone raised from 0.9 m to 1.6 m " +
            "(a short and a tall user, or hip and chest carry). Only the height changes.",
        frames = run {
            val world = World(street(), listOf(personAt(0.0, 3.0)))
            (sweep(0.9f, 1.6f, 36) + List(10) { 1.6f }).map { ScenarioFrame(Pose(it, PITCH), world, stationary = true) }
        },
    )

    val groundNoGround = Scenario(
        name = "ground_no_ground",
        description = "Recognized sidewalk; an indoor floor the model reads as building; looking up so little " +
            "ground is in view; back to the sidewalk; the lens covered; uncovered again.",
        frames = run {
            val outdoor = World(street())
            val indoor = World(
                ground = listOf(GroundPatch(SceneClasses.BUILDING, -30.0, 30.0, -5.0, 39.9)),
                boxes = listOf(Box(SceneClasses.WALL, -30.0, 30.0, 8.0, 8.3, 3.0)),
            )
            List(20) { ScenarioFrame(Pose(HEIGHT, PITCH), outdoor) } +
                List(20) { ScenarioFrame(Pose(HEIGHT, PITCH), indoor) } +
                List(20) { ScenarioFrame(Pose(HEIGHT, -10f), outdoor) } +
                List(15) { ScenarioFrame(Pose(HEIGHT, PITCH), outdoor) } +
                List(15) { ScenarioFrame(Pose(HEIGHT, PITCH), outdoor, lensCovered = true) } +
                List(10) { ScenarioFrame(Pose(HEIGHT, PITCH), outdoor) }
        },
    )

    val curbAndDrop = Scenario(
        name = "curb_and_drop",
        description = "Walking toward a 15 cm curb down into the road 4 m ahead, then, from the start again, " +
            "toward a 1.5 m drop to the verge below. A drop is ground the model can still label.",
        frames = run {
            val curb = World(street(sidewalkEndZ = 4.0, beyond = listOf(GroundPatch(SceneClasses.ROAD, -30.0, 30.0, 4.0, 39.9, y = -0.15))))
            val drop = World(street(sidewalkEndZ = 4.0, beyond = listOf(GroundPatch(SceneClasses.TERRAIN, -30.0, 30.0, 4.0, 39.9, y = -1.5))))
            (0 until 40).map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.08f), curb) } +
                (0 until 40).map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.08f), drop) }
        },
    )

    val partiallyOccludedPerson = Scenario(
        name = "partially_occluded_person",
        description = "Walking toward a person behind a 1 m wall that covers the left of the path: the detector " +
            "sees only the upper body, so the box ends at the wall top. The person then steps out into the open.",
        frames = run {
            val wall = Box(SceneClasses.WALL, -1.5, 0.2, 5.0, 5.2, 1.0)
            val hidden = World(street(), listOf(wall, personAt(-0.25, 5.8, visibleAboveM = 1.0)))
            val open = World(street(), listOf(wall, personAt(0.6, 5.8)))
            (0 until 50).map { i -> ScenarioFrame(Pose(HEIGHT, PITCH, walkedM = i * 0.08f), if (i < 30) hidden else open) }
        },
    )

    val all = listOf(
        straightClearPath,
        pitchChangeFixedObstacle,
        phoneHeightChange,
        groundNoGround,
        curbAndDrop,
        partiallyOccludedPerson,
    )
}
