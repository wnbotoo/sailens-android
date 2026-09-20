package com.sailens.shell.device

import com.sailens.domain.model.common.DirectionZone
import com.sailens.domain.model.common.EventCategory
import com.sailens.domain.model.common.EventPriority
import com.sailens.domain.model.scene.SceneEvent

// 放在文件级而不是 companion object 里：枚举常量在初始化时读不到自己的伴生对象。
private const val SHORT = 60L
private const val MEDIUM = 160L
private const val LONG = 350L
private const val EXTRA_LONG = 400L
private const val GAP = 90L
private const val LONG_GAP = 150L

/**
 * 震动词汇表。
 *
 * 以前震动只按事件优先级分四档节拍——也就是说它只传达"有事发生"，方位信息全部丢在语音里。
 * 后果是关掉语音之后震动不携带任何可用信息，"纯震动出行"（图书馆、会议、深夜、耳机没电）
 * 实际上不成立。
 *
 * 这里给每个语义一个可辨认的节奏，构成一套用户能学会的小语言：
 *
 * | 语义 | 节奏 |
 * | --- | --- |
 * | 左侧 | 1 短 |
 * | 前方 | 2 短 |
 * | 右侧 | 3 短 |
 * | 多处 | 4 短 |
 * | 前方不通 | 1 长 |
 * | 输入失效 | 长-短-长 |
 * | 一般提示 | 1 中 |
 * | 语音失效 | 短-短-特长 |
 * | 辅助中断 | 3 特长 |
 *
 * 设计约束：
 * - 短(60ms) / 中(160ms) / 长(350ms) 之间保持 ~2.5 倍以上的时长差，隔着裤袋也能分辨。
 * - "左 1 下、前 2 下、右 3 下"按从左到右的顺序递增，方便形成直觉而不是死记。
 * - 中断用最重最长的节奏，且和任何导航提示都不相似——它说的是"我不工作了"，
 *   绝不能被误听成"前方有障碍"。
 */
enum class GuidanceHaptic(val timings: LongArray) {
    /** 左侧：1 短。 */
    LEFT(longArrayOf(0, SHORT)),

    /** 前方：2 短。 */
    AHEAD(longArrayOf(0, SHORT, GAP, SHORT)),

    /** 右侧：3 短。 */
    RIGHT(longArrayOf(0, SHORT, GAP, SHORT, GAP, SHORT)),

    /**
     * 多处：4 短。
     *
     * 两侧都有障碍时单独成一个符号，而不是复用"前方"。两侧被占、正前方反而是唯一出路，
     * 此时震出"前方"会把用户往错误的方向推。
     */
    MULTIPLE(longArrayOf(0, SHORT, GAP, SHORT, GAP, SHORT, GAP, SHORT)),

    /** 前方不通：1 长。停下，而不是绕。 */
    BLOCKED(longArrayOf(0, LONG)),

    /** 输入失效（镜头遮挡/过暗）：长-短-长。 */
    SENSOR_FAILURE(longArrayOf(0, LONG, GAP, SHORT, GAP, LONG)),

    /** 一般提示（路口、红绿灯、路面变化）：1 中。非方位类，不需要用户立刻动作。 */
    NOTICE(longArrayOf(0, MEDIUM)),

    /** 辅助中断：3 特长。整套词汇里最重的一个，绝不与导航提示混淆。 */
    INTERRUPTED(longArrayOf(0, EXTRA_LONG, LONG_GAP, EXTRA_LONG, LONG_GAP, EXTRA_LONG)),

    /**
     * 语音引擎彻底失效：短-短-特长。
     *
     * 必须存在这个符号，因为它是这条失效**唯一**能到达用户的通道：TTS 已经死了，说不出话；
     * 视觉卡片对盲人不存在；没开读屏的用户会把"语音坏了"直接理解成"前方安全"。
     *
     * 收尾的特长震区别于 [SENSOR_FAILURE]（长-短-长）：一个说"我看不见"，
     * 另一个说"我说不出话"，处置方式完全不同（前者挪手机，后者查系统语音设置）。
     */
    SPEECH_UNAVAILABLE(longArrayOf(0, SHORT, GAP, SHORT, GAP, EXTRA_LONG));

    companion object {
        /** 用户可以在设置页逐个试触感的顺序，从最常见到最少见。 */
        val learnableVocabulary: List<GuidanceHaptic> = listOf(
            LEFT, AHEAD, RIGHT, MULTIPLE, BLOCKED, NOTICE,
            SENSOR_FAILURE, SPEECH_UNAVAILABLE, INTERRUPTED,
        )

        fun forEvent(event: SceneEvent): GuidanceHaptic = when (event.category) {
            EventCategory.SENSOR_QUALITY -> SENSOR_FAILURE
            EventCategory.BLOCKED -> BLOCKED
            EventCategory.OBSTACLE,
            EventCategory.PATH_COMPLEX,
            EventCategory.NARROWING -> forZones(event.relatedZones)

            EventCategory.INTERSECTION,
            EventCategory.TRAFFIC_LIGHT,
            EventCategory.ROAD_WARNING,
            EventCategory.GROUND_CHANGE -> NOTICE
        }

        private fun forZones(zones: List<DirectionZone>): GuidanceHaptic {
            if (zones.isEmpty()) return AHEAD

            val hasLeft = zones.any { it == DirectionZone.LEFT || it == DirectionZone.FRONT_LEFT }
            val hasRight = zones.any { it == DirectionZone.RIGHT || it == DirectionZone.FRONT_RIGHT }
            val hasCenter = DirectionZone.CENTER in zones

            return when {
                // 正前方被占是最需要立刻处理的，优先于左右。
                hasCenter -> AHEAD
                hasLeft && hasRight -> MULTIPLE
                hasLeft -> LEFT
                hasRight -> RIGHT
                else -> AHEAD
            }
        }

        /** 震动强度按事件优先级分档；节奏表方位，强度表紧迫。 */
        fun amplitudeFor(priority: EventPriority): Int = when (priority) {
            EventPriority.CRITICAL -> 255
            EventPriority.HIGH -> 205
            EventPriority.MEDIUM -> 155
            EventPriority.LOW -> 110
        }
    }
}
