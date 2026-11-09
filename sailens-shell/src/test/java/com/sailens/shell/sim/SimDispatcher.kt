package com.sailens.shell.sim

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * The shell side's one thread, as the main thread is in the app: every shell coroutine of a simulated session runs
 * here -- the analysis collector with its `onStart`, `catch` and `collectLatest` coroutines, the run sequencer's runs
 * and resets, the state-flow collectors, the stall loop. A task is queued, first in first out, and runs only when the
 * test thread [drain]s the queue, so every shell call happens on the test thread in an order fixed by the session.
 *
 * A task queued from another thread (the pipeline's, resuming the collector with a result) calls [onForeignDispatch],
 * so that the director, blocked waiting for a handshake, wakes up and drains.
 *
 * `delay` is virtual: a coroutine that delays is resumed by an event [scheduleDelay] puts in the director's queue at
 * the time it is due, and cancelling the coroutine takes the event out. The stall loop's 500 ms polls are such events.
 */
@OptIn(InternalCoroutinesApi::class)
internal class SimDispatcher(private val onForeignDispatch: () -> Unit) : CoroutineDispatcher(), Delay {
    private val testThread: Thread = Thread.currentThread()
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    /** Puts `resume` in the director's queue [delayMs] from now; the returned function takes it out again. */
    lateinit var scheduleDelay: (delayMs: Long, resume: () -> Unit) -> (() -> Unit)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.add(block)
        if (Thread.currentThread() !== testThread) onForeignDispatch()
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val cancel = scheduleDelay(timeMillis.coerceAtLeast(0)) { continuation.resumeWith(Result.success(Unit)) }
        continuation.invokeOnCancellation { cancel() }
    }

    /** Runs queued tasks, and those they queue, until none is left. Test thread only. */
    fun drain() {
        check(Thread.currentThread() === testThread) { "the simulated main thread is drained from the test thread only" }
        while (true) {
            val task = tasks.poll() ?: return
            task.run()
        }
    }

    val isIdle: Boolean get() = tasks.isEmpty()
}
