package com.sailens.guidance.evaluation

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.evaluation.StageCredit.Credit
import com.sailens.guidance.evaluation.StageCredit.Role
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * det-first plan D1.1-4: a prompt's stage credit goes only to the hazards its words are true of;
 * ambiguous credit is listed apart and never counts. Prompts come from the real generator and merger.
 */
class StageCreditTest {

    private val generator = EventGenerator()
    private val o = EvaluationFixtures

    private fun creditsOfOffered(vararg obstacles: com.sailens.guidance.model.perception.DetectedObstacle): List<Credit> {
        val snapshot = o.snapshot(obstacles.toList())
        val merged = EventMerger().merge(EventConflictResolver().resolve(generator.generate(snapshot, 1_000)))
        val offered = merged.first { it.category == EventCategory.OBSTACLE }
        return StageCredit.credits(StageCredit.spoken(offered), generator.announcedObstacleGroups(snapshot.obstacles), generator)
    }

    private fun roles(credits: List<Credit>, stage: Stage) = credits.filter { it.stage == stage }.associate { it.trackId to it.role }

    @Test
    fun `the first prompt goes to the primary, same-word tracks are covered, others are ambiguous`() {
        val person = o.obstacle(1, ObstacleCategory.PERSON, urgency = UrgencyLevel.HIGH)
        val otherPerson = o.obstacle(2, ObstacleCategory.PERSON, urgency = UrgencyLevel.MEDIUM, confidence = 0.8f)
        val chair = o.obstacle(3, ObstacleCategory.STATIC_OBSTACLE, urgency = UrgencyLevel.MEDIUM, box = NormalizedRect(0.4f, 0.6f, 0.3f, 0.35f))

        val first = roles(creditsOfOffered(person, otherPerson, chair), Stage.FIRST)

        assertEquals(Role.PRIMARY, first[o.id(1)])
        assertEquals(Role.COVERED, first[o.id(2)])
        assertEquals("'a person ahead' says nothing true of the chair", Role.AMBIGUOUS, first[o.id(3)])
    }

    @Test
    fun `a merge that drops the category word makes even the primary ambiguous`() {
        val person = o.obstacle(1, ObstacleCategory.PERSON, zone = DirectionZone.CENTER, urgency = UrgencyLevel.HIGH)
        val leftChair = o.obstacle(
            2, ObstacleCategory.STATIC_OBSTACLE, zone = DirectionZone.LEFT, urgency = UrgencyLevel.HIGH,
            box = NormalizedRect(0.02f, 0.5f, 0.25f, 0.45f),
        )

        val credits = creditsOfOffered(person, leftChair)

        assertEquals(Role.AMBIGUOUS, roles(credits, Stage.FIRST)[o.id(1)])
        assertEquals("the generic word is true of a static obstacle", Role.PRIMARY, roles(credits, Stage.FIRST)[o.id(2)])
    }

    @Test
    fun `NEAR goes only to a NEAR track the word fits, or under the generic word`() {
        val nearPerson = o.obstacle(1, ObstacleCategory.PERSON, distance = DistanceLevel.NEAR, urgency = UrgencyLevel.CRITICAL)
        val nearChair = o.obstacle(2, ObstacleCategory.STATIC_OBSTACLE, distance = DistanceLevel.NEAR, urgency = UrgencyLevel.HIGH, confidence = 0.8f)
        val mediumPerson = o.obstacle(3, ObstacleCategory.PERSON, distance = DistanceLevel.MEDIUM, urgency = UrgencyLevel.HIGH, confidence = 0.7f)

        val near = roles(creditsOfOffered(nearPerson, nearChair, mediumPerson), Stage.NEAR)

        assertEquals(Role.CREDITED, near[o.id(1)])
        assertEquals("'careful, a person' does not say the chair is near", Role.AMBIGUOUS, near[o.id(2)])
        assertTrue("not NEAR on this frame", o.id(3) !in near)
    }

    @Test
    fun `CRITICAL goes only to components whose own priority is CRITICAL`() {
        val car = o.obstacle(1, ObstacleCategory.VEHICLE, urgency = UrgencyLevel.MEDIUM)
        val person = o.obstacle(2, ObstacleCategory.PERSON, urgency = UrgencyLevel.MEDIUM, confidence = 0.7f)

        val credits = creditsOfOffered(car, person)

        assertEquals(mapOf(o.id(1) to Role.CREDITED), roles(credits, Stage.CRITICAL))
    }

    @Test
    fun `a HIGH prompt credits no CRITICAL stage, whatever its components`() {
        val person = o.obstacle(1, ObstacleCategory.PERSON, urgency = UrgencyLevel.HIGH)
        assertTrue(roles(creditsOfOffered(person), Stage.CRITICAL).isEmpty())
    }

    @Test
    fun `credit counts only for the zones the prompt speaks of`() {
        val center = o.obstacle(1, ObstacleCategory.PERSON, zone = DirectionZone.CENTER)
        val left = o.obstacle(2, ObstacleCategory.PERSON, zone = DirectionZone.LEFT, box = NormalizedRect(0.02f, 0.5f, 0.2f, 0.4f))
        val groups = generator.announcedObstacleGroups(listOf(center, left))
        val spoken = StageCredit.Spoken(setOf(DirectionZone.CENTER), "person", near = false, priority = groups.first().priority)

        assertEquals(setOf(o.id(1)), StageCredit.credits(spoken, groups, generator).map { it.trackId }.toSet())
    }
}
