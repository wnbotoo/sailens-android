package com.sailens.runtime.session

import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.runtime.ModelAcceleratorSelectionMode
import com.google.ai.edge.litert.Accelerator
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AcceleratorSelectorTest {
    @Test
    fun `selector falls back after recoverable initialization failure`() {
        val attempts = mutableListOf<Accelerator>()

        val resolved = AcceleratorSelector.select(
            selection = AcceleratorSelection(
                mode = ModelAcceleratorSelectionMode.PREFER_BACKEND,
                preferredBackend = ModelAcceleratorBackend.NPU,
                fallbackOrder = listOf(Accelerator.GPU, Accelerator.CPU),
            ),
            logTag = "AcceleratorSelectorTest",
            modelLabel = "test model",
        ) { accelerator ->
            attempts += accelerator
            if (accelerator == Accelerator.NPU) {
                throw RuntimeException("NPU unavailable")
            }
            "runtime"
        }

        assertEquals(listOf(Accelerator.NPU, Accelerator.GPU), attempts)
        assertEquals(Accelerator.GPU, resolved.accelerator)
        assertEquals("runtime", resolved.value)
        assertTrue(resolved.selectionLabel.contains("NPU:failed"))
        assertTrue(resolved.selectionLabel.contains("GPU:active"))
    }

    @Test
    fun `selector rethrows cancellation without fallback`() {
        val attempts = mutableListOf<Accelerator>()
        val cancellation = CancellationException("cancelled")

        try {
            AcceleratorSelector.select(
                selection = AcceleratorSelection(
                    mode = ModelAcceleratorSelectionMode.PREFER_BACKEND,
                    preferredBackend = ModelAcceleratorBackend.NPU,
                    fallbackOrder = listOf(Accelerator.GPU, Accelerator.CPU),
                ),
                logTag = "AcceleratorSelectorTest",
                modelLabel = "test model",
            ) { accelerator ->
                attempts += accelerator
                throw cancellation
            }
            fail("Expected CancellationException")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }

        assertEquals(listOf(Accelerator.NPU), attempts)
    }
}
