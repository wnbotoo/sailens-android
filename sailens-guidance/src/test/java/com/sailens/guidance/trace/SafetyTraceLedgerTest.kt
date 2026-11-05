package com.sailens.guidance.trace

import com.sailens.core.log.LogService
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Alert and safety-state records come from the main thread: they are ordered against the session's opening and
 * closing by the ledger, numbered within the session, and never wait for the disk. The latest safety state opens
 * every session.
 */
class SafetyTraceLedgerTest {

    private fun alert(result: String = GuidanceAlertResults.REQUESTED) = GuidanceAlertTrace(
        at = 1L,
        elapsedMs = 2L,
        source = GuidanceAlertSources.VISION,
        kind = GuidanceAlertKinds.FAULT_RHYTHM,
        haptic = "vision_unreliable",
        priority = "critical",
        channel = GuidanceAlertChannels.HAPTICS,
        result = result,
    )

    private fun state(
        state: String,
        elapsedMs: Long,
        since: Long = elapsedMs,
        phase: String = SafetyTraceNames.Phases.RUNNING,
        cause: String = SafetyTraceNames.Causes.RESULT,
        reasons: List<String> = emptyList(),
    ) = SafetyStateTrace(
        at = 1_700_000_000_000L + elapsedMs,
        elapsedMs = elapsedMs,
        stateSinceElapsedMs = since,
        state = state,
        reasons = reasons,
        phase = phase,
        interrupted = false,
        stall = SafetyTraceNames.Stalls.NONE,
        expectedNavigationChannel = SafetyTraceNames.Channels.SPEECH,
        cause = cause,
    )

    private fun metadata(id: String) = SessionTraceMetadata(sessionId = id, startedAt = 0L, pipelineMode = "default", targetHardwareProfile = "test")

    /** The ledger's elapsed clock, which bounds the window; the wall clock runs with it. */
    private val elapsed = AtomicLong(100L)

    private fun newLedger(queue: CountedTraceQueue<TraceQueueEntry>, elapsedClock: () -> Long = elapsed::get) =
        SafetyTraceLedger(queue, elapsedClock, wallClock = { 1_700_000_000_000L + elapsed.get() })

    /** Opens [id] at [openedElapsedMs] on the ledger's clock. */
    private fun SafetyTraceLedger.open(id: String, openedElapsedMs: Long = 100L) {
        elapsed.set(openedElapsedMs)
        attach(metadata(id))
    }

    /** Closes [id] at [closedElapsedMs] on the ledger's clock. */
    private fun SafetyTraceLedger.close(id: String, closedElapsedMs: Long = 900L) {
        elapsed.set(closedElapsedMs)
        detach(summary(id))
    }

    private fun summary(id: String) = SessionTraceSummary(
        sessionId = id, startedAt = 0L, completedAt = 0L, totalFrames = 0, droppedFrames = 0, totalEvents = 0,
        blockedFrames = 0, dangerousFrames = 0, avgProcessFrameMs = 0.0, avgTotalPipelineMs = 0.0, avgInferenceMs = 0.0,
        p95TotalPipelineMs = 0L, maxTotalPipelineMs = 0L,
    )

    /** One line per entry, enough to check the order: kind, session and the numbers. */
    private fun describe(entry: TraceQueueEntry): String = when (entry) {
        is TraceQueueEntry.SessionStart -> "start ${entry.metadata.sessionId} opened=${entry.metadata.openedElapsedMs}"
        is TraceQueueEntry.GuidanceAlert -> "alert ${entry.alert.sessionId}#${entry.alert.seq}"
        is TraceQueueEntry.SafetyState -> entry.state.let {
            "state ${it.sessionId}#${it.seq} ${it.stateLabel} at=${it.elapsedMs} since=${it.stateSinceElapsedMs} cause=${it.cause}"
        }
        is TraceQueueEntry.SessionSummary -> entry.summary.let {
            "summary ${it.sessionId} alerts=${it.guidanceAlertCount} states=${it.safetyStateCount} closed=${it.closedElapsedMs}"
        }
        else -> entry.javaClass.simpleName
    }

    private fun drain(queue: CountedTraceQueue<TraceQueueEntry>) = generateSequence { queue.pollCounted() }.map(::describe).toList()

    @Test
    fun `an alert before the session opens or after it closes is in no trace, and each session numbers its own`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = newLedger(queue)

        assertEquals(false, ledger.recordAlert(alert()))
        ledger.open("a")
        assertEquals(true, ledger.recordAlert(alert()))
        ledger.recordAlert(alert())
        ledger.close("a")
        assertEquals(false, ledger.recordAlert(alert()))
        ledger.open("b")
        ledger.recordAlert(alert())
        ledger.close("b")

        assertEquals(
            listOf(
                "start a opened=100", "alert a#1", "alert a#2", "summary a alerts=2 states=0 closed=900",
                "start b opened=100", "alert b#1", "summary b alerts=1 states=0 closed=900",
            ),
            drain(queue),
        )
    }

    @Test
    fun `an alert racing the opening is either before it and dropped, or after it, never ahead of the start`() {
        repeat(500) {
            val queue = CountedTraceQueue<TraceQueueEntry>()
            val ledger = newLedger(queue)
            val barrier = CyclicBarrier(2)
            val opener = thread { barrier.await(); ledger.open("s") }
            val main = thread { barrier.await(); ledger.recordAlert(alert()) }
            opener.join()
            main.join()
            val lines = drain(queue)
            assertTrue("$lines", lines == listOf("start s opened=100") || lines == listOf("start s opened=100", "alert s#1"))
        }
    }

    @Test
    fun `an alert racing the closing is either before the summary and counted, or in no trace`() {
        repeat(500) {
            val queue = CountedTraceQueue<TraceQueueEntry>()
            val ledger = newLedger(queue)
            ledger.open("s")
            val barrier = CyclicBarrier(2)
            val closer = thread { barrier.await(); ledger.close("s") }
            val main = thread { barrier.await(); ledger.recordAlert(alert()) }
            closer.join()
            main.join()
            val lines = drain(queue)
            assertTrue(
                "$lines",
                lines == listOf("start s opened=100", "summary s alerts=0 states=0 closed=900") ||
                    lines == listOf("start s opened=100", "alert s#1", "summary s alerts=1 states=0 closed=900"),
            )
        }
    }

    // ---- the safety state

    @Test
    fun `cold start - the state from before the trace opened is its first state, observed at the opening, with its own start`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = newLedger(queue)

        assertEquals(false, ledger.recordState(state("initializing", elapsedMs = 40, phase = "starting", cause = "start_requested")))
        ledger.open("a", openedElapsedMs = 2_000)
        val opening = generateSequence { queue.pollCounted() }.toList()
        assertEquals(true, ledger.recordState(state("guiding", elapsedMs = 2_500)))
        ledger.close("a", closedElapsedMs = 3_000)

        assertEquals(
            listOf("start a opened=2000", "state a#1 initializing at=2000 since=40 cause=session_start"),
            opening.map(::describe),
        )
        assertEquals("the wall clock at the opening", 1_700_000_002_000L, (opening[1] as TraceQueueEntry.SafetyState).state.at)
        assertEquals(
            listOf("state a#2 guiding at=2500 since=2500 cause=result", "summary a alerts=0 states=2 closed=3000"),
            drain(queue),
        )
    }

    @Test
    fun `second session - the state kept since the first closed opens it, numbered from 1 again`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = newLedger(queue)
        ledger.open("a", openedElapsedMs = 100)
        ledger.recordState(state("guiding", elapsedMs = 200))
        ledger.close("a", closedElapsedMs = 250)
        ledger.recordState(state("idle", elapsedMs = 300, phase = "not_started", cause = "stopped"))
        ledger.recordState(state("initializing", elapsedMs = 400, phase = "starting", cause = "start_requested"))
        drain(queue)

        ledger.open("b", openedElapsedMs = 900)
        ledger.close("b", closedElapsedMs = 1_000)

        assertEquals(
            listOf(
                "start b opened=900",
                "state b#1 initializing at=900 since=400 cause=session_start",
                "summary b alerts=0 states=1 closed=1000",
            ),
            drain(queue),
        )
    }

    @Test
    fun `a stall during initialisation, before the trace opened, opens it`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = newLedger(queue)
        ledger.recordState(state("initializing", elapsedMs = 0, phase = "awaiting_first_result", cause = "flow_started"))
        ledger.recordState(
            state("stop_required", elapsedMs = 20_000, phase = "awaiting_first_result", cause = "poll", reasons = listOf("startup_stalled")),
        )
        ledger.open("a", openedElapsedMs = 21_000)

        assertEquals(
            listOf("start a opened=21000", "state a#1 stop_required(startup_stalled) at=21000 since=20000 cause=session_start"),
            drain(queue),
        )
    }

    @Test
    fun `with no state ever recorded, a session opens without one`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = newLedger(queue)
        ledger.open("a")
        ledger.close("a")

        assertEquals(listOf("start a opened=100", "summary a alerts=0 states=0 closed=900"), drain(queue))
    }

    @Test
    fun `a state racing the opening opens the session itself, or follows the kept one that does`() {
        repeat(500) {
            val queue = CountedTraceQueue<TraceQueueEntry>()
            val ledger = newLedger(queue)
            ledger.recordState(state("initializing", elapsedMs = 10, phase = "awaiting_first_result", cause = "flow_started"))
            val barrier = CyclicBarrier(2)
            val opener = thread { barrier.await(); ledger.open("s", openedElapsedMs = 50) }
            val main = thread { barrier.await(); ledger.recordState(state("guiding", elapsedMs = 60)) }
            opener.join()
            main.join()
            val lines = drain(queue)
            assertTrue(
                "$lines",
                lines == listOf("start s opened=50", "state s#1 guiding at=50 since=60 cause=session_start") ||
                    lines == listOf(
                        "start s opened=50",
                        "state s#1 initializing at=50 since=10 cause=session_start",
                        "state s#2 guiding at=60 since=60 cause=result",
                    ),
            )
        }
    }

    @Test
    fun `a state racing the closing is before the summary and counted, or in no trace and kept for the next`() {
        repeat(500) {
            val queue = CountedTraceQueue<TraceQueueEntry>()
            val ledger = newLedger(queue)
            ledger.open("s", openedElapsedMs = 50)
            val barrier = CyclicBarrier(2)
            val closer = thread { barrier.await(); ledger.close("s") }
            val main = thread { barrier.await(); ledger.recordState(state("guiding", elapsedMs = 60)) }
            closer.join()
            main.join()
            val lines = drain(queue)
            assertTrue(
                "$lines",
                lines == listOf("start s opened=50", "summary s alerts=0 states=0 closed=900") ||
                    lines == listOf("start s opened=50", "state s#1 guiding at=60 since=60 cause=result", "summary s alerts=0 states=1 closed=900"),
            )
            ledger.open("t", openedElapsedMs = 70)
            assertEquals(listOf("start t opened=70", "state t#1 guiding at=70 since=60 cause=session_start"), drain(queue))
        }
    }

    @Test
    fun `a state after the closing is in no trace of that session`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = newLedger(queue)
        ledger.open("a")
        ledger.close("a")
        assertEquals(false, ledger.recordState(state("idle", elapsedMs = 500, phase = "not_started", cause = "stopped")))

        assertEquals(listOf("start a opened=100", "summary a alerts=0 states=0 closed=900"), drain(queue))
    }

    // ---- the window's bounds are read inside the lock

    /**
     * Reads [value]. The first read after [during] is set first runs [during] on another thread, and returns only once
     * that thread has finished or waits for a lock: what [during] records, it records while the bound is being read.
     */
    private class InterleavingClock(private val value: () -> Long) {
        @Volatile
        var during: (() -> Unit)? = null
        var other: Thread? = null

        fun read(): Long {
            during?.let { action ->
                during = null
                val running = thread(block = action)
                other = running
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (running.state != Thread.State.BLOCKED && running.state != Thread.State.TERMINATED) {
                    check(System.nanoTime() < deadline) { "the other thread neither finished nor waited for a lock" }
                    Thread.yield()
                }
            }
            return value()
        }
    }

    @Test
    fun `a state recorded while the opening reads its time follows the opening, with the input that set it`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val clock = InterleavingClock(elapsed::get)
        val ledger = newLedger(queue, clock::read)
        ledger.recordState(state("guiding", elapsedMs = 50))
        val interruption = state("interrupted", elapsedMs = 120, cause = "lifecycle_stop")
        clock.during = { ledger.recordState(interruption) }

        ledger.open("s", openedElapsedMs = 100)
        checkNotNull(clock.other).join()

        assertEquals(
            listOf(
                "start s opened=100",
                "state s#1 guiding at=100 since=50 cause=session_start",
                "state s#2 interrupted at=120 since=120 cause=lifecycle_stop",
            ),
            drain(queue),
        )
    }

    @Test
    fun `an alert recorded while the closing reads its time is in no trace, and not counted in the window it missed`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val clock = InterleavingClock(elapsed::get)
        val ledger = newLedger(queue, clock::read)
        ledger.open("s", openedElapsedMs = 100)
        ledger.recordAlert(alert())
        val recorded = AtomicReference<Boolean>()
        clock.during = { recorded.set(ledger.recordAlert(alert(GuidanceAlertResults.PLAYED))) }

        ledger.close("s", closedElapsedMs = 200)
        checkNotNull(clock.other).join()

        assertEquals(false, recorded.get())
        assertEquals(listOf("start s opened=100", "alert s#1", "summary s alerts=1 states=0 closed=200"), drain(queue))
    }

    // ---- through the file trace service

    private object Silent : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun <T> withTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("trace-ledger").toFile()
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an alert is recorded while a flush holds the disk, and the file keeps start, alerts, summary in order`() = withTempDir { dir ->
        val written = Collections.synchronizedList(mutableListOf<String>())
        val blockNext = AtomicBoolean(false)
        val flushing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = AtomicLong(1_000L)
        val service = FileTraceService(
            directory = { dir },
            logService = Silent,
            traceRuntimeConfig = TraceRuntimeConfig(enabled = true),
            elapsedClock = clock::get,
            appendEntry = { _, entry ->
                if (blockNext.compareAndSet(true, false)) {
                    flushing.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "never released" }
                }
                written += describe(entry)
            },
        )
        try {
            service.startSession(metadata("s"))
            service.recordGuidanceAlert(alert())
            blockNext.set(true)
            // The periodic flush takes the service's lock and stalls on the disk.
            assertTrue("no flush came", flushing.await(20, TimeUnit.SECONDS))

            val returned = CountDownLatch(1)
            thread {
                service.recordGuidanceAlert(alert(GuidanceAlertResults.PLAYED))
                returned.countDown()
            }
            assertTrue("the alert waited for the disk", returned.await(2, TimeUnit.SECONDS))

            release.countDown()
            clock.set(4_000L)
            service.finishSession(summary("s"))
        } finally {
            release.countDown()
            service.shutdown()
        }
        assertEquals(
            listOf("start s opened=1000", "alert s#1", "alert s#2", "summary s alerts=2 states=0 closed=4000"),
            written.toList(),
        )
    }

    @Test
    fun `a state is recorded while a flush holds the disk, and the file keeps start, the kept state, later states, summary`() = withTempDir { dir ->
        val written = Collections.synchronizedList(mutableListOf<String>())
        val blockNext = AtomicBoolean(false)
        val flushing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = AtomicLong(1_000L)
        val service = FileTraceService(
            directory = { dir },
            logService = Silent,
            traceRuntimeConfig = TraceRuntimeConfig(enabled = true),
            elapsedClock = clock::get,
            appendEntry = { _, entry ->
                if (blockNext.compareAndSet(true, false)) {
                    flushing.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "never released" }
                }
                written += describe(entry)
            },
        )
        try {
            service.recordSafetyState(state("initializing", elapsedMs = 300, phase = "starting", cause = "start_requested"))
            service.startSession(metadata("s"))
            service.recordSafetyState(state("guiding", elapsedMs = 1_200))
            blockNext.set(true)
            assertTrue("no flush came", flushing.await(20, TimeUnit.SECONDS))

            val returned = CountDownLatch(1)
            thread {
                service.recordSafetyState(state("degraded", elapsedMs = 2_600, reasons = listOf("ground_unrecognized")))
                returned.countDown()
            }
            assertTrue("the state waited for the disk", returned.await(2, TimeUnit.SECONDS))

            release.countDown()
            clock.set(4_000L)
            service.finishSession(summary("s"))
        } finally {
            release.countDown()
            service.shutdown()
        }
        assertEquals(
            listOf(
                "start s opened=1000",
                "state s#1 initializing at=1000 since=300 cause=session_start",
                "state s#2 guiding at=1200 since=1200 cause=result",
                "state s#3 degraded(ground_unrecognized) at=2600 since=2600 cause=result",
                "summary s alerts=0 states=3 closed=4000",
            ),
            written.toList(),
        )
    }
}
