package com.sailens.shell.guidance.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only a prompt that reached the user may count as delivered: DecideEventsUseCase keeps the
 * cooldown record only when this says true.
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
    ) = deliverGuidanceEvent(
        speechEnabled = speech,
        screenReaderActive = screenReader,
        speechEngineReady = ready,
        hapticsEnabled = haptics,
        announceToScreenReader = { calls += "announce" },
        speak = { calls += "speak"; speakAccepts },
        vibrate = { calls += "vibrate"; vibrateAccepts },
    )

    @Test
    fun `speech-only user whose engine is still starting has not received the prompt`() {
        // Before: speak() parked it in a pending slot and returned true, so it entered cooldown
        // even though it could be replaced, expire or be cleared by a failed init unheard.
        assertFalse(deliver(ready = false, speakAccepts = false))
        assertEquals(listOf("speak"), calls) // still asks the engine to start
    }

    @Test
    fun `while the engine is starting, vibration is the channel that remains`() {
        assertTrue(deliver(ready = false, haptics = true, speakAccepts = false))
        assertFalse(deliver(ready = false, haptics = true, speakAccepts = false, vibrateAccepts = false))
    }

    @Test
    fun `speech outranked by a prompt still speaking is not delivered, and does not vibrate alone`() {
        assertFalse(deliver(haptics = true, speakAccepts = false))
        assertEquals(listOf("speak"), calls)
    }

    @Test
    fun `spoken prompts are delivered and vibration accompanies them`() {
        assertTrue(deliver(haptics = true))
        assertEquals(listOf("speak", "vibrate"), calls)
    }

    @Test
    fun `the screen reader always accepts`() {
        assertTrue(deliver(screenReader = true, ready = false))
        assertEquals(listOf("announce"), calls)
    }

    @Test
    fun `haptics-only delivery depends on the motor accepting`() {
        assertTrue(deliver(speech = false, haptics = true))
        assertFalse(deliver(speech = false, haptics = true, vibrateAccepts = false))
    }

    @Test
    fun `with every output off the status card is the chosen channel`() {
        assertTrue(deliver(speech = false, haptics = false))
        assertTrue(calls.isEmpty())
    }
}
