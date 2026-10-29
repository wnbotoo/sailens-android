package com.sailens.guidance.processor.decision

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.evaluation.EvaluationFixtures
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The obstacle grouping moved out of `createObstacleEvents` into the pure
 * [EventGenerator.announcedObstacleGroups], which production still calls. Its events must be the
 * ones the code before the move made, bit for bit (ids aside: they are random).
 *
 * [Reference] is a frozen copy of `createObstacleEvents` as it was at `683ba08`. Random scenes go
 * through today's [EventGenerator.generate]; the obstacle events must equal the reference's on the
 * same announced obstacles, field by field and in order.
 */
class ObstacleGroupingEquivalenceTest {

    @Test
    fun `obstacle events are the ones the pre-extraction code made, on random scenes`() {
        val random = Random(20261021)
        var nonEmpty = 0
        for (config in listOf(AnalysisConfig(), AnalysisConfig(enableProximityPrefix = false))) {
            val generator = EventGenerator(config)
            repeat(4_000) {
                val obstacles = List(random.nextInt(0, 7)) { n -> randomObstacle(n + 1, random) }
                val now = random.nextLong(0, 1_000_000)
                val actual = generator.generate(EvaluationFixtures.snapshot(obstacles), now).filter { it.category == EventCategory.OBSTACLE }
                val announced = generator.announcedObstacleGroups(obstacles).flatMap { it.obstacles }.toSet()
                val expected = Reference(config).createObstacleEvents(obstacles.filter { it in announced }, now)
                assertEquals(expected.map(::withoutId), actual.map(::withoutId))
                if (actual.isNotEmpty()) nonEmpty++
            }
        }
        assertTrue("the scenes must exercise the code: $nonEmpty", nonEmpty > 1_000)
    }

    @Test
    fun `a group names its primary, its nearest level and the priority of its event`() {
        val generator = EventGenerator()
        val person = EvaluationFixtures.obstacle(1, ObstacleCategory.PERSON, distance = DistanceLevel.MEDIUM, urgency = UrgencyLevel.HIGH)
        val car = EvaluationFixtures.obstacle(2, ObstacleCategory.VEHICLE, distance = DistanceLevel.NEAR, urgency = UrgencyLevel.HIGH)

        val group = generator.announcedObstacleGroups(listOf(person, car)).single()

        assertEquals(DirectionZone.CENTER, group.zone)
        assertEquals("vehicle outranks person at equal urgency", car, group.primary)
        assertEquals(DistanceLevel.NEAR, group.nearestDistance)
        assertEquals(EventPriority.CRITICAL, group.priority)
        assertEquals(listOf(person, car), group.obstacles)
        assertEquals(EventPriority.HIGH, generator.ownObstaclePriority(person))
        assertEquals(EventPriority.CRITICAL, generator.ownObstaclePriority(car))
    }

    private fun withoutId(event: SceneEvent) = event.copy(id = ZERO)

    private fun randomObstacle(n: Int, random: Random): DetectedObstacle {
        val zone = DirectionZone.entries.random(random)
        val width = random.nextFloat() * 0.4f + 0.02f
        val height = random.nextFloat() * 0.5f + 0.02f
        val box = NormalizedRect(x = random.nextFloat() * (1f - width), y = random.nextFloat() * (1f - height), width = width, height = height)
        return EvaluationFixtures.obstacle(
            n = n,
            category = ObstacleCategory.entries.random(random),
            zone = zone,
            distance = DistanceLevel.entries.random(random),
            urgency = UrgencyLevel.entries.random(random),
            box = box,
            confidence = random.nextFloat() * 0.6f + 0.4f,
        ).let { if (random.nextInt(4) == 0) it.copy(className = listOf("bench", "chair", "potted_plant", "pole").random(random)) else it }
    }

    /** `EventGenerator.createObstacleEvents` and its helpers, frozen as of `683ba08`. Do not edit. */
    private class Reference(private val config: AnalysisConfig) {
        fun createObstacleEvents(obstacles: List<DetectedObstacle>, now: Long): List<SceneEvent> {
            val byZone = obstacles.groupBy { it.zone }
            return byZone.map { (zone, zoneObstacles) ->
                val primaryObstacle = zoneObstacles.maxWithOrNull(
                    compareBy<DetectedObstacle> { it.urgency.value }
                        .thenBy { obstacleCategoryPriority(it.category) }
                        .thenBy { it.confidence }
                        .thenBy { it.areaRatio }
                ) ?: zoneObstacles.first()
                val maxUrgency = zoneObstacles.maxOf { it.urgency }
                val priority = obstacleEventPriority(primaryObstacle, maxUrgency)
                val nearestDistance = zoneObstacles.minOf { it.distance }
                SceneEvent(
                    timestamp = now,
                    category = EventCategory.OBSTACLE,
                    priority = priority,
                    messageKey = SceneEventMessageKeys.obstacle(SceneEventMessageKeys.zonePart(zone), suffix(primaryObstacle.category)),
                    messageParams = mapOf("count" to zoneObstacles.size.toString()),
                    expiresAt = now + 3000,
                    dedupeKey = "obstacle_${zone.name}",
                    cooldownKeys = cooldownKeys(listOf(zone)),
                    relatedZones = listOf(zone),
                    distance = nearestDistance.takeIf { config.enableProximityPrefix },
                )
            }
        }

        private fun suffix(category: ObstacleCategory): String? = when (category) {
            ObstacleCategory.PERSON -> SceneEventMessageKeys.SUFFIX_PERSON
            ObstacleCategory.BICYCLE, ObstacleCategory.VEHICLE -> SceneEventMessageKeys.SUFFIX_VEHICLE
            ObstacleCategory.STATIC_OBSTACLE, ObstacleCategory.UNKNOWN -> null
        }

        private fun obstacleCategoryPriority(category: ObstacleCategory): Int = when (category) {
            ObstacleCategory.VEHICLE -> 3
            ObstacleCategory.PERSON, ObstacleCategory.BICYCLE -> 2
            ObstacleCategory.STATIC_OBSTACLE, ObstacleCategory.UNKNOWN -> 1
        }

        private fun obstacleEventPriority(obstacle: DetectedObstacle, maxUrgency: UrgencyLevel): EventPriority {
            val basePriority = when (maxUrgency) {
                UrgencyLevel.CRITICAL -> EventPriority.CRITICAL
                UrgencyLevel.HIGH -> EventPriority.HIGH
                else -> EventPriority.MEDIUM
            }
            if (obstacle.category == ObstacleCategory.VEHICLE && forward(obstacle.zone)) return EventPriority.CRITICAL
            val dynamic = obstacle.category == ObstacleCategory.PERSON || obstacle.category == ObstacleCategory.BICYCLE ||
                obstacle.category == ObstacleCategory.VEHICLE
            return if (dynamic && forward(obstacle.zone) && basePriority.value < EventPriority.HIGH.value) EventPriority.HIGH else basePriority
        }

        private fun forward(zone: DirectionZone) =
            zone == DirectionZone.CENTER || zone == DirectionZone.FRONT_LEFT || zone == DirectionZone.FRONT_RIGHT

        private fun cooldownKeys(zones: List<DirectionZone>): Set<String> {
            val keys = zones.mapTo(mutableSetOf()) { "obstacle_${it.name}" }
            if (zones.any { it == DirectionZone.CENTER }) keys.add("obstacle_PRIMARY_CENTER")
            if (zones.any { it == DirectionZone.FRONT_LEFT || it == DirectionZone.FRONT_RIGHT }) keys.add("obstacle_PRIMARY_FRONT")
            if (zones.any { it == DirectionZone.LEFT || it == DirectionZone.RIGHT }) keys.add("obstacle_PRIMARY_SIDE")
            return keys
        }
    }

    private companion object {
        val ZERO = java.util.UUID(0, 0)
    }
}
