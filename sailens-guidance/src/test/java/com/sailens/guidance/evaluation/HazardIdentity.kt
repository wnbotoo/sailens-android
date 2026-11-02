package com.sailens.guidance.evaluation

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleSourceFrame
import com.sailens.guidance.model.perception.RawObstacle
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.ObstacleZoneGroup
import com.sailens.guidance.processor.decision.PromptCredit
import com.sailens.guidance.processor.perception.ObstacleTracker
import java.util.UUID

/**
 * Track ids numbered by birth, for replay (det-first plan D1.1-3). Two runs whose detection input
 * and extraction/tracking config are the same give birth to the same tracks in the same order, so
 * their ids pair -- but only if their [births] are identical, which an evaluation checks before
 * pairing anything ([sameBirths]).
 */
internal class BirthOrderTrackIds : ObstacleTracker.TrackIdSource {
    data class Birth(val frameSequence: Long?, val box: NormalizedRect, val category: ObstacleCategory)

    private val _births = mutableListOf<Birth>()
    val births: List<Birth> get() = _births

    override fun newTrackId(birth: RawObstacle, frame: ObstacleSourceFrame?): UUID {
        _births += Birth(frame?.sequenceNumber, birth.boundingBox, birth.category)
        return idOf(_births.size)
    }

    companion object {
        fun idOf(birthNumber: Int): UUID = UUID(0L, birthNumber.toLong())

        /** Whether two runs' tracks can be paired by id: the same births, frame and first box alike. */
        fun sameBirths(a: BirthOrderTrackIds, b: BirthOrderTrackIds): Boolean = a.births == b.births
    }
}

/** The stages a hazard can reach (D1.2). A trackless hazard has only [FIRST]. */
internal enum class Stage { FIRST, CRITICAL, NEAR }

/**
 * The production rules of [PromptCredit], over what an evaluation has to hand: zones before an event is
 * made, and [Stage]s, which are this package's own.
 */
internal object StageCredit {
    enum class Role { PRIMARY, COVERED, CREDITED, AMBIGUOUS }

    data class Credit(val trackId: UUID, val stage: Stage, val role: Role) {
        val counts: Boolean get() = role != Role.AMBIGUOUS
    }

    @Suppress("FunctionName")
    fun Spoken(zones: Set<DirectionZone>, word: String?, near: Boolean, priority: EventPriority) = PromptCredit.Spoken(zones, word, near, priority)

    fun spoken(event: SceneEvent): PromptCredit.Spoken = PromptCredit.spoken(event)

    /** What one zone's own event (before merging) says. */
    fun spoken(group: ObstacleZoneGroup, generator: EventGenerator, proximityPrefix: Boolean) = PromptCredit.Spoken(
        zones = setOf(group.zone),
        word = generator.obstacleCategorySuffix(group.primary.category),
        near = proximityPrefix && group.nearestDistance == DistanceLevel.NEAR,
        priority = group.priority,
    )

    fun credits(spoken: PromptCredit.Spoken, groups: List<ObstacleZoneGroup>, generator: EventGenerator): List<Credit> =
        PromptCredit.credits(spoken, groups.map(generator::componentOf)).map {
            Credit(it.trackId, Stage.valueOf(it.stage.name), Role.valueOf(it.role.name))
        }
}

/**
 * The identity of a hazard without a track (D1.1-5): a state episode. While the event generator --
 * before the cooldown -- keeps making the same kind of event, it is one hazard; a gap longer than
 * [gapMs] between two frames that make it ends the episode.
 */
internal class StateEpisodes(private val gapMs: Long = 1_000) {
    class Episode(val key: String, val number: Int, val startAt: Long, val startSeq: Long) {
        var endAt: Long = startAt
            internal set
        var frames: Int = 1
            internal set
        val id: String get() = "$key#$number"
    }

    private val open = HashMap<String, Episode>()
    private val counts = HashMap<String, Int>()
    val all = mutableListOf<Episode>()

    /** The episode [key] belongs to at [at] (a frame that makes it), opening one if needed. */
    fun observe(key: String, at: Long, seq: Long): Episode {
        val current = open[key]
        if (current != null && at - current.endAt <= gapMs) {
            current.endAt = at
            current.frames++
            return current
        }
        val number = (counts[key] ?: 0) + 1
        counts[key] = number
        return Episode(key, number, at, seq).also {
            open[key] = it
            all += it
        }
    }

    /** The open episode of [key] at [at], if it has not lapsed. */
    fun current(key: String, at: Long): Episode? = open[key]?.takeIf { at - it.endAt <= gapMs }

    companion object {
        /** Hazards without a track: a path state, the input quality, the ground. */
        val TRACKLESS: Set<EventCategory> = setOf(
            EventCategory.BLOCKED,
            EventCategory.PATH_COMPLEX,
            EventCategory.SENSOR_QUALITY,
            EventCategory.GROUND_UNRECOGNIZED,
        )

        /** One kind of state per key; the two input-quality states are kept apart. */
        fun keyOf(event: SceneEvent): String = when (event.category) {
            EventCategory.SENSOR_QUALITY -> "sensor:${event.messageKey.removePrefix("event_")}"
            else -> event.category.name.lowercase()
        }
    }
}
