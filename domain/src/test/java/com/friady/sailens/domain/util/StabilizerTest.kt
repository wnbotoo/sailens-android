package com.friady.sailens.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizerTest {
    @Test
    fun `boolean stabilizer switches immediately when one frame is required`() {
        val stabilizer = BooleanStabilizer(requiredFrames = 1)

        assertTrue(stabilizer.update(true))
        assertFalse(stabilizer.update(false))
    }

    @Test
    fun `boolean stabilizer waits for configured consecutive frames`() {
        val stabilizer = BooleanStabilizer(requiredFrames = 3)

        assertFalse(stabilizer.update(true))
        assertFalse(stabilizer.update(true))
        assertTrue(stabilizer.update(true))
    }
}
