package com.sailens.shell.guidance.screen

import com.sailens.domain.model.common.EventCategory
import com.sailens.domain.model.common.EventPriority
import com.sailens.domain.model.scene.SceneEvent
import com.sailens.shell.device.SpeechEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAnalysisGuidanceStateTest {

    @Test
    fun `cooldown empty frame retains active status until event expires`() {
        val event = event(expiresAt = 5_000L)
        val initial = SceneAnalysisUiState(activeStatusEvent = event)

        val beforeExpiry = initial.withFrameEvents(emptyList(), now = 4_999L)
        val afterExpiry = beforeExpiry.withFrameEvents(emptyList(), now = 5_001L)

        assertSame(event, beforeExpiry.activeStatusEvent)
        assertNull(afterExpiry.activeStatusEvent)
        assertTrue(afterExpiry.lastEvents.isEmpty())
    }

    @Test
    fun `new frame event replaces active status without overwriting replay history`() {
        val previous = event(messageKey = "event_obstacle_center_person", expiresAt = 5_000L)
        val replacement = event(messageKey = "event_camera_blocked", expiresAt = 8_000L)
        val initial = SceneAnalysisUiState(
            activeStatusEvent = previous,
            lastAnnouncedEvent = previous,
        )

        val updated = initial.withFrameEvents(listOf(replacement), now = 2_000L)

        assertSame(replacement, updated.activeStatusEvent)
        assertEquals(listOf(replacement), updated.lastEvents)
        assertSame(previous, updated.lastAnnouncedEvent)
    }

    @Test
    fun `speech unavailable alert fires only on entering unavailable`() {
        assertTrue(
            shouldAlertSpeechUnavailable(
                previousEngineState = SpeechEngineState.INITIALIZING,
                newEngineState = SpeechEngineState.UNAVAILABLE,
                speechEnabled = true,
                screenReaderActive = false,
            )
        )
        assertFalse(
            shouldAlertSpeechUnavailable(
                previousEngineState = SpeechEngineState.UNAVAILABLE,
                newEngineState = SpeechEngineState.UNAVAILABLE,
                speechEnabled = true,
                screenReaderActive = false,
            )
        )
    }

    @Test
    fun `speech unavailable alert is irrelevant when speech is disabled or screen reader owns output`() {
        assertFalse(
            shouldAlertSpeechUnavailable(
                previousEngineState = SpeechEngineState.READY,
                newEngineState = SpeechEngineState.UNAVAILABLE,
                speechEnabled = false,
                screenReaderActive = false,
            )
        )
        assertFalse(
            shouldAlertSpeechUnavailable(
                previousEngineState = SpeechEngineState.READY,
                newEngineState = SpeechEngineState.UNAVAILABLE,
                speechEnabled = true,
                screenReaderActive = true,
            )
        )
    }

    @Test
    fun `replay history requires at least one usable output channel`() {
        assertFalse(
            SceneAnalysisUiState(
                isSpeechEnabled = false,
                isHapticsEnabled = false,
            ).hasGuidanceOutputChannel()
        )
        assertFalse(
            SceneAnalysisUiState(
                isSpeechEnabled = true,
                isHapticsEnabled = false,
                speechEngineState = SpeechEngineState.UNAVAILABLE,
            ).hasGuidanceOutputChannel()
        )
        assertTrue(
            SceneAnalysisUiState(
                isSpeechEnabled = true,
                isHapticsEnabled = false,
                isScreenReaderActive = true,
                speechEngineState = SpeechEngineState.UNAVAILABLE,
            ).hasGuidanceOutputChannel()
        )
        assertTrue(
            SceneAnalysisUiState(
                isSpeechEnabled = false,
                isHapticsEnabled = true,
                speechEngineState = SpeechEngineState.UNAVAILABLE,
            ).hasGuidanceOutputChannel()
        )
    }

    private fun event(
        messageKey: String = "event_obstacle_center_vehicle",
        expiresAt: Long,
    ): SceneEvent = SceneEvent(
        timestamp = 1_000L,
        category = EventCategory.OBSTACLE,
        priority = EventPriority.HIGH,
        messageKey = messageKey,
        expiresAt = expiresAt,
        dedupeKey = messageKey,
    )
}
