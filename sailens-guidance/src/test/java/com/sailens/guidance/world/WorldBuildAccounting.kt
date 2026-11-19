package com.sailens.guidance.world

import java.util.UUID

/** Reduces a bounded set of acceptance observations in submission order. */
internal class WorldBuildAccounting {
    private data class FrameKey(val run: UUID, val sequence: Long)
    private data class Outcome(val complete: Boolean, val projectable: Boolean, val spatial: Boolean)
    private val first = linkedMapOf<FrameKey, Outcome>()
    private var submissions = 0

    fun record(run: UUID, sequence: Long, built: BuiltWorld?) {
        val snapshot = built?.snapshot
        val value = Outcome(snapshot != null, snapshot?.projectable == true, snapshot?.hasSpatialEvidence == true)
        submissions++
        first.putIfAbsent(FrameKey(run, sequence), value)
    }

    fun counts() = Counts(
        submissions, first.size, first.values.count { it.complete },
        first.values.count { it.projectable }, first.values.count { it.spatial },
    )

    data class Counts(
        val submissions: Int,
        val distinct: Int,
        val complete: Int,
        val projectable: Int,
        val spatial: Int,
    )
}
