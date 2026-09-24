package com.sailens.guidance.processor.decision

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.analysis.GroundTypeChange
import com.sailens.guidance.model.analysis.RoadSafetyState
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.model.perception.DetectedObstacle

/**
 * 事件生成器
 */
class EventGenerator(
    private val config: AnalysisConfig = AnalysisConfig(),
) {
    private companion object {
        private const val MIN_HARD_BLOCKED_CONFIDENCE = 0.75f
        private const val MAX_HARD_BLOCKED_VERTICAL_REACH = 0.55f
        private const val MAX_HARD_BLOCKED_FLOOD_REACH = 0.12f
        private const val MAX_HARD_BLOCKED_WIDTH_RETENTION = 0.35f
        private const val MIN_OBSTACLE_CONFIDENCE_TO_ANNOUNCE = 0.55f
        private const val MIN_PERSON_CENTER_AREA_RATIO = 0.012f
        private const val MIN_BICYCLE_CENTER_AREA_RATIO = 0.012f
        private const val MIN_VEHICLE_CENTER_AREA_RATIO = 0.016f
        private const val MIN_STATIC_CENTER_AREA_RATIO = 0.030f
        private const val MIN_ROADSIDE_STATIC_CENTER_AREA_RATIO = 0.050f
        private const val MIN_PERSON_SIDE_AREA_RATIO = 0.016f
        private const val MIN_SIDE_OBSTACLE_AREA_RATIO = 0.035f
        private const val MIN_ROADSIDE_STATIC_SIDE_AREA_RATIO = 0.060f
        private const val MIN_PERSON_CENTER_BOTTOM_Y = 0.56f
        private const val MIN_DYNAMIC_CENTER_BOTTOM_Y = 0.55f
        private const val MIN_STATIC_CENTER_BOTTOM_Y = 0.65f
        private const val MIN_ROADSIDE_STATIC_BOTTOM_Y = 0.72f
        private const val MIN_PERSON_SIDE_BOTTOM_Y = 0.54f
        private const val MIN_SIDE_OBSTACLE_BOTTOM_Y = 0.65f
        private val ROADSIDE_STATIC_CLASS_NAMES = setOf(
            "potted_plant",
            "stop_sign",
            "bench",
            "chair",
        )
    }

    fun generate(snapshot: SceneSnapshot, now: Long): List<SceneEvent> {
        val events = mutableListOf<SceneEvent>()

        // 0. 输入质量。镜头被挡/环境过暗时，下面所有基于画面的判断都不可信，
        //    但这条事件仍然照常生成——由 EventConflictResolver 负责让它压掉其余全部事件。
        if (config.enableSensorQualityEvents && snapshot.frameQuality != FrameQuality.OK) {
            events.add(createSensorQualityEvent(snapshot.frameQuality, now))
        }

        // 1. 连通性事件的前提是底部是模型认得的地面。不成立时它们只是在复述"模型不认识这种地面"
        //    （室内地板被判成 building → 一直"前方不通"），所以一律不播；确认后改播一条状态提示，
        //    让用户知道这项判断停了，而不是把"没提示"当成"能走"。障碍物检测不依赖地面，照常。
        val pathJudgementReliable = snapshot.groundRecognition.isPathJudgementReliable
        if (snapshot.groundRecognition == GroundRecognition.UNRECOGNIZED) {
            events.add(createGroundUnrecognizedEvent(now))
        }

        // 2. 阻塞 / 复杂路况事件
        if (pathJudgementReliable && snapshot.connectivity.isBlocked) {
            if (isHardBlocked(snapshot)) {
                events.add(createBlockedEvent(snapshot, now))
            } else {
                events.add(createPathComplexEvent(snapshot, now))
            }
        }

        // 3. 收窄事件
        if (pathJudgementReliable &&
            config.enableNarrowingEvents &&
            snapshot.connectivity.isNarrowing &&
            !snapshot.connectivity.isBlocked
        ) {
            events.add(createNarrowingEvent(snapshot, now))
        }

        // 4.  障碍物事件
        val significantObstacles = snapshot.obstacles.filter(::shouldAnnounceObstacle)
        if (significantObstacles.isNotEmpty()) {
            events.addAll(createObstacleEvents(significantObstacles, now))
        }

        // 5. 路口事件
        val emitsIntersection = config.enableIntersectionEvents && snapshot.sceneElements.hasIntersection
        if (emitsIntersection) {
            events.add(createIntersectionEvent(now))
        } else if (config.enableTrafficLightEvents && snapshot.sceneElements.hasTrafficLight) {
            events.add(createTrafficLightEvent(now))
        }

        // 6. 道路安全事件默认不播，避免把不稳定的车道/机动车道识别当作确定提示。
        if (config.enableRoadWarningEvents && snapshot.roadSafety.isDangerous) {
            events.add(createRoadWarningEvent(snapshot.roadSafety, now))
        }

        // 7. 地面变化事件默认不播，当前只作为分析/debug 信号保留。
        if (config.enableGroundChangeEvents) {
            snapshot.groundTypeChange?.let {
                events.add(createGroundChangeEvent(it, now))
            }
        }

        // suggestedBias 目前只是各扫描层最宽通行片段的质心偏移，不证明该侧片段与用户脚下
        // 属于同一个前向连通分量。因此它只能保留为诊断信号，不能生成"靠左/靠右"这类
        // 可执行指令。恢复方向后缀前，分析层必须提供左右候选路线各自的连通性与安全性证据。
        return events
    }

    private fun createSensorQualityEvent(quality: FrameQuality, now: Long): SceneEvent {
        val messageKey = when (quality) {
            FrameQuality.OBSTRUCTED -> SceneEventMessageKeys.CAMERA_BLOCKED
            FrameQuality.TOO_DARK -> SceneEventMessageKeys.LOW_LIGHT
            FrameQuality.OK -> error("createSensorQualityEvent called with FrameQuality.OK")
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.SENSOR_QUALITY,
            // 遮挡是 CRITICAL：用户正举着一个瞎掉的手机在走路，这比任何前方障碍都紧急，
            // 且需要打断当前播报队列。过暗只是可靠性下降，HIGH 足够。
            priority = if (quality == FrameQuality.OBSTRUCTED) {
                EventPriority.CRITICAL
            } else {
                EventPriority.HIGH
            },
            messageKey = messageKey,
            expiresAt = now + 6000,
            dedupeKey = "sensor_quality_${quality.name.lowercase()}",
            severity = Severity.SEVERE,
        )
    }

    private fun createGroundUnrecognizedEvent(now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.GROUND_UNRECOGNIZED,
            // 状态说明，不是眼前的危险：任何障碍物提示都该排在它前面，它也不该打断场景描述。
            priority = EventPriority.LOW,
            messageKey = SceneEventMessageKeys.GROUND_UNRECOGNIZED,
            expiresAt = now + 5000,
            dedupeKey = "ground_unrecognized",
            severity = Severity.MODERATE,
        )
    }

    private fun createBlockedEvent(snapshot: SceneSnapshot, now: Long): SceneEvent {
        val priority = when (snapshot.connectivity.blockageSeverity) {
            Severity.SEVERE -> EventPriority.CRITICAL
            Severity.MODERATE -> EventPriority.HIGH
            else -> EventPriority.MEDIUM
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.BLOCKED,
            priority = priority,
            messageKey = SceneEventMessageKeys.BLOCKED,
            expiresAt = now + 5000,
            dedupeKey = "blocked",
            confidence = snapshot.connectivity.blockageConfidence,
            severity = snapshot.connectivity.blockageSeverity
        )
    }

    private fun isHardBlocked(snapshot: SceneSnapshot): Boolean {
        val connectivity = snapshot.connectivity
        if (connectivity.blockageSeverity != Severity.SEVERE) return false
        if (connectivity.blockageConfidence < MIN_HARD_BLOCKED_CONFIDENCE) return false
        return connectivity.verticalReachRatio <= MAX_HARD_BLOCKED_VERTICAL_REACH &&
            connectivity.floodReachRatio <= MAX_HARD_BLOCKED_FLOOD_REACH &&
            connectivity.widthRetentionP25 <= MAX_HARD_BLOCKED_WIDTH_RETENTION
    }

    private fun createPathComplexEvent(snapshot: SceneSnapshot, now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.PATH_COMPLEX,
            priority = EventPriority.MEDIUM,
            messageKey = SceneEventMessageKeys.PATH_COMPLEX,
            expiresAt = now + 5000,
            dedupeKey = "path_complex",
            confidence = snapshot.connectivity.blockageConfidence,
            severity = snapshot.connectivity.blockageSeverity
        )
    }

    private fun shouldAnnounceObstacle(obstacle: DetectedObstacle): Boolean {
        if (!obstacle.isStable(minFrames = 3)) return false
        if (obstacle.confidence < MIN_OBSTACLE_CONFIDENCE_TO_ANNOUNCE) return false
        if (obstacle.distance == DistanceLevel.FAR && obstacle.urgency < UrgencyLevel.CRITICAL) return false

        return when (obstacle.zone) {
            DirectionZone.CENTER -> {
                if (!isCloseEnoughForCenterAnnouncement(obstacle)) return false
                obstacle.urgency >= UrgencyLevel.CRITICAL ||
                    obstacle.distance == DistanceLevel.NEAR ||
                    obstacle.areaRatio >= centerAreaThreshold(obstacle)
            }

            DirectionZone.FRONT_LEFT,
            DirectionZone.FRONT_RIGHT -> {
                if (obstacle.boundingBox.maxY < sideBottomThreshold(obstacle)) return false
                hasEnoughSideObstacleEvidence(obstacle)
            }

            DirectionZone.LEFT,
            DirectionZone.RIGHT -> {
                if (obstacle.boundingBox.maxY < sideBottomThreshold(obstacle)) return false
                hasEnoughSideObstacleEvidence(obstacle)
            }
        }
    }

    private fun isCloseEnoughForCenterAnnouncement(obstacle: DetectedObstacle): Boolean {
        if (obstacle.distance == DistanceLevel.NEAR) return true
        val minBottomY = when (obstacle.category) {
            ObstacleCategory.PERSON -> MIN_PERSON_CENTER_BOTTOM_Y
            ObstacleCategory.BICYCLE,
            ObstacleCategory.VEHICLE -> MIN_DYNAMIC_CENTER_BOTTOM_Y
            ObstacleCategory.STATIC_OBSTACLE,
            ObstacleCategory.UNKNOWN -> staticGate(obstacle.className).minBottomY
        }
        return obstacle.boundingBox.maxY >= minBottomY
    }

    private fun centerAreaThreshold(obstacle: DetectedObstacle): Float {
        return when (obstacle.category) {
            ObstacleCategory.PERSON -> MIN_PERSON_CENTER_AREA_RATIO
            ObstacleCategory.BICYCLE -> MIN_BICYCLE_CENTER_AREA_RATIO
            ObstacleCategory.VEHICLE -> MIN_VEHICLE_CENTER_AREA_RATIO
            ObstacleCategory.STATIC_OBSTACLE,
            ObstacleCategory.UNKNOWN -> staticGate(obstacle.className).centerAreaRatio
        }
    }

    private fun sideAreaThreshold(obstacle: DetectedObstacle): Float {
        return when (obstacle.category) {
            ObstacleCategory.PERSON -> MIN_PERSON_SIDE_AREA_RATIO
            ObstacleCategory.STATIC_OBSTACLE,
            ObstacleCategory.UNKNOWN -> staticGate(obstacle.className).sideAreaRatio
            else -> MIN_SIDE_OBSTACLE_AREA_RATIO
        }
    }

    private fun sideBottomThreshold(obstacle: DetectedObstacle): Float {
        return when (obstacle.category) {
            ObstacleCategory.PERSON -> MIN_PERSON_SIDE_BOTTOM_Y
            ObstacleCategory.STATIC_OBSTACLE,
            ObstacleCategory.UNKNOWN -> staticGate(obstacle.className).minBottomY
            else -> MIN_SIDE_OBSTACLE_BOTTOM_Y
        }
    }

    private fun hasEnoughSideObstacleEvidence(obstacle: DetectedObstacle): Boolean {
        val minUrgency = if (obstacle.category == ObstacleCategory.PERSON) {
            UrgencyLevel.MEDIUM
        } else {
            UrgencyLevel.HIGH
        }
        return obstacle.urgency >= minUrgency &&
            obstacle.areaRatio >= sideAreaThreshold(obstacle)
    }

    private fun staticGate(className: String): StaticObstacleGate {
        val normalized = className.lowercase()
        return if (normalized in ROADSIDE_STATIC_CLASS_NAMES) {
            StaticObstacleGate(
                minBottomY = MIN_ROADSIDE_STATIC_BOTTOM_Y,
                centerAreaRatio = MIN_ROADSIDE_STATIC_CENTER_AREA_RATIO,
                sideAreaRatio = MIN_ROADSIDE_STATIC_SIDE_AREA_RATIO,
            )
        } else {
            StaticObstacleGate(
                minBottomY = MIN_STATIC_CENTER_BOTTOM_Y,
                centerAreaRatio = MIN_STATIC_CENTER_AREA_RATIO,
                sideAreaRatio = MIN_SIDE_OBSTACLE_AREA_RATIO,
            )
        }
    }

    private data class StaticObstacleGate(
        val minBottomY: Float,
        val centerAreaRatio: Float,
        val sideAreaRatio: Float,
    )

    private fun createNarrowingEvent(snapshot: SceneSnapshot, now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.NARROWING,
            priority = EventPriority.MEDIUM,
            messageKey = SceneEventMessageKeys.NARROWING,
            expiresAt = now + 4000,
            dedupeKey = "narrowing",
            confidence = snapshot.connectivity.narrowingConfidence,
            severity = snapshot.connectivity.narrowingSeverity
        )
    }

    private fun createObstacleEvents(
        obstacles: List<DetectedObstacle>,
        now: Long,
    ): List<SceneEvent> {
        val byZone = obstacles.groupBy { it.zone }

        return byZone.map { (zone, zoneObstacles) ->
            val primaryObstacle = zoneObstacles.maxWithOrNull(
                compareBy<DetectedObstacle> { it.urgency.value }
                    .thenBy { obstacleCategoryPriority(it.category) }
                    .thenBy { it.confidence }
                    .thenBy { it.areaRatio }
            ) ?: zoneObstacles.first()
            val maxUrgency = zoneObstacles.maxOf { it.urgency }
            val priority = obstacleEventPriority(primaryObstacle, maxUrgency)
            // 用该方位里**最近**的那个障碍物定紧迫度：主障碍物是按紧急度/类别选出来的，
            // 未必是离得最近的，而"要不要立刻停下"取决于最近的那个。
            val nearestDistance = zoneObstacles.minOf { it.distance }

            SceneEvent(
                timestamp = now,
                category = EventCategory.OBSTACLE,
                priority = priority,
                messageKey = obstacleMessageKey(zone, primaryObstacle.category),
                messageParams = mapOf("count" to zoneObstacles.size.toString()),
                expiresAt = now + 3000,
                dedupeKey = "obstacle_${zone.name}",
                cooldownKeys = obstacleCooldownKeys(listOf(zone)),
                relatedZones = listOf(zone),
                distance = nearestDistance.takeIf { config.enableProximityPrefix },
            )
        }
    }

    private fun obstacleMessageKey(
        zone: DirectionZone,
        category: ObstacleCategory,
    ): String = SceneEventMessageKeys.obstacle(
        zonePart = SceneEventMessageKeys.zonePart(zone),
        suffix = obstacleCategorySuffix(category),
    )

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
    private fun obstacleCategorySuffix(category: ObstacleCategory): String? {
        return when (category) {
            ObstacleCategory.PERSON -> SceneEventMessageKeys.SUFFIX_PERSON
            ObstacleCategory.BICYCLE,
            ObstacleCategory.VEHICLE -> SceneEventMessageKeys.SUFFIX_VEHICLE
            ObstacleCategory.STATIC_OBSTACLE,
            ObstacleCategory.UNKNOWN -> null
        }
    }

    private fun obstacleCategoryPriority(category: ObstacleCategory): Int {
        return when (category) {
            ObstacleCategory.VEHICLE -> 3
            ObstacleCategory.PERSON,
            ObstacleCategory.BICYCLE -> 2
            ObstacleCategory.STATIC_OBSTACLE,
            ObstacleCategory.UNKNOWN -> 1
        }
    }

    private fun obstacleEventPriority(
        obstacle: DetectedObstacle,
        maxUrgency: UrgencyLevel,
    ): EventPriority {
        val basePriority = when (maxUrgency) {
            UrgencyLevel.CRITICAL -> EventPriority.CRITICAL
            UrgencyLevel.HIGH -> EventPriority.HIGH
            else -> EventPriority.MEDIUM
        }
        if (isForwardVehicleObstacle(obstacle)) {
            return EventPriority.CRITICAL
        }
        return if (isForwardDynamicObstacle(obstacle) && basePriority.value < EventPriority.HIGH.value) {
            EventPriority.HIGH
        } else {
            basePriority
        }
    }

    private fun isForwardDynamicObstacle(obstacle: DetectedObstacle): Boolean {
        val isDynamic = obstacle.category == ObstacleCategory.PERSON ||
            obstacle.category == ObstacleCategory.BICYCLE ||
            obstacle.category == ObstacleCategory.VEHICLE
        return isDynamic && isForwardZone(obstacle.zone)
    }

    private fun isForwardVehicleObstacle(obstacle: DetectedObstacle): Boolean {
        return obstacle.category == ObstacleCategory.VEHICLE && isForwardZone(obstacle.zone)
    }

    private fun isForwardZone(zone: DirectionZone): Boolean {
        return zone == DirectionZone.CENTER ||
            zone == DirectionZone.FRONT_LEFT ||
            zone == DirectionZone.FRONT_RIGHT
    }

    private fun createIntersectionEvent(now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.INTERSECTION,
            priority = EventPriority.LOW,
            messageKey = SceneEventMessageKeys.INTERSECTION,
            expiresAt = now + 5000,
            dedupeKey = "intersection"
        )
    }

    private fun createTrafficLightEvent(now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.TRAFFIC_LIGHT,
            priority = EventPriority.LOW,
            messageKey = SceneEventMessageKeys.TRAFFIC_LIGHT,
            expiresAt = now + 5000,
            dedupeKey = "traffic_light"
        )
    }

    private fun createRoadWarningEvent(roadSafety: RoadSafetyState, now: Long): SceneEvent {
        val messageKey = if (roadSafety.hasVehicleOnRoad) {
            SceneEventMessageKeys.ROAD_WARNING_VEHICLE
        } else {
            SceneEventMessageKeys.ROAD_WARNING
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.ROAD_WARNING,
            priority = EventPriority.HIGH,
            messageKey = messageKey,
            expiresAt = now + 5000,
            dedupeKey = if (roadSafety.hasVehicleOnRoad) {
                "road_warning_vehicle"
            } else {
                "road_warning_area"
            },
            confidence = roadSafety.dangerConfidence
        )
    }

    private fun createGroundChangeEvent(change: GroundTypeChange, now: Long): SceneEvent {
        val messageKey = when (change.to) {
            GroundType.ROAD -> SceneEventMessageKeys.GROUND_TO_ROAD
            GroundType.TERRAIN -> SceneEventMessageKeys.GROUND_TO_TERRAIN
            GroundType.SIDEWALK -> SceneEventMessageKeys.GROUND_TO_SIDEWALK
            GroundType.INDOOR -> SceneEventMessageKeys.GROUND_TO_INDOOR
            else -> SceneEventMessageKeys.GROUND_CHANGE
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.GROUND_CHANGE,
            priority = EventPriority.MEDIUM,
            messageKey = messageKey,
            messageParams = mapOf(
                "from" to change.from.name.lowercase(),
                "to" to change.to.name.lowercase()
            ),
            expiresAt = now + 4000,
            dedupeKey = "ground_change_${change.to.name}"
        )
    }

    private fun obstacleCooldownKeys(zones: List<DirectionZone>): Set<String> {
        val keys = zones.mapTo(mutableSetOf()) { "obstacle_${it.name}" }
        if (zones.any { it == DirectionZone.CENTER }) {
            keys.add("obstacle_PRIMARY_CENTER")
        }
        if (zones.any {
                it == DirectionZone.FRONT_LEFT ||
                    it == DirectionZone.FRONT_RIGHT
            }
        ) {
            keys.add("obstacle_PRIMARY_FRONT")
        }
        if (zones.any {
                it == DirectionZone.LEFT ||
                    it == DirectionZone.RIGHT
            }
        ) {
            keys.add("obstacle_PRIMARY_SIDE")
        }
        return keys
    }
}
