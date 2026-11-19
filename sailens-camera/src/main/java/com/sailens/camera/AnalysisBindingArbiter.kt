package com.sailens.camera

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * The binding entry point for a source that controls all ways its analysis can stop.
 * Use one arbiter per exclusive camera. The backend binds only to the supplied private lifecycle,
 * creates a new analyzer for each binding, and signals a known loss before invalidating analysis.
 */
public class AnalysisBindingArbiter(
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val started = LinkedHashSet<Request>()
    private var binding: Binding? = null
    private var updating = false
    private var updatePending = false
    private var failed = false

    /** Register a host. Return its exact unbind action, or null after a clean binding failure. */
    public fun register(host: LifecycleOwner, bind: (Binding) -> AutoCloseable?): Request {
        checkMainThread()
        require(host.lifecycle.currentState != Lifecycle.State.DESTROYED)
        return Request(this, host, bind).also { host.lifecycle.addObserver(it.observer) }
    }

    /** Identifies one continuous analysis interval of a request. */
    public class Grant internal constructor(public val openedAtNanos: Long, public val generation: Long)

    public class Request internal constructor(
        private val arbiter: AnalysisBindingArbiter,
        internal val host: LifecycleOwner,
        internal val bind: (Binding) -> AutoCloseable?,
    ) : AutoCloseable {
        internal val observer = LifecycleEventObserver { source, event ->
            if (source === host) arbiter.onHostEvent(this, event)
        }
        internal var closed = false
        internal var resumed = false
        internal var generation = 0L

        /** Query on the main thread, including during stop and unbind callbacks. */
        public fun currentGrant(): Grant? = arbiter.currentGrant(this)

        public override fun close(): Unit = arbiter.unregister(this)
    }

    /** A binding and capture identity used only by the backend that received it. */
    public class Binding internal constructor(
        private val arbiter: AnalysisBindingArbiter,
        internal val request: Request,
    ) : LifecycleOwner {
        internal val registry = LifecycleRegistry(this)
        public override val lifecycle: Lifecycle get() = registry
        internal var retired = false
        internal var cameraOpen = false
        internal var unbind: AutoCloseable? = null

        /**
         * An analyzer keeps this binding with its captures. Queued frames keep the original identity
         * after shutdown and must still be checked against the request's current grant.
         */
        @Volatile
        public var captureGrant: Grant? = null
            internal set

        /** Call on main when the camera and this binding's analysis are open. */
        public fun cameraOpened(): Unit = arbiter.cameraOpened(this)

        /**
         * Report imminent analysis loss on main. This retires the binding and can run its unbind
         * action before returning. That action owns releasing this binding's resources.
         */
        public fun beforeAnalysisStops(): Unit = arbiter.beforeAnalysisStops(this)
    }

    private fun onHostEvent(request: Request, event: Lifecycle.Event) {
        checkMainThread()
        if (request.closed) return
        when (event) {
            Lifecycle.Event.ON_START -> started.add(request)
            Lifecycle.Event.ON_RESUME -> request.resumed = true
            Lifecycle.Event.ON_PAUSE -> request.resumed = false
            Lifecycle.Event.ON_STOP -> {
                started.remove(request)
                request.resumed = false
                retire(request)
            }
            Lifecycle.Event.ON_DESTROY -> {
                unregister(request)
                return
            }
            else -> return
        }
        reconcile()
    }

    private fun unregister(request: Request) {
        checkMainThread()
        if (request.closed) return
        request.closed = true
        started.remove(request)
        retire(request)
        request.host.lifecycle.removeObserver(request.observer)
        reconcile()
    }

    private fun retire(request: Request) {
        binding?.takeIf { it.request === request }?.retired = true
    }

    private fun currentGrant(request: Request): Grant? {
        checkMainThread()
        val current = binding ?: return null
        return current.captureGrant.takeIf {
            current.request === request && !current.retired && hostStarted(request)
        }
    }

    private fun cameraOpened(candidate: Binding) {
        checkMainThread()
        if (binding !== candidate || candidate.retired) return
        candidate.cameraOpen = true
        reconcile()
    }

    private fun beforeAnalysisStops(candidate: Binding) {
        checkMainThread()
        if (binding !== candidate || candidate.retired) return
        candidate.retired = true
        if (candidate.captureGrant == null) unregister(candidate.request) else reconcile()
    }

    private fun hostStarted(request: Request): Boolean =
        !request.closed && request.host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun winner(): Request? = started.lastOrNull(::hostStarted)

    private fun reconcile() {
        // Revocation is synchronous even when an external lifecycle or binding callback reenters.
        binding?.let { if (it.request !== winner()) it.retired = true }
        updatePending = true
        if (updating || failed) return
        updating = true
        try {
            while (updatePending) {
                updatePending = false
                advance()
            }
        } catch (error: Throwable) {
            // A throwing backend may still own a group. Keep its grant revoked and block further binds.
            binding?.retired = true
            failed = true
            throw error
        } finally {
            updating = false
        }
    }

    private fun advance() {
        val current = binding
        if (current == null) {
            val next = winner() ?: return
            val candidate = Binding(this, next)
            binding = candidate
            candidate.registry.currentState = Lifecycle.State.CREATED
            candidate.unbind = next.bind(candidate)
            if (candidate.unbind == null) unregister(next)
            updatePending = true
            return
        }
        if (current.retired) {
            try {
                if (current.registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    current.registry.currentState = Lifecycle.State.CREATED
                }
            } finally {
                try {
                    current.unbind?.close()
                } finally {
                    current.registry.currentState = Lifecycle.State.DESTROYED
                }
            }
            binding = null
            updatePending = true
            return
        }

        val request = current.request
        current.registry.currentState =
            if (request.resumed && request.host.lifecycle.currentState == Lifecycle.State.RESUMED) {
                Lifecycle.State.RESUMED
            } else Lifecycle.State.STARTED
        if (!current.retired && current.cameraOpen && current.captureGrant == null && request === winner()) {
            val openedAt = nowNanos()
            if (openedAt > 0L) current.captureGrant = Grant(openedAt, ++request.generation)
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper())
    }
}
