package com.sailens.guidance.trace.regression

import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.SegmentationAnalysisStats
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.EventRecord
import com.sailens.vision.detection.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class RegressionRecordMappingTest {

    private fun mask(width: Int, height: Int, on: (Int, Int) -> Boolean) =
        BinaryMask(width, height).also { m -> for (y in 0 until height) for (x in 0 until width) m.set(x, y, on(x, y)) }

    @Test
    fun `an event round-trips through its record and JSON`() {
        val event = SceneEvent(
            id = UUID.randomUUID(),
            timestamp = 1031,
            category = EventCategory.values().first(),
            priority = EventPriority.values().last(),
            messageKey = "event_obstacle_center_person",
            messageParams = mapOf("count" to "2"),
            expiresAt = 6031,
            dedupeKey = "obstacle_center",
            cooldownKeys = setOf("b", "a"),
            cooldownGroups = listOf(setOf("y", "x"), setOf("z")),
            confidence = 0.8765432f,
            severity = Severity.values().last(),
            relatedZones = DirectionZone.values().toList(),
            distance = DistanceLevel.values().first(),
            directionHint = DirectionBias.values().first(),
        )
        val json = CaptureSchema.json.encodeToString(EventRecord.serializer(), event.toRecord())
        val back = CaptureSchema.json.decodeFromString(EventRecord.serializer(), json).toSceneEvent()
        assertEquals(event, back)
        // Without the random id, two equal decisions compare equal.
        assertEquals(event.toRecord().withoutId(), event.copy(id = UUID.randomUUID()).toRecord().withoutId())
    }

    @Test
    fun `semantic stats round-trip when the rebuilt masks are the recorded ones`() {
        val passable = mask(36, 64) { x, y -> y > 40 && x in 5..30 }
        val obstacle = mask(36, 64) { x, y -> y in 20..30 && x < 10 }
        val stats = SegmentationAnalysisStats(
            passableMask = passable,
            obstacleMask = obstacle,
            roadRatio = 0.12f,
            hasTrafficLight = true,
            bottomCenterGroundDistribution = mapOf(GroundType.values().first() to 0.6f),
            bottomCenterRoadRatio = 0.3f,
            bottomStats = BottomStats(0.5f, 20, 0.55f, 60, 5, 25, 0.42f),
            passablePixelCount = passable.countTrue(),
            navigationPassableRatio = 0.4f,
            obstaclePixelCount = obstacle.countTrue(),
            classCounts = IntArray(19) { it },
        )
        val record = CaptureSchema.json.decodeFromString(
            com.sailens.guidance.trace.capture.SemanticStatsRecord.serializer(),
            CaptureSchema.json.encodeToString(com.sailens.guidance.trace.capture.SemanticStatsRecord.serializer(), stats.toRecord()),
        )
        assertEquals(stats, record.toStats(mask(36, 64) { x, y -> y > 40 && x in 5..30 }, mask(36, 64) { x, y -> y in 20..30 && x < 10 }))
    }

    @Test(expected = IllegalStateException::class)
    fun `a rebuilt mask that differs from the recorded one stops the replay`() {
        val passable = mask(8, 8) { x, _ -> x < 4 }
        val stats = SegmentationAnalysisStats(
            passable, passable, 0f, false, emptyMap(), 0f, BottomStats(0f, 0, 0f, 0, 0, 0, 0f), 0, 0f, 0, IntArray(0),
        )
        stats.toRecord().toStats(mask(8, 8) { x, _ -> x < 5 }, passable)
    }

    @Test
    fun `mask CRC depends on every pixel and on the size`() {
        val a = mask(10, 7) { x, y -> (x + y) % 3 == 0 }
        assertEquals(a.regressionCrc32(), mask(10, 7) { x, y -> (x + y) % 3 == 0 }.regressionCrc32())
        assertNotEquals(a.regressionCrc32(), mask(10, 7) { x, y -> (x + y) % 3 == 0 && !(x == 9 && y == 6) }.regressionCrc32())
        assertNotEquals(BinaryMask(10, 7).regressionCrc32(), BinaryMask(7, 10).regressionCrc32())
        assertEquals(a.regressionCrc32(), a.regressionCrc32(LongArray(64))) // with a reused buffer
    }

    @Test
    fun `connectivity stats and detections round-trip`() {
        val stats = ConnectivityStats(4, 6, 0.81f, 0.6f, -0.12f, 0.93f, 0.55f, 0.41f, DirectionBias.values().last())
        assertEquals(stats, stats.toRecord().toStats())
        assertEquals(stats.copy(suggestedBias = null), stats.copy(suggestedBias = null).toRecord().toStats())
        val detection = Detection(56, "chair", 0.92f, NormalizedRect(0.3f, 0.4f, 0.2f, 0.5f))
        assertEquals(detection, detection.toItem().toDetection())
        val obstacle = ObstacleDetection(0, "person", 0.81f, NormalizedRect(0.1f, 0.2f, 0.3f, 0.6f), ObstacleCategory.values().first())
        assertEquals(obstacle, obstacle.toItem().toObstacleDetection())
    }
}
