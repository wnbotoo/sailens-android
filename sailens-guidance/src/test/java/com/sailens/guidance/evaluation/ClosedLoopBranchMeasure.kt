package com.sailens.guidance.evaluation

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.trace.regression.RegressionReplay
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R10's closed loop for today's policy on the legacy output path: each branch carries its own cooldown,
 * a refused offer is revoked there and comes again on a later frame, and two branches merge only when
 * speaker, cooldown and target marks are all equal. What a null experiment, an arm that changes
 * nothing, would score against P under M5: the floor under any real arm's regressions.
 */
internal class ClosedLoopBranchMeasure(
    private val analysis: AnalysisConfig,
    private val durations: SpeakerDurations,
    private val measuredLabel: String,
    targets: List<BranchLoadMeasure.Target>,
    queued: List<SpeakerModel.Queued>,
    heldAfterDone: Boolean,
    frameSkewMs: Long,
    private val ignoreDeadRecords: Boolean = false,
    private val maxBranches: Int = 64,
) {
    private val wordings = HashMap<String, Wording>()
    private val kernel = ClosedLoopSpeakerBranches(
        epsilonMs = { id ->
            val measured = wordings[id]?.let { durations.of(it).first == measuredLabel } ?: true
            if (measured) SpeakerModel.END_ERROR_BOUND_MS else 2 * SpeakerModel.END_ERROR_BOUND_MS
        },
        heldAfterDone = heldAfterDone,
        frameSkewMs = frameSkewMs,
    )
    private val pendingQueued = ArrayDeque(queued.sortedBy { it.atMs })
    private val generator = EventGenerator(analysis)
    private val merger = EventMerger()
    private val resolver = EventConflictResolver()
    private val episodes = StateEpisodes()

    private val pending = targets.associateBy { it.key }.toMutableMap()
    private val creditsOf = HashMap<String, List<StageKey>>()
    private val targetsAll = targets.associateBy { it.key }

    private var now = 0L

    private inner class Branch(
        val speaker: ClosedLoopSpeakerBranches.Speaker,
        val cooldown: CooldownManager,
        val kept: Map<StageKey, Long>,
    ) {
        private val identity = Triple(speaker, canonical(cooldown), kept)
        override fun equals(other: Any?) = other is Branch && identity == other.identity
        override fun hashCode() = identity.hashCode()
    }

    /**
     * With [ignoreDeadRecords], a record past the longest cooldown its key can have under any event and
     * scale (threshold note §3.3's bound) and an emission out of the storm window are left out: neither
     * can affect a later decision, so two states that differ only there behave the same.
     */
    private fun canonical(cooldown: CooldownManager): CooldownManager.Canonical {
        val state = cooldown.canonicalState()
        if (!ignoreDeadRecords) return state
        fun live(records: Map<String, Pair<Long, com.sailens.guidance.model.common.EventPriority>>) =
            records.filter { (key, record) -> now - record.first < longestCooldownMs(key) }
        return state.copy(
            records = live(state.records),
            confirmed = live(state.confirmed),
            emissions = state.emissions.filter { now - it <= STORM_WINDOW_MS },
        )
    }

    private fun longestCooldownMs(key: String): Long = when {
        key.startsWith("obstacle_") || key == "blocked" || key == "path_complex" -> 8_000
        key.startsWith("road_warning") || key.startsWith("traffic_light") -> 12_000
        key.startsWith("sensor_quality") -> 15_000
        else -> 60_000
    }

    private var branches = listOf(Branch(kernel.start(), CooldownManager(), emptyMap()))
    private var capHit = false
    private var highest = 1
    private val keptKeys = mutableSetOf<StageKey>()
    private val lostKeys = mutableSetOf<StageKey>()
    private val undeterminableKeys = mutableSetOf<StageKey>()

    class Result(
        val maxBranches: Int,
        val capHit: Boolean,
        val kept: Set<StageKey>,
        val lost: Set<StageKey>,
        val undeterminable: Set<StageKey>,
    )

    private fun apply(branch: Branch, step: ClosedLoopSpeakerBranches.Step): Branch {
        var marks = branch.kept
        for (outcome in step.outcomes) {
            if (outcome !is ClosedLoopSpeakerBranches.Spoken) continue
            for (key in creditsOf[outcome.id].orEmpty()) {
                val target = pending[key] ?: continue
                if (key !in marks && outcome.endAtMs <= target.deadlineMs) marks = marks + (key to outcome.endAtMs)
            }
        }
        return Branch(step.speaker, branch.cooldown, marks)
    }

    fun onScene(scene: RegressionReplay.SceneFrame) {
        if (capHit) return
        val t = scene.decidedAt
        now = t
        // Decide per branch; the stream of perception is shared.
        val generated = generator.generate(scene.snapshot, t)
        for (event in generated) {
            if (event.category in StateEpisodes.TRACKLESS) episodes.observe(StateEpisodes.keyOf(event), t, scene.record.seq)
        }
        val groups = generator.announcedObstacleGroups(scene.snapshot.obstacles)
        val stationary = MutableStateFlow(scene.stationary)
        val sensors = object : DeviceSensorRepository {
            override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
            override val deviceRotationValue: Int = 0
            override val deviceRotationDegree: Int = 0
            override val isStationary: StateFlow<Boolean> = stationary
            override fun startObserving() = Unit
            override fun stopObserving() = Unit
        }

        var settled = branches.flatMap { b -> kernel.settle(b.speaker, t).map { apply(b, it) } }
        while (pendingQueued.firstOrNull()?.let { it.atMs <= t } == true) {
            val q = pendingQueued.removeFirst()
            settled = settled.map { b -> apply(b, kernel.submit(b.speaker, q).single()) }
        }
        val next = LinkedHashSet<Branch>()
        for (branch in settled) {
            val cooldown = branch.cooldown.copy()
            val decide = DecideEventsUseCase(generator, resolver, merger, cooldown, sensors, clock = { t })
            val primary = decide(scene.snapshot).firstOrNull()
            if (primary == null) {
                next += Branch(branch.speaker, cooldown, branch.kept)
                continue
            }
            val id = offerId(scene.record.seq, primary)
            wordings[id] = primary.messageKey to primary.isNear
            creditsOf.getOrPut(id) { creditsFor(primary, groups, t) }
            val offer = SpeakerModel.Offer(t, id, primary.priority.value, primary.expiresAt, durations.of(primary.messageKey to primary.isNear).second)
            for (step in kernel.submit(branch.speaker, offer)) {
                val own = cooldown.copy()
                val accepted = step.outcomes.any { it is ClosedLoopSpeakerBranches.Accepted && it.id == id }
                if (accepted) own.confirm(primary.id) else own.revoke(primary.id)
                val after = apply(Branch(branch.speaker, own, branch.kept), step)
                next += after
            }
        }
        branches = next.toList()
        highest = maxOf(highest, branches.size)
        if (branches.size > maxBranches) {
            capHit = true
            undeterminableKeys += pending.keys
            pending.clear()
            return
        }
        resolve(t)
    }

    private fun creditsFor(event: SceneEvent, groups: List<com.sailens.guidance.processor.decision.ObstacleZoneGroup>, t: Long): List<StageKey> =
        if (event.category in StateEpisodes.TRACKLESS) {
            listOfNotNull(episodes.current(StateEpisodes.keyOf(event), t)?.let { StageKey(it.id, Stage.FIRST) })
        } else {
            StageCredit.credits(StageCredit.spoken(event), groups, generator).filter { it.counts }.map { StageKey(it.trackId.toString(), it.stage) }
        }

    /** The same in every branch for the same words on the same frame, so branches can merge. */
    private fun offerId(seq: Long, event: SceneEvent) = "$seq|${event.messageKey}|${event.isNear}|${event.priority.value}|${event.relatedZones}"

    private fun resolve(t: Long) {
        val closed = mutableSetOf<StageKey>()
        for (target in pending.values) {
            if (branches.all { target.key in it.kept }) {
                keptKeys += target.key
                closed += target.key
            } else if (t > target.deadlineMs) {
                lostKeys += target.key
                closed += target.key
            }
        }
        if (closed.isEmpty()) return
        closed.forEach(pending::remove)
        branches = branches.map { b -> Branch(b.speaker, b.cooldown, b.kept - closed) }.distinct()
    }

    private companion object {
        const val STORM_WINDOW_MS = 20_000L
    }

    fun finish(): Result {
        if (!capHit) {
            branches = branches.flatMap { b -> kernel.settle(b.speaker, Long.MAX_VALUE).map { apply(b, it) } }.distinct()
            resolve(Long.MAX_VALUE)
        }
        check(targetsAll.keys == keptKeys + lostKeys + undeterminableKeys + pending.keys)
        return Result(highest, capHit, keptKeys, lostKeys, undeterminableKeys + pending.keys)
    }
}
