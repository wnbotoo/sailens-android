package com.sailens.runtime.session

/**
 * GPU options a model asks for when it runs on the GPU. A session built without them (null) gets
 * exactly the options it had before they existed, which is what the semantic and obstacle models
 * run with: LiteRT's default precision, FP16 on the GPUs measured so far (docs/models.md).
 *
 * @param priority the GPU queue priority. LOW lets a background model's work wait behind the
 *   real-time models' on the same GPU.
 */
data class GpuSettings(
    val precision: GpuPrecision = GpuPrecision.DEFAULT,
    val priority: GpuQueuePriority = GpuQueuePriority.DEFAULT,
)

enum class GpuPrecision {
    DEFAULT,
    FP16,
    FP32,
}

enum class GpuQueuePriority {
    DEFAULT,
    LOW,
    NORMAL,
    HIGH,
}
