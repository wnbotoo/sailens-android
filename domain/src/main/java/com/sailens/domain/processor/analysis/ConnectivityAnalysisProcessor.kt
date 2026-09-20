package com.sailens.domain.processor.analysis

import com.sailens.domain.model.analysis.WalkPathConnectivity
import com.sailens.core.mask.BinaryMask
import com.sailens.domain.model.perception.SegmentationAnalysis

interface ConnectivityAnalysisProcessor {
    fun analyze(analysis: SegmentationAnalysis): WalkPathConnectivity

    /** 直接对（可能已扣除障碍物遮挡的）可行走 mask 做连通性分析。 */
    fun analyze(passableMask: BinaryMask): WalkPathConnectivity

    fun reset()
}
