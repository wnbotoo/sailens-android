package com.sailens.vision.detection

import com.sailens.runtime.ModelAcceleratorSelectionMode
import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.runtime.ModelInputDataType
import com.sailens.runtime.ModelInputQuantization
import com.sailens.runtime.ResizeFilter

/**
 * Obstacle detection model contract. The runtime resolves the detection head layout
 * (raw attribute-major `[1, 4+classCount, N]` vs end-to-end `[1, N, 6]`) from the output tensor
 * shape. What the shape cannot say is configured: the class count and the box [coordinateSpace].
 */
data class DetectionModelConfig(
    val classCount: Int = 80,
    /**
     * The unit the head reports boxes in. Declared with the model, never inferred from its output;
     * see [DetectionCoordinateSpace]. A wrong value misplaces every box without an error, so it is
     * checked by hand when a model is swapped, like the class order (docs/models.md).
     */
    val coordinateSpace: DetectionCoordinateSpace = DetectionCoordinateSpace.NORMALIZED,
    val inputDataType: ModelInputDataType = ModelInputDataType.AUTO,
    val inputQuantization: ModelInputQuantization = ModelInputQuantization(),
    val preferNativeYuvPreprocessing: Boolean = true,
    val resizeFilter: ResizeFilter = ResizeFilter.NEAREST,
    val acceleratorSelectionMode: ModelAcceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT,
    // Vision models run on the GPU; ModelCatalog has no NPU asset for them.
    val acceleratorBackend: ModelAcceleratorBackend = ModelAcceleratorBackend.GPU,
)
