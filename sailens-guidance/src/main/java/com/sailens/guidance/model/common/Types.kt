package com.sailens.guidance.model.common

/**
 * 感知挡位：决定哪些模型参与运行
 *
 * - BASIC:   sem only，导航底线（可通行区域/道路方向），最稳定省电
 * - DEFAULT: sem + det，默认模式（障碍物类别与位置按 detectionTargetFps 周期运行）
 */
enum class PerceptionProfile {
    BASIC,
    DEFAULT,
}

/**
 * 语义分割提供者类型
 */
enum class SemanticProviderType {
    SEGMENTATION_MODEL
}

/**
 * 障碍物模型类型
 */
enum class ObstacleProviderType {
    NONE,
    DETECTION_MODEL,
}

/**
 * 单帧 obstacle 槽位实际运行的模型（用于 trace 统计 det 的真实运行频率）
 */
enum class ObstacleRunKind(val traceName: String) {
    NONE("none"),          // 未运行：tracker 预测补偿或 BASIC 语义提取
    DETECTION("det"),      // det 周期运行
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

    // 3 曾是 DIRECTION_ADVICE（"建议靠左行走"）。SceneEvent.directionHint 暂为兼容字段；
    // 在分析层能证明建议侧与用户脚下前向连通之前，事件生成器不会输出可执行方向。

    INTERSECTION(4),
    ROAD_WARNING(5),

    // 6 曾是 ROAD_EXIT（"前方交通环境变化"）。它在用户已经走过之后才触发，
    // 且把"离开机动车道"这件好事播成了警告，纯噪音，已移除。

    // 空缺的值保留不复用，避免历史 trace 的 fromValue 映射到别的语义。

    GROUND_CHANGE(7),
    PATH_COMPLEX(8),
    TRAFFIC_LIGHT(9),

    /**
     * 传感器/输入质量问题（镜头被遮挡、环境过暗）。
     *
     * 与其他类别的关键区别：它说明"本应用现在看不见"，因此必须抑制其余全部事件——
     * 此时任何基于画面的判断都是不可信的，而用户无法靠自己察觉这一点。
     */
    SENSOR_QUALITY(10);

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
