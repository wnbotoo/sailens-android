package com.sailens.shell.guidance.screen

import com.sailens.shell.app.GuidanceStartGate
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceToggleActionTest {

    @Test
    fun `a gate that gives a reason refuses the start and carries the reason`() {
        val action = guidanceToggleAction(isRunning = false, startGate = { "Finish exporting first." })

        assertEquals(GuidanceToggleAction.Refuse("Finish exporting first."), action)
    }

    @Test
    fun `no gate, or a gate with no reason, starts Guidance`() {
        assertEquals(GuidanceToggleAction.Start, guidanceToggleAction(isRunning = false, startGate = null))
        assertEquals(GuidanceToggleAction.Start, guidanceToggleAction(isRunning = false, startGate = { null }))
    }

    @Test
    fun `stopping is never held off and does not consult the gate`() {
        var asked = 0
        val gate = GuidanceStartGate { asked++; "blocked" }

        assertEquals(GuidanceToggleAction.Stop, guidanceToggleAction(isRunning = true, startGate = gate))
        assertEquals(0, asked)
    }
}
