package com.sailens.shell.guidance.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Q11a: with voice off, fault alert vibration is locked on; the user's own choice is kept for later. */
class GuidanceFeedbackSettingsTest {

    @Test
    fun `fault vibration is in effect when chosen, and always while speech is off`() {
        val cases = listOf(
            // speechEnabled, faultHapticsEnabled -> locked, in effect
            Triple(true, true, false to true),
            Triple(true, false, false to false),
            Triple(false, true, true to true),
            Triple(false, false, true to true),
        )
        for ((speech, fault, expected) in cases) {
            val settings = GuidanceFeedbackSettings(speechEnabled = speech, faultHapticsEnabled = fault)
            assertEquals("speech $speech, fault $fault", expected, settings.faultHapticsLocked to settings.faultHapticsInEffect)
        }
    }

    @Test
    fun `turning speech back on restores the user's own choice`() {
        val off = GuidanceFeedbackSettings(speechEnabled = false, faultHapticsEnabled = false)
        assertEquals(true, off.faultHapticsInEffect)
        assertEquals(false, off.copy(speechEnabled = true).faultHapticsInEffect)
    }

    @Test
    fun `fault vibration defaults on`() {
        assertEquals(true, GuidanceFeedbackSettings().faultHapticsEnabled)
    }
}
