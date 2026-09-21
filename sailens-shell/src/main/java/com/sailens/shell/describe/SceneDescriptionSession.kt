package com.sailens.shell.describe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The job and token of one scene description — the mechanism [SceneDescriptionCoordinator] uses to
 * make "Guidance outranks Describe" hold. It is not shared on its own: there is exactly one
 * coordinator, and this is private to it. Giving each screen its own session is the bug the
 * coordinator exists to prevent.
 *
 * Cancelling a coroutine is not instant: a chunk already decoded and dispatched can still reach the
 * collector after `cancel()` returns, and a `flow { }` gives no guarantee that the body will be
 * skipped. For ordinary work that is harmless. Here it is not — a leftover clause spoken after an
 * obstacle warning is a sentence about scenery landing on top of "step down ahead", and a person
 * who cannot see the screen has no way to tell which one was current.
 *
 * So ownership is explicit. Each description carries the token it started with, and every output it
 * wants to produce is gated on [isCurrent]. [cancel] both cancels the job and invalidates the
 * token, which closes the window: whatever is still in flight can run, but it cannot speak.
 *
 * This is product policy and deliberately lives in the shell. sailens-output stays a mechanism that
 * does not know Guidance and Describe exist (architecture.md §6.6).
 */
class SceneDescriptionSession {
    private var job: Job? = null

    /**
     * Advanced on every start and every cancellation, so a stale description can always tell that
     * it no longer owns the output channel. Atomic because the value is written by whoever
     * preempts and read by the description coroutine.
     */
    private val token = AtomicLong(0L)

    val isDescribing: Boolean
        get() = job?.isActive == true

    /**
     * Starts a description in [scope], replacing any already running. [block] receives the token
     * that identifies it for the whole of its life.
     */
    fun start(scope: CoroutineScope, block: suspend (token: Long) -> Unit): Job {
        cancel()
        val startedToken = token.get()
        return scope.launch { block(startedToken) }.also { job = it }
    }

    /**
     * Whether [describeToken] still owns the speech/announcement channel. False once something
     * preempted it or a newer description replaced it.
     */
    fun isCurrent(describeToken: Long): Boolean = token.get() == describeToken

    /**
     * Cancels the in-flight description and invalidates its token.
     *
     * @return true when a description was actually running, so the caller knows whether anything
     *   needs flushing out of the speech queue.
     */
    fun cancel(reason: String? = null): Boolean {
        val running = job?.isActive == true
        job?.cancel(reason?.let { CancellationException(it) })
        job = null
        token.incrementAndGet()
        return running
    }
}
