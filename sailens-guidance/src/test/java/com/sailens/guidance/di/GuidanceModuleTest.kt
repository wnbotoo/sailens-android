package com.sailens.guidance.di

import com.sailens.runtime.ModelSourceResolver
import org.junit.Assert.assertNull
import org.junit.Test
import org.koin.dsl.koinApplication

class GuidanceModuleTest {

    @Test
    fun `Guidance leaves the choice of model files to the host`() {
        // A binding here would silently override, or be overridden by, the host's: which one wins
        // would depend on module order, and the static preflight would no longer be guaranteed to
        // look at the file the runners load.
        val koin = koinApplication(createEagerInstances = false) { modules(guidanceModule) }.koin

        assertNull(koin.getOrNull<ModelSourceResolver>())
    }
}
