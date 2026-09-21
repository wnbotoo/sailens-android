package com.sailens.guidance.processor.perception

import com.sailens.guidance.model.perception.SegmentationAnalysis
import com.sailens.guidance.model.perception.SegmentationAnalysisStats
import com.sailens.guidance.model.perception.SegmentationMask

interface SegmentationAnalysisProcessor {
    fun analyze(
        segmentation: SegmentationMask,
        stats: SegmentationAnalysisStats? = null,
    ): SegmentationAnalysis

    fun reset()
}
