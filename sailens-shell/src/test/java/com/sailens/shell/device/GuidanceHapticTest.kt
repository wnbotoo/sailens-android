package com.sailens.shell.device

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With voice off, vibration is the only channel, so every symbol has to carry its meaning on its
 * own. "Path judgement is paused" must not be felt as a general notice.
 */
class GuidanceHapticTest {

    @Test
    fun `unrecognised ground vibrates as vision unreliable, not as a general notice`() {
        val event = event(EventCategory.GROUND_UNRECOGNIZED, EventPriority.LOW, SceneEventMessageKeys.GROUND_UNRECOGNIZED)

        assertEquals(GuidanceHaptic.VISION_UNRELIABLE, GuidanceHaptic.forEvent(event))
    }

    @Test
    fun `a covered or dark camera uses the same symbol`() {
        val covered = event(EventCategory.SENSOR_QUALITY, EventPriority.CRITICAL, SceneEventMessageKeys.CAMERA_BLOCKED)
        val dark = event(EventCategory.SENSOR_QUALITY, EventPriority.HIGH, SceneEventMessageKeys.LOW_LIGHT)

        assertEquals(GuidanceHaptic.VISION_UNRELIABLE, GuidanceHaptic.forEvent(covered))
        assertEquals(GuidanceHaptic.VISION_UNRELIABLE, GuidanceHaptic.forEvent(dark))
    }

    @Test
    fun `a low priority vision-unreliable event still vibrates at least at high strength`() {
        val event = event(EventCategory.GROUND_UNRECOGNIZED, EventPriority.LOW, SceneEventMessageKeys.GROUND_UNRECOGNIZED)

        assertTrue(GuidanceHaptic.amplitudeFor(event) >= GuidanceHaptic.amplitudeFor(EventPriority.HIGH))
    }

    @Test
    fun `other symbols keep their priority strength`() {
        val trafficLight = event(EventCategory.TRAFFIC_LIGHT, EventPriority.LOW, SceneEventMessageKeys.TRAFFIC_LIGHT)
        val covered = event(EventCategory.SENSOR_QUALITY, EventPriority.CRITICAL, SceneEventMessageKeys.CAMERA_BLOCKED)

        assertEquals(GuidanceHaptic.amplitudeFor(EventPriority.LOW), GuidanceHaptic.amplitudeFor(trafficLight))
        assertEquals(GuidanceHaptic.amplitudeFor(EventPriority.CRITICAL), GuidanceHaptic.amplitudeFor(covered))
    }

    private fun event(category: EventCategory, priority: EventPriority, key: String) = SceneEvent(
        timestamp = 0L,
        category = category,
        priority = priority,
        messageKey = key,
        expiresAt = 5_000L,
        dedupeKey = key,
    )
}
