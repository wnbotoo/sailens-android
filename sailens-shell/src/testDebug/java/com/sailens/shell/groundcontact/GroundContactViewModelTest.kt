package com.sailens.shell.groundcontact

import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.shell.guidance.settings.PhoneGeometryChoice
import com.sailens.shell.guidance.settings.PhoneGeometryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundContactViewModelTest {

    private class Memory(var stored: PhoneGeometryChoice = PhoneGeometryChoice()) : PhoneGeometryStore.Persistence {
        override fun read() = stored
        override fun write(choice: PhoneGeometryChoice) {
            stored = choice
        }
    }

    private val memory = Memory()
    private val store = PhoneGeometryStore(memory)
    private val profiles = PerceptionProfileManager(PerceptionConfig())
    private val viewModel = GroundContactViewModel(store, profiles)

    @Test
    fun `the switch is remembered and reaches Guidance at its next session, not the running one`() {
        viewModel.setEnabled(true)
        assertTrue(memory.stored.groundContactEnabled)
        assertTrue(profiles.selectedGroundContact)
        assertFalse(profiles.activeConfig.groundContact.enabled)
        assertTrue(profiles.activateSelected().groundContact.enabled)

        viewModel.setEnabled(false)
        assertFalse(memory.stored.groundContactEnabled)
        assertFalse(profiles.activateSelected().groundContact.enabled)
    }

    @Test
    fun `a body height is saved only when it is one, and blank clears it`() {
        assertTrue(viewModel.saveBodyHeight(" 172 "))
        assertEquals(172, memory.stored.bodyHeightCm)
        assertFalse(viewModel.saveBodyHeight("17"))
        assertFalse(viewModel.saveBodyHeight("250"))
        assertFalse(viewModel.saveBodyHeight("abc"))
        assertEquals("invalid input leaves the saved height", 172, memory.stored.bodyHeightCm)
        assertTrue(viewModel.saveBodyHeight(""))
        assertNull(memory.stored.bodyHeightCm)
    }
}
