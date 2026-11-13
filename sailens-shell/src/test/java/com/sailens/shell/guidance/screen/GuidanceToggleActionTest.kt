package com.sailens.shell.guidance.screen

import com.sailens.shell.app.GuidanceStartGate
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceToggleActionTest {

    @Test
    fun `a gate that gives a reason refuses the start and carries the reason`() {
        val action = guidanceToggleAction(isRunning = false, startGate = { "Finish exporting first." }, safetyNoticeConfirmed = true)

        assertEquals(GuidanceToggleAction.Refuse("Finish exporting first."), action)
    }

    @Test
    fun `no gate, or a gate with no reason, starts Guidance once the safety notice is confirmed`() {
        assertEquals(GuidanceToggleAction.Start, guidanceToggleAction(isRunning = false, startGate = null, safetyNoticeConfirmed = true))
        assertEquals(GuidanceToggleAction.Start, guidanceToggleAction(isRunning = false, startGate = { null }, safetyNoticeConfirmed = true))
    }

    @Test
    fun `a start without the current safety notice confirmed shows the notice instead`() {
        assertEquals(
            GuidanceToggleAction.ShowSafetyNotice,
            guidanceToggleAction(isRunning = false, startGate = null, safetyNoticeConfirmed = false),
        )
        assertEquals(
            GuidanceToggleAction.ShowSafetyNotice,
            guidanceToggleAction(isRunning = false, startGate = { null }, safetyNoticeConfirmed = false),
        )
    }

    @Test
    fun `a start the gate refuses is refused before the safety notice is asked for`() {
        val action = guidanceToggleAction(isRunning = false, startGate = { "Finish exporting first." }, safetyNoticeConfirmed = false)

        assertEquals(GuidanceToggleAction.Refuse("Finish exporting first."), action)
    }

    @Test
    fun `stopping is never held off and consults neither the gate nor the safety notice`() {
        var asked = 0
        val gate = GuidanceStartGate { asked++; "blocked" }

        assertEquals(GuidanceToggleAction.Stop, guidanceToggleAction(isRunning = true, startGate = gate, safetyNoticeConfirmed = true))
        assertEquals(GuidanceToggleAction.Stop, guidanceToggleAction(isRunning = true, startGate = gate, safetyNoticeConfirmed = false))
        assertEquals(0, asked)
    }
}
