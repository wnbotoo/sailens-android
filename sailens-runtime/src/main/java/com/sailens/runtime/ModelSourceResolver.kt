package com.sailens.runtime

import com.google.ai.edge.litert.Accelerator

/**
 * Resolves the physical model source for one logical model and one actual accelerator attempt.
 *
 * Which file a model is, is the host's choice, not the platform's: the host builds one resolver and
 * binds it, and the same instance answers both the runners and the static preflight, so the check
 * looks at the file that will be loaded (docs/models.md, "Where to put a model"). Nothing in the
 * platform modules binds or defaults one. A later AI Pack, download or per-device implementation
 * returns another [ModelSource] without changing semantic or obstacle providers.
 */
interface ModelSourceResolver {
    fun source(type: ModelType, accelerator: Accelerator): ModelSource
}

/** The source a model configured to run on [backend] loads: what a static check of it must read. */
fun ModelSourceResolver.source(type: ModelType, backend: ModelAcceleratorBackend): ModelSource =
    source(type, backend.toLiteRtAccelerator())

/** The bundled files of [ModelCatalog]. A host binds it when its models are packaged assets. */
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
