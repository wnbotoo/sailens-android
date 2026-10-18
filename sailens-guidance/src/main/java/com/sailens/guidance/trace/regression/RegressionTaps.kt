package com.sailens.guidance.trace.regression

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.processor.analysis.ConnectivityStatsExtractor

/**
 * [clock], reporting every value it returns to the recorder as [component]
 * (`RegressionClocks`). The plain clock when nothing can record.
 */
fun RegressionRecorder.recordingClock(component: String, clock: () -> Long): () -> Long {
    if (this === RegressionRecorder.None) return clock
    return { clock().also { if (isRecording) onClock(component, it) } }
}

/** Reports each connectivity call -- its input and the delegate's answer -- to the recorder. */
class RecordingConnectivityStatsExtractor(
    private val delegate: ConnectivityStatsExtractor,
    private val recorder: RegressionRecorder,
) : ConnectivityStatsExtractor {
    override fun extract(passableMask: BinaryMask): ConnectivityStats? {
        val stats = delegate.extract(passableMask)
        if (recorder.isRecording) recorder.onConnectivity(passableMask, stats)
        return stats
    }
}
