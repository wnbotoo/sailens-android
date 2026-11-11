package com.sailens.runtime.accelerator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Who may start work on the shared GPU (docs/architecture.md §6.9). Mechanism only: the tiers are
 * assigned by the callers' wiring, and nothing here knows Guidance or Describe.
 *
 * A higher tier opens a yield interval ([yieldFor]); while one is open, [tryAdmit] refuses every lower
 * tier. Work already admitted is not interrupted (a LiteRT run cannot be cancelled midway): it finishes,
 * and nothing lower starts after it. [Priority.REALTIME] is always admitted and never waits.
 *
 * The real-time models (semantic, obstacle) do not go through this at all: their path stays as it is.
 * The depth model is [Priority.BACKGROUND]. A later model that should push the depth model aside, the
 * scene description for example, opens an [Priority.INTERACTIVE] interval while it runs.
 */
class AcceleratorCoordinator {

    enum class Priority { REALTIME, INTERACTIVE, BACKGROUND }

    /** How many yield intervals are open, per tier, and how many runs are admitted, per tier. */
    data class State(
        val openIntervals: Map<Priority, Int> = emptyMap(),
        val admitted: Map<Priority, Int> = emptyMap(),
    )

    private val lock = Any()
    private val intervals = IntArray(Priority.entries.size)
    private val running = IntArray(Priority.entries.size)
    private val _state = MutableStateFlow(State())

    /** For the debug panel and traces. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Opens a yield interval for [priority]; closing it is idempotent. Intervals nest and are counted. */
    fun yieldFor(priority: Priority): AutoCloseable {
        synchronized(lock) {
            intervals[priority.ordinal]++
            publish()
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    intervals[priority.ordinal]--
                    publish()
                }
            }
        }
    }

    /** Whether work of [priority] would be refused right now. A cheap check before preparing work. */
    fun isYielding(priority: Priority): Boolean = synchronized(lock) { refuses(priority) }

    /**
     * Admits work of [priority] unless a higher tier's interval is open. The returned [Admission] is
     * closed when the work ends; null when refused.
     */
    fun tryAdmit(priority: Priority): Admission? = synchronized(lock) {
        if (refuses(priority)) return null
        running[priority.ordinal]++
        publish()
        Admission(priority)
    }

    private fun refuses(priority: Priority): Boolean =
        priority != Priority.REALTIME && (0 until priority.ordinal).any { intervals[it] > 0 }

    private fun publish() {
        _state.value = State(
            openIntervals = Priority.entries.filter { intervals[it.ordinal] > 0 }.associateWith { intervals[it.ordinal] },
            admitted = Priority.entries.filter { running[it.ordinal] > 0 }.associateWith { running[it.ordinal] },
        )
    }

    /** One admitted run. [close] ends it; closing twice counts once. */
    inner class Admission internal constructor(val priority: Priority) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    running[priority.ordinal]--
                    publish()
                }
            }
        }
    }
}
