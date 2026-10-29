package com.sailens.guidance.depth

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The risk-conservative fusion (docs/geometry.md §5), over its whole input table: the ground contact may raise
 * risk or keep it, never lower it, and only when enabled, calibrated beyond the seed and valid.
 */
class DistanceFusionTest {

    @Test
    fun `the fused level is never farther than today's, and changes only when everything allows it`() {
        val levels = DistanceLevel.entries
        val heights = HeightSource.entries + listOf<HeightSource?>(null)
        for (today in levels) for (contactLevel in levels + listOf<DistanceLevel?>(null)) for (validity in ContactValidity.entries) {
            for (enabled in listOf(true, false)) for (height in heights) {
                val used = DistanceFusion.fuse(today, GroundContactEstimate(validity), contactLevel, enabled, height)
                val case = "today $today contact $contactLevel $validity enabled $enabled height $height"
                assertTrue("$case gave the farther $used", used.value <= today.value)
                val allowed = enabled && validity == ContactValidity.VALID && contactLevel != null &&
                    (height == HeightSource.PRESET || height == HeightSource.CALIBRATED)
                val expected = if (allowed) DistanceFusion.nearerOf(today, contactLevel!!) else today
                assertEquals(case, expected, used)
            }
        }
    }

    @Test
    fun `nearerOf picks the nearer level`() {
        assertEquals(DistanceLevel.NEAR, DistanceFusion.nearerOf(DistanceLevel.NEAR, DistanceLevel.FAR))
        assertEquals(DistanceLevel.NEAR, DistanceFusion.nearerOf(DistanceLevel.FAR, DistanceLevel.NEAR))
        assertEquals(DistanceLevel.MEDIUM, DistanceFusion.nearerOf(DistanceLevel.MEDIUM, DistanceLevel.FAR))
    }

    @Test
    fun `levels follow the metre thresholds`() {
        val config = GroundContactDistanceConfig()
        assertEquals(DistanceLevel.NEAR, DistanceFusion.level(1.49f, config))
        assertEquals(DistanceLevel.MEDIUM, DistanceFusion.level(1.5f, config))
        assertEquals(DistanceLevel.MEDIUM, DistanceFusion.level(3.99f, config))
        assertEquals(DistanceLevel.FAR, DistanceFusion.level(4.0f, config))
    }

    @Test
    fun `an estimate cannot be built farther than today`() {
        assertThrows(IllegalArgumentException::class.java) {
            DistanceEstimate(DistanceLevel.FAR, DistanceLevel.NEAR, GroundContactEstimate(ContactValidity.VALID), DistanceLevel.FAR)
        }
    }

    private val repository = DefaultDepthRepository(ImagePositionDepthEstimator(), GroundContactDistanceEstimator(CityscapesNavigationSemantics))

    private fun geometry(source: HeightSource, pitchDeg: Double = 30.0): CameraGeometry {
        val t = Math.toRadians(pitchDeg)
        return CameraGeometry(
            frameTimeNanos = 1, timeBasis = TimeBasis.CAMERA_REALTIME,
            intrinsics = CameraIntrinsics(449.625f, 449.625f, 180f, 320f, 360, 640, IntrinsicsSource.CALIBRATION),
            gravityInCamera = Vec3(0f, cos(t).toFloat(), sin(t).toFloat()), gravityAgeNanos = 0,
            phone = PhoneGeometrySettings(1.3f, source), quality = GeometryQuality.Ok,
        )
    }

    // At 30 degrees down a contact at y = 0.70 is today MEDIUM; the ground puts it about 1.25 m away.
    private val box = NormalizedRect(0.45f, 0.42f, 0.1f, 0.28f)

    @Test
    fun `off, the contact is still computed for the trace but the level stays today's`() {
        val off = repository.estimateDistance(box, ObstacleCategory.STATIC_OBSTACLE, DistanceFrame(geometry(HeightSource.PRESET)))
        assertEquals(DistanceLevel.MEDIUM, off.today)
        assertEquals(DistanceLevel.MEDIUM, off.used)
        assertEquals(ContactValidity.VALID, off.contact.validity)
        assertEquals(DistanceLevel.NEAR, off.contactLevel)
    }

    @Test
    fun `on, a preset height raises the level and the seed alone does not`() {
        val on = GroundContactDistanceConfig(enabled = true)
        val preset = repository.estimateDistance(box, ObstacleCategory.STATIC_OBSTACLE, DistanceFrame(geometry(HeightSource.PRESET), config = on))
        assertEquals(DistanceLevel.NEAR, preset.used)
        val seed = repository.estimateDistance(box, ObstacleCategory.STATIC_OBSTACLE, DistanceFrame(geometry(HeightSource.DEFAULT_SEED), config = on))
        assertEquals(DistanceLevel.MEDIUM, seed.used)
        val noGeometry = repository.estimateDistance(box, ObstacleCategory.STATIC_OBSTACLE, DistanceFrame(null, config = on))
        assertEquals(DistanceLevel.MEDIUM, noGeometry.used)
    }

    @Test
    fun `on, a contact farther than today's level does not lower it`() {
        val on = GroundContactDistanceConfig(enabled = true)
        // At 5 degrees down the same image row is about 3.3 m away by the ground, so MEDIUM, while
        // a box at y = 0.80 is today NEAR.
        val near = NormalizedRect(0.45f, 0.5f, 0.1f, 0.3f)
        val e = repository.estimateDistance(near, ObstacleCategory.STATIC_OBSTACLE, DistanceFrame(geometry(HeightSource.PRESET, pitchDeg = 5.0), config = on))
        assertEquals(DistanceLevel.NEAR, e.today)
        assertEquals(DistanceLevel.MEDIUM, e.contactLevel)
        assertEquals(DistanceLevel.NEAR, e.used)
    }
}
