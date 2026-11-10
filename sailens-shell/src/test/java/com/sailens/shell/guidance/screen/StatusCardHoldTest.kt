package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.safety.DegradeReason
import com.sailens.shell.guidance.safety.GuidanceSafetyState
import com.sailens.shell.guidance.safety.OutputFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The card's current event across a hold: cleared when the hold begins, not written while it lasts, written afresh from
 * the frame that releases it. Filtering a frame's own events would not do: [withFrameEvents] keeps an unexpired older
 * event when a frame has none. Likewise across the floor's pause, for the path prompts it keeps back.
 */
class StatusCardHoldTest {

    private fun event(key: String, category: EventCategory, decidedAt: Long) = SceneEvent(
        timestamp = decidedAt, category = category, priority = EventPriority.HIGH, messageKey = key,
        expiresAt = decidedAt + 3_000, dedupeKey = key,
    )

    private val person = event("event_obstacle_center_person", EventCategory.OBSTACLE, decidedAt = 1_000)

    @Test
    fun `an event from before the stop does not come back when the hold is released`() {
        var state = SceneAnalysisUiState().withResultEvents(listOf(person), now = 1_000, holdsPrompts = false)
        assertEquals(person, state.activeStatusEvent)
        state = state.withHoldBegun()
        assertNull(state.activeStatusEvent)
        state = state.withResultEvents(emptyList(), now = 1_500, holdsPrompts = true)
        assertNull(state.activeStatusEvent)
        // Released at 2 000, the person's record still unexpired, the frame with no event: the card is clear.
        state = state.withResultEvents(emptyList(), now = 2_000, holdsPrompts = false)
        assertNull(state.activeStatusEvent)
        assertEquals("the same frames without a hold keep it", person, SceneAnalysisUiState().withResultEvents(listOf(person), 1_000, false).withResultEvents(emptyList(), 2_000, false).activeStatusEvent)
    }

    @Test
    fun `a vision fault decided during the hold is not shown after it`() {
        val covered = event(SceneEventMessageKeys.CAMERA_BLOCKED, EventCategory.SENSOR_QUALITY, decidedAt = 1_200)
        var state = SceneAnalysisUiState().withHoldBegun()
        state = state.withResultEvents(listOf(covered), now = 1_200, holdsPrompts = true)
        assertNull(state.activeStatusEvent)
        assertEquals("the debug list takes every decision", listOf(covered), state.lastEvents)
        state = state.withResultEvents(emptyList(), now = 1_800, holdsPrompts = false)
        assertNull(state.activeStatusEvent)
    }

    @Test
    fun `a path prompt from before the floor's pause is forgotten once the pause begins, and does not come back`() {
        val floor = GuidanceSafetyState.Degraded(setOf(DegradeReason.GROUND_UNRECOGNIZED), emptySet())
        for (category in listOf(EventCategory.BLOCKED, EventCategory.PATH_COMPLEX, EventCategory.NARROWING)) {
            val path = event("path_$category", category, decidedAt = 1_000)
            var state = SceneAnalysisUiState(safetyState = GuidanceSafetyState.Guiding).withResultEvents(listOf(path), now = 1_000, holdsPrompts = false)
            assertEquals(category.name, path, state.activeStatusEvent)
            // Confirmed unrecognised at 1 500, the floor's own event cooled down: the frame decides nothing.
            state = state.copy(safetyState = floor).withResultEvents(emptyList(), now = 1_500, holdsPrompts = false)
            assertNull(category.name, state.activeStatusEvent)
            // Recognised again at 2 500, the path prompt's record still unexpired: it stays forgotten.
            state = state.copy(safetyState = GuidanceSafetyState.Guiding).withResultEvents(emptyList(), now = 2_500, holdsPrompts = false)
            assertNull(category.name, state.activeStatusEvent)
        }
    }

    @Test
    fun `an obstacle's prompt stays through the floor's pause, and a degraded output forgets nothing`() {
        val floor = GuidanceSafetyState.Degraded(setOf(DegradeReason.GROUND_UNRECOGNIZED), emptySet())
        val kept = SceneAnalysisUiState(activeStatusEvent = person, safetyState = floor).withResultEvents(emptyList(), now = 1_500, holdsPrompts = false)
        assertEquals(person, kept.activeStatusEvent)
        val blocked = event(SceneEventMessageKeys.BLOCKED, EventCategory.BLOCKED, decidedAt = 1_000)
        val speechOnly = GuidanceSafetyState.Degraded(emptySet(), setOf(OutputFault.SPEECH_NOT_READY))
        assertEquals(blocked, SceneAnalysisUiState(activeStatusEvent = blocked, safetyState = speechOnly).withResultEvents(emptyList(), now = 1_500, holdsPrompts = false).activeStatusEvent)
    }

    @Test
    fun `ordinary events during the hold never reach the card, and the releasing frame's own event does`() {
        var state = SceneAnalysisUiState().withHoldBegun()
        for (at in 1_000L..1_400L step 100) {
            state = state.withResultEvents(listOf(person.copy(timestamp = at)), now = at, holdsPrompts = true)
            assertNull(state.activeStatusEvent)
        }
        val released = person.copy(timestamp = 1_500)
        state = state.withResultEvents(listOf(released), now = 1_500, holdsPrompts = false)
        assertEquals(released, state.activeStatusEvent)
    }
}
