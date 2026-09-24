package com.sailens.runtime

/**
 * The single place that maps a logical model — its [ModelType] and the accelerator it is intended to
 * run on — to a physical [ModelSource]. Nothing else (providers, the loading layer, the runtime
 * profile) needs a model's file name; they refer to a model by `(type, accelerator)`.
 *
 * No model weights ship with this repository: the app is bring-your-own-model. Drop graphs that
 * satisfy the contract in `docs/models.md` at the asset paths below and the pipeline picks them up
 * — nothing else needs to change, because shape, layout, dtype and quantization are all read back
 * from the TFLite metadata at load time. Every `.tflite` under `app/src/main/assets` is git-ignored,
 * so a local working copy can carry weights without them ever entering a commit. With no weights
 * present nothing is loaded at all: static preflight finds no model, and the edition decides what
 * that means — a zero-pipeline screen when it promises nothing, a fatal configuration state when it
 * declared the pipeline required (architecture.md §5.2).
 *
 * Vision models are expected to run on the GPU. Requesting NPU for any vision type is unsupported
 * here (throws). Prefer NHWC graphs: NCHW exports with a float32 I/O wrapper
 * cost a per-frame host transpose and fragment on the GPU/NPU delegates.
 *
 * [ModelSourceResolver] wraps this catalog today. When model download lands, another resolver can
 * still use this as the bundled [Asset] fallback and upgrade to a downloaded [ModelSource.File].
 */
object ModelCatalog {
    fun source(type: ModelType, accelerator: ModelAcceleratorBackend): ModelSource =
        when (type) {
            ModelType.SEMANTIC_SEGMENTATION -> when (accelerator) {
                ModelAcceleratorBackend.CPU,
                ModelAcceleratorBackend.GPU -> ModelSource.Asset(SEMANTIC_SEGMENTATION_ASSET)
                ModelAcceleratorBackend.NPU -> unsupported(type, accelerator)
            }
            ModelType.OBSTACLE_DETECTION -> when (accelerator) {
                ModelAcceleratorBackend.CPU,
                ModelAcceleratorBackend.GPU -> ModelSource.Asset(OBSTACLE_DETECTION_ASSET)
                ModelAcceleratorBackend.NPU -> unsupported(type, accelerator)
            }
        }

    private fun unsupported(type: ModelType, accelerator: ModelAcceleratorBackend): Nothing {
        throw IllegalArgumentException("No bundled model source for $type on $accelerator")
    }

    private const val SEMANTIC_SEGMENTATION_ASSET = "sem.tflite"
    private const val OBSTACLE_DETECTION_ASSET = "det.tflite"
}
