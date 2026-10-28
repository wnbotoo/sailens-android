package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptDeliveryChannels.HAPTICS
import com.sailens.guidance.model.trace.PromptDeliveryChannels.SCREEN_READER
import com.sailens.guidance.model.trace.PromptDeliveryChannels.SPEECH
import com.sailens.guidance.model.trace.PromptDeliveryChannels.STATUS_CARD
import com.sailens.output.SpeechEngineState
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

    private val alert = SpeechUnavailableAlert()

    private fun alertDue(
        state: SpeechEngineState,
        speech: Boolean = true,
        screenReader: Boolean = false,
    ) = alert.update(state, speechEnabled = speech, screenReaderActive = screenReader)

    @Test
    fun `speech unavailable alert fires once on entering unavailable`() {
        assertFalse(alertDue(SpeechEngineState.INITIALIZING))
        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE))
        assertFalse("same outage", alertDue(SpeechEngineState.UNAVAILABLE))
    }

    @Test
    fun `speech unavailable alert is irrelevant when speech is disabled or screen reader owns output`() {
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, speech = false))
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, screenReader = true))
    }

    @Test
    fun `an outage that began under the screen reader is announced when own speech is back`() {
        // Review round 1: a failure under the screen reader raised no alert, and switching the
        // screen reader off left the engine state where it was, so no change ever alerted.
        assertFalse(alertDue(SpeechEngineState.READY, screenReader = true))
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, screenReader = true))

        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE, screenReader = false))
        assertFalse("still failing: announced once", alertDue(SpeechEngineState.UNAVAILABLE))
    }

    @Test
    fun `an outage that began with speech off is announced when speech is switched on`() {
        assertFalse(alertDue(SpeechEngineState.UNAVAILABLE, speech = false))
        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE, speech = true))
    }

    @Test
    fun `a recovered engine that fails again is a new outage`() {
        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE))
        assertFalse(alertDue(SpeechEngineState.READY))

        assertTrue(alertDue(SpeechEngineState.UNAVAILABLE))
    }

    @Test
    fun `replay history follows what was heard or felt, not the status card alone`() {
        assertFalse(GuidanceDeliveryResult.NOT_DELIVERED.reachedUser)
        assertFalse(GuidanceDeliveryResult(listOf(STATUS_CARD)).reachedUser)
        // Spoken by an engine that was still reported unavailable when the offer began (the first
        // prompt after an outage): the delivery decides, not that earlier state.
        assertTrue(GuidanceDeliveryResult(listOf(SPEECH)).reachedUser)
        assertTrue(GuidanceDeliveryResult(listOf(SCREEN_READER)).reachedUser)
        assertTrue(GuidanceDeliveryResult(listOf(HAPTICS)).reachedUser)
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
