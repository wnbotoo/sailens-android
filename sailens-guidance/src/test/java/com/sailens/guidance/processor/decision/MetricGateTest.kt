package com.sailens.guidance.processor.decision

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.config.MetricGateConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.DistanceEstimate
import com.sailens.guidance.depth.GroundContactEstimate
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleSource
import com.sailens.guidance.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MetricGateTest {
    private val config = DetFirstConfig(speechRank = true, proximityEscalation = true, metricGate = MetricGateConfig())
    private val generator = EventGenerator(AnalysisConfig(detFirst = config))
    private val merger = EventMerger()

    /** High in the image, so today's gates hold it back: bottom at 0.5. */
    private val farBox = NormalizedRect(x = 0.40f, y = 0.20f, width = 0.20f, height = 0.30f)

    /** Low in the image, so today's gates admit it. */
    private val nearBox = NormalizedRect(x = 0.40f, y = 0.40f, width = 0.20f, height = 0.40f)

    private fun obstacle(
        n: Int,
        lowerM: Float?,
        category: ObstacleCategory = ObstacleCategory.PERSON,
        zone: DirectionZone = DirectionZone.CENTER,
        box: NormalizedRect = farBox,
        distance: DistanceLevel = DistanceLevel.MEDIUM,
        urgency: UrgencyLevel = UrgencyLevel.HIGH,
        validity: ContactValidity = ContactValidity.VALID,
        height: HeightSource? = HeightSource.PRESET,
        source: ObstacleSource = ObstacleSource.DETECTION,
        className: String = category.name.lowercase(),
        stable: Int = 5,
        confidence: Float = 0.9f,
    ) = DetectedObstacle(
        id = UUID(0, n.toLong()),
        boundingBox = box,
        category = category,
        className = className,
        zone = zone,
        distance = distance,
        urgency = urgency,
        confidence = confidence,
        stableFrames = stable,
        areaRatio = box.area,
        timestamp = 0,
        distanceEstimate = lowerM?.let { DistanceEstimate(distance, distance, GroundContactEstimate(validity, lowerM = it, pointM = it, upperM = it), distance, height) },
        source = source,
    )

    private fun groups(vararg o: DetectedObstacle, g: EventGenerator = generator) = g.announcedObstacleGroups(o.toList())

    @Test
    fun `the table, by category and side, on its lower bound`() {
        val table = listOf(
            Triple(ObstacleCategory.VEHICLE, DirectionZone.CENTER, 5.0f),
            Triple(ObstacleCategory.VEHICLE, DirectionZone.FRONT_LEFT, 5.0f),
            Triple(ObstacleCategory.BICYCLE, DirectionZone.FRONT_RIGHT, 5.0f),
            Triple(ObstacleCategory.VEHICLE, DirectionZone.LEFT, 2.5f),
            Triple(ObstacleCategory.BICYCLE, DirectionZone.RIGHT, 2.5f),
            Triple(ObstacleCategory.PERSON, DirectionZone.CENTER, 4.0f),
            Triple(ObstacleCategory.PERSON, DirectionZone.FRONT_LEFT, 4.0f),
            Triple(ObstacleCategory.PERSON, DirectionZone.LEFT, 3.0f),
            Triple(ObstacleCategory.STATIC_OBSTACLE, DirectionZone.CENTER, 2.5f),
            Triple(ObstacleCategory.STATIC_OBSTACLE, DirectionZone.RIGHT, 2.5f),
        )
        for ((category, zone, limit) in table) {
            val at = obstacle(1, limit, category, zone)
            val over = obstacle(1, limit + 0.01f, category, zone)
            assertTrue("$category $zone at $limit", groups(at).single().metricOnly)
            assertEquals("$category $zone beyond $limit", 0, groups(over).size)
        }
        for (zone in listOf(DirectionZone.CENTER, DirectionZone.LEFT)) {
            assertEquals("a bench at 2.0", 1, groups(obstacle(1, 2.0f, ObstacleCategory.STATIC_OBSTACLE, zone, className = "bench", box = NormalizedRect(0.1f, 0.1f, 0.5f, 0.45f))).size)
            assertEquals(0, groups(obstacle(1, 2.1f, ObstacleCategory.STATIC_OBSTACLE, zone, className = "bench", box = NormalizedRect(0.1f, 0.1f, 0.5f, 0.45f))).size)
        }
    }

    @Test
    fun `without a measured distance nothing passes by it`() {
        for (o in listOf(
            obstacle(1, 2f, height = HeightSource.DEFAULT_SEED),
            obstacle(1, 2f, height = null),
            obstacle(1, 2f, validity = ContactValidity.CLIPPED),
            obstacle(1, 2f, validity = ContactValidity.NO_GEOMETRY),
            obstacle(1, 2f, source = ObstacleSource.SEMANTIC),
            obstacle(1, null),
        )) {
            assertEquals(0, groups(o).size)
        }
    }

    @Test
    fun `what today asks besides distance it still asks`() {
        assertEquals("not stable", 0, groups(obstacle(1, 2f, stable = 2)).size)
        assertEquals("not confident", 0, groups(obstacle(1, 2f, confidence = 0.5f)).size)
        val small = NormalizedRect(0.45f, 0.40f, 0.02f, 0.05f)
        assertEquals("too small ahead", 0, groups(obstacle(1, 2f, box = small)).size)
        assertEquals("too small at the side", 0, groups(obstacle(1, 2f, zone = DirectionZone.LEFT, box = small)).size)
        assertEquals("too little urgency at the side", 0, groups(obstacle(1, 2f, zone = DirectionZone.LEFT, urgency = UrgencyLevel.LOW)).size)
    }

    @Test
    fun `FAR and high in the image are all it stands in for`() {
        assertEquals(1, groups(obstacle(1, 3f, distance = DistanceLevel.FAR)).size)
        assertEquals("today holds it back", 0, EventGenerator(AnalysisConfig()).announcedObstacleGroups(listOf(obstacle(1, 3f, distance = DistanceLevel.FAR))).size)
    }

    @Test
    fun `a zone with anything today admits says what it says today`() {
        val person = obstacle(1, 1f, box = nearBox)
        val farCar = obstacle(2, 4f, ObstacleCategory.VEHICLE)
        val group = groups(person, farCar).single()
        assertFalse(group.metricOnly)
        assertEquals(listOf(person), group.obstacles)
        assertEquals(person, group.primary)
    }

    @Test
    fun `a zone with nothing today admits is made of those the measure admits, each zone by itself`() {
        val center = obstacle(1, 3f)
        val left = obstacle(2, 2.5f, zone = DirectionZone.LEFT)
        val today = obstacle(3, 1f, zone = DirectionZone.RIGHT, box = NormalizedRect(0.7f, 0.4f, 0.25f, 0.45f))
        val all = groups(center, left, today)
        assertEquals(setOf(DirectionZone.CENTER, DirectionZone.LEFT), all.filter { it.metricOnly }.map { it.zone }.toSet())
        assertEquals(listOf(DirectionZone.RIGHT), all.filterNot { it.metricOnly }.map { it.zone })
    }

    private fun events(vararg o: DetectedObstacle, g: EventGenerator = generator, t: Long = 0): List<SceneEvent> =
        merger.merge(g.announcedObstacleGroups(o.toList()).let { groups -> g.obstacleEventsFor(groups, t) })

    @Test
    fun `it writes no key, is not merged, and stands behind any prompt today admits`() {
        val merged = events(obstacle(1, 3f), obstacle(2, 2.5f, zone = DirectionZone.LEFT))
        assertEquals("not merged", 2, merged.size)
        val e = merged.first()
        assertTrue(e.isMetricOnly)
        assertEquals(emptySet<String>(), e.cooldownKeys)
        assertEquals("obstacle_metric_CENTER", e.dedupeKey)
        assertEquals(3, SpeechRanks.of(e, config))
        val ranked = e.copy(rank = 3)
        assertEquals(EventPriority.MEDIUM, ranked.speechBand)
        assertEquals(EventPriority.MEDIUM.value * 8 + 3, SpeechScale.of(ranked))
        for (band in listOf(EventPriority.MEDIUM, EventPriority.HIGH, EventPriority.CRITICAL)) {
            assertTrue(SpeechScale.of(ranked) < SpeechScale.of(band, 4))
        }
        assertTrue("above a rank 0 MEDIUM", SpeechScale.of(ranked) > SpeechScale.of(EventPriority.MEDIUM, 0))
    }

    @Test
    fun `a car far off keeps its event priority for the cooldown, and MEDIUM on the speaker`() {
        val car = events(obstacle(1, 4f, ObstacleCategory.VEHICLE)).single().let { it.copy(rank = SpeechRanks.of(it, config)) }
        assertEquals(EventPriority.CRITICAL, car.priority)
        assertEquals(11, SpeechScale.of(car).let { it - (EventPriority.MEDIUM.value - 1) * 8 })
    }

    @Test
    fun `nearMeters changes what is said of what is admitted, and never what is admitted`() {
        val withNear = EventGenerator(AnalysisConfig(detFirst = config.copy(nearMeters = 2.0f)))
        val notAdmitted = obstacle(1, 1.8f, box = NormalizedRect(0.48f, 0.3f, 0.02f, 0.05f))
        assertEquals(0, groups(notAdmitted).size)
        assertEquals(0, groups(notAdmitted, g = withNear).size)

        val admitted = obstacle(1, 1.8f, box = farBox)
        val before = groups(admitted).single()
        val after = groups(admitted, g = withNear).single()
        assertEquals(before.obstacles, after.obstacles)
        assertEquals(DistanceLevel.MEDIUM, before.nearestDistance)
        assertEquals(DistanceLevel.NEAR, after.nearestDistance)
        assertTrue(events(admitted, g = withNear).single().components.single().hazards.single().speechNear)
        assertFalse("today's stays", events(admitted, g = withNear).single().components.single().hazards.single().todayNear)
        assertEquals("beyond it, not", DistanceLevel.MEDIUM, groups(obstacle(1, 2.0f), g = withNear).single().nearestDistance)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the measure needs the rank`() {
        DetFirstConfig(metricGate = MetricGateConfig())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nearMeters needs the measure`() {
        DetFirstConfig(speechRank = true, nearMeters = 2.0f)
    }

    @Test
    fun `off, today's gates alone`() {
        val off = EventGenerator(AnalysisConfig())
        assertEquals(0, off.announcedObstacleGroups(listOf(obstacle(1, 3f))).size)
    }

    // ---- the cooldown

    private val cooldown get() = CooldownManager(proximityEscalation = true, retryInterrupted = true)

    private fun CooldownManager.passes(event: SceneEvent, t: Long) = filter(listOf(event), t).isNotEmpty()

    private fun metric(n: Int, t: Long, own: UrgencyLevel = UrgencyLevel.HIGH, category: ObstacleCategory = ObstacleCategory.PERSON, zone: DirectionZone = DirectionZone.CENTER) =
        events(obstacle(n, 2.5f, category, zone, urgency = own), t = t).single().also { assertTrue(it.isMetricOnly) }

    private fun today(n: Int, t: Long, own: UrgencyLevel = UrgencyLevel.HIGH, near: Boolean = false, zone: DirectionZone = DirectionZone.CENTER, category: ObstacleCategory = ObstacleCategory.PERSON) =
        events(obstacle(n, 1f, category, zone, urgency = own, distance = if (near) DistanceLevel.NEAR else DistanceLevel.MEDIUM, box = nearBox), t = t).single().also { assertFalse(it.isMetricOnly) }

    private fun CooldownManager.taken(event: SceneEvent, t: Long) {
        recordEvent(event, t)
        promptAccepted(event.id)
    }

    @Test
    fun `its write touches no key and no storm count`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        val state = c.canonicalState()
        assertEquals(emptyMap<String, Pair<Long, EventPriority>>(), state.records)
        assertEquals(emptyList<Long>(), state.emissions)
    }

    @Test
    fun `another hazard's prompt is not held by it, on the cooldown`() {
        val c = cooldown
        val car = metric(1, 0, category = ObstacleCategory.VEHICLE)
        c.taken(car, 0)
        assertTrue(c.passes(today(2, 1_000, category = ObstacleCategory.VEHICLE), 1_000))
        assertTrue("a side person too", c.passes(today(3, 1_000, zone = DirectionZone.LEFT, own = UrgencyLevel.MEDIUM), 1_000))
    }

    @Test
    fun `it repeats only after its window, or for a hazard not yet said, or an escalation`() {
        val c = cooldown
        c.taken(metric(1, 0), 0)
        c.confirm(metric(1, 0).id)
        assertFalse("still said", c.passes(metric(1, 1_000), 1_000))
        assertTrue("another hazard", c.passes(metric(2, 1_000), 1_000))
        assertTrue("window over", c.passes(metric(1, 6_000), 6_000))
        val withNear = EventGenerator(AnalysisConfig(detFirst = config.copy(nearMeters = 2.0f)))
        assertTrue("turning NEAR", c.passes(events(obstacle(1, 1.8f), g = withNear, t = 1_000).single(), 1_000))
    }

    @Test
    fun `a hazard said by the measure and heard is not said again for the same stage, however long it stays`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        c.confirm(m.id)
        for (t in listOf(1_000L, 8_000L, 20_000L, 60_000L)) assertFalse("t $t", c.passes(today(1, t), t))
    }

    @Test
    fun `while it is being said it holds too, and cut off and taken back it does not`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        assertFalse("still saying it", c.passes(today(1, 500), 500))
        assertEquals(CutResult.REVOKED, c.reportCut(m.id).result)
        assertTrue(c.passes(today(1, 600), 600))
    }

    @Test
    fun `cut off and kept it does not hold, until the engine says it was heard after all`() {
        val c = cooldown
        val first = metric(1, 0)
        c.taken(first, 0)
        c.reportCut(first.id)
        val second = metric(1, 1_000)
        c.taken(second, 1_000)
        assertEquals(CutResult.KEPT, c.reportCut(second.id).result)
        assertTrue(c.passes(today(1, 2_000), 2_000))
        c.reportHeardAfterCut(second.id)
        assertFalse(c.passes(today(1, 2_100), 2_100))
    }

    @Test
    fun `a real change of stage passes`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        c.confirm(m.id)
        assertTrue("entering NEAR", c.passes(today(1, 1_000, near = true), 1_000))
        assertTrue("entering CRITICAL", c.passes(today(1, 1_000, own = UrgencyLevel.CRITICAL), 1_000))
    }

    @Test
    fun `a CRITICAL car said far off stays said when today's gate reaches it, past the escalation window`() {
        val c = cooldown
        val car = metric(1, 0, category = ObstacleCategory.VEHICLE)
        c.taken(car, 0)
        c.confirm(car.id)
        assertFalse(c.passes(today(1, 10_000, category = ObstacleCategory.VEHICLE), 10_000))
    }

    @Test
    fun `a prompt saying also of a hazard the measure never said passes`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        c.confirm(m.id)
        val both = events(obstacle(1, 1f, box = nearBox), obstacle(2, 1f, box = nearBox), t = 1_000).single()
        assertTrue(c.passes(both, 1_000))
    }

    @Test
    fun `a track that left and came back is a new hazard`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        c.confirm(m.id)
        c.observeHazards(emptySet(), emptyList(), 100)
        assertEquals(emptyMap<Long, CooldownManager.MetricOnlyWrite>(), c.canonicalState().metricOnlyWrites)
        assertTrue(c.passes(today(1, 200), 200))
    }

    @Test
    fun `the highest own priority it was said at counts, and only of what was really said`() {
        val c = cooldown
        val low = metric(1, 0, own = UrgencyLevel.MEDIUM, zone = DirectionZone.LEFT)
        c.taken(low, 0)
        c.confirm(low.id)
        val high = metric(1, 7_000, own = UrgencyLevel.HIGH, zone = DirectionZone.LEFT)
        c.taken(high, 7_000)
        c.confirm(high.id)
        val todayHigh = today(1, 8_000, own = UrgencyLevel.HIGH, zone = DirectionZone.LEFT)
        assertFalse("said at HIGH", c.passes(todayHigh, 8_000))

        val other = cooldown
        val onlyLow = metric(1, 0, own = UrgencyLevel.MEDIUM, zone = DirectionZone.LEFT)
        other.taken(onlyLow, 0)
        other.confirm(onlyLow.id)
        val revokedHigh = metric(1, 7_000, own = UrgencyLevel.HIGH, zone = DirectionZone.LEFT)
        other.taken(revokedHigh, 7_000)
        other.revoke(revokedHigh.id)
        assertTrue("the HIGH one was taken back", other.passes(today(1, 8_000, own = UrgencyLevel.HIGH, zone = DirectionZone.LEFT), 8_000))
    }

    @Test
    fun `after today has said it itself, its own rhythm goes on`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        c.confirm(m.id)
        val near = today(1, 1_000, near = true)
        assertTrue(c.passes(near, 1_000))
        c.taken(near, 1_000)
        c.confirm(near.id)
        assertTrue("past the key's cooldown", c.passes(today(1, 10_000, near = true), 10_000))
    }

    @Test
    fun `a copy goes on as the original does`() {
        val c = cooldown
        val m = metric(1, 0)
        c.taken(m, 0)
        assertEquals(c.canonicalState(), c.copy().canonicalState())
        assertFalse(c.copy().passes(today(1, 500), 500))
    }
}
