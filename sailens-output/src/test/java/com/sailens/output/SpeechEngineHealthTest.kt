package com.sailens.output

import com.sailens.output.SpeechEngineHealth.Loss
import com.sailens.output.SpeechEngineState.IDLE
import com.sailens.output.SpeechEngineState.INITIALIZING
import com.sailens.output.SpeechEngineState.READY
import com.sailens.output.SpeechEngineState.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineHealthTest {
    private val health = SpeechEngineHealth(maxRebuilds = 3)

    private fun fail(textLength: Int = 8) = health.onSubmitFailed(textLength, maxInputLength = 4000)

    @Test
    fun `a working engine reports its own state`() {
        listOf(IDLE, INITIALIZING, READY, UNAVAILABLE).forEach { assertEquals(it, health.reported(it)) }
    }

    @Test
    fun `one failed submission is a loss, reported unavailable through the rebuild`() {
        assertEquals(Loss.REBUILD, fail())

        // The rebuild's own states must not hide the loss: the UI alerts on the change to unavailable.
        assertEquals(UNAVAILABLE, health.reported(INITIALIZING))
        assertEquals(UNAVAILABLE, health.reported(READY))
    }

    @Test
    fun `the outage ends only when speech is accepted again`() {
        fail()
        assertTrue(health.onSubmitAccepted())

        assertEquals(READY, health.reported(READY))
        assertFalse("no outage left to end", health.onSubmitAccepted())
    }

    @Test
    fun `text too long for the engine is not a loss`() {
        assertEquals(Loss.NONE, health.onSubmitFailed(textLength = 4001, maxInputLength = 4000))
        assertFalse(health.outage)
        assertEquals(READY, health.reported(READY))
    }

    @Test
    fun `rebuilds stop after repeated losses with nothing accepted between`() {
        assertEquals(listOf(Loss.REBUILD, Loss.REBUILD, Loss.REBUILD, Loss.GIVE_UP), List(4) { fail() })
        assertEquals(UNAVAILABLE, health.reported(READY))
    }

    @Test
    fun `an accepted submission starts the count again`() {
        repeat(3) { fail() }
        health.onSubmitAccepted()

        assertEquals(Loss.REBUILD, fail())
    }

    @Test
    fun `release clears the outage, and an idle engine is reported idle`() {
        fail()
        assertEquals(IDLE, health.reported(IDLE))

        health.reset()

        assertFalse(health.outage)
        assertEquals(INITIALIZING, health.reported(INITIALIZING))
    }
}
