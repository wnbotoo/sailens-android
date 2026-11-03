package com.sailens.guidance.evaluation

import java.io.File

/**
 * An acceptance run reads one whole frozen D1.8 set ([D18Sets]) or nothing: every capture of it, none twice, nothing
 * else, each complete and replaying with no problem (threshold document R7). Checked before any metric is computed, so a
 * missing or extra capture cannot be noticed only after its numbers are seen.
 */
internal object AcceptanceSet {
    /** A capture found under the path: its session id, null when it is no model-regression capture, and why it cannot be scored. */
    class Found(val directory: String, val id: String?, val unusable: String?)

    fun of(directory: File, result: PromptPolicyCaptureEvaluation.Result?): Found = Found(
        directory.name,
        result?.id,
        when {
            result == null -> "not a model-regression capture"
            !result.complete || result.evaluation == null -> "not complete"
            result.problems.isNotEmpty() -> "replay problems: ${result.problems}"
            else -> null
        },
    )

    /** What keeps [found] from being one whole frozen set; empty when it is one. */
    fun problems(found: List<Found>): List<String> = buildList {
        for (f in found) f.unusable?.let { add("${f.directory}: $it") }
        val ids = found.mapNotNull { it.id }
        for ((id, n) in ids.groupingBy { it }.eachCount()) if (n > 1) add("$id: found $n times")
        for (id in ids.toSet()) if (D18Sets.of(id) == null) add("$id: in neither frozen set")
        val groups = ids.mapNotNullTo(HashSet()) { D18Sets.of(it) }
        when (groups.size) {
            0 -> add("no capture of a frozen set")
            1 -> {
                val group = groups.single()
                val all = if (group == D18Sets.Group.CALIBRATION) D18Sets.CALIBRATION else D18Sets.SEALED
                val missing = all - ids.toSet()
                if (missing.isNotEmpty()) add("${group.name.lowercase()}: ${missing.size} of ${all.size} missing: ${missing.sorted()}")
            }
            else -> add("calibration and sealed captures together")
        }
    }
}
