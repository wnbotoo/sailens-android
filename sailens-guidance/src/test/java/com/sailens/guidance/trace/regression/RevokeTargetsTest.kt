package com.sailens.guidance.trace.regression

import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.trace.regression.RegressionReplay.CounterfactualDelivery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a recorded revoke lands in replay: a counterfactual run must not
 * pin the device's "not delivered" on a prompt the device never made -- such as the same prompt
 * upgraded to a priority that would have interrupted the speech that blocked the original.
 */
class RevokeTargetsTest {

    private fun event(priority: EventPriority, distance: DistanceLevel = DistanceLevel.MEDIUM, key: String = "event_obstacle_left_person") =
        SceneEvent(
            timestamp = 1000, category = EventCategory.entries.first(), priority = priority, messageKey = key,
            expiresAt = 5000, dedupeKey = key, distance = distance,
        )

    private val recordedHigh = event(EventPriority.HIGH)
    private val recorded = listOf(recordedHigh.toRecord())

    @Test
    fun `exact replay revokes the event in the same position`() {
        val replayed = listOf(recordedHigh.copy(id = java.util.UUID.randomUUID()))
        assertEquals(mapOf(recordedHigh.id.toString() to replayed[0].id), RegressionReplay.revokeTargets(recorded, replayed, null))
    }

    @Test
    fun `counterfactually, an identical event takes the recorded revoke`() {
        val replayed = listOf(recordedHigh.copy(id = java.util.UUID.randomUUID()))
        assertEquals(
            mapOf(recordedHigh.id.toString() to replayed[0].id),
            RegressionReplay.revokeTargets(recorded, replayed, CounterfactualDelivery.UNCHANGED_AS_RECORDED),
        )
    }

    @Test
    fun `counterfactually, an upgraded or reworded event with the same key is not revoked`() {
        val upgraded = listOf(event(EventPriority.CRITICAL))
        val nearer = listOf(event(EventPriority.HIGH, DistanceLevel.NEAR))
        assertEquals(emptyMap<String, Any>(), RegressionReplay.revokeTargets(recorded, upgraded, CounterfactualDelivery.UNCHANGED_AS_RECORDED))
        assertEquals(emptyMap<String, Any>(), RegressionReplay.revokeTargets(recorded, nearer, CounterfactualDelivery.UNCHANGED_AS_RECORDED))
    }

    @Test
    fun `assuming every prompt delivered, no revoke applies`() {
        val replayed = listOf(recordedHigh.copy(id = java.util.UUID.randomUUID()))
        assertEquals(emptyMap<String, Any>(), RegressionReplay.revokeTargets(recorded, replayed, CounterfactualDelivery.ALL_DELIVERED))
    }

    @Test
    fun `identical events are matched one to one`() {
        // Two identical recorded events must not both land on one replayed event.
        val first = recordedHigh
        val second = recordedHigh.copy(id = java.util.UUID.randomUUID())
        val replayed = listOf(recordedHigh.copy(id = java.util.UUID.randomUUID()), recordedHigh.copy(id = java.util.UUID.randomUUID()))
        val targets = RegressionReplay.revokeTargets(listOf(first.toRecord(), second.toRecord()), replayed, CounterfactualDelivery.UNCHANGED_AS_RECORDED)
        assertEquals(setOf(replayed[0].id, replayed[1].id), targets.values.toSet())
        assertEquals(2, targets.size)
    }
}
