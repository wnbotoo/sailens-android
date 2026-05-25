package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.model.analysis.WalkPathConnectivity
import com.friady.sailens.domain.model.perception.SegmentationAnalysis

interface ConnectivityAnalysisProcessor {
    fun analyze(analysis: SegmentationAnalysis): WalkPathConnectivity
    fun reset()
}
