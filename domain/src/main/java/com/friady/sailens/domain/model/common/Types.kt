package com.friady.sailens.domain.model.common

/**
 * 感知模式
 */
enum class PerceptionMode {
    SEMANTIC_ONLY,  // 只用语义分割
    COMBINED        // 语义 + 实例分割
}

/**
 * 语义分割提供者类型
 */
enum class SemanticProviderType {
    YOLO26_SEM
}

/**
 * 双模型推理策略
 *
 * - SIMULTANEOUS: sem + seg 每帧同时推理，障碍物信息最新，功耗较高
 * - ALTERNATING:  sem 每帧推理，seg 奇偶帧交替推理，跟踪器补偿偶数帧，功耗较低
 */
enum class InferenceStrategy {
    SIMULTANEOUS,  // 每帧同时运行 sem（可行走区域）+ seg（障碍物识别）
    ALTERNATING,   // sem 每帧运行；seg 交替运行，偶数帧用 tracker.predict() 补偿
}

/**
 * 实例分割提供者类型
 */
enum class InstanceProviderType {
    YOLO26_SEG
}

/**
 * 严重程度
 */
enum class Severity(val value: Int) {
    NONE(0),        // 无
    MILD(1),        // 轻微
    MODERATE(2),    // 中等
    SEVERE(3);      // 严重

    companion object {
        fun fromValue(value: Int): Severity {
            return entries.find { it.value == value } ?: NONE
        }

        fun fromConfidence(confidence: Float): Severity {
            return when {
                confidence < 0.3f -> NONE
                confidence < 0.5f -> MILD
                confidence < 0.7f -> MODERATE
                else -> SEVERE
            }
        }
    }
}

/**
 * 细分地面类型（用于地面变化提醒）
 */
enum class GroundType {
    SIDEWALK,   // 人行道（铺装）
    ROAD,       // 机动车道
    TERRAIN,    // 自然路面（草地、土路）
    INDOOR,     // 室内
    UNKNOWN
}

/**
 * 障碍物类别
 */
enum class ObstacleCategory(val value: Int) {
    PERSON(0),
    BICYCLE(1),         // 自行车/摩托车（不触发道路警告）
    VEHICLE(2),         // 汽车/卡车/公交（触发道路警告）
    STATIC_OBSTACLE(3),
    UNKNOWN(4);

    companion object {
        fun fromValue(value: Int): ObstacleCategory {
            return entries.find { it.value == value } ?: UNKNOWN
        }
    }
}

/**
 * 方位区域
 */
enum class DirectionZone(val value: Int) {
    LEFT(0),
    CENTER(1),
    RIGHT(2),
    FRONT_LEFT(3),
    FRONT_RIGHT(4);

    companion object {
        fun fromValue(value: Int): DirectionZone {
            return entries.find { it.value == value } ?: CENTER
        }

        fun fromNormalizedX(x: Float, mode: ZoneMode = ZoneMode.THREE): DirectionZone {
            return when (mode) {
                ZoneMode.THREE -> when {
                    x < 0.33f -> LEFT
                    x > 0.67f -> RIGHT
                    else -> CENTER
                }

                ZoneMode.FIVE -> when {
                    x < 0.2f -> LEFT
                    x < 0.4f -> FRONT_LEFT
                    x < 0.6f -> CENTER
                    x < 0.8f -> FRONT_RIGHT
                    else -> RIGHT
                }
            }
        }
    }
}

/**
 * 距离级别
 */
enum class DistanceLevel(val value: Int) {
    NEAR(0),    // "近处"
    MEDIUM(1),  // "前方"
    FAR(2);     // "远处"

    companion object {
        fun fromValue(value: Int): DistanceLevel {
            return entries.find { it.value == value } ?: MEDIUM
        }

        fun fromNormalizedY(y: Float): DistanceLevel {
            return when {
                y > 0.75f -> NEAR
                y > 0.45f -> MEDIUM
                else -> FAR
            }
        }
    }
}

/**
 * 紧急程度
 */
enum class UrgencyLevel(val value: Int) : Comparable<UrgencyLevel> {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    companion object {
        fun fromValue(value: Int): UrgencyLevel {
            return entries.find { it.value == value } ?: LOW
        }
    }
}

/**
 * 方向偏移建议
 */
enum class DirectionBias {
    LEFT,
    RIGHT
}

/**
 * 方位模式
 */
enum class ZoneMode {
    THREE,  // 三方位：左/中/右
    FIVE    // 五方位：左/左前/中/右前/右
}

/**
 * 事件类别
 */
enum class EventCategory(val value: Int) {
    OBSTACLE(0),
    BLOCKED(1),
    NARROWING(2),
    DIRECTION_ADVICE(3),
    INTERSECTION(4),
    ROAD_WARNING(5),
    ROAD_EXIT(6),
    GROUND_CHANGE(7);

    companion object {
        fun fromValue(value: Int): EventCategory {
            return entries.find { it.value == value } ?: OBSTACLE
        }
    }
}

/**
 * 事件优先级
 */
enum class EventPriority(val value: Int) : Comparable<EventPriority> {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    companion object {
        fun fromValue(value: Int): EventPriority {
            return entries.find { it.value == value } ?: LOW
        }

        fun fromSeverity(severity: Severity): EventPriority {
            return when (severity) {
                Severity.NONE -> LOW
                Severity.MILD -> MEDIUM
                Severity.MODERATE -> HIGH
                Severity.SEVERE -> CRITICAL
            }
        }
    }
}
