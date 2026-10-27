package com.sailens.shell.guidance.screen

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidanceRunSequencerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val otherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Open unless a test closes it to hold the reset. */
    @Volatile
    private var finishReset = CountDownLatch(0)
    private val runs = GuidanceRunSequencer(scope) {
        finishReset.await()
        log += "reset"
    }

    // A frame in the decision step: blocking, no suspension point, so cancel() cannot stop it.
    private val inFrame = CountDownLatch(1)
    private val finishFrame = CountDownLatch(1)
    private val blockingFrame: suspend CoroutineScope.() -> Unit = {
        inFrame.countDown()
        finishFrame.await()
        log += "decided"
    }

    @After
    fun tearDown() {
        finishFrame.countDown()
        finishReset.countDown()
        scope.cancel()
        otherScope.cancel()
    }

    @Test
    fun `stop resets only after the frame the run is still processing`() = runBlocking {
        runs.start(blockingFrame)
        inFrame.await()

        val stopped = runs.stop()
        assertNull("reset ran while the frame was in flight", withTimeoutOrNull(200) { stopped.join() })
        finishFrame.countDown()
        stopped.join()

        assertEquals(listOf("decided", "reset"), log.toList())
    }

    @Test
    fun `a new run starts only after the pending reset`() = runBlocking {
        finishReset = CountDownLatch(1)
        runs.start(blockingFrame)
        inFrame.await()
        runs.stop()
        val next = runs.start { log += "next run" }
        assertNull("the next run started while the frame was in flight", withTimeoutOrNull(200) { next.join() })

        finishFrame.countDown()
        assertNull("the next run started before the reset finished", withTimeoutOrNull(200) { next.join() })
        finishReset.countDown()
        next.join()

        assertEquals(listOf("decided", "reset", "next run"), log.toList())
    }

    @Test
    fun `a finished run is reset before stop returns on an immediate dispatcher`() {
        val immediate = GuidanceRunSequencer(CoroutineScope(Dispatchers.Unconfined)) { log += "reset" }
        immediate.start { log += "ran" }

        immediate.stop()

        assertEquals(listOf("ran", "reset"), log.toList())
    }

    @Test
    fun `stopping in another scope after the owner is cancelled still waits, resets, then releases`() = runBlocking {
        runs.start(blockingFrame)
        inFrame.await()
        scope.cancel() // onCleared: viewModelScope is gone

        val stopped = runs.stop(on = otherScope) { log += "released" }
        assertNull("reset ran while the frame was in flight", withTimeoutOrNull(200) { stopped.join() })
        finishFrame.countDown()
        stopped.join()

        assertEquals(listOf("decided", "reset", "released"), log.toList())
    }

    @Test
    fun `stopping a run that is still waiting keeps waiting for the frame before it`() = runBlocking {
        runs.start(blockingFrame)
        inFrame.await()
        runs.stop()
        runs.start { log += "waiting run ran" } // waits for the frame and the first reset

        val stoppedAgain = runs.stop() // cancels the waiting run
        assertNull("reset ran while the frame was in flight", withTimeoutOrNull(200) { stoppedAgain.join() })
        finishFrame.countDown()
        stoppedAgain.join()

        assertEquals(listOf("decided", "reset", "reset"), log.toList())
    }

    @Test
    fun `the owner going away while a run waits still releases only after the frame before it`() = runBlocking {
        runs.start(blockingFrame)
        inFrame.await()
        runs.stop()
        runs.start { log += "waiting run ran" }
        scope.cancel() // onCleared: the pending reset and the waiting run are both cancelled

        val stopped = runs.stop(on = otherScope) { log += "released" }
        assertNull("released while the frame was in flight", withTimeoutOrNull(200) { stopped.join() })
        finishFrame.countDown()
        stopped.join()

        assertEquals(listOf("decided", "reset", "released"), log.toList())
    }
}
