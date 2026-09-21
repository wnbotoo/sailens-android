package com.sailens.shell.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared engines outlive any one screen.
 *
 * Before, each screen released the speech engine on its way out, so closing the Describe screen
 * released the engine Guidance was still speaking through. These pin down the replacement rule:
 * release happens when the last screen lets go, and not a moment earlier.
 */
class SharedOutputOwnershipTest {

    private var releases = 0
    private val owner = SharedEngineOwner(releaseEngines = { releases++ })

    @Test
    fun `closing the Describe screen does not release the engine Guidance still holds`() {
        val guidance = owner.acquire()
        val describe = owner.acquire()

        describe.close()

        assertEquals("Guidance is still speaking through it", 0, releases)
        assertEquals(1, owner.activeLeases)

        guidance.close()

        assertEquals(1, releases)
    }

    @Test
    fun `the engines are released once, when the last screen lets go`() {
        val describeOnly = owner.acquire()

        describeOnly.close()

        assertEquals(1, releases)
    }

    @Test
    fun `closing the same lease twice counts once`() {
        val guidance = owner.acquire()
        val describe = owner.acquire()

        describe.close()
        describe.close()

        assertEquals("a double close must not steal Guidance's claim", 0, releases)
        guidance.close()
        assertEquals(1, releases)
    }

    @Test
    fun `a screen opened after everything was released gets a fresh claim`() {
        owner.acquire().close()
        assertEquals(1, releases)

        val next = owner.acquire()
        assertEquals(1, owner.activeLeases)

        next.close()
        assertEquals(2, releases)
    }

    @Test
    fun `speech goes to the screen reader whenever one runs, whatever the speech setting`() {
        assertEquals(SpokenChannel.SCREEN_READER, SpokenChannel.of(speechEnabled = true, screenReaderActive = true))
        assertEquals(SpokenChannel.SCREEN_READER, SpokenChannel.of(speechEnabled = false, screenReaderActive = true))
    }

    @Test
    fun `without a screen reader the speech setting decides between the app's voice and silence`() {
        assertEquals(SpokenChannel.OWN_TTS, SpokenChannel.of(speechEnabled = true, screenReaderActive = false))
        assertEquals(SpokenChannel.SILENT, SpokenChannel.of(speechEnabled = false, screenReaderActive = false))
    }
}
