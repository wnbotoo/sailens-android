package com.sailens.shell.guidance.safety

import com.sailens.core.log.LogService
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.output.Announcement
import com.sailens.output.SpeechEngineState
import com.sailens.shell.device.NavigationClaim
import com.sailens.shell.guidance.screen.GuidanceOutputWiring
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expected channel is the channel the real delivery carries a prompt on, under cooperating outputs (a ready
 * engine takes it, the motor always does), for every combination of the settings and `isReady`. A not-ready engine is
 * still asked to start by one `speak()`, which carries no prompt.
 */
class ExpectedNavigationChannelTest {

    private class Delivery(val channel: NavigationChannel, val speakCalls: Int)

    private fun deliver(speech: Boolean, screenReader: Boolean, haptics: Boolean, ready: Boolean): Delivery {
        val outcomes = mutableListOf<PromptOutcomeTrace>()
        var speakCalls = 0
        val wiring = GuidanceOutputWiring(
            speakOwn = { _, _, _, _ ->
                speakCalls++
                ready
            },
            settleFrame = { 0L },
            speechReady = { ready },
            stopSpeech = {},
            announcementFor = { event -> Announcement(id = event.id.toString(), key = event.messageKey, text = "text", priority = event.priority.value, expiresAtMs = event.expiresAt) },
            eventText = { "text" },
            noticeText = { "text" },
            announceToScreenReader = {},
            vibrateEvent = { true },
            vibrateEventAlone = { _, _ -> NavigationClaim.STARTED },
            playOrReserveVisionFault = { _, onPlayed -> onPlayed() },
            cancelVisionFaultReservation = {},
            cancelHaptics = {},
            descriptionHoldsTheFloor = { false },
            preemptDescription = {},
            confirmDelivered = {},
            revokeUndelivered = { _, _ -> },
            reportCut = {},
            reportHeardAfterCut = {},
            promptAccepted = { true },
            outputSettings = { GuidanceOutputSettings(speechEnabled = speech, screenReaderActive = screenReader, hapticsEnabled = haptics) },
            visionFaultSettings = { VisionFaultAlerts.Settings(speech, true) },
            logger = Silent,
            recordOutcome = { outcomes += it },
            onReachedUser = {},
        )
        wiring.protocol.onFrame(listOf(EVENT), 1, VisionFaults.NONE)
        val via = outcomes.single().deliveredVia
        val channel = when {
            PromptDeliveryChannels.SCREEN_READER in via -> NavigationChannel.SCREEN_READER
            PromptDeliveryChannels.SPEECH in via -> NavigationChannel.SPEECH
            via == listOf(PromptDeliveryChannels.HAPTICS) -> NavigationChannel.HAPTICS_ONLY
            via == listOf(PromptDeliveryChannels.STATUS_CARD) -> NavigationChannel.STATUS_CARD_ONLY
            via.isEmpty() -> NavigationChannel.NONE
            else -> error("unexpected channels $via")
        }
        return Delivery(channel, speakCalls)
    }

    @Test
    fun `for every setting and isReady, the expected channel is the one delivery carries the prompt on`() {
        var combinations = 0
        for (speech in listOf(false, true)) for (screenReader in listOf(false, true)) for (haptics in listOf(false, true)) for (ready in listOf(false, true)) {
            val actual = deliver(speech, screenReader, haptics, ready)
            val label = "speech=$speech screenReader=$screenReader haptics=$haptics isReady=$ready"
            assertEquals(label, actual.channel, expectedNavigationChannel(speech, screenReader, haptics, ready))
            if (speech && !screenReader && !ready) assertEquals("$label: one speak() asks the engine to start", 1, actual.speakCalls)
            combinations++
        }
        assertEquals(16, combinations)
    }

    @Test
    fun `an engine that failed but is ready again carries speech while the health still says unavailable`() {
        val channel = expectedNavigationChannel(speechEnabled = true, screenReaderActive = false, hapticsEnabled = true, speechReady = true)
        assertEquals(NavigationChannel.SPEECH, channel)
        assertEquals(NavigationChannel.SPEECH, deliver(speech = true, screenReader = false, haptics = true, ready = true).channel)
        assertEquals(setOf(OutputFault.SPEECH_UNAVAILABLE), outputFaults(SpeechEngineState.UNAVAILABLE, speechEnabled = true, screenReaderActive = false))
    }

    @Test
    fun `with speech off the screen reader is not used, even when it is on`() {
        for (haptics in listOf(false, true)) for (ready in listOf(false, true)) {
            val expected = if (haptics) NavigationChannel.HAPTICS_ONLY else NavigationChannel.STATUS_CARD_ONLY
            assertEquals(expected, expectedNavigationChannel(speechEnabled = false, screenReaderActive = true, hapticsEnabled = haptics, speechReady = ready))
            assertEquals(expected, deliver(speech = false, screenReader = true, haptics = haptics, ready = ready).channel)
        }
    }

    private object Silent : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        val EVENT = SceneEvent(
            timestamp = 0L,
            category = EventCategory.OBSTACLE,
            priority = EventPriority.HIGH,
            messageKey = "event_obstacle_center_vehicle",
            expiresAt = 2_000L,
            dedupeKey = "event_obstacle_center_vehicle",
        )
    }
}
