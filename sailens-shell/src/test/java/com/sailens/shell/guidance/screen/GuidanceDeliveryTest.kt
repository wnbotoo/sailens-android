package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.trace.PromptDeliveryChannels.HAPTICS
import com.sailens.guidance.model.trace.PromptDeliveryChannels.SCREEN_READER
import com.sailens.guidance.model.trace.PromptDeliveryChannels.SPEECH
import com.sailens.guidance.model.trace.PromptDeliveryChannels.STATUS_CARD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only a prompt that reached the user may count as delivered (DecideEventsUseCase keeps the cooldown
 * record only then), and the channels it reached are what the trace records: heard, felt or shown.
 */
class GuidanceDeliveryTest {
    private val calls = mutableListOf<String>()

    private fun deliver(
        speech: Boolean = true,
        screenReader: Boolean = false,
        ready: Boolean = true,
        haptics: Boolean = false,
        speakAccepts: Boolean = true,
        vibrateAccepts: Boolean = true,
        // The engine's readiness once speak() has returned: false when that attempt killed it.
        readyAfterSpeak: Boolean = ready,
    ): List<String> {
        var engineReady = ready
        return deliverGuidanceEvent(
            speechEnabled = speech,
            screenReaderActive = screenReader,
            speechEngineReady = { engineReady },
            hapticsEnabled = haptics,
            announceToScreenReader = { calls += "announce" },
            speak = { calls += "speak"; engineReady = readyAfterSpeak; speakAccepts },
            vibrate = { calls += "vibrate"; vibrateAccepts },
        ).channels
    }

    @Test
    fun `an engine that fails while speaking leaves the prompt to vibration`() {
        // An engine that counts as ready while every speak() fails must not drop the prompt without
        // trying the motor: then nothing is heard or felt.
        assertEquals(listOf(HAPTICS), deliver(haptics = true, speakAccepts = false, readyAfterSpeak = false))
        assertEquals(listOf("speak", "vibrate"), calls) // and the failed engine is not asked again
    }

    @Test
    fun `an engine that fails while speaking, with vibration off, reaches no one`() {
        assertEquals(emptyList<String>(), deliver(speakAccepts = false, readyAfterSpeak = false))
        assertEquals(listOf("speak"), calls)
    }

    @Test
    fun `speech-only user whose engine is still starting has not received the prompt`() {
        // Before: speak() parked it in a pending slot and returned true, so it entered cooldown
        // even though it could be replaced, expire or be cleared by a failed init unheard.
        assertEquals(emptyList<String>(), deliver(ready = false, speakAccepts = false))
        assertEquals(listOf("speak"), calls) // still asks the engine to start
    }

    @Test
    fun `engine still starting with haptics on is felt, not heard`() {
        // Same settings as a spoken delivery (speech on, haptics on); only the channels differ.
        assertEquals(listOf(HAPTICS), deliver(ready = false, haptics = true, speakAccepts = false))
    }

    @Test
    fun `engine still starting and the motor refusing reaches no one`() {
        assertEquals(
            emptyList<String>(),
            deliver(ready = false, haptics = true, speakAccepts = false, vibrateAccepts = false),
        )
    }

    @Test
    fun `speech outranked by a prompt still speaking is not delivered, and does not vibrate alone`() {
        assertEquals(emptyList<String>(), deliver(haptics = true, speakAccepts = false))
        assertEquals(listOf("speak"), calls)
    }

    @Test
    fun `spoken prompts are heard and felt`() {
        assertEquals(listOf(SPEECH, HAPTICS), deliver(haptics = true))
        assertEquals(listOf("speak", "vibrate"), calls)
    }

    @Test
    fun `spoken prompt whose vibration the motor refused was heard only`() {
        assertEquals(listOf(SPEECH), deliver(haptics = true, vibrateAccepts = false))
    }

    @Test
    fun `the screen reader always accepts`() {
        assertEquals(listOf(SCREEN_READER), deliver(screenReader = true, ready = false))
        assertEquals(listOf("announce"), calls)
    }

    @Test
    fun `the screen reader with haptics lists the vibration only when it played`() {
        assertEquals(listOf(SCREEN_READER, HAPTICS), deliver(screenReader = true, haptics = true))
        assertEquals(
            listOf(SCREEN_READER),
            deliver(screenReader = true, haptics = true, vibrateAccepts = false),
        )
    }

    @Test
    fun `haptics-only delivery depends on the motor accepting`() {
        assertEquals(listOf(HAPTICS), deliver(speech = false, haptics = true))
        assertEquals(emptyList<String>(), deliver(speech = false, haptics = true, vibrateAccepts = false))
    }

    @Test
    fun `with every output off the status card is the chosen channel`() {
        assertEquals(listOf(STATUS_CARD), deliver(speech = false, haptics = false))
        assertTrue(calls.isEmpty())
    }
}
