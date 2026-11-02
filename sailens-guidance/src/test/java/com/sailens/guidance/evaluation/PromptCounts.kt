package com.sailens.guidance.evaluation

import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys

/**
 * The counting lines of the frozen thresholds (B6, B7, C4 to C7, D1), over one arm's sentences with no error. A
 * regive is a line the arm's own cooldown reports, not one read from a sentence's wording.
 */
internal object PromptCounts {
    /** What a prompt admitted by today's gates is, when it credits a stage a metric-only prompt already completed. */
    enum class Repeat {
        /** It brings something new: a stage not yet said, or a hazard higher than it was said at. Reported only. */
        OVERLAP,

        /** It says again only what metric-only prompts said, at no higher level. C6: must be 0. */
        REPEAT,

        /** Nothing new, but today's cadence would repeat it for a hazard that was never metric-only. Reported only. */
        PERIODIC,
    }

    /** Completed prompts in the order they complete: what has been said, and at which of each hazard's levels. */
    class RepeatHistory {
        private val said = HashSet<StageKey>()
        private val saidMetric = HashSet<StageKey>()
        private val highest = HashMap<String, Int>()
        private val highestMetric = HashMap<String, Int>()

        /** Each stage a today's prompt credited that a metric-only prompt had completed: C6 as first written. */
        var stagesCreditedAgain = 0
            private set

        /**
         * @param credits the stages the prompt credits, ambiguous ones left out.
         * @param own each hazard's own priority as the prompt's event had it.
         * @return null when it is not a today's prompt crediting a stage a metric-only prompt completed.
         */
        fun onCompleted(credits: Set<StageKey>, own: Map<String, Int>, metricOnly: Boolean): Repeat? {
            val result = if (metricOnly || credits.none { it in saidMetric }) null else classify(credits, own)
            if (!metricOnly) stagesCreditedAgain += credits.count { it in saidMetric }
            said += credits
            if (metricOnly) saidMetric += credits
            for (hazard in credits.map { it.hazard }.toSet()) {
                val level = own[hazard] ?: continue
                highest.merge(hazard, level, ::maxOf)
                if (metricOnly) highestMetric.merge(hazard, level, ::maxOf)
            }
            return result
        }

        private fun classify(credits: Set<StageKey>, own: Map<String, Int>): Repeat {
            val hazards = credits.map { it.hazard }.toSet()
            fun above(limits: Map<String, Int>) = hazards.any { (own[it] ?: Int.MIN_VALUE) > (limits[it] ?: Int.MIN_VALUE) }
            return when {
                credits.any { it !in said } || above(highest) -> Repeat.OVERLAP
                credits.all { it in saidMetric } && !above(highestMetric) -> Repeat.REPEAT
                else -> Repeat.PERIODIC
            }
        }
    }

    /** One arm's counts on some captures. */
    data class Counts(
        val minutes: Double = 0.0,
        val stationaryMinutes: Double = 0.0,
        val offered: Int = 0,
        val completed: Int = 0,
        val completedStationary: Int = 0,
        /** Stages that went from waiting to be given again to given again: B7 as defined. */
        val regivenStages: Int = 0,
        /** Sentences carrying at least one of those: how many more sentences the user hears. */
        val regivenSentences: Int = 0,
        val mostRegivesOfAStage: Int = 0,
        val metricOnlyOffered: Int = 0,
        val metricOnlyCompleted: Int = 0,
        val metricOnlyNeverWithin2m: Int = 0,
        val metricOnlyShortLived: Int = 0,
        val overlap: Int = 0,
        val repeat: Int = 0,
        val periodic: Int = 0,
        val promptsCreditedAgain: Int = 0,
        val stagesCreditedAgain: Int = 0,
        val pathComplexOffered: Int = 0,
        val fallbackWordings: Int = 0,
    ) {
        operator fun plus(o: Counts) = Counts(
            minutes + o.minutes,
            stationaryMinutes + o.stationaryMinutes,
            offered + o.offered,
            completed + o.completed,
            completedStationary + o.completedStationary,
            regivenStages + o.regivenStages,
            regivenSentences + o.regivenSentences,
            maxOf(mostRegivesOfAStage, o.mostRegivesOfAStage),
            metricOnlyOffered + o.metricOnlyOffered,
            metricOnlyCompleted + o.metricOnlyCompleted,
            metricOnlyNeverWithin2m + o.metricOnlyNeverWithin2m,
            metricOnlyShortLived + o.metricOnlyShortLived,
            overlap + o.overlap,
            repeat + o.repeat,
            periodic + o.periodic,
            promptsCreditedAgain + o.promptsCreditedAgain,
            stagesCreditedAgain + o.stagesCreditedAgain,
            pathComplexOffered + o.pathComplexOffered,
            fallbackWordings + o.fallbackWordings,
        )

        val perMinute get() = if (minutes > 0) completed / minutes else 0.0
        val stationaryPerMinute get() = if (stationaryMinutes > 0) completedStationary / stationaryMinutes else 0.0
        val neverWithin2mPerMinute get() = if (minutes > 0) metricOnlyNeverWithin2m / minutes else 0.0
    }

    /** What is known of a hazard's whole life, for C4 and C5. */
    interface Lives {
        fun everWithin2m(hazard: String): Boolean

        fun shorterThan1s(hazard: String): Boolean
    }

    fun of(
        log: Collection<ProtocolDriver.Offered>,
        credits: Map<String, List<StageKey>>,
        lives: Lives,
        minutes: Double,
        stationaryMinutes: Double,
        isFallback: (String) -> Boolean,
    ): Counts {
        val prompts = log.filter { it.event != null }
        val metricOnly = prompts.filter { it.event!!.isMetricOnly }
        val history = RepeatHistory()
        val repeats = java.util.EnumMap<Repeat, Int>(Repeat::class.java)
        var neverWithin = 0
        var short = 0
        val spoken = prompts.filter { it.endAtMs != null }.sortedBy { it.endAtMs }
        for (sentence in spoken) {
            val event = sentence.event!!
            history.onCompleted(credits[sentence.id].orEmpty().toSet(), ownPriorities(event), event.isMetricOnly)?.let { repeats.merge(it, 1, Int::plus) }
            if (event.isMetricOnly) {
                val primaries = event.components.map { it.primaryTrackId.toString() }
                if (primaries.none(lives::everWithin2m)) neverWithin++
                if (primaries.all(lives::shorterThan1s)) short++
            }
        }
        val regives = prompts.flatMap { it.regivenStages }.groupingBy { it }.eachCount()
        return Counts(
            minutes = minutes,
            stationaryMinutes = stationaryMinutes,
            offered = prompts.size,
            completed = spoken.size,
            completedStationary = spoken.count { it.stationary },
            regivenStages = regives.values.sum(),
            regivenSentences = prompts.count { it.regivenStages.isNotEmpty() },
            mostRegivesOfAStage = regives.values.maxOrNull() ?: 0,
            metricOnlyOffered = metricOnly.size,
            metricOnlyCompleted = spoken.count { it.event!!.isMetricOnly },
            metricOnlyNeverWithin2m = neverWithin,
            metricOnlyShortLived = short,
            overlap = repeats[Repeat.OVERLAP] ?: 0,
            repeat = repeats[Repeat.REPEAT] ?: 0,
            periodic = repeats[Repeat.PERIODIC] ?: 0,
            promptsCreditedAgain = repeats.values.sum(),
            stagesCreditedAgain = history.stagesCreditedAgain,
            pathComplexOffered = prompts.count { it.event!!.messageKey == SceneEventMessageKeys.PATH_COMPLEX },
            fallbackWordings = log.count { isFallback(it.id) },
        )
    }

    private fun ownPriorities(event: SceneEvent): Map<String, Int> = buildMap {
        for (component in event.components) for (hazard in component.hazards) put(hazard.trackId.toString(), hazard.ownPriority.value)
    }
}

/**
 * What a run can no longer vouch for, from the first thing it does not model on: an X3 or X5 window, or a frame that
 * took longer than the branches' skew. Every stage whose deadline is at or after that moment counts as undeterminable.
 * The state after such a moment could be shown unrelated to it (threshold note §3.3), but that is not done: never
 * recovering can only count more.
 */
internal class UnmodeledFrom(windows: List<LongRange>, overrunFrames: List<Long>) {
    val windowCount = windows.size
    val overrunCount = overrunFrames.size
    val atMs: Long? = (windows.map { it.first } + overrunFrames).minOrNull()

    fun covers(deadlineMs: Long): Boolean = atMs != null && deadlineMs >= atMs
}
