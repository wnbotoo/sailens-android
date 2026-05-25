package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.model.perception.SegmentationAnalysisStats
import com.friady.sailens.domain.model.perception.SegmentationMask

interface SegmentationAnalysisProcessor {
    fun analyze(
        segmentation: SegmentationMask,
        stats: SegmentationAnalysisStats? = null,
    ): SegmentationAnalysis

    fun reset()
}
