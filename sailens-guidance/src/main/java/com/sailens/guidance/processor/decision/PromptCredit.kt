package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.scene.EventComponent
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import java.util.UUID

/**
 * Which hazard a prompt speaks for, and for which stage.
 *
 * What a prompt says out loud is a category word (the primary's, kept on a merge only when every component
 * agrees), the zones, the NEAR prefix (from the nearest in a zone) and its priority. Credit goes only to the
 * tracks those words are true of:
 * - **FIRST**: the primary of each spoken zone ([Role.PRIMARY]); another track in that zone whose category
 *   word is the spoken one is covered by the same prompt ([Role.COVERED]); any other track there, including
 *   a primary whose word a merge replaced, is [Role.AMBIGUOUS].
 * - **NEAR**: only a track that is NEAR on this frame, in a spoken zone, of the spoken category or under the
 *   generic word; a NEAR track of another category makes the prefix ambiguous.
 * - **CRITICAL**: only a CRITICAL prompt, and only to a track whose own priority is CRITICAL.
 *
 * Ambiguous credit is listed apart and never counts.
 */
object PromptCredit {
    enum class Stage { FIRST, CRITICAL, NEAR }

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

    fun credits(spoken: Spoken, components: List<EventComponent>): List<Credit> = buildList {
        for (component in components) {
            if (component.zone !in spoken.zones) continue
            for (hazard in component.hazards) {
                val sameWord = obstacleSpeechWord(hazard.category) == spoken.word
                val firstRole = when {
                    !sameWord -> Role.AMBIGUOUS
                    hazard.trackId == component.primaryTrackId -> Role.PRIMARY
                    else -> Role.COVERED
                }
                add(Credit(hazard.trackId, Stage.FIRST, firstRole))
                if (spoken.near && hazard.speechNear) {
                    add(Credit(hazard.trackId, Stage.NEAR, if (sameWord || spoken.word == null) Role.CREDITED else Role.AMBIGUOUS))
                }
                if (spoken.priority == EventPriority.CRITICAL && hazard.ownPriority == EventPriority.CRITICAL) {
                    add(Credit(hazard.trackId, Stage.CRITICAL, Role.CREDITED))
                }
            }
        }
    }

    fun credits(event: SceneEvent): List<Credit> = credits(spoken(event), event.components)
}

/**
 * 播报用的类别词，比感知类别更粗。返回 null 表示不带类别后缀（"前方有障碍"）。
 *
 * 按**用户的处置动作**归并，而不是按模型类别：
 * - 自行车/摩托车与汽车归到"车"：两者都是停下让行，而模型分不清一辆自行车是停在路边
 *   还是正骑过来——归到更保守的那一侧。
 * - 静态障碍与未知归到无后缀：原先 `_static` 那组文案和无后缀组逐字相同，纯属重复。
 *
 * 注意这里只影响措辞。[ObstacleCategory.BICYCLE] 在感知侧仍然独立存在，
 * 因为 RoadSafetyAnalyzer 依赖它——停在路边的自行车不该触发机动车道警告。
 */
internal fun obstacleSpeechWord(category: ObstacleCategory): String? = when (category) {
    ObstacleCategory.PERSON -> SceneEventMessageKeys.SUFFIX_PERSON
    ObstacleCategory.BICYCLE,
    ObstacleCategory.VEHICLE -> SceneEventMessageKeys.SUFFIX_VEHICLE
    ObstacleCategory.STATIC_OBSTACLE,
    ObstacleCategory.UNKNOWN -> null
}
