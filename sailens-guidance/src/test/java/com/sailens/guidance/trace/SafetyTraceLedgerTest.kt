package com.sailens.guidance.trace

import com.sailens.core.log.LogService
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.GuidanceAlertTrace
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
import kotlin.concurrent.thread

/**
 * Alert records come from the main thread: they are ordered against the session's opening and closing by the ledger,
 * numbered within the session, and never wait for the disk.
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

    private fun metadata(id: String) = SessionTraceMetadata(sessionId = id, startedAt = 0L, pipelineMode = "default", targetHardwareProfile = "test")

    private fun summary(id: String) = SessionTraceSummary(
        sessionId = id, startedAt = 0L, completedAt = 0L, totalFrames = 0, droppedFrames = 0, totalEvents = 0,
        blockedFrames = 0, dangerousFrames = 0, avgProcessFrameMs = 0.0, avgTotalPipelineMs = 0.0, avgInferenceMs = 0.0,
        p95TotalPipelineMs = 0L, maxTotalPipelineMs = 0L,
    )

    /** One line per entry, enough to check the order: kind, session and, for alerts and summaries, the numbers. */
    private fun describe(entry: TraceQueueEntry): String = when (entry) {
        is TraceQueueEntry.SessionStart -> "start ${entry.metadata.sessionId} opened=${entry.metadata.openedElapsedMs}"
        is TraceQueueEntry.GuidanceAlert -> "alert ${entry.alert.sessionId}#${entry.alert.seq}"
        is TraceQueueEntry.SessionSummary ->
            "summary ${entry.summary.sessionId} alerts=${entry.summary.guidanceAlertCount} closed=${entry.summary.closedElapsedMs}"
        else -> entry.javaClass.simpleName
    }

    private fun drain(queue: CountedTraceQueue<TraceQueueEntry>) = generateSequence { queue.pollCounted() }.map(::describe).toList()

    @Test
    fun `an alert before the session opens or after it closes is in no trace, and each session numbers its own`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = SafetyTraceLedger(queue)

        assertEquals(false, ledger.recordAlert(alert()))
        ledger.attach(metadata("a"))
        assertEquals(true, ledger.recordAlert(alert()))
        ledger.recordAlert(alert())
        ledger.detach(summary("a"))
        assertEquals(false, ledger.recordAlert(alert()))
        ledger.attach(metadata("b"))
        ledger.recordAlert(alert())
        ledger.detach(summary("b"))

        assertEquals(
            listOf(
                "start a opened=null", "alert a#1", "alert a#2", "summary a alerts=2 closed=null",
                "start b opened=null", "alert b#1", "summary b alerts=1 closed=null",
            ),
            drain(queue),
        )
    }

    @Test
    fun `an alert racing the opening is either before it and dropped, or after it, never ahead of the start`() {
        repeat(500) {
            val queue = CountedTraceQueue<TraceQueueEntry>()
            val ledger = SafetyTraceLedger(queue)
            val barrier = CyclicBarrier(2)
            val opener = thread { barrier.await(); ledger.attach(metadata("s")) }
            val main = thread { barrier.await(); ledger.recordAlert(alert()) }
            opener.join()
            main.join()
            val lines = drain(queue)
            assertTrue("$lines", lines == listOf("start s opened=null") || lines == listOf("start s opened=null", "alert s#1"))
        }
    }

    @Test
    fun `an alert racing the closing is either before the summary and counted, or in no trace`() {
        repeat(500) {
            val queue = CountedTraceQueue<TraceQueueEntry>()
            val ledger = SafetyTraceLedger(queue)
            ledger.attach(metadata("s"))
            val barrier = CyclicBarrier(2)
            val closer = thread { barrier.await(); ledger.detach(summary("s")) }
            val main = thread { barrier.await(); ledger.recordAlert(alert()) }
            closer.join()
            main.join()
            val lines = drain(queue)
            assertTrue(
                "$lines",
                lines == listOf("start s opened=null", "summary s alerts=0 closed=null") ||
                    lines == listOf("start s opened=null", "alert s#1", "summary s alerts=1 closed=null"),
            )
        }
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
            listOf("start s opened=1000", "alert s#1", "alert s#2", "summary s alerts=2 closed=4000"),
            written.toList(),
        )
    }
}
