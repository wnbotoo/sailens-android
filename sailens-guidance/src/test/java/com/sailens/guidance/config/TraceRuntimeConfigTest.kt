package com.sailens.guidance.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceRuntimeConfigTest {
    @Test
    fun `disabled tracing records no frames`() {
        val config = TraceRuntimeConfig(
            enabled = false,
            sampleEveryNFrames = 2,
        )

        assertFalse(config.shouldRecordFrame(0L))
        assertFalse(config.shouldRecordFrame(2L))
    }

    @Test
    fun `enabled tracing samples by sequence number`() {
        val config = TraceRuntimeConfig(
            enabled = true,
            sampleEveryNFrames = 3,
        )

        assertTrue(config.shouldRecordFrame(0L))
        assertFalse(config.shouldRecordFrame(1L))
        assertFalse(config.shouldRecordFrame(2L))
        assertTrue(config.shouldRecordFrame(3L))
    }

    @Test
    fun `a frame that offered a prompt is kept whatever the sampling`() {
        val config = TraceRuntimeConfig(enabled = true, sampleEveryNFrames = 3)

        assertFalse(config.shouldRecordFrame(10L))
        assertTrue(config.shouldRecordFrame(10L, offeredPrompt = true))
    }

    @Test
    fun `disabled tracing records nothing even for a prompt`() {
        val config = TraceRuntimeConfig(enabled = false)

        assertFalse(config.shouldRecordFrame(10L, offeredPrompt = true))
    }
}
