package com.sailens.guidance.evaluation

import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.processor.decision.EventGenerator
import java.util.Locale
import kotlin.math.abs

/**
 * The protected stages of threshold note §4.2 (P), taken from the device's exact run under R9, and how
 * well a model run reproduces them (A12). P is the device's, not the model's: whatever the model does,
 * a stage the device spoke to the end stays in it.
 */
internal object ProtectedStages {
    private const val PATH_BLOCKED = "blocked"
    private val COVERED_LENS = "sensor:${SceneEventMessageKeys.CAMERA_BLOCKED.removePrefix("event_")}"
    private val FRONT_CLASSES = setOf("vehicle/front", "person/front")

    /** Of the kinds P protects: every CRITICAL and NEAR stage, a person ahead's first, a blocked path, a covered lens. */
    fun isProtectedKind(row: PromptPolicyEvaluation.StageRow): Boolean = when {
        row.ambiguousOnly -> false
        row.trackless -> row.hazardClass == PATH_BLOCKED || row.hazardClass == COVERED_LENS
        row.stage == Stage.CRITICAL || row.stage == Stage.NEAR -> true
        else -> row.hazardClass == "person/front"
    }

    /** The stages A12a and M5 hold to the stricter bar: the CRITICAL stage of a vehicle or person ahead, a blocked path. */
    fun isFrontCritical(row: PromptPolicyEvaluation.StageRow): Boolean =
        row.hazardClass == PATH_BLOCKED || (row.stage == Stage.CRITICAL && row.hazardClass in FRONT_CLASSES)

    fun of(device: PromptPolicyEvaluation.Result): List<PromptPolicyEvaluation.StageRow> =
        device.stages.filter { it.completedAtNanos != null && isProtectedKind(it) }

    /**
     * Which stages each prompt the device offered speaks for, by the frozen credit rules, in [frames]'
     * order: the same walk as [PromptPolicyEvaluation.evaluate] makes, ambiguous credit left out.
     */
    fun creditsOf(frames: List<PromptPolicyEvaluation.Frame>, generator: EventGenerator): Map<String, List<StageKey>> {
        val episodes = StateEpisodes()
        val credits = LinkedHashMap<String, List<StageKey>>()
        for (f in frames) {
            for (event in f.generated) {
                if (event.category in StateEpisodes.TRACKLESS) episodes.observe(StateEpisodes.keyOf(event), f.decidedAt, f.seq)
            }
            val id = f.offeredId ?: continue
            val offered: SceneEvent = f.decided.firstOrNull() ?: continue
            credits[id] = if (offered.category in StateEpisodes.TRACKLESS) {
                listOfNotNull(episodes.current(StateEpisodes.keyOf(offered), f.decidedAt)?.let { StageKey(it.id, Stage.FIRST) })
            } else {
                StageCredit.credits(StageCredit.spoken(offered), f.groups, generator).filter { it.counts }.map { StageKey(it.trackId.toString(), it.stage) }
            }
        }
        return credits
    }

    /** Hazard ids are per capture, so this is counted per capture and summed. */
    data class M5Count(val regressions: Int, val regressionsFront: Int, val undeterminable: Int, val undeterminableFront: Int) {
        operator fun plus(o: M5Count) = M5Count(regressions + o.regressions, regressionsFront + o.regressionsFront, undeterminable + o.undeterminable, undeterminableFront + o.undeterminableFront)

        override fun toString() = "regressions $regressions (ahead CRITICAL and blocked $regressionsFront), undeterminable $undeterminable (ahead CRITICAL and blocked $undeterminableFront)"
    }

    /** One capture or several: P against what a model run spoke. */
    class Reproduction(private val rows: List<Row>, private val deviceCompleted: Int, private val modelCompleted: Int) {
        class Row(
            val key: StageKey,
            val frontCritical: Boolean,
            /** In P: the device spoke it to the end. */
            val inP: Boolean,
            val deviceNs: Long?,
            val modelNs: Long?,
            /** The model's run has this stage at all (at another phone height it may not). */
            val modelHasStage: Boolean,
        )

        val p = rows.filter { it.inP }
        private val modelSpoke = rows.filter { it.modelNs != null }

        private fun reproduced(row: Row) = row.modelNs != null && row.deviceNs != null && abs(row.modelNs - row.deviceNs) <= 500_000_000L

        /**
         * What M5 makes of the stages [notKept] an arm did not keep: a stage the baseline reproduces is a
         * regression outright; one it does not is undeterminable.
         */
        fun m5Of(notKept: Set<StageKey>): M5Count {
            val hit = p.filter { it.key in notKept }
            val regressions = hit.filter(::reproduced)
            val undeterminable = hit.filterNot(::reproduced)
            return M5Count(regressions.size, regressions.count { it.frontCritical }, undeterminable.size, undeterminable.count { it.frontCritical })
        }

        val reproducedAll = p.count(::reproduced)
        val frontP = p.filter { it.frontCritical }
        val reproducedFront = frontP.count(::reproduced)
        val notReproducedAll = p.size - reproducedAll
        val notReproducedFront = frontP.size - reproducedFront
        val spokenLater = p.count { it.modelNs != null && !reproduced(it) }
        val neverSpoken = p.count { it.modelNs == null }
        val stageAbsentInRun = p.count { !it.modelHasStage }
        val precisionHits = modelSpoke.count { it.deviceNs != null }
        private val errorsMs = rows.filter { it.modelNs != null && it.deviceNs != null }.map { abs(it.modelNs!! - it.deviceNs!!) / 1e6 }.sorted()
        val p95Ms: Double = if (errorsMs.isEmpty()) 0.0 else errorsMs[((errorsMs.size - 1) * 0.95).toInt()]
        val countDifference: Double = if (deviceCompleted == 0) 0.0 else abs(modelCompleted - deviceCompleted).toDouble() / deviceCompleted

        val a12a = ratio(reproducedFront, frontP.size) >= 0.95 && ratio(reproducedAll, p.size) >= 0.90
        val a12b = ratio(precisionHits, modelSpoke.size) >= 0.90
        val a12c = p95Ms <= 250.0
        val a12d = countDifference <= 0.10
        val pass: Boolean get() = a12a && a12b && a12c && a12d

        operator fun plus(other: Reproduction) = Reproduction(rows + other.rows, deviceCompleted + other.deviceCompleted, modelCompleted + other.modelCompleted)

        fun render(label: String): String = buildString {
            appendLine("  $label")
            appendLine(
                "    P ${p.size}: reproduced within 0.5 s $reproducedAll (${pct(reproducedAll, p.size)}), not reproduced $notReproducedAll " +
                    "(spoken beyond 0.5 s $spokenLater, never spoken $neverSpoken, of those stage absent in this run $stageAbsentInRun)",
            )
            appendLine("    of P, ahead CRITICAL and blocked ${frontP.size}: reproduced $reproducedFront (${pct(reproducedFront, frontP.size)}), not reproduced $notReproducedFront")
            appendLine("    precision: the model spoke ${modelSpoke.size} protected-kind stages, the device spoke $precisionHits of them (${pct(precisionHits, modelSpoke.size)})")
            appendLine("    first completion |model - device| p95 ${String.format(Locale.ROOT, "%.0f", p95Ms)} ms over ${errorsMs.size} stages")
            appendLine("    prompts spoken to the end: device $deviceCompleted, model $modelCompleted, difference ${String.format(Locale.ROOT, "%.1f", countDifference * 100)}%")
        }

        private fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b
        private fun pct(a: Int, b: Int) = if (b == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * a / b)

        companion object {
            fun of(device: PromptPolicyEvaluation.Result, run: ClosedLoopRun.Result): Reproduction {
                val deviceRows = device.stages.filter(::isProtectedKind).associateBy { StageKey(it.hazard, it.stage) }
                val modelRows = run.evaluation.stages.filter(::isProtectedKind).associateBy { StageKey(it.hazard, it.stage) }
                val rows = (deviceRows.keys + modelRows.keys).map { key ->
                    val d = deviceRows[key]
                    val m = modelRows[key]
                    Row(
                        key = key,
                        frontCritical = isFrontCritical(d ?: m!!),
                        inP = d?.completedAtNanos != null,
                        deviceNs = d?.completedAtNanos,
                        modelNs = run.completedAtNanos[key],
                        modelHasStage = m != null,
                    )
                }
                return Reproduction(rows, device.offersCompleted, run.evaluation.offersCompleted)
            }
        }
    }

    /**
     * How often today's policy says the same stage of the same hazard to the end more than once on the
     * device, and how far apart: the evidence for the C6 exemption of today's own rhythm. C has no
     * metric-only prompt, so this can only show the rhythm, not the three classes.
     */
    class Repeats(private val completionsMs: List<Pair<Stage, List<Long>>>) {
        fun render(): String = buildString {
            for (stage in Stage.entries) {
                val rows = completionsMs.filter { it.first == stage }
                val repeated = rows.filter { it.second.size > 1 }
                val gaps = repeated.flatMap { (_, times) -> times.zipWithNext { a, b -> b - a } }.sorted()
                append("    $stage: stages spoken ${rows.size}, spoken more than once ${repeated.size}, extra completions ${gaps.size}")
                if (gaps.isNotEmpty()) {
                    fun q(f: Double) = gaps[((gaps.size - 1) * f).toInt()] / 1000.0
                    append(String.format(Locale.ROOT, "; gap s min %.1f, p25 %.1f, p50 %.1f, p75 %.1f, max %.1f", gaps.first() / 1000.0, q(0.25), q(0.5), q(0.75), gaps.last() / 1000.0))
                }
                appendLine()
            }
        }

        operator fun plus(other: Repeats) = Repeats(completionsMs + other.completionsMs)

        companion object {
            fun of(credits: Map<String, List<StageKey>>, completedAtNanos: (String) -> Long?): Repeats {
                val byStage = HashMap<StageKey, MutableList<Long>>()
                for ((id, stages) in credits) {
                    val at = completedAtNanos(id) ?: continue
                    for (stage in stages) byStage.getOrPut(stage) { mutableListOf() } += at / 1_000_000
                }
                return Repeats(byStage.map { (key, times) -> key.stage to times.sorted() })
            }
        }
    }
}
