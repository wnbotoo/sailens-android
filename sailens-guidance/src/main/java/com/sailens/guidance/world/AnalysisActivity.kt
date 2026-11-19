package com.sailens.guidance.world

/**
 * Producer-side lifetime for a source that controls analysis invalidation. Capture callbacks keep
 * the lease from their own activation, and the owner consults the live grant at admission and read.
 */
internal class AnalysisActivity(
    private val nowNanos: () -> Long,
    private val hostStarted: () -> Boolean,
) {
    private val controller = Thread.currentThread()
    private var generations = 0L
    private var invalidating = false
    @Volatile private var active: AnalysisGrant? = null

    fun currentGrant(): AnalysisGrant? = if (hostStarted()) active else null

    fun grantAfterActivation(): AnalysisGrant? {
        check(Thread.currentThread() === controller)
        if (invalidating || !hostStarted()) return null
        active?.let { return it }
        val boundary = nowNanos()
        if (boundary <= 0L) return null
        return AnalysisGrant(boundary, ++generations).also { active = it }
    }

    fun revokeBeforeInvalidating(expected: AnalysisGrant, invalidate: () -> Unit): Boolean {
        check(Thread.currentThread() === controller)
        if (active !== expected) return false
        active = null
        invalidating = true
        try {
            invalidate()
        } finally {
            invalidating = false
        }
        return true
    }
}
