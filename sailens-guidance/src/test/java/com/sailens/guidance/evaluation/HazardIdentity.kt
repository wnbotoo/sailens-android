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
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.ObstacleZoneGroup
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
 * Which hazard a prompt speaks for, and for which stage (det-first plan D1.1-4).
 *
 * What a prompt says out loud is a category word (the primary's, kept on a merge only when every
 * component agrees), the zones, the NEAR prefix (from the nearest in a zone) and its priority. Credit
 * goes only to the tracks those words are true of:
 * - **FIRST**: the primary of each spoken zone ([Role.PRIMARY]); another track in that zone whose
 *   category word is the spoken one is covered by the same prompt ([Role.COVERED]); any other track
 *   there -- including a primary whose word a merge replaced -- is [Role.AMBIGUOUS].
 * - **NEAR**: only a track that is NEAR on this frame, in a spoken zone, of the spoken category or
 *   under the generic word; a NEAR track of another category makes the prefix ambiguous.
 * - **CRITICAL**: only a CRITICAL prompt, and only to a component whose own priority is CRITICAL.
 *
 * Ambiguous credit is listed apart and never counts as success.
 */
internal object StageCredit {
    enum class Role { PRIMARY, COVERED, CREDITED, AMBIGUOUS }

    data class Credit(val trackId: UUID, val stage: Stage, val role: Role) {
        val counts: Boolean get() = role != Role.AMBIGUOUS
    }

    data class Spoken(val zones: Set<DirectionZone>, val word: String?, val near: Boolean, val priority: EventPriority)

    fun spoken(event: SceneEvent): Spoken = Spoken(
        zones = event.relatedZones.toSet(),
        word = SceneEventMessageKeys.obstacleSuffix(event.messageKey),
        near = event.distance == DistanceLevel.NEAR,
        priority = event.priority,
    )

    /** What one zone's own event (before merging) says. */
    fun spoken(group: ObstacleZoneGroup, generator: EventGenerator, proximityPrefix: Boolean): Spoken = Spoken(
        zones = setOf(group.zone),
        word = generator.obstacleCategorySuffix(group.primary.category),
        near = proximityPrefix && group.nearestDistance == DistanceLevel.NEAR,
        priority = group.priority,
    )

    fun credits(spoken: Spoken, groups: List<ObstacleZoneGroup>, generator: EventGenerator): List<Credit> = buildList {
        for (group in groups) {
            if (group.zone !in spoken.zones) continue
            for (obstacle in group.obstacles) {
                val sameWord = generator.obstacleCategorySuffix(obstacle.category) == spoken.word
                val firstRole = when {
                    !sameWord -> Role.AMBIGUOUS
                    obstacle === group.primary -> Role.PRIMARY
                    else -> Role.COVERED
                }
                add(Credit(obstacle.id, Stage.FIRST, firstRole))
                if (spoken.near && obstacle.distance == DistanceLevel.NEAR) {
                    add(Credit(obstacle.id, Stage.NEAR, if (sameWord || spoken.word == null) Role.CREDITED else Role.AMBIGUOUS))
                }
                if (spoken.priority == EventPriority.CRITICAL && generator.ownObstaclePriority(obstacle) == EventPriority.CRITICAL) {
                    add(Credit(obstacle.id, Stage.CRITICAL, Role.CREDITED))
                }
            }
        }
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
