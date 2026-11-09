package com.sailens.shell.sim

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.sim.ModelInitGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Collections
import java.util.PriorityQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What happens at one virtual millisecond happens in this order: what the devices have already done first (a
 * sentence ending on the engine, the motor coming free), then the script, then the camera, then the stall loop's
 * poll. Within a kind, in the order the events were put in.
 */
internal enum class EventKind { SPEECH_END, MOTOR, SCRIPT, FRAME, POLL }

/**
 * The test thread's loop that drives a simulated session (`docs/geometry.md` §7): virtual time, the shell's one
 * thread ([dispatcher]) and the handshakes with the pipeline, which runs on `Dispatchers.Default` as on a phone.
 *
 * Each step takes the next event from the queue, sets the clock to its time, runs it, and then [settle]s: drains
 * the dispatcher, takes every handshake signal that has come, and, while any run of the pipeline is moving -- will
 * get further with no future event -- blocks for the next signal, draining before each read. A run held at a gate
 * that only a future event opens is still, so time goes on around it.
 *
 * Every run the mirror starts gets a number when start is asked for, and a record in the ledger once its block
 * begins (a block that never begins, stopped while it waited for the run before it, has none). Signals carry the
 * run's number; a signal of a run still in the ledger moves that run's record and nothing else; only the current
 * run, the latest whose block began, is sent frames. A record leaves the ledger once its run has ended and the reset
 * the stop that ended it asked for is done, whichever comes first; a signal for a run no longer there is ignored.
 *
 * A frame is sent only to a current run subscribed to its frame source with the camera not paused, and the step
 * waits for the pipeline to have processed it or for the run to end. Then whether the collector handled a result of
 * it must match the script's prediction ([predict]), or the session fails at that frame.
 *
 * Every blocking read has a real-time limit: a session that hangs fails, naming what it waited for.
 *
 * @param reverseCompletion read a run's end only after the reset that ends its record, when both have come: the
 *   other order of the two signals, which the ledger must take as well.
 */
internal class Director(
    private val predict: (index: Int) -> Boolean,
    private val reverseCompletion: Boolean = false,
    private val handshakeTimeoutMs: Long = 30_000,
) {
    /** The session's time; set before the session runs. */
    lateinit var clock: SessionClock

    /** What the pipeline's thread and the jobs' completions tell the director, thread-safe and never suspending. */
    sealed interface Signal {
        /** A task was queued on the shell's thread from another one: drain. */
        data object Wake : Signal

        /** A model's initialisation began waiting at [gate]. */
        data class InitWaiting(val gate: String) : Signal

        /** [run]'s frame source is collected: initialisation is over. */
        data class SourceSubscribed(val run: Int) : Signal

        /** The pipeline of [run] is done with frame [seq]: its result, if any, has gone to the collector. */
        data class FrameProcessed(val run: Int, val seq: Long) : Signal

        /** [run]'s job completed, however. */
        data class RunEnded(val run: Int) : Signal

        /** The reset a stop of [run] asked for is done. */
        data class ResetDone(val run: Int) : Signal
    }

    enum class RunState(val moving: Boolean) {
        STARTING(true),
        AWAITING_INIT(false),
        SUBSCRIBED(false),
        PROCESSING(true),
        ENDING(true),
        ENDED(false),
    }

    inner class RunRecord(val run: Int) {
        var state = RunState.STARTING
            internal set
        var cancelled = false
            internal set
        var awaitingGate: Gate? = null
            internal set
        val inbox = Channel<ImageFrame>(capacity = 1)
        var processing: Long? = null
            internal set
        var ended = false
            internal set
        var resetAssigned = false
            internal set
        var resetDone = false
            internal set
        val handled = mutableSetOf<Long>()

        override fun toString() = "run $run ${state.name.lowercase()}" + (if (cancelled) " (stopped)" else "")
    }

    /** A model's initialisation the script holds and ends. */
    inner class Gate(val name: String, cancellable: Boolean) {
        val gate = ModelInitGate(cancellable) { signals.add(Signal.InitWaiting(name)) }
        var ended = false
            internal set
    }

    private val signals = LinkedBlockingQueue<Signal>()

    /** The shell's one thread. */
    val dispatcher = SimDispatcher { signals.add(Signal.Wake) }.also { dispatcher ->
        dispatcher.scheduleDelay = { delayMs, resume ->
            log += "delay ${clock.nowMs}+$delayMs"
            schedule(clock.nowMs + delayMs, EventKind.POLL, resume)
        }
    }

    private class Event(val atMs: Long, val kind: EventKind, val order: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val events = PriorityQueue<Event>(compareBy<Event>({ it.atMs }, { it.kind.ordinal }, { it.order }))
    private var nextOrder = 0L

    /** Puts [action] in the queue at [atMs]; the returned function takes it out. */
    fun schedule(atMs: Long, kind: EventKind, action: () -> Unit): () -> Unit {
        require(atMs >= clock.nowMs) { "an event at $atMs is in the past (now ${clock.nowMs})" }
        val event = Event(atMs, kind, nextOrder++, action)
        events += event
        return { event.cancelled = true }
    }

    val ledger = LinkedHashMap<Int, RunRecord>()
    private var requested = 0
    private var current: RunRecord? = null
    private val gates = LinkedHashMap<String, Gate>()

    /** Whether the camera delivers frames: paused while the app is in the background. */
    var cameraPaused = false

    /** What the director did and read, in order: for its own tests. A job's completion may be told on another thread. */
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The session's failure, if one was found on a thread that could not throw it. */
    private var broken: Throwable? = null

    fun gate(name: String, cancellable: Boolean = true): Gate =
        Gate(name, cancellable).also { check(gates.put(name, it) == null) { "two gates named $name" } }

    // ---- the mirror's observer

    val observer = object : MirrorObserver {
        override fun runRequested(): Int {
            val run = ++requested
            log += "run $run: requested"
            return run
        }

        override fun blockStarted(run: Int, job: Job) {
            check(run == requested) { "run $run began, but the latest asked for is $requested" }
            val record = RunRecord(run)
            ledger[run] = record
            current = record
            log += "run $run: began"
            job.invokeOnCompletion {
                log += "job $run completed"
                signals.add(Signal.RunEnded(run))
            }
        }

        override fun resultArrived(run: Int, result: SceneResult) {
            val now = current
            if (now == null || now.run != run) fail("a result of run $run arrived while the current run is ${now?.run}")
        }

        override fun resultHandled(run: Int, sequenceNumber: Long) {
            val record = ledger[run] ?: fail("run $run handled frame $sequenceNumber after leaving the ledger")
            record.handled += sequenceNumber
        }

        override fun stopIssued(resetJob: Job) {
            val record = ledger[requested]
            if (record == null) {
                // Its block never began: the stop's reset belongs to no record, and completes in a later drain.
                log += "run $requested: stopped before it began"
                return
            }
            stop(record)
            if (!record.resetAssigned) {
                record.resetAssigned = true
                resetJob.invokeOnCompletion {
                    log += "reset job ${record.run} completed"
                    signals.add(Signal.ResetDone(record.run))
                }
            }
        }
    }

    private fun stop(record: RunRecord) {
        if (record.cancelled || record.state == RunState.ENDED) return
        record.cancelled = true
        log += "run ${record.run}: stopped while ${record.state.name.lowercase()}"
        record.state = when (record.state) {
            RunState.AWAITING_INIT -> if (record.awaitingGate!!.gate.cancellable) RunState.ENDING else RunState.AWAITING_INIT
            RunState.ENDED -> RunState.ENDED
            else -> RunState.ENDING
        }
    }

    /**
     * The frame source of [run]: it says when it is collected, takes the frames sent to it, and says when each is
     * processed. Made by the run's block, on the test thread; collected on the pipeline's, which touches only the
     * record's inbox and the signal queue.
     */
    fun frames(run: Int): Flow<ImageFrame> {
        val record = ledger[run] ?: error("run $run has no record")
        return frameSource(run, record)
    }

    private fun frameSource(run: Int, record: RunRecord): Flow<ImageFrame> = flow {
        signals.add(Signal.SourceSubscribed(run))
        for (frame in record.inbox) {
            emit(frame)
            signals.add(Signal.FrameProcessed(run, frame.sequenceNumber))
        }
    }

    // ---- the script's actions on the pipeline

    /** Lets [gate]'s initialisation succeed. */
    fun release(gate: Gate) = endGate(gate) { gate.gate.release() }

    /** Makes [gate]'s initialisation throw [cause]. */
    fun fail(gate: Gate, cause: Throwable) = endGate(gate) { gate.gate.fail(cause) }

    private fun endGate(gate: Gate, end: () -> Unit) {
        check(!gate.ended) { "${gate.name} has already ended" }
        gate.ended = true
        val waiting = ledger.values.firstOrNull { it.awaitingGate === gate && it.state == RunState.AWAITING_INIT }
        if (waiting != null) {
            waiting.awaitingGate = null
            waiting.state = if (waiting.cancelled) RunState.ENDING else RunState.STARTING
        }
        end()
        settle("${gate.name} ended")
    }

    /** The camera stops for good: the current run's frame source completes. */
    fun endFrameSource() {
        val record = current ?: error("no run to end")
        record.inbox.close()
        if (record.state == RunState.SUBSCRIBED) record.state = RunState.ENDING
        settle("the frame source ended")
    }

    /**
     * The camera delivers frame [index] ([image]): to the current run when it is subscribed and the camera is not
     * paused, and then only after the pipeline is done with it is the next event taken.
     *
     * @return whether the frame was sent.
     */
    fun deliver(index: Int, image: ImageFrame): Boolean {
        val record = current
        if (cameraPaused || record == null || record.cancelled || record.state != RunState.SUBSCRIBED) return false
        record.state = RunState.PROCESSING
        record.processing = image.sequenceNumber
        check(record.inbox.trySend(image).isSuccess) { "run ${record.run}'s frame source had a frame waiting" }
        settle("frame ${image.sequenceNumber}")
        val got = image.sequenceNumber in record.handled
        val expected = predict(index)
        if (got != expected) {
            fail(if (expected) "seq ${image.sequenceNumber} was expected to give a result and gave none" else "seq ${image.sequenceNumber} was expected to give no result and gave one")
        }
        return true
    }

    // ---- the loop

    /** Runs every event at or before [endMs], in order. */
    fun runUntil(endMs: Long) {
        settle("the session's start")
        while (true) {
            val event = events.peek() ?: break
            if (event.atMs > endMs) break
            events.poll()
            if (event.cancelled) continue
            clock.advanceTo(event.atMs)
            event.action()
            settle("the event at ${event.atMs}")
        }
    }

    /**
     * Drains, takes every signal that has come, and, while a run is moving, blocks for the next one, draining before
     * each read. With [reverseCompletion] a run's end is taken only once nothing else is left to drain or read.
     */
    fun settle(what: String) {
        val deferred = ArrayDeque<Signal.RunEnded>()
        while (true) {
            broken?.let { throw it }
            dispatcher.drain()
            val ready = signals.poll()
            if (ready != null) {
                take(ready, deferred)
                continue
            }
            if (deferred.isNotEmpty()) {
                handle(deferred.removeFirst())
                continue
            }
            val moving = ledger.values.filter { it.state.moving }
            if (moving.isEmpty()) return
            val next = signals.poll(handshakeTimeoutMs, TimeUnit.MILLISECONDS)
                ?: fail("no handshake within $handshakeTimeoutMs ms after $what; waiting for $moving")
            take(next, deferred)
        }
    }

    private fun take(signal: Signal, deferred: ArrayDeque<Signal.RunEnded>) {
        if (reverseCompletion && signal is Signal.RunEnded) deferred.addLast(signal) else handle(signal)
    }

    /** A failure inside a coroutine of the session: the shell's thread has nobody to throw it to, so the next settle does. */
    fun uncaught(error: Throwable) {
        if (broken == null) broken = error
    }

    private fun handle(signal: Signal) {
        when (signal) {
            Signal.Wake -> Unit
            is Signal.InitWaiting -> {
                val gate = gates[signal.gate] ?: fail("an unknown gate ${signal.gate}")
                val record = current ?: fail("${signal.gate} waits, but no run has begun")
                log += "run ${record.run}: init waiting at ${gate.name}"
                when {
                    // Already ended: the wait is over at once, so the run is still on its way.
                    gate.ended -> Unit
                    record.state == RunState.ENDING && gate.gate.cancellable -> Unit
                    record.state == RunState.STARTING || record.state == RunState.ENDING -> {
                        record.state = RunState.AWAITING_INIT
                        record.awaitingGate = gate
                    }
                    else -> fail("${gate.name} waits while $record")
                }
            }
            is Signal.SourceSubscribed -> on(signal.run, "subscribed") { record ->
                if (!record.cancelled) {
                    check(record.state == RunState.STARTING) { "run ${record.run} subscribed while ${record.state}" }
                    record.state = RunState.SUBSCRIBED
                }
            }
            is Signal.FrameProcessed -> on(signal.run, "processed ${signal.seq}") { record ->
                check(record.processing == signal.seq) { "run ${record.run} processed ${signal.seq}, sent ${record.processing}" }
                record.processing = null
                if (record.state == RunState.PROCESSING) record.state = if (record.cancelled) RunState.ENDING else RunState.SUBSCRIBED
            }
            is Signal.RunEnded -> on(signal.run, "ended") { record ->
                check(!record.ended) { "run ${record.run} ended twice" }
                record.ended = true
                record.state = RunState.ENDED
                record.processing = null
                removeIfDone(record)
            }
            is Signal.ResetDone -> on(signal.run, "reset done") { record ->
                check(!record.resetDone) { "run ${record.run}'s reset was done twice" }
                record.resetDone = true
                removeIfDone(record)
            }
        }
    }

    private inline fun on(run: Int, what: String, change: (RunRecord) -> Unit) {
        val record = ledger[run]
        if (record == null) {
            log += "run $run: late $what"
            return
        }
        log += "run $run: $what"
        change(record)
    }

    private fun removeIfDone(record: RunRecord) {
        if (!record.ended || !record.resetDone) return
        ledger.remove(record.run)
        if (current === record) current = null
        log += "run ${record.run}: removed"
    }

    private fun fail(message: String): Nothing {
        val error = AssertionError("$message (at ${clock.nowMs} ms)")
        broken = error
        throw error
    }
}

/** The session's clock: milliseconds since start was pressed, and the pipeline's clock, which it moves. */
internal class SessionClock(private val advancePipeline: (Long) -> Unit, private val pipelineNow: () -> Long) {
    var nowMs = 0L
        private set

    /** The pipeline clock's reading at the session's time 0. */
    val origin: Long = pipelineNow()

    fun advanceTo(atMs: Long) {
        require(atMs >= nowMs) { "time goes forward only: $atMs after $nowMs" }
        advancePipeline(atMs - nowMs)
        nowMs = atMs
        check(pipelineNow() == origin + nowMs) { "the pipeline's clock moved on its own" }
    }
}
