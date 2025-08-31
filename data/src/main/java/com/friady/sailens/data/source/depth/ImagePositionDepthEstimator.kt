package com.friady.sailens.data.source.depth

import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect

/**
 * 基于图像位置的深度估计器
 * 假设：图像底部的物体更近
 */
class ImagePositionDepthEstimator {

    fun estimate(boundingBox: NormalizedRect): DistanceLevel {
        // 使用边界框底部 Y 坐标估计距离
        return DistanceLevel.fromNormalizedY(boundingBox.maxY)
    }
}