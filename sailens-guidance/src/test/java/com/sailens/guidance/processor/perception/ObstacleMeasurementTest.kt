package com.sailens.guidance.processor.perception

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.evaluation.BirthOrderTrackIds
import com.sailens.guidance.evaluation.LeadTime
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleSourceFrame
import com.sailens.guidance.model.perception.RawObstacle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * det-first plan D1.5-2: a tracked obstacle says which frame last measured it. Running detection on a
 * frame does not make every track's evidence fresh: a track the detection missed keeps its old box
 * and distance under the current time, and so does every predicted frame. Only the measurement's
 * frame sequence, compared with the frame's own, tells -- never times, which two updates can share.
 *
 * And D1.1-3: the id source changes nothing but the ids.
 */
class ObstacleMeasurementTest {

    private val config = PerceptionConfig(trackerMinStableFrames = 1, trackerMaxMissedFrames = 5, detectionResultTtlMs = 10_000)
    private val box = NormalizedRect(x = 0.4f, y = 0.5f, width = 0.2f, height = 0.3f)
    private val raw = RawObstacle(box, ObstacleCategory.PERSON, zone = DirectionZone.CENTER, distance = DistanceLevel.MEDIUM, confidence = 0.9f, areaRatio = box.area)

    private fun frame(seq: Long) = ObstacleSourceFrame(seq, cameraNanos(seq))
    private fun cameraNanos(seq: Long) = 1_000_000_000L + seq * 33_000_000L
    private fun fresh(o: DetectedObstacle, seq: Long) = LeadTime.freshSample(o, seq, pitchDownDegrees = 15f) != null

    @Test
    fun `a matching detection stamps the track with its frame, camera time and processing time`() {
        val tracker = ObstacleTracker(config)

        val tracked = tracker.update(listOf(raw), timestamp = 5_000, frame = frame(7)).single()

        val m = checkNotNull(tracked.measurement)
        assertEquals(7L, m.frameSequence)
        assertEquals(cameraNanos(7), m.frameTimestampNanos)
        assertEquals(5_000L, m.processedAt)
        assertTrue(fresh(tracked, 7))
    }

    @Test
    fun `detection running while the target is missed for several frames in a row measures nothing new`() {
        val tracker = ObstacleTracker(config)
        tracker.update(listOf(raw), timestamp = 1_000, frame = frame(1))

        for (seq in 2L..4L) {
            val tracked = tracker.update(emptyList(), timestamp = 1_000 + seq * 33, frame = frame(seq)).single()
            // Still reported, with the old box and distance -- and the current time.
            assertEquals(1_000 + seq * 33, tracked.timestamp)
            assertEquals(1L, tracked.measurement?.frameSequence)
            assertFalse("seq $seq: detection ran but missed the target", fresh(tracked, seq))
        }
        val back = tracker.update(listOf(raw), timestamp = 1_200, frame = frame(5)).single()
        assertTrue(fresh(back, 5))
    }

    @Test
    fun `a predicted frame repeats the last measurement`() {
        val tracker = ObstacleTracker(config)
        tracker.update(listOf(raw), timestamp = 1_000, frame = frame(1))

        val predicted = tracker.predict(timestamp = 1_033).single()

        assertEquals(1L, predicted.measurement?.frameSequence)
        assertFalse(fresh(predicted, 2))
    }

    @Test
    fun `two detection updates in the same millisecond -- the second empty -- leave the unmatched track stale`() {
        val tracker = ObstacleTracker(config)
        val first = tracker.update(listOf(raw), timestamp = 2_000, frame = frame(10)).single()
        val second = tracker.update(emptyList(), timestamp = 2_000, frame = frame(11)).single()

        // Times cannot tell them apart: the stale report carries the same processing time.
        assertEquals(first.measurement?.processedAt, second.measurement?.processedAt)
        assertEquals(second.timestamp, second.measurement?.processedAt)
        assertTrue(fresh(first, 10))
        assertFalse("not matched on frame 11, whatever the clock says", fresh(second, 11))
    }

    @Test
    fun `without a source frame the tracker records no measurement and tracks as before`() {
        val tracker = ObstacleTracker(config)
        val tracked = tracker.update(listOf(raw), timestamp = 1_000).single()
        assertNull(tracked.measurement)
    }

    @Test
    fun `ids numbered by birth change nothing but the ids`() {
        val births = BirthOrderTrackIds()
        val numbered = ObstacleTracker(config, births)
        val random = ObstacleTracker(config)
        val other = raw.copy(boundingBox = NormalizedRect(x = 0.0f, y = 0.5f, width = 0.1f, height = 0.2f), zone = DirectionZone.LEFT)
        val script = listOf(listOf(raw), listOf(raw, other), listOf(other), emptyList(), listOf(raw))

        val seen = mutableSetOf<UUID>()
        script.forEachIndexed { i, detections ->
            val a = numbered.update(detections, timestamp = 1_000L + i * 50, frame = frame(i.toLong()))
            val b = random.update(detections, timestamp = 1_000L + i * 50, frame = frame(i.toLong()))
            assertEquals(b.map { it.copy(id = ZERO) }, a.map { it.copy(id = ZERO) })
            seen += a.map { it.id }
        }
        assertEquals(setOf(BirthOrderTrackIds.idOf(1), BirthOrderTrackIds.idOf(2)), seen)
        assertEquals(listOf(0L, 1L), births.births.map { it.frameSequence })
        assertEquals(other.boundingBox, births.births[1].box)
    }

    @Test
    fun `two runs with the same detections give birth alike, and a different run does not`() {
        fun run(script: List<List<RawObstacle>>) = BirthOrderTrackIds().also { ids ->
            val tracker = ObstacleTracker(config, ids)
            script.forEachIndexed { i, d -> tracker.update(d, 1_000L + i, frame(i.toLong())) }
        }
        val script = listOf(listOf(raw), emptyList(), listOf(raw))
        assertTrue(BirthOrderTrackIds.sameBirths(run(script), run(script)))
        val shifted = listOf(emptyList(), listOf(raw), listOf(raw))
        assertFalse(BirthOrderTrackIds.sameBirths(run(script), run(shifted)))
    }

    private companion object {
        val ZERO = UUID(0, 0)
    }
}
