package com.sailens.runtime

import com.google.ai.edge.litert.Accelerator

/**
 * Resolves the physical model source for one logical model and one actual accelerator attempt.
 *
 * Keep this injectable so a later AI Pack / download implementation can return [ModelSource.File]
 * without changing semantic or obstacle providers.
 */
interface ModelSourceResolver {
    fun source(type: ModelType, accelerator: Accelerator): ModelSource
}

object CatalogModelSourceResolver : ModelSourceResolver {
    override fun source(type: ModelType, accelerator: Accelerator): ModelSource =
        ModelCatalog.source(type, accelerator.toModelAcceleratorBackend())
}

internal fun Accelerator.toModelAcceleratorBackend(): ModelAcceleratorBackend = when (this) {
    Accelerator.NPU -> ModelAcceleratorBackend.NPU
    Accelerator.GPU -> ModelAcceleratorBackend.GPU
    Accelerator.CPU -> ModelAcceleratorBackend.CPU
    else -> error("Unsupported LiteRT accelerator for model source resolution: $this")
}
