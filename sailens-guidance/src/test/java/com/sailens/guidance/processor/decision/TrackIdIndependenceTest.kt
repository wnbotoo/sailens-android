package com.sailens.guidance.processor.decision

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.DistanceEstimate
import com.sailens.guidance.depth.GroundContactEstimate
import com.sailens.guidance.evaluation.EvaluationFixtures
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/**
 * A capture holds the phone's random track ids and a replay numbers tracks by birth, so with every switch on the decisions
 * must not depend on what the id is. The sequences lean on ties, where a primary obstacle is settled by list order alone
 * and several hazards trip an escalation in the same frame: anything that orders by id or walks a hash of ids shows here.
 */
class TrackIdIndependenceTest {
    private val detFirst = DetFirstConfig.LAUNCH
    /** High in the image, so only the metric gate admits it. */
    private val farBox = NormalizedRect(x = 0.40f, y = 0.20f, width = 0.20f, height = 0.30f)

    /** Low and large, so today's gates admit it in any zone. */
    private val nearBox = NormalizedRect(x = 0.30f, y = 0.40f, width = 0.40f, height = 0.50f)

    private val sensors = object : DeviceSensorRepository {
        override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
        override val deviceRotationValue: Int = 0
        override val deviceRotationDegree: Int = 0
        override val isStationary: StateFlow<Boolean> = MutableStateFlow(false)
        override fun startObserving() = Unit
        override fun stopObserving() = Unit
    }

    private fun obstacle(id: UUID, zone: DirectionZone, category: ObstacleCategory, distance: DistanceLevel, lowerM: Float, box: NormalizedRect) = DetectedObstacle(
        id = id,
        boundingBox = box,
        category = category,
        className = category.name.lowercase(),
        zone = zone,
        distance = distance,
        urgency = UrgencyLevel.HIGH,
        confidence = 0.9f,
        stableFrames = 20,
        areaRatio = box.area,
        timestamp = 0,
        distanceEstimate = DistanceEstimate(distance, distance, GroundContactEstimate(ContactValidity.VALID, lowerM = lowerM, pointM = lowerM, upperM = lowerM), distance, HeightSource.PRESET),
    )

    /** One decision per frame over a scripted walk, with delivery outcomes from [seed], written with birth numbers for ids. */
    private fun decisions(seed: Int, idOf: (Int) -> UUID): List<String> {
        val script = Random(seed)
        val cooldown = CooldownManager(proximityEscalation = detFirst.proximityEscalation, retryInterrupted = detFirst.retryInterrupted, metricOnlyRepeatMs = detFirst.metricGate!!.metricOnlyRepeatMs)
        var now = 1_000_000L
        val decide = DecideEventsUseCase(
            EventGenerator(AnalysisConfig(detFirst = detFirst)),
            EventConflictResolver(detFirst.keepBlockedWithObstacle),
            EventMerger(),
            cooldown,
            sensors,
            clock = { now },
        )
        val birthOf = HashMap<UUID, Int>()
        val lines = ArrayList<String>()
        var births = 0
        var alive = emptyList<Int>()
        for (frame in 0 until FRAMES) {
            alive = alive.filter { script.nextInt(100) >= LEAVES_PERCENT }
            if (alive.size < 5 && script.nextInt(100) < BORN_PERCENT) {
                val twins = 1 + script.nextInt(3)
                alive = alive + List(twins) { births++ }
            }
            // Each hazard's look comes from the seed and its birth only, so twins born together are alike to the last field.
            val tied = script.nextInt(100) < TIED_PERCENT
            val distance = DistanceLevel.entries[script.nextInt(DistanceLevel.entries.size)]
            val zone = ZONES[script.nextInt(ZONES.size)]
            val obstacles = alive.map { n ->
                val id = idOf(n)
                birthOf[id] = n
                val own = Random(seed * 31 + n)
                obstacle(
                    id,
                    if (tied) zone else ZONES[own.nextInt(ZONES.size)],
                    if (tied) ObstacleCategory.PERSON else ObstacleCategory.entries[own.nextInt(ObstacleCategory.entries.size)],
                    if (tied) distance else DistanceLevel.entries[(own.nextInt(3) + frame / 40) % DistanceLevel.entries.size],
                    if (tied) 1.8f else 1.5f + own.nextInt(10) * 0.5f,
                    if (tied || own.nextBoolean()) nearBox else farBox,
                )
            }
            val events = decide(EvaluationFixtures.snapshot(obstacles))
            lines += "$frame: " + events.joinToString { event ->
                val hazards = event.components.joinToString("/") { c -> "${birthOf[c.primaryTrackId]}:" + c.hazards.map { birthOf.getValue(it.trackId) }.sorted() }
                "${event.messageKey} ${event.priority} ${event.isNear} ${event.relatedZones.sorted()} $hazards"
            }
            events.firstOrNull()?.let { primary ->
                when (script.nextInt(4)) {
                    0 -> cooldown.revoke(primary.id)
                    1 -> {
                        cooldown.promptAccepted(primary.id)
                        cooldown.reportCut(primary.id)
                    }
                    2 -> {
                        cooldown.promptAccepted(primary.id)
                        cooldown.confirm(primary.id)
                    }
                    else -> cooldown.promptAccepted(primary.id)
                }
            }
            now += 100
        }
        return lines
    }

    @Test
    fun `random ids decide exactly as ids by birth`() {
        for (seed in 0 until SEEDS) {
            val byBirth = decisions(seed) { EvaluationFixtures.id(it) }
            val random = Random(seed + 1_000)
            val ids = HashMap<Int, UUID>()
            val shuffled = decisions(seed) { n -> ids.getOrPut(n) { UUID(random.nextLong(), random.nextLong()) } }
            assertEquals("seed $seed", byBirth, shuffled)
        }
    }

    @Test
    fun `the sequences reach the ties and the escalations they are for`() {
        val all = (0 until SEEDS).flatMap { seed -> decisions(seed) { EvaluationFixtures.id(it) } }
        assertTrue("no frame with several hazards in one prompt", all.any { line -> Regex(":\\[\\d+, \\d+").containsMatchIn(line) })
        assertTrue("no NEAR prompt", all.any { " true " in it })
        assertTrue("no multi-zone prompt", all.any { "/" in it })
    }

    private companion object {
        const val SEEDS = 40
        const val FRAMES = 300
        const val LEAVES_PERCENT = 3
        const val BORN_PERCENT = 15
        const val TIED_PERCENT = 40
        val ZONES = listOf(DirectionZone.CENTER, DirectionZone.FRONT_LEFT, DirectionZone.FRONT_RIGHT, DirectionZone.LEFT, DirectionZone.RIGHT)
    }
}
