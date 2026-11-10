package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.shell.guidance.safety.HoldCause
import com.sailens.shell.guidance.safety.PromptHoldView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status card's choice of sentence, tier by tier, before any word is looked up. */
class StatusCardContentTest {

    private val person = SceneEvent(
        timestamp = 0, category = EventCategory.OBSTACLE, priority = EventPriority.HIGH,
        messageKey = "event_obstacle_center_person", expiresAt = 3_000, dedupeKey = "obstacle_CENTER",
    )
    private val light = person.copy(priority = EventPriority.LOW, messageKey = "event_traffic_light")

    private fun card(
        error: Boolean = false,
        interrupted: Boolean = false,
        speechBroken: Boolean = false,
        loading: Boolean = false,
        running: Boolean = true,
        hold: PromptHoldView? = null,
        event: SceneEvent? = null,
    ) = statusCardContent(error, interrupted, speechBroken, loading, running, hold, event)

    @Test
    fun `without a hold the card is as it was`() {
        assertEquals(StatusCardContent(StatusTitle.Error, StatusDetail.ERROR, StatusLabel.ERROR, alert = true), card(error = true, running = false, event = person))
        assertEquals(StatusCardContent(StatusTitle.Interrupted, StatusDetail.INTERRUPTED, StatusLabel.ERROR, alert = true), card(interrupted = true))
        assertEquals(StatusCardContent(StatusTitle.SpeechUnavailable, StatusDetail.SPEECH_UNAVAILABLE, StatusLabel.ERROR, alert = true), card(speechBroken = true, event = light))
        assertEquals(StatusCardContent(StatusTitle.Starting, StatusDetail.STARTING, StatusLabel.STARTING, alert = false), card(loading = true, running = false))
        assertEquals(StatusCardContent(StatusTitle.Idle, StatusDetail.IDLE, StatusLabel.STANDBY, alert = false), card(running = false))
        assertEquals(StatusCardContent(StatusTitle.Event(person), StatusDetail.GUIDANCE, StatusLabel.LATEST_GUIDANCE, alert = true), card(event = person))
        assertEquals(StatusCardContent(StatusTitle.Event(light), StatusDetail.GUIDANCE, StatusLabel.LATEST_GUIDANCE, alert = false), card(event = light))
        assertEquals(StatusCardContent(StatusTitle.Clear, StatusDetail.CLEAR, StatusLabel.MONITORING, alert = false), card())
    }

    @Test
    fun `a stop that still holds is named by its own sentence, prompts paused`() {
        val expected = mapOf(
            HoldCause.CAMERA_OBSTRUCTED to StatusTitle.HeldFault(SceneEventMessageKeys.CAMERA_BLOCKED),
            HoldCause.TOO_DARK to StatusTitle.HeldFault(SceneEventMessageKeys.LOW_LIGHT),
            HoldCause.STARTUP_STALLED to StatusTitle.HeldStall,
            HoldCause.PERCEPTION_STALLED to StatusTitle.HeldStall,
        )
        for ((cause, title) in expected) {
            assertEquals(cause.name, StatusCardContent(title, StatusDetail.HOLD_STOPPING, StatusLabel.PAUSED, alert = true), card(hold = PromptHoldView(cause, stopping = true)))
        }
    }

    @Test
    fun `once the cause has gone the card says the view is being confirmed, back from the background too`() {
        for (cause in HoldCause.entries) {
            assertEquals(cause.name, StatusCardContent(StatusTitle.Confirming, StatusDetail.HOLD_RECOVERING, StatusLabel.PAUSED, alert = true), card(hold = PromptHoldView(cause, stopping = false)))
        }
    }

    @Test
    fun `a hold sits above the event and the clear way, below an error, an interruption and speech gone`() {
        val held = PromptHoldView(HoldCause.CAMERA_OBSTRUCTED, stopping = false)
        assertEquals("above an event the screen would not keep anyway", StatusTitle.Confirming, card(hold = held, event = person).title)
        assertEquals(StatusTitle.Error, card(error = true, hold = held).title)
        assertEquals(StatusDetail.ERROR, card(error = true, hold = held).detail)
        val interrupted = card(interrupted = true, hold = PromptHoldView(HoldCause.LIFECYCLE, stopping = true))
        assertEquals("an interruption is itself the stop", StatusCardContent(StatusTitle.Interrupted, StatusDetail.INTERRUPTED, StatusLabel.ERROR, alert = true), interrupted)
        assertEquals(StatusTitle.Idle, card(running = false, hold = held).title)
    }

    @Test
    fun `with speech gone the title is speech's and the detail still says prompts are paused`() {
        val stopping = card(speechBroken = true, hold = PromptHoldView(HoldCause.TOO_DARK, stopping = true))
        assertEquals(StatusCardContent(StatusTitle.SpeechUnavailable, StatusDetail.HOLD_STOPPING, StatusLabel.ERROR, alert = true), stopping)
        val recovering = card(speechBroken = true, hold = PromptHoldView(HoldCause.TOO_DARK, stopping = false))
        assertEquals(StatusDetail.HOLD_RECOVERING, recovering.detail)
    }

    @Test
    fun `no tier but the event's names an event`() {
        val tiers = listOf(
            card(error = true, event = person), card(interrupted = true, event = person), card(speechBroken = true, event = person),
            card(loading = true, event = person), card(running = false, event = person),
            card(hold = PromptHoldView(HoldCause.PERCEPTION_STALLED, stopping = true), event = person),
        )
        for (content in tiers) assertFalse(content.toString(), content.title is StatusTitle.Event)
        assertTrue(card(event = person).title is StatusTitle.Event)
    }
}
