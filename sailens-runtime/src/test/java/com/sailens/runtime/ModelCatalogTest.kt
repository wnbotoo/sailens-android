package com.sailens.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.google.ai.edge.litert.Accelerator

class ModelCatalogTest {

    // Asset names are the bring-your-own-model contract surface (docs/models.md): they are the
    // paths a working copy drops weights at. Pin the literals, not the catalog's own constants.

    @Test
    fun `semantic model resolves to the same asset on every supported accelerator`() {
        assertEquals(
            ModelSource.Asset("sem.tflite"),
            ModelCatalog.source(ModelType.SEMANTIC_SEGMENTATION, ModelAcceleratorBackend.GPU),
        )
        assertEquals(
            ModelSource.Asset("sem.tflite"),
            ModelCatalog.source(ModelType.SEMANTIC_SEGMENTATION, ModelAcceleratorBackend.CPU),
        )
    }

    @Test
    fun `obstacle model resolves to the same asset on every supported accelerator`() {
        assertEquals(
            ModelSource.Asset("det.tflite"),
            ModelCatalog.source(ModelType.OBSTACLE_DETECTION, ModelAcceleratorBackend.GPU),
        )
        assertEquals(
            ModelSource.Asset("det.tflite"),
            ModelCatalog.source(ModelType.OBSTACLE_DETECTION, ModelAcceleratorBackend.CPU),
        )
    }

    @Test
    fun `NPU sources are intentionally unsupported (NPU is reserved for the VLM)`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalog.source(ModelType.SEMANTIC_SEGMENTATION, ModelAcceleratorBackend.NPU)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalog.source(ModelType.OBSTACLE_DETECTION, ModelAcceleratorBackend.NPU)
        }
    }

    @Test
    fun `catalog resolver maps actual LiteRT accelerator attempts to catalog entries`() {
        assertEquals(
            ModelCatalog.source(ModelType.SEMANTIC_SEGMENTATION, ModelAcceleratorBackend.GPU),
            CatalogModelSourceResolver.source(ModelType.SEMANTIC_SEGMENTATION, Accelerator.GPU),
        )
        assertEquals(
            ModelCatalog.source(ModelType.OBSTACLE_DETECTION, ModelAcceleratorBackend.GPU),
            CatalogModelSourceResolver.source(ModelType.OBSTACLE_DETECTION, Accelerator.GPU),
        )
    }
}
