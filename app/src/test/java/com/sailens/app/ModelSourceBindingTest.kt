package com.sailens.app

import com.google.ai.edge.litert.Accelerator
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.runtime.ModelSource
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelType
import com.sailens.vision.semantic.SemanticModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.dsl.koinApplication

class ModelSourceBindingTest {

    @Test
    fun `the host binds the resolver its runners use`() {
        // Eager instances need an Android context; the binding itself does not.
        val koin = koinApplication(createEagerInstances = false) { modules(appModule) }.koin

        assertSame(CatalogModelSourceResolver, koin.get<ModelSourceResolver>())
    }

    @Test
    fun `the semantic preflight reads what the resolver gives for the configured accelerator`() {
        val asked = mutableListOf<Pair<ModelType, Accelerator>>()
        val elsewhere = ModelSource.Asset("elsewhere.tflite")
        val resolver = object : ModelSourceResolver {
            override fun source(type: ModelType, accelerator: Accelerator): ModelSource {
                asked += type to accelerator
                return elsewhere
            }
        }

        val source = semanticPreflightSource(resolver, SemanticModelConfig(acceleratorBackend = ModelAcceleratorBackend.CPU))

        assertSame(elsewhere, source)
        assertEquals(listOf(ModelType.SEMANTIC_SEGMENTATION to Accelerator.CPU), asked)
    }
}
