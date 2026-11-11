package com.sailens.shell.trace

import com.sailens.guidance.model.trace.DepthRecordCompleteness
import com.sailens.guidance.model.trace.DepthRunReport
import com.sailens.guidance.model.trace.TimingStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** The replay report's `depthMs` line names every timing the device budget reads, the output read and its sum included. */
class DepthReportTextTest {

    @Test
    fun `the timings line shows inference, the output read and their per-run sum`() {
        val report = DepthRunReport(
            completeness = DepthRecordCompleteness.COMPLETE,
            prepareMs = TimingStats(3, 6, 7, 8),
            inferMs = TimingStats(3, 30, 31, 32),
            outputReadMs = TimingStats(3, 1, 100, 100),
            gpuMs = TimingStats(3, 31, 130, 130),
            fitMs = TimingStats(3, 9, 10, 11),
        )
        assertEquals(
            "prepare:6/7/8(3) admitWait:- infer:30/31/32(3) read:1/100/100(3) gpu:31/130/130(3) fit:9/10/11(3) age:-",
            report.timingsText(),
        )
    }
}
