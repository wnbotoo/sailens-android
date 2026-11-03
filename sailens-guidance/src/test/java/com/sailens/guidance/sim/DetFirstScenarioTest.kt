package com.sailens.guidance.sim

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.Placement
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.scene.SceneResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch combination through the real pipeline, on the scenes it is about. The goldens are its decisions: with no
 * delivery in this simulator nothing is ever confirmed or revoked, so they show what the policy offers, not what is heard.
 * Regenerate as in [ScenarioGoldenTest].
 */
class DetFirstScenarioTest {
    private val perception = PerceptionConfig.forProfile(
        profile = PerceptionProfile.DEFAULT,
        realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
    ).copy(groundContact = GroundContactDistanceConfig(enabled = true))
    private val preset = PhoneGeometrySettings(HEIGHT, HeightSource.PRESET, Placement.HANDHELD_CHEST)

    private fun run(scenario: Scenario, detFirst: DetFirstConfig = DetFirstConfig.LAUNCH): List<SceneResult> =
        GuidanceSimulator(AnalysisConfig(detFirst = detFirst), perception, { preset }).run(scenario)

    private fun check(scenario: Scenario): List<SceneResult> {
        val results = run(scenario)
        Goldens.check(scenario, "${scenario.name}.detfirst.golden.txt", results.map { GuidanceSimulator.goldenLine(it, distances = true) })
        return results
    }

    private fun List<SceneResult>.firstWith(pick: (SceneResult) -> Boolean) = indexOfFirst(pick).takeIf { it >= 0 }

    private fun List<SceneResult>.firstObstaclePrompt() = firstWith { result -> result.events.any { it.category == EventCategory.OBSTACLE } }

    @Test
    fun `a parked car is announced earlier than today`() {
        val launch = checkNotNull(check(approachParkedCar).firstObstaclePrompt())
        val today = checkNotNull(run(approachParkedCar, DetFirstConfig()).firstObstaclePrompt())
        assertTrue("launch $launch, today $today", launch < today)
    }

    @Test
    fun `a person at a steep pitch is announced earlier than today`() {
        val launch = checkNotNull(check(approachPersonSteepPitch).firstObstaclePrompt())
        val today = checkNotNull(run(approachPersonSteepPitch, DetFirstConfig()).firstObstaclePrompt())
        assertTrue("launch $launch, today $today", launch < today)
    }

    @Test
    fun `a person on a floor that does not read as ground is announced`() {
        assertNotNull(check(reflectiveFloorPerson).firstObstaclePrompt())
    }

    @Test
    fun `a car ahead is still announced after a person at the side`() {
        val results = check(sidePersonThenCenterCar)
        val person = results.firstWith { r -> r.events.any { "person" in it.messageKey } }
        val car = results.firstWith { r -> r.events.any { "vehicle" in it.messageKey } }
        assertNotNull(person)
        assertNotNull(car)
    }

    @Test
    fun `a second hazard reaching today's gates is not held back by a metric-only prompt about the first`() {
        val results = check(metricThenOtherHazard)
        val car = checkNotNull(results.firstWith { r -> r.events.any { it.isMetricOnly } })
        val person = results.firstWith { r -> r.events.any { "person" in it.messageKey } }
        assertNotNull(person)
        assertTrue("car $car, person $person", car < person!!)
    }

    @Test
    fun `a wall behind a person, both are said and neither is removed`() {
        val results = check(wallAfterPerson)
        assertNotNull(results.firstWith { r -> r.events.any { it.category == EventCategory.BLOCKED } })
        assertNotNull(results.firstObstaclePrompt())
    }

    @Test
    fun `with every switch off the goldens are today's`() {
        val off = run(Scenarios.approachParkedCar, DetFirstConfig())
        val plain = GuidanceSimulator(AnalysisConfig(), perception, { preset }).run(Scenarios.approachParkedCar)
        assertEquals(plain.map { GuidanceSimulator.goldenLine(it, distances = true) }, off.map { GuidanceSimulator.goldenLine(it, distances = true) })
    }

    private companion object {
        const val HEIGHT = 1.15f
        const val FIELD_PITCH = 17f

        val person = DetectedAs(cocoId = 0, label = "person", category = ObstacleCategory.PERSON)
        val car = DetectedAs(cocoId = 2, label = "car", category = ObstacleCategory.VEHICLE)

        fun street() = listOf(
            GroundPatch(SceneClasses.ROAD, -30.0, -1.5, -5.0, 39.9),
            GroundPatch(SceneClasses.SIDEWALK, -1.5, 1.5, -5.0, 39.9),
            GroundPatch(SceneClasses.TERRAIN, 1.5, 30.0, -5.0, 39.9),
        )

        fun personAt(x: Double, z: Double) = Box(SceneClasses.PERSON, x - 0.22, x + 0.22, z, z + 0.3, 1.7, detection = person)

        fun walk(frames: Int, stopAtM: Float, pitch: Float = FIELD_PITCH, world: (Int) -> World) = (0 until frames).map { i ->
            ScenarioFrame(Pose(HEIGHT, pitch, walkedM = minOf(i * 0.12f, stopAtM)), world(i))
        }

        val approachParkedCar = Scenario(
            name = "approach_parked_car",
            description = "Walking 1.2 m/s at 17 degrees down toward a car parked across the sidewalk 8 m ahead, stopping 0.6 m short of it.",
            frames = World(street(), listOf(Box(SceneClasses.CAR, -1.0, 1.0, 8.0, 9.8, 1.5, detection = car))).let { w -> walk(62, 7.4f) { w } },
        )

        val approachPersonSteepPitch = Scenario(
            name = "approach_person_steep_pitch",
            description = "Walking 1.2 m/s at 32 degrees down toward a person standing 6 m ahead, stopping 0.6 m short: the feet stay in view, the head leaves it.",
            frames = World(street(), listOf(personAt(0.0, 6.0))).let { w -> walk(45, 5.4f, pitch = 32f) { w } },
        )

        val reflectiveFloorPerson = Scenario(
            name = "reflective_floor_person",
            description = "A person 6 m ahead on a shining floor the segmentation reads as a wall in front of the feet: the detection must carry the prompt.",
            frames = World(
                listOf(GroundPatch(SceneClasses.SIDEWALK, -30.0, 30.0, -5.0, 2.0), GroundPatch(SceneClasses.WALL, -30.0, 30.0, 2.0, 39.9)),
                listOf(personAt(0.0, 6.0)),
            ).let { w -> walk(45, 5.4f) { w } },
        )

        val sidePersonThenCenterCar = Scenario(
            name = "side_person_then_center_car",
            description = "A person standing to the right, then a car parked ahead coming within its gate: the first must not keep the second from being said.",
            frames = World(street(), listOf(personAt(1.3, 3.0), Box(SceneClasses.CAR, -1.0, 1.0, 9.0, 10.8, 1.5, detection = car))).let { w -> walk(70, 8.4f) { w } },
        )

        val metricThenOtherHazard = Scenario(
            name = "metric_then_ygate_other_hazard",
            description = "A car ahead announced on its measured distance alone, then a person stepping into the path close by, whom today's gates admit.",
            frames = run {
                val carBox = Box(SceneClasses.CAR, -1.0, 1.0, 8.0, 9.8, 1.5, detection = car)
                val before = World(street(), listOf(carBox))
                val after = World(street(), listOf(carBox, personAt(0.1, 5.0)))
                walk(40, 3.6f) { i -> if (i < 22) before else after }
            },
        )

        val wallAfterPerson = Scenario(
            name = "wall_after_person",
            description = "A person 3 m ahead with a wall right behind them across the whole path: the wall makes the path blocked, the person is a detection, and neither prompt removes the other.",
            frames = World(street(), listOf(personAt(0.0, 3.0), Box(SceneClasses.WALL, -8.0, 8.0, 3.6, 3.8, 3.0))).let { w -> walk(28, 2.4f) { w } },
        )
    }
}
