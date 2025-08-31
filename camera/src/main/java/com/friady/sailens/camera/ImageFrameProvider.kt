package com.friady.sailens.camera

import com.friady.sailens.domain.model.perception.ImageFrame
import kotlinx.coroutines.flow.SharedFlow

interface ImageFrameProvider {
    val frames: SharedFlow<ImageFrame>
}