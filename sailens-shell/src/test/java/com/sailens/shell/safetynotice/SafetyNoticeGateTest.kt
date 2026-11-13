package com.sailens.shell.safetynotice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNoticeGateTest {

    /** Kept across "launches": a new gate on the same store is the app started again. */
    private class Memory(var confirmed: Int? = null) : SafetyNoticeStore {
        var writes = 0
        override fun confirmedVersion(): Int? = confirmed
        override fun recordConfirmed(version: Int) {
            writes++
            confirmed = version
        }
    }

    @Test
    fun `on a fresh install the notice is not confirmed, nor showing`() {
        val gate = SafetyNoticeGate(Memory())

        assertFalse(gate.isConfirmed)
        assertFalse(gate.isShown.value)
    }

    @Test
    fun `its button records this version and closes it, and the next launch finds it confirmed`() {
        val memory = Memory()
        val gate = SafetyNoticeGate(memory, version = 3)
        gate.show()

        assertTrue("the start goes ahead", gate.confirm())
        assertEquals(3, memory.confirmed)
        assertFalse(gate.isShown.value)
        assertTrue(gate.isConfirmed)
        assertTrue("the app started again", SafetyNoticeGate(memory, version = 3).isConfirmed)
    }

    @Test
    fun `leaving it records nothing, so it is still not confirmed`() {
        val memory = Memory()
        val gate = SafetyNoticeGate(memory)
        gate.show()
        gate.dismiss()

        assertFalse(gate.isShown.value)
        assertFalse(gate.isConfirmed)
        assertNull(memory.confirmed)
        assertEquals(0, memory.writes)
    }

    @Test
    fun `a press that arrives after it closed starts nothing and records nothing`() {
        val memory = Memory()
        val gate = SafetyNoticeGate(memory)

        assertFalse("never shown", gate.confirm())
        assertEquals(0, memory.writes)

        gate.show()
        assertTrue(gate.confirm())
        assertFalse("the second press of a double tap", gate.confirm())
        assertEquals(1, memory.writes)
    }

    @Test
    fun `a new version is not confirmed by the old one, so it is shown again`() {
        val memory = Memory(confirmed = 1)

        assertTrue(SafetyNoticeGate(memory, version = 1).isConfirmed)
        assertFalse(SafetyNoticeGate(memory, version = 2).isConfirmed)
    }

    @Test
    fun `nor is an older version confirmed by a newer one`() {
        assertFalse(SafetyNoticeGate(Memory(confirmed = 2), version = 1).isConfirmed)
    }

    @Test
    fun `the app's own gate asks for the current version`() {
        val memory = Memory()
        val gate = SafetyNoticeGate(memory)
        gate.show()
        gate.confirm()

        assertEquals(SAFETY_NOTICE_VERSION, memory.confirmed)
    }
}
