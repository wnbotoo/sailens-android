package com.sailens.guidance.service

/**
 * An optional wrapper around the [TraceService] Guidance uses, applied once when the service is
 * built (`GuidanceModule`). The debug build binds one that adds field capture; release binds none.
 *
 * A decorator hook rather than a DI override: an override would have to resolve the very service it
 * replaces, and it would have to duplicate how the base service (file or no-op) is chosen.
 *
 * A decorator must forward every call to [base] and must never make a call fail or block because
 * of its own work.
 */
fun interface TraceServiceDecorator {
    fun decorate(base: TraceService): TraceService
}
