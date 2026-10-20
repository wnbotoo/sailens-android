package com.sailens.shell.guidance.settings

import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneGeometryStoreTest {

    private class Memory(var stored: PhoneGeometryChoice = PhoneGeometryChoice()) : PhoneGeometryStore.Persistence {
        override fun read() = stored
        override fun write(choice: PhoneGeometryChoice) {
            stored = choice
        }
    }

    @Test
    fun `a fresh store gives the seed, switched off`() {
        val store = PhoneGeometryStore(Memory())
        assertEquals(PhoneGeometrySettings.DEFAULT, store.current())
        assertEquals(false, store.choice.value.groundContactEnabled)
    }

    @Test
    fun `every change is persisted and survives a restart`() {
        val memory = Memory()
        val store = PhoneGeometryStore(memory)
        store.setPlacement(Placement.HANDHELD_CHEST)
        store.setBodyHeightCm(175)
        store.setGroundContactEnabled(true)

        assertEquals(PhoneGeometryChoice(Placement.HANDHELD_CHEST, 175, true), memory.stored)
        val restarted = PhoneGeometryStore(memory)
        assertEquals(PhoneGeometrySettings(1.23f, HeightSource.CALIBRATED, Placement.HANDHELD_CHEST), restarted.current())
        assertEquals(true, restarted.choice.value.groundContactEnabled)

        restarted.setPlacement(null)
        assertEquals(PhoneGeometrySettings.DEFAULT, restarted.current())
        assertEquals("the body height stays for when a placement comes back", 175, memory.stored.bodyHeightCm)
    }
}
