package com.sailens.shell.guidance.screen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Orders a Guidance run's start and stop against [reset] (the processors' reset,
 * `StopSceneAnalysisUseCase`).
 *
 * Cancelling a run does not stop the frame it is processing: the pipeline runs on
 * `Dispatchers.Default`, and its analysis and decision steps have no suspension point, so that
 * frame still finishes after `cancel()` returns. A reset made right then let the frame decide on
 * the freshly reset cooldown and leave its record, and one counted decision, to the next run (a
 * 2026-10-27 capture lost exact replay that way). So [reset] waits until every earlier run has
 * finished, and a new run starts only after every earlier run and reset.
 *
 * The wait is on [barrier], not on the jobs themselves: a job cancelled while it waits completes at
 * once, and joining it would no longer wait for what it was waiting for. A link of the barrier
 * completes only when its own job and everything before it have.
 */
internal class GuidanceRunSequencer(
    private val scope: CoroutineScope,
    private val reset: () -> Unit,
) {
    private var run: Job? = null

    /** Completes once every run and reset handed to this sequencer so far has finished. */
    private var barrier: Job? = null

    /** Starts [block] as the new run, after every earlier run and reset has finished. */
    fun start(block: suspend CoroutineScope.() -> Unit): Job {
        run?.cancel()
        val earlier = barrier
        return scope.launch {
            earlier?.join()
            // Cancelled while it waited (a stop, or the owner going away): the run never begins.
            ensureActive()
            block()
        }.also {
            run = it
            barrier = link(it, earlier)
        }
    }

    /**
     * Cancels the run and resets once every earlier run has finished, in [on]; then runs [then].
     * With an immediate dispatcher and nothing still running, the reset happens before this
     * returns, as it did before the wait was added.
     */
    fun stop(on: CoroutineScope = scope, then: suspend () -> Unit = {}): Job {
        run?.cancel()
        val earlier = barrier
        return on.launch {
            earlier?.join()
            reset()
            then()
        }.also { barrier = link(it, earlier) }
    }

    private fun link(job: Job, earlier: Job?): Job {
        val done = Job()
        job.invokeOnCompletion {
            if (earlier == null) done.complete() else earlier.invokeOnCompletion { done.complete() }
        }
        return done
    }
}
