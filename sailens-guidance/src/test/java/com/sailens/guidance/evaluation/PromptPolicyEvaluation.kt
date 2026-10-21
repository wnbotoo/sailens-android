package com.sailens.guidance.evaluation

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.decision.ObstacleZoneGroup
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.regression.RegressionReplay
import java.util.UUID

/**
 * The det-first plan's measurement (D1.1–D1.6) over one replayed run: which hazards the prompt
 * policy spoke for, at which stage, when each stage first appeared (before the cooldown), was first
 * received, and was first spoken to the end; how early that was against the hazard's approach; and
 * how often a hazard turning NEAR was held by a same-priority cooldown.
 *
 * Hazard identity (D1.1): a track (ids numbered by birth, [BirthOrderTrackIds]) or, without one, a
 * state episode ([StateEpisodes]). A track rebuilt after missed detections is a new hazard.
 *
 * Delivery evidence comes from the device, so this is the **exact** run's measurement: the offered
 * event of each frame is the recorded one, received unless revoked, completed when the speech
 * lifecycle ([CompletionCredit]) says so. Older captures carry no lifecycle: completion is then
 * undeterminable -- never "no failure" -- and lead times fall back to an explicitly labelled
 * receipt proxy.
 */
internal class PromptPolicyEvaluation(
    private val analysis: AnalysisConfig,
    val firstThresholdsM: List<Float> = listOf(2.0f, 3.0f, 4.0f),
    val nearThresholdsM: List<Float> = listOf(1.5f),
) {
    private val generator = EventGenerator(analysis)
    private val resolver = EventConflictResolver()
    private val merger = EventMerger()

    /** What the session can say about completion. */
    enum class LifecycleEvidence(val label: String) {
        RECORDED("recorded"),

        /** Recorded, but events were dropped: reported, never accepted on. */
        INCOMPLETE("incomplete (events dropped)"),

        /** An older record: no lifecycle at all. Not "zero failures". */
        NONE("no lifecycle evidence"),

        /** A prompt went to the screen reader, which reports no lifecycle: completion cannot be judged. */
        NOT_MODELLABLE("screen reader: not modellable"),
        ;

        val completionKnown: Boolean get() = this == RECORDED || this == INCOMPLETE
    }

    /** One replayed frame as this evaluation needs it. */
    class Frame(
        val seq: Long,
        /** The decision's time (ms, the elapsed-realtime clock of event timestamps). */
        val decidedAt: Long,
        val obstacles: List<DetectedObstacle>,
        val groups: List<ObstacleZoneGroup>,
        val generated: List<SceneEvent>,
        val resolved: List<SceneEvent>,
        /** What the cooldown chooses among: [resolved] after merging. Before the cooldown, like [generated]. */
        val merged: List<SceneEvent>,
        val decided: List<SceneEvent>,
        /** The cooldown as the decision found it (read-only); null when unknown. */
        val cooldown: CooldownManager.Snapshot?,
        /** The device's id of the offered (first decided) event; null when nothing was offered. */
        val offeredId: String?,
        val offeredReceived: Boolean,
        val pitchDownDegrees: Float?,
    )

    /** [Frame] from an exact replay's outcome: the decided events are the recorded ones. */
    fun frame(record: RegressionFrameRecord, outcome: RegressionReplay.FrameOutcome, revoked: Set<String>, pitchDownDegrees: Float?): Frame {
        val snapshot = checkNotNull(outcome.snapshot) { "seq ${record.seq}: no snapshot" }
        val now = checkNotNull(outcome.decidedAt) { "seq ${record.seq}: no decision time" }
        val generated = generator.generate(snapshot, now)
        val resolved = resolver.resolve(generated)
        check(outcome.events.size == record.events.size) { "seq ${record.seq}: not an exact run" }
        val offeredId = record.events.firstOrNull()?.id
        return Frame(
            seq = record.seq,
            decidedAt = now,
            obstacles = outcome.obstacles,
            groups = generator.announcedObstacleGroups(snapshot.obstacles),
            generated = generated,
            resolved = resolved,
            merged = merger.merge(resolved),
            decided = outcome.events,
            cooldown = outcome.cooldown,
            offeredId = offeredId,
            offeredReceived = offeredId != null && offeredId !in revoked,
            pitchDownDegrees = pitchDownDegrees,
        )
    }

    // ---- results -----------------------------------------------------------------------------

    /** One hazard stage (D1.2). Times in ms on the decision clock; completion in ns (lifecycle clock). */
    class StageRow(
        val hazard: String,
        val trackless: Boolean,
        /** "vehicle/front", "person/side", "static/front"; for a state episode, its event key. */
        val hazardClass: String,
        val stage: Stage,
        val appearedAt: Long,
        val receivedAt: Long?,
        val completedAtNanos: Long?,
        /** Spoken for only through ambiguous credit: listed apart, never a success. */
        val ambiguousOnly: Boolean,
        /** Some received offer spoke for it only ambiguously. */
        val ambiguousReceived: Boolean,
        val offers: Int,
        val ambiguousOffers: Int,
        val endAt: Long,
    )

    class LeadRow(val hazard: String, val hazardClass: String, val stage: Stage, val thresholdM: Float, val result: LeadTime.Result)

    enum class NearEntry {
        /** The offered prompt said it. */
        OFFERED,

        /** It passed the cooldown, but a higher-priority prompt was offered instead. */
        PASSED_NOT_OFFERED,

        /** Removed by conflict resolution (BLOCKED suppressing a central obstacle). */
        SUPPRESSED,

        /** Held by the cooldown: a key that holds it records the same priority (no escalation through it). */
        HELD_SAME_PRIORITY,

        /** Held by the cooldown, every holding key recording a higher priority. */
        HELD_HIGHER_PRIORITY,

        /** Not offered, and the cooldown copy does not explain why (none recorded, or it passes there). */
        UNEXPLAINED,
    }

    /** D1.6: the first frame a track hazard's NEAR stage appeared, and what came of it afterwards. */
    class NearEntryRow(val hazard: String, val seq: Long, val entry: NearEntry, val receivedLater: Boolean, val completedLater: Boolean?)

    class Result(
        val evidence: LifecycleEvidence,
        val cameraRealtime: Boolean,
        val stages: List<StageRow>,
        val leads: List<LeadRow>,
        val nearEntries: List<NearEntryRow>,
        val tracksSeen: Int,
        val tracksNeverAnnounced: Int,
        val rebuilds: Int,
        val births: Int,
        val offers: Int,
        val offersReceived: Int,
        val offersCompleted: Int,
        val evidenceConflicts: Int,
    )

    // ---- the evaluation ----------------------------------------------------------------------

    private class StageState(val appearedAt: Long) {
        var receivedAt: Long? = null
        var completedAtNanos: Long? = null
        var offers = 0
    }

    /** A stage spoken for only ambiguously before the cooldown. Kept apart: it never makes the stage appear. */
    private class Ambiguous(val firstAt: Long) {
        var offers = 0
        var received = false
    }

    private class TrackState(val id: UUID) {
        /** Category and zone where the hazard first appeared, else where it was first seen. */
        var category: ObstacleCategory? = null
        var zone: DirectionZone? = null
        var lastSeenAt = 0L
        var lastBox: DetectedObstacle? = null
        val stages = HashMap<Stage, StageState>()
        val ambiguous = HashMap<Stage, Ambiguous>()
        val samples = mutableListOf<LeadTime.Sample>()
    }

    /**
     * @param credit the session's completion credit; null when [evidence] has none.
     * @param births the run's track births, for the rebuild count; null skips it.
     */
    fun evaluate(
        frames: List<Frame>,
        evidence: LifecycleEvidence,
        credit: CompletionCredit?,
        cameraRealtime: Boolean,
        births: BirthOrderTrackIds? = null,
    ): Result {
        val tracks = LinkedHashMap<UUID, TrackState>()
        val episodes = StateEpisodes()
        val episodeStages = LinkedHashMap<StateEpisodes.Episode, StageState>()
        val nearEntries = mutableListOf<Pair<UUID, Pair<Long, NearEntry>>>()
        var offers = 0
        var offersReceived = 0
        var offersCompleted = 0
        val seqTime = HashMap<Long, Long>()

        fun completionOf(id: String?): Long? = if (id == null || !evidence.completionKnown) null else credit?.get(id)?.completedAtNanos

        for (f in frames) {
            seqTime[f.seq] = f.decidedAt
            for (o in f.obstacles) {
                val t = tracks.getOrPut(o.id) { TrackState(o.id) }
                if (t.category == null) {
                    t.category = o.category
                    t.zone = o.zone
                }
                t.lastSeenAt = f.decidedAt
                t.lastBox = o
                LeadTime.freshSample(o, f.seq, f.pitchDownDegrees)?.let(t.samples::add)
            }

            // Appearance, entirely before the cooldown: each zone's own event as the generator made
            // it, and every merged event the cooldown chooses among (a merge can say what no zone's
            // own event says -- the generic word). Never the offer: whether something was offered
            // must not move when, or whether, its stage appeared.
            val newlyNear = mutableListOf<Pair<UUID, ObstacleZoneGroup>>()
            val candidates = f.groups.map { g -> StageCredit.spoken(g, generator, analysis.enableProximityPrefix) to listOf(g) } +
                f.merged.filter { it.category == EventCategory.OBSTACLE }.map { StageCredit.spoken(it) to f.groups }
            for ((spoken, groups) in candidates) {
                for (c in StageCredit.credits(spoken, groups, generator)) {
                    val t = tracks.getOrPut(c.trackId) { TrackState(c.trackId) }
                    if (!c.counts) {
                        t.ambiguous.getOrPut(c.stage) { Ambiguous(f.decidedAt) }
                        continue
                    }
                    if (c.stage !in t.stages) {
                        t.stages[c.stage] = StageState(f.decidedAt)
                        val group = f.groups.first { g -> g.obstacles.any { it.id == c.trackId } }
                        if (c.stage == Stage.FIRST) {
                            val o = group.obstacles.first { it.id == c.trackId }
                            t.category = o.category
                            t.zone = o.zone
                        }
                        if (c.stage == Stage.NEAR) newlyNear += c.trackId to group
                    }
                }
            }
            for (event in f.generated) {
                if (event.category in StateEpisodes.TRACKLESS) {
                    val episode = episodes.observe(StateEpisodes.keyOf(event), f.decidedAt, f.seq)
                    episodeStages.getOrPut(episode) { StageState(f.decidedAt) }
                }
            }

            // The offered prompt: received unless revoked, completed per the lifecycle.
            val offered = f.decided.firstOrNull()
            if (offered != null) {
                offers++
                if (f.offeredReceived) offersReceived++
                val completedAt = if (f.offeredReceived) completionOf(f.offeredId) else null
                if (completedAt != null) offersCompleted++
                if (offered.category == EventCategory.OBSTACLE) {
                    val spoken = StageCredit.spoken(offered)
                    // The offer only adds offers, receipts and completions: it is one of the merged
                    // candidates, so every stage it speaks for has appeared already.
                    for (c in StageCredit.credits(spoken, f.groups, generator)) {
                        val t = tracks.getValue(c.trackId)
                        if (!c.counts) {
                            val ambiguous = checkNotNull(t.ambiguous[c.stage]) { "seq ${f.seq}: an offer outside the candidates" }
                            ambiguous.offers++
                            if (f.offeredReceived) ambiguous.received = true
                            continue
                        }
                        val stage = checkNotNull(t.stages[c.stage]) { "seq ${f.seq}: an offer outside the candidates" }
                        stage.offers++
                        if (f.offeredReceived && stage.receivedAt == null) stage.receivedAt = f.decidedAt
                        if (completedAt != null) stage.completedAtNanos = minOf(stage.completedAtNanos ?: Long.MAX_VALUE, completedAt)
                    }
                } else if (offered.category in StateEpisodes.TRACKLESS) {
                    episodes.current(StateEpisodes.keyOf(offered), f.decidedAt)?.let { episode ->
                        val stage = episodeStages.getValue(episode)
                        stage.offers++
                        if (f.offeredReceived && stage.receivedAt == null) stage.receivedAt = f.decidedAt
                        if (completedAt != null) stage.completedAtNanos = minOf(stage.completedAtNanos ?: Long.MAX_VALUE, completedAt)
                    }
                }
            }

            // D1.6: what happened on the frame each hazard's NEAR stage first appeared.
            for ((trackId, group) in newlyNear) {
                nearEntries += trackId to (f.seq to nearEntry(trackId, group, f))
            }
        }

        val stages = buildList {
            for (t in tracks.values) {
                val cls = trackClass(t.category, t.zone) ?: continue
                for (stage in Stage.entries) {
                    val s = t.stages[stage]
                    val a = t.ambiguous[stage]
                    if (s == null && a == null) continue
                    add(
                        StageRow(
                            hazard = t.id.toString(),
                            trackless = false,
                            hazardClass = cls,
                            stage = stage,
                            appearedAt = s?.appearedAt ?: a!!.firstAt,
                            receivedAt = s?.receivedAt,
                            completedAtNanos = s?.completedAtNanos,
                            ambiguousOnly = s == null,
                            ambiguousReceived = a?.received == true,
                            offers = s?.offers ?: 0,
                            ambiguousOffers = a?.offers ?: 0,
                            endAt = t.lastSeenAt,
                        ),
                    )
                }
            }
            for ((episode, s) in episodeStages) {
                add(
                    StageRow(
                        hazard = episode.id,
                        trackless = true,
                        hazardClass = episode.key,
                        stage = Stage.FIRST,
                        appearedAt = s.appearedAt,
                        receivedAt = s.receivedAt,
                        completedAtNanos = s.completedAtNanos,
                        ambiguousOnly = false,
                        ambiguousReceived = false,
                        offers = s.offers,
                        ambiguousOffers = 0,
                        endAt = episode.endAt,
                    ),
                )
            }
        }

        val leads = if (!cameraRealtime || evidence == LifecycleEvidence.NOT_MODELLABLE) {
            emptyList()
        } else {
            buildList {
                for (t in tracks.values) {
                    val cls = trackClass(t.category, t.zone) ?: continue
                    if (Stage.FIRST !in t.stages) continue
                    fun completion(stage: Stage): Long? {
                        val s = t.stages[stage] ?: return null
                        // Without a lifecycle, the receipt stands in for completion, labelled as such.
                        return if (evidence.completionKnown) s.completedAtNanos else s.receivedAt?.let { it * 1_000_000 }
                    }
                    for (x in firstThresholdsM) add(LeadRow(t.id.toString(), cls, Stage.FIRST, x, LeadTime.classify(t.samples, x, completion(Stage.FIRST))))
                    for (x in nearThresholdsM) add(LeadRow(t.id.toString(), cls, Stage.NEAR, x, LeadTime.classify(t.samples, x, completion(Stage.NEAR))))
                }
            }
        }

        val nearRows = nearEntries.map { (trackId, at) ->
            val (seq, entry) = at
            val near = tracks.getValue(trackId).stages.getValue(Stage.NEAR)
            NearEntryRow(
                hazard = trackId.toString(),
                seq = seq,
                entry = entry,
                receivedLater = near.receivedAt != null,
                completedLater = if (evidence.completionKnown) near.completedAtNanos != null else null,
            )
        }

        return Result(
            evidence = evidence,
            cameraRealtime = cameraRealtime,
            stages = stages,
            leads = leads,
            nearEntries = nearRows,
            tracksSeen = tracks.size,
            tracksNeverAnnounced = tracks.values.count { it.stages.isEmpty() },
            rebuilds = births?.let { rebuilds(it, tracks.values, seqTime) } ?: 0,
            births = births?.births?.size ?: 0,
            offers = offers,
            offersReceived = offersReceived,
            offersCompleted = offersCompleted,
            evidenceConflicts = if (evidence.completionKnown) credit?.conflicts ?: 0 else 0,
        )
    }

    /**
     * Why the frame on which a hazard's NEAR stage appeared did or did not say it. A hold is read from
     * the cooldown as that very decision found it ([Frame.cooldown]), for the merged candidate that
     * carries the hazard's zone -- the event the cooldown actually judged.
     */
    private fun nearEntry(trackId: UUID, group: ObstacleZoneGroup, f: Frame): NearEntry {
        fun says(event: SceneEvent) = event.category == EventCategory.OBSTACLE &&
            StageCredit.credits(StageCredit.spoken(event), f.groups, generator).any { it.trackId == trackId && it.stage == Stage.NEAR && it.counts }
        val offered = f.decided.firstOrNull()
        if (offered != null && says(offered)) return NearEntry.OFFERED
        if (f.decided.any(::says)) return NearEntry.PASSED_NOT_OFFERED
        val candidate = f.merged.firstOrNull { it.category == EventCategory.OBSTACLE && group.zone in it.relatedZones }
            ?: return NearEntry.SUPPRESSED
        val groups = f.cooldown?.holds(candidate) ?: return NearEntry.UNEXPLAINED
        // A merged event is held only when every component's group is; what matters for this hazard
        // is its own zone's group -- another zone's recent prompt says nothing about its escalation.
        val own = groups.filter { "obstacle_${group.zone.name}" in it.keys }.ifEmpty { groups }
        val same = own.any { g -> g.holds.any { it.recordedPriority == candidate.priority } }
        return if (same) NearEntry.HELD_SAME_PRIORITY else NearEntry.HELD_HIGHER_PRIORITY
    }

    /**
     * Births that look like a track rebuilt after missed detections (reported, D1.1-3; the new track
     * is a new hazard all the same): same category, first box overlapping (IoU ≥ 0.3) the last box
     * of a track whose last output was within the second before the birth.
     */
    private fun rebuilds(births: BirthOrderTrackIds, tracks: Collection<TrackState>, seqTime: Map<Long, Long>): Int =
        births.births.count { birth ->
            val bornAt = birth.frameSequence?.let(seqTime::get) ?: return@count false
            tracks.any { t ->
                val last = t.lastBox ?: return@any false
                t.lastSeenAt < bornAt && bornAt - t.lastSeenAt <= 1_000 &&
                    last.category == birth.category && last.boundingBox.iou(birth.box) >= 0.3f
            }
        }

    companion object {
        fun trackClass(category: ObstacleCategory?, zone: DirectionZone?): String? {
            category ?: return null
            zone ?: return null
            val kind = when (category) {
                ObstacleCategory.VEHICLE, ObstacleCategory.BICYCLE -> "vehicle"
                ObstacleCategory.PERSON -> "person"
                ObstacleCategory.STATIC_OBSTACLE, ObstacleCategory.UNKNOWN -> "static"
            }
            val where = when (zone) {
                DirectionZone.CENTER, DirectionZone.FRONT_LEFT, DirectionZone.FRONT_RIGHT -> "front"
                DirectionZone.LEFT, DirectionZone.RIGHT -> "side"
            }
            return "$kind/$where"
        }
    }
}
