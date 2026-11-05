package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facts the safety monitor and the UI state both hold reach the monitor first: when it hears of each, the UI
 * state does not say it yet; right after, it does.
 */
class SafetyForwardingTest {

    @Test
    fun `the start request reaches the monitor before the UI state says loading`() {
        val state = MutableStateFlow(SceneAnalysisUiState())
        var loadingWhenTold: Boolean? = null

        state.startRequested { loadingWhenTold = state.value.isLoading }

        assertEquals(false, loadingWhenTold)
        assertTrue(state.value.isLoading)
    }

    @Test
    fun `the flow's start reaches the monitor before the UI state says running`() {
        val state = MutableStateFlow(SceneAnalysisUiState(isLoading = true, isInitializing = true))
        var runningWhenTold: Boolean? = null

        state.flowStarted { runningWhenTold = state.value.isRunning }

        assertEquals(false, runningWhenTold)
        assertTrue(state.value.isRunning)
        assertFalse(state.value.isLoading)
        assertFalse(state.value.isInitializing)
    }

    @Test
    fun `the user's stop reaches the monitor before the UI state stops running and clears the interruption`() {
        val event = SceneEvent(timestamp = 0L, category = EventCategory.OBSTACLE, priority = EventPriority.HIGH, messageKey = "k", expiresAt = 1L, dedupeKey = "k")
        val state = MutableStateFlow(
            SceneAnalysisUiState(isRunning = true, wasInterrupted = true, lastEvents = listOf(event), activeStatusEvent = event, lastAnnouncedEvent = event),
        )
        var whenTold: Pair<Boolean, Boolean>? = null

        state.stopped { whenTold = state.value.isRunning to state.value.wasInterrupted }

        assertEquals(true to true, whenTold)
        val after = state.value
        assertFalse(after.isRunning)
        assertFalse(after.wasInterrupted)
        assertEquals(emptyList<SceneEvent>(), after.lastEvents)
        assertNull(after.activeStatusEvent)
        assertNull(after.lastAnnouncedEvent)
    }
}
