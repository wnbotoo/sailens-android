package com.sailens.guidance.di

import android.content.Context
import android.content.ContextWrapper
import com.sailens.core.log.LogService
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.depth.geometry.DepthGeometryLane
import com.sailens.guidance.depth.geometry.DepthGeometrySwitch
import com.sailens.guidance.depth.geometry.DepthLaneState
import com.sailens.guidance.depth.geometry.DepthStartContext
import com.sailens.runtime.ModelSourceResolver
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class GuidanceModuleTest {

    @Test
    fun `Guidance leaves the choice of model files to the host`() {
        // A binding here would silently override, or be overridden by, the host's: which one wins
        // would depend on module order, and the static preflight would no longer be guaranteed to
        // look at the file the runners load.
        val koin = koinApplication(createEagerInstances = false) { modules(guidanceModule) }.koin

        assertNull(koin.getOrNull<ModelSourceResolver>())
    }

    @Test
    fun `a host that binds no depth model gets one lane, off, whose sessions are not configured`() = runBlocking {
        val host = module {
            single<Context> { ContextWrapper(null) }
            single<LogService> { SilentLog }
            single { TraceRuntimeConfig(enabled = false) }
        }
        val koin = koinApplication(createEagerInstances = false) { modules(guidanceModule, host) }.koin

        val lane = koin.get<DepthGeometryLane>()
        assertSame("one lane for the app: the barrier chain spans sessions", lane, koin.get<DepthGeometryLane>())
        assertFalse("off unless the debug settings switch it on", koin.get<DepthGeometrySwitch>().selected)
        val session = lane.start(DepthStartContext(emptyFlow(), {}, { null }, { error("no frame expected") }, null))
        withTimeout(10_000) { session.job!!.join() }
        assertEquals(DepthLaneState.NOT_CONFIGURED, session.latest().state)
        lane.stop(session)
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
