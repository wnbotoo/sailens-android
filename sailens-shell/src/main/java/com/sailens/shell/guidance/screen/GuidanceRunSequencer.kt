package com.sailens.shell.guidance.screen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Orders a Guidance run's start and stop against [reset] (the processors' reset,
 * `StopSceneAnalysisUseCase`).
 *
 * Cancelling a run does not stop the frame it is processing: the pipeline runs on
 * `Dispatchers.Default`, and its analysis and decision steps have no suspension point, so that
 * frame still finishes after `cancel()` returns. A reset made right then let the frame decide on
 * the freshly reset cooldown and leave its record, and one counted decision, to the next run (a
 * 2026-10-27 capture lost exact replay that way). So [reset] waits until the run has finished, and
 * a new run starts only after a pending reset.
 */
internal class GuidanceRunSequencer(
    private val scope: CoroutineScope,
    private val reset: () -> Unit,
) {
    /** The latest run, kept after it stops: joining a finished job costs nothing. */
    private var run: Job? = null
    private var pendingReset: Job? = null

    /** Starts [block] as the new run, after the previous run and any pending reset have finished. */
    fun start(block: suspend CoroutineScope.() -> Unit): Job {
        val previousRun = run
        val previousReset = pendingReset
        previousRun?.cancel()
        return scope.launch {
            previousRun?.join()
            previousReset?.join()
            block()
        }.also { run = it }
    }

    /**
     * Cancels the run and resets once its last frame is done, in [on]; then runs [then]. With an
     * immediate dispatcher and a run that has already finished, the reset happens before this
     * returns, as it did before the wait was added.
     */
    fun stop(on: CoroutineScope = scope, then: suspend () -> Unit = {}): Job {
        val stopping = run
        stopping?.cancel()
        return on.launch {
            stopping?.join()
            reset()
            then()
        }.also { pendingReset = it }
    }
}
