package com.sailens.runtime.accelerator

import com.sailens.runtime.accelerator.AcceleratorCoordinator.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceleratorCoordinatorTest {

    @Test
    fun `a higher tier's interval refuses the lower tiers until it closes`() {
        val coordinator = AcceleratorCoordinator()
        assertNotNull(coordinator.tryAdmit(Priority.BACKGROUND))
        val interval = coordinator.yieldFor(Priority.INTERACTIVE)
        assertNull(coordinator.tryAdmit(Priority.BACKGROUND))
        assertTrue(coordinator.isYielding(Priority.BACKGROUND))
        assertNotNull(coordinator.tryAdmit(Priority.INTERACTIVE))
        interval.close()
        assertNotNull(coordinator.tryAdmit(Priority.BACKGROUND))
    }

    @Test
    fun `real-time work is never refused`() {
        val coordinator = AcceleratorCoordinator()
        coordinator.yieldFor(Priority.REALTIME)
        coordinator.yieldFor(Priority.INTERACTIVE)
        assertNotNull(coordinator.tryAdmit(Priority.REALTIME))
        assertNull(coordinator.tryAdmit(Priority.INTERACTIVE))
        assertNull(coordinator.tryAdmit(Priority.BACKGROUND))
    }

    @Test
    fun `intervals nest and closing one twice counts once`() {
        val coordinator = AcceleratorCoordinator()
        val outer = coordinator.yieldFor(Priority.INTERACTIVE)
        val inner = coordinator.yieldFor(Priority.INTERACTIVE)
        inner.close()
        inner.close()
        assertTrue(coordinator.isYielding(Priority.BACKGROUND))
        assertEquals(1, coordinator.state.value.openIntervals[Priority.INTERACTIVE])
        outer.close()
        assertFalse(coordinator.isYielding(Priority.BACKGROUND))
    }

    @Test
    fun `an admission is counted until closed, and closing it twice counts once`() {
        val coordinator = AcceleratorCoordinator()
        val admission = checkNotNull(coordinator.tryAdmit(Priority.BACKGROUND))
        assertEquals(1, coordinator.state.value.admitted[Priority.BACKGROUND])
        // Work admitted before an interval opens is not interrupted; it runs to its close.
        coordinator.yieldFor(Priority.INTERACTIVE)
        assertEquals(1, coordinator.state.value.admitted[Priority.BACKGROUND])
        admission.close()
        admission.close()
        assertNull(coordinator.state.value.admitted[Priority.BACKGROUND])
    }
}
