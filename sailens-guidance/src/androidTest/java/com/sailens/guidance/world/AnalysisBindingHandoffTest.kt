package com.sailens.guidance.world

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.camera.AnalysisBindingArbiter
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.Scenario
import com.sailens.guidance.sim.ScenarioFrame
import com.sailens.guidance.sim.ScenarioRenders
import com.sailens.guidance.sim.Scenarios
import com.sailens.guidance.sim.SimulatedPipeline
import com.sailens.guidance.sim.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AnalysisBindingHandoffTest {
    @Test
    fun bothStartedHostsRevokeBeforeDetachingAndReacquisitionNeedsNewEvidence() = onMain {
        val hostA = Host()
        val hostB = Host()
        val renders = ScenarioRenders()
        val scenario = sidewalk()
        val pipeA = pipe(scenario, renders)
        val arbiter = AnalysisBindingArbiter { pipeA.clock() * 1_000_000L }
        val backend = RecordingBackend()
        pipeA.at(980)
        val requestA = arbiter.register(hostA, backend.bind("A"))
        val first = checkNotNull(backend.bound)
        val firstGrant = checkNotNull(first.captureGrant)
        val a = GuidanceRun(this, pipeA, requestA)
        var requestB: AnalysisBindingArbiter.Request? = null
        var b: GuidanceRun? = null
        try {
            val a1 = a.frame(first, 990, 992, 995)
            val cell = positiveCell(a1)
            assertEquals(SurfaceEvidence.SEM_SURFACE, a.read(cell).surface())
            var readBeforeADetach: CurrentCellQuery? = null
            backend.beforeDetach = { binding ->
                assertEquals(Lifecycle.State.STARTED, hostA.lifecycle.currentState)
                assertEquals(Lifecycle.State.STARTED, hostB.lifecycle.currentState)
                if (binding === first) {
                    readBeforeADetach = a.read(cell)
                    assertEquals(ns(1000), pipeA.clock() * 1_000_000L)
                } else {
                    assertEquals(noGrant, b?.read(cell))
                }
            }

            pipeA.at(1000)
            requestB = arbiter.register(hostB, backend.bind("B"))
            assertEquals("old reader must be revoked before A analysis detaches", noGrant, readBeforeADetach)
            val second = checkNotNull(backend.bound)
            assertNotSame(first, second)
            assertEquals(listOf("bind A", "attach A", "detach A", "unbind A", "destroy A", "bind B", "attach B"), backend.events)
            assertEquals(noGrant, a.read(cell))

            val bRun = GuidanceRun(this, pipe(scenario, renders), requestB)
            b = bRun
            bRun.frame(second, 1010, 1012, 1014)
            assertEquals(SurfaceEvidence.SEM_SURFACE, bRun.read(cell).surface())
            val foreign = a.frame(second, 1010, 1012, 1014)
            assertEquals(cell, positiveCell(foreign))
            assertEquals(noGrant, a.read(cell))

            pipeA.at(1100)
            requestB.close()
            backend.beforeDetach = {}
            val third = checkNotNull(backend.bound)
            assertNotSame(first, third)
            val newGrant = checkNotNull(requestA.currentGrant())
            assertEquals(2L, newGrant.generation)
            assertEquals(ns(1100), newGrant.openedAtNanos)
            assertEquals(listOf("detach B", "unbind B", "destroy B", "bind A", "attach A"), backend.events.takeLast(5))
            assertEquals(noFrame, a.read(cell))
            first.cameraOpened()
            first.beforeAnalysisStops()
            requestB.close()
            assertSame(newGrant, requestA.currentGrant())
            assertSame(firstGrant, first.captureGrant)

            // Even a delivery with timestamps eligible for A2 cannot borrow A2's capture identity.
            val oldCallback = a.frame(first, 1102, 1103, 1104)
            assertSame(a1.grant, oldCallback.grant)
            assertEquals(cell, positiveCell(oldCallback))
            assertEquals(noFrame, a.read(cell))

            a.frame(third, 1105, 1108, 1110)
            assertEquals(SurfaceEvidence.UNKNOWN, a.read(cell).surface())
            a.frame(third, 1355, 1358, 1360)
            assertEquals(SurfaceEvidence.UNKNOWN, a.read(cell).surface())
            a.frame(third, 1605, 1608, 1610)
            assertEquals(SurfaceEvidence.SEM_SURFACE, a.read(cell).surface())
            assertEquals(6, a.released.get())
            assertEquals(1, bRun.released.get())
            Log.i(TAG, "dual STARTED: ${backend.events.joinToString()} | A1 990 ms, A→B 1000 ms, B→A 1100 ms, A2 positive 1610 ms")
        } finally {
            backend.beforeDetach = {}
            requestB?.close()
            requestA.close()
            b?.finish()
            a.finish()
        }
    }

    @Test
    fun hostStopStartReentryUsesThePrivateLifecycleInBothObserverOrders() = onMain {
        for (addObserverBeforeRegistration in listOf(true, false)) {
            val host = Host()
            val pipeline = pipe(sidewalk(), ScenarioRenders())
            val arbiter = AnalysisBindingArbiter { pipeline.clock() * 1_000_000L }
            val backend = RecordingBackend()
            var reentries = 0
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    reentries++
                    host.registry.currentState = Lifecycle.State.STARTED
                }
            }
            if (addObserverBeforeRegistration) host.lifecycle.addObserver(observer)
            pipeline.at(980)
            val request = arbiter.register(host, backend.bind("host"))
            if (!addObserverBeforeRegistration) host.lifecycle.addObserver(observer)
            val guide = GuidanceRun(this, pipeline, request)
            try {
                val initial = checkNotNull(backend.bound)
                val initialGrant = checkNotNull(request.currentGrant())
                val cell = positiveCell(guide.frame(initial, 990, 992, 995))
                assertEquals(SurfaceEvidence.SEM_SURFACE, guide.read(cell).surface())
                backend.beforeDetach = {
                    assertEquals("reader must be closed at the private lifecycle stop", noGrant, guide.read(cell))
                }
                pipeline.at(1000)
                host.registry.currentState = Lifecycle.State.CREATED
                assertEquals(1, reentries)
                assertEquals(Lifecycle.State.STARTED, host.lifecycle.currentState)
                if (addObserverBeforeRegistration) {
                    assertEquals(listOf("bind host", "attach host", "detach host", "unbind host", "destroy host", "bind host", "attach host"), backend.events)
                    assertNotSame(initialGrant, request.currentGrant())
                    assertEquals(noFrame, guide.read(cell))
                } else {
                    assertEquals(listOf("bind host", "attach host"), backend.events)
                    assertSame(initialGrant, request.currentGrant())
                    assertEquals(SurfaceEvidence.SEM_SURFACE, guide.read(cell).surface())
                }
                Log.i(TAG, "host observer first=$addObserverBeforeRegistration: ${backend.events.joinToString()}")
            } finally {
                host.lifecycle.removeObserver(observer)
                backend.beforeDetach = {}
                request.close()
                guide.finish()
            }
        }
    }

    @Test
    fun lostAndFailedBindingsCannotClaimAnIntervalOrAffectTheNextBinding() = onMain {
        val arbiter = AnalysisBindingArbiter { ns(1000) }
        val backend = RecordingBackend()
        val host = Host()
        val request = arbiter.register(host, backend.bind("A", open = false))
        val a1 = checkNotNull(backend.bound)
        assertNull(request.currentGrant())
        a1.cameraOpened()
        val a1Grant = checkNotNull(request.currentGrant())
        a1.beforeAnalysisStops()
        val a2 = checkNotNull(backend.bound)
        assertNotSame(a1, a2)
        assertNull(request.currentGrant())
        a1.cameraOpened()
        a1.beforeAnalysisStops()
        assertSame(a2, backend.bound)
        assertNull(request.currentGrant())
        a2.cameraOpened()
        assertNotSame(a1Grant, request.currentGrant())

        val failure = arbiter.register(Host()) { pending ->
            assertEquals(Lifecycle.State.CREATED, pending.lifecycle.currentState)
            assertNull(backend.bound)
            pending.cameraOpened()
            assertNull(pending.captureGrant)
            null
        }
        assertNull(failure.currentGrant())
        val a3 = checkNotNull(backend.bound)
        assertNotSame(a2, a3)
        a3.cameraOpened()
        assertEquals(3L, request.currentGrant()?.generation)

        val destroyed = Host().apply { registry.currentState = Lifecycle.State.DESTROYED }
        assertThrows(IllegalArgumentException::class.java) {
            arbiter.register(destroyed) { error("destroyed host bound") }
        }
        request.close()
        failure.close()
        assertNull(backend.bound)
    }

    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class RecordingBackend {
        var bound: AnalysisBindingArbiter.Binding? = null
            private set
        val events = mutableListOf<String>()
        var beforeDetach: (AnalysisBindingArbiter.Binding) -> Unit = {}

        fun bind(name: String, open: Boolean = true): (AnalysisBindingArbiter.Binding) -> AutoCloseable = { binding ->
            assertNull("the previous use case group must already be unbound", bound)
            assertEquals(Lifecycle.State.CREATED, binding.lifecycle.currentState)
            bound = binding
            events += "bind $name"
            var attached = false
            binding.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        assertSame(binding, bound)
                        attached = true
                        events += "attach $name"
                        if (open) binding.cameraOpened()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        assertSame(binding, bound)
                        assertTrue(attached)
                        beforeDetach(binding)
                        attached = false
                        events += "detach $name"
                    }
                    Lifecycle.Event.ON_DESTROY -> events += "destroy $name"
                    else -> Unit
                }
            })
            AutoCloseable {
                assertSame(binding, bound)
                assertTrue("the private lifecycle must stop before unbind", !attached)
                bound = null
                events += "unbind $name"
            }
        }
    }

    private class GuidanceRun(scope: CoroutineScope, private val pipeline: SimulatedPipeline, request: AnalysisBindingArbiter.Request) {
        private val world = checkNotNull(pipeline.world)
        private val owner = world.openOwner(request).apply {
            observeSafety(LocalWorldOwner.PublicationSafety(running = true, clearView = true), stopBegan = false)
        }
        private val frames = Channel<ImageFrame>()
        private val publications = Channel<WorldPublication>()
        val released = AtomicInteger()
        private val job = scope.launch {
            pipeline.start(frames.receiveAsFlow(), { released.incrementAndGet() }, { false }, localWorldRun = owner.run).collect {
                val result = checkNotNull(it.worldPublication)
                owner.finishResult(owner.beginResult(it.sequenceNumber, result), result, it.qualification.baseline)
                assertEquals(BaselineQualification.Qualified, it.qualification.baseline)
                publications.send(result)
            }
        }

        suspend fun frame(binding: AnalysisBindingArbiter.Binding, capture: Long, receipt: Long, admit: Long): WorldPublication {
            pipeline.at(admit)
            val index = capture.toInt() - 1
            pipeline.currentIndex = index
            val frame = pipeline.cameraFrame(index).copy(receivedElapsedRealtimeNanos = ns(receipt))
            assertEquals(ns(capture), frame.timestamp)
            world.deliver(frame, binding.captureGrant)
            frames.send(frame)
            return publications.receive()
        }

        fun read(cell: Int) = owner.reader.currentCell(cell / LocalGroundGrid.COLUMNS, cell % LocalGroundGrid.COLUMNS)

        suspend fun finish() {
            owner.close()
            job.cancelAndJoin()
        }
    }

    private fun onMain(action: suspend CoroutineScope.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) { withTimeout(30_000) { action() } }
    }

    private companion object {
        const val TAG = "SailensHandoff"
        val noGrant = CurrentCellQuery.Unavailable(UnavailableReason.NO_ANALYSIS_GRANT)
        val noFrame = CurrentCellQuery.Unavailable(UnavailableReason.NO_FRAME)
        fun ns(ms: Long) = (Scenario.CLOCK_START_MS + ms) * 1_000_000L
        fun SimulatedPipeline.at(ms: Long) = clock.advanceBy(Scenario.CLOCK_START_MS + ms - clock())
        fun sidewalk(): Scenario {
            val frame = ScenarioFrame(Pose(Scenarios.HEIGHT, Scenarios.PITCH), World(Scenarios.street()))
            return Scenario("binding_handoff", "", List(1610) { frame }, stepMs = 1, alignClockToFrames = true)
        }
        fun pipe(scenario: Scenario, renders: ScenarioRenders) = SimulatedPipeline(
            scenario, renders = renders, withLocalWorld = true,
            phoneGeometry = { PhoneGeometrySettings(Scenarios.HEIGHT, HeightSource.PRESET) },
        )
        fun positiveCell(publication: WorldPublication): Int {
            val snapshot = checkNotNull(publication.built?.snapshot)
            return (0 until LocalGroundGrid.ROWS * LocalGroundGrid.COLUMNS).first {
                snapshot.read(it, allowSurface = true).evidence.surface == SurfaceEvidence.SEM_SURFACE
            }
        }
        fun CurrentCellQuery.surface() = (this as CurrentCellQuery.Observed).evidence.surface
    }
}
