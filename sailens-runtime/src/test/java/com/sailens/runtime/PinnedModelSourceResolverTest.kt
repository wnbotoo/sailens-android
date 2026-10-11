package com.sailens.runtime

import com.google.ai.edge.litert.Accelerator
import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedModelSourceResolverTest {

    /** A resolver that, as the interface allows, gives each accelerator its own file. */
    private object PerAcceleratorFiles : ModelSourceResolver {
        override fun source(type: ModelType, accelerator: Accelerator): ModelSource =
            ModelSource.Asset("${type.name.lowercase()}_${accelerator.name.lowercase()}.tflite")
    }

    @Test
    fun `every accelerator gets the file resolved for the pinned one`() {
        val pinned = PinnedModelSourceResolver.pin(PerAcceleratorFiles, ModelType.SEMANTIC_SEGMENTATION, ModelAcceleratorBackend.GPU)
        val gpuFile = ModelSource.Asset("semantic_segmentation_gpu.tflite")
        assertEquals(gpuFile, pinned.pinned)
        assertEquals(gpuFile, pinned.source(ModelType.SEMANTIC_SEGMENTATION, Accelerator.GPU))
        assertEquals(gpuFile, pinned.source(ModelType.SEMANTIC_SEGMENTATION, Accelerator.CPU))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `another model type is refused`() {
        PinnedModelSourceResolver.pin(PerAcceleratorFiles, ModelType.SEMANTIC_SEGMENTATION, ModelAcceleratorBackend.GPU)
            .source(ModelType.OBSTACLE_DETECTION, Accelerator.CPU)
    }
}
