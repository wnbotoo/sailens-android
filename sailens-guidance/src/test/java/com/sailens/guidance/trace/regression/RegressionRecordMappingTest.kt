package com.sailens.guidance.trace.regression

import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
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
import com.sailens.guidance.trace.capture.GeometryRecord
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

    /** Recorded captures hold CRCs from the word-at-a-time version: the bulk one must match it. */
    @Test
    fun `mask CRC is unchanged from the word-at-a-time version`() {
        val random = java.util.Random(11)
        for ((w, h) in listOf(1 to 1, 7 to 10, 64 to 1, 63 to 3, 360 to 640)) {
            val m = mask(w, h) { _, _ -> random.nextInt(3) == 0 }
            val words = m.copyPackedBits()
            val crc = java.util.zip.CRC32()
            for (v in listOf(w, h)) crc.update(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
            for (word in 0 until (w * h + 63) / 64) {
                val value = words.getOrElse(word) { 0L }
                crc.update(ByteArray(8) { b -> (value ushr (8 * b)).toByte() })
            }
            assertEquals("${w}x$h", crc.value, m.regressionCrc32())
        }
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

    @Test
    fun `a camera geometry round-trips through its record and JSON, for every quality`() {
        val k = CameraIntrinsics(449.625f, 449.6123f, 180.25f, 319.875f, 360, 640, IntrinsicsSource.FOCAL_LENGTH_ESTIMATE, bufferMappingAssumed = true)
        val geometries = listOf(
            CameraGeometry(123_456_789L, TimeBasis.CAMERA_REALTIME, k, Vec3(0.01234567f, 0.9567891f, 0.2905f), -1_500_000L,
                PhoneGeometrySettings(1.2345f, HeightSource.CALIBRATED, Placement.HANDHELD_CHEST), GeometryQuality.Ok),
            CameraGeometry(1L, TimeBasis.SOURCE_RECEIPT, k, Vec3(0f, 1f, 0f), 140_000_000L, PhoneGeometrySettings.DEFAULT,
                GeometryQuality.Degraded(setOf(GeometryIssue.STALE_GRAVITY, GeometryIssue.UNMEASURED_ALIGNMENT))),
            CameraGeometry(null, TimeBasis.SOURCE_RECEIPT, null, null, null, PhoneGeometrySettings(1.0f, HeightSource.PRESET, Placement.WAIST),
                GeometryQuality.Unavailable(setOf(GeometryIssue.NO_FRAME_TIME, GeometryIssue.NO_INTRINSICS))),
        )
        for (geometry in geometries) {
            val json = CaptureSchema.json.encodeToString(GeometryRecord.serializer(), geometry.toRecord())
            assertEquals(geometry, CaptureSchema.json.decodeFromString(GeometryRecord.serializer(), json).toGeometry())
        }
    }
}
