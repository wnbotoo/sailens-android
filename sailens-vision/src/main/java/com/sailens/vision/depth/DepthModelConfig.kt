package com.sailens.vision.depth

import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.runtime.ModelAcceleratorSelectionMode
import com.sailens.runtime.ResizeFilter
import com.sailens.runtime.session.GpuPrecision
import com.sailens.runtime.session.GpuQueuePriority
import com.sailens.runtime.session.GpuSettings

/**
 * How the depth model is run: choices only, no facts about the model. Its shapes and layouts come
 * from the file's metadata, its normalization is inside the model, and its resize is fixed by the
 * contract (docs/models.md, "The `depth` contract"), so a conforming model swaps in without touching
 * this. The host's runtime profile sets it.
 *
 * @param gpuPriority LOW: the depth model runs in the background, and its GPU work waits behind the
 *   semantic and obstacle models'.
 */
data class DepthModelConfig(
    val acceleratorBackend: ModelAcceleratorBackend = ModelAcceleratorBackend.GPU,
    val acceleratorSelectionMode: ModelAcceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT,
    val gpuPrecision: GpuPrecision = GpuPrecision.FP16,
    val gpuPriority: GpuQueuePriority = GpuQueuePriority.LOW,
    val resizeFilter: ResizeFilter = ResizeFilter.BILINEAR,
) {
    val gpuSettings: GpuSettings get() = GpuSettings(precision = gpuPrecision, priority = gpuPriority)
}
