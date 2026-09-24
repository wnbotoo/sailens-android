package com.sailens.guidance.processor.analysis

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.widthFractionOfLongSide
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.semantics.NavigationSemantics

/**
 * 判断语义模型是否认得用户脚下的地面，见 [GroundRecognition]。
 *
 * ## 判据
 *
 * 只看底部中央区域（与地面分布统计同一块：[AnalysisConfig.segmentationBottomRatio] 高、
 * [AnalysisConfig.segmentationCenterRatio] 宽）。正常持机行走时这块就是脚前一两米的地面，
 * 而用户正站在地面上——所以这里出现的类别只应该是：
 *
 * - 可行走地面（[NavigationSemantics.isPassable]）或其它地面（草地等，[NavigationSemantics.toGroundType]）；
 * - 挡在脚前的已知障碍物（[NavigationSemantics.isObstacle]，行人、车、杆）；
 * - 模型明确认出的挡路结构（[NavigationSemantics.isBarrier]，墙、栅栏）——这是"认得，且走不通"，
 *   不是"认不出地面"，"前方不通"照常。
 *
 * 其余类别（Cityscapes 下是 building、vegetation、sky……）占比过高，说明模型在用它的类别表
 * 硬套一个它没见过的地面。这条判据**不区分室内室外**：镜头抬得太高、朝天，也是同样的"看不到地面"，
 * 连通性同样不可信。
 *
 * ## sem 分不清的情况，以及为什么偏向多报
 *
 * 这个模型把近处的室内地板判成 building，也把近处的楼体外墙判成 building。只看 sem，"不认识的
 * 地板"和"贴脸的墙"是同一个信号；此时连通性给出的正是高置信度的硬阻塞，靠规则区分不开。
 * 所以取舍偏向安全一侧：
 *
 * - 信号出现的头 [AnalysisConfig.groundUnrecognizedEnterMs] 内（[GroundRecognition.UNCERTAIN]）
 *   **不压任何提示**。开机就对着墙、转身面对墙、墙突然进入视野，都会立刻听到"前方不通"。
 *   代价是刚进室内时会先误报一次。
 * - 持续后才确认为 [GroundRecognition.UNRECOGNIZED]，暂停连通性提示，并播一条明确说"无法判断
 *   前方能否通行"的状态提示——用户知道此时前方可能有墙，而不是以为"没提示 = 能走"。
 *
 * 真正区分两者要靠几何（墙是竖直面、地板是水平面），不是 sem 能回答的问题。
 *
 * ## 滞回
 *
 * 进入：本帧比例 ≥ [AnalysisConfig.groundUnrecognizedEnterRatio] 进入 [GroundRecognition.UNCERTAIN]，
 * 持续 [AnalysisConfig.groundUnrecognizedEnterMs] 才确认为 [GroundRecognition.UNRECOGNIZED]。
 * 退出：比例 ≤ [AnalysisConfig.groundUnrecognizedExitRatio] 持续 [AnalysisConfig.groundUnrecognizedExitMs]。
 * 用时间而不是帧数：语义结果在调度间隔内会被复用，帧数不等于模型真的看了几次。
 *
 * ## 阈值尚未实测校准
 *
 * 四个阈值只有合成 mask 的单测支撑。上线前用 trace 里的 `unrecognizedGroundRatio` 在室内、
 * 人行道、草地边、贴墙几类场景下反查。
 */
class GroundRecognitionAnalyzer(
    private val config: AnalysisConfig,
    navigationSemantics: NavigationSemantics,
    private val clock: () -> Long,
) {
    /** 按类别 id 预先算好"这类像素在脚下是否说得通"，热循环里只查数组、不装箱。 */
    private val explainsGround = BooleanArray(navigationSemantics.classCount) { classId ->
        navigationSemantics.isPassable(classId) ||
            navigationSemantics.isObstacle(classId) ||
            navigationSemantics.isBarrier(classId) ||
            navigationSemantics.toGroundType(classId) != GroundType.UNKNOWN
    }

    private var state = GroundRecognition.RECOGNIZED

    /** 当前这段"进入中 / 退出中"开始的时刻；null 表示没有正在计时的切换。 */
    private var transitionStartedAt: Long? = null

    data class Result(
        val recognition: GroundRecognition,
        /** 本帧底部中央区域里模型解释不了的像素比例，未去抖。 */
        val unrecognizedRatio: Float,
    )

    fun analyze(segmentation: SegmentationMask): Result {
        val ratio = unrecognizedRatio(segmentation)
        if (!config.enableGroundRecognitionGate) {
            return Result(GroundRecognition.RECOGNIZED, ratio)
        }
        state = next(ratio, clock())
        return Result(state, ratio)
    }

    fun reset() {
        state = GroundRecognition.RECOGNIZED
        transitionStartedAt = null
    }

    private fun next(ratio: Float, now: Long): GroundRecognition = when (state) {
        GroundRecognition.RECOGNIZED -> {
            if (ratio >= config.groundUnrecognizedEnterRatio) {
                transitionStartedAt = now
                confirmIfHeld(now)
            } else {
                GroundRecognition.RECOGNIZED
            }
        }

        GroundRecognition.UNCERTAIN -> {
            if (ratio >= config.groundUnrecognizedEnterRatio) {
                confirmIfHeld(now)
            } else {
                transitionStartedAt = null
                GroundRecognition.RECOGNIZED
            }
        }

        GroundRecognition.UNRECOGNIZED -> {
            if (ratio <= config.groundUnrecognizedExitRatio) {
                val startedAt = transitionStartedAt ?: now.also { transitionStartedAt = it }
                if (now - startedAt >= config.groundUnrecognizedExitMs) {
                    transitionStartedAt = null
                    GroundRecognition.RECOGNIZED
                } else {
                    GroundRecognition.UNRECOGNIZED
                }
            } else {
                transitionStartedAt = null
                GroundRecognition.UNRECOGNIZED
            }
        }
    }

    private fun confirmIfHeld(now: Long): GroundRecognition {
        val startedAt = transitionStartedAt ?: now
        return if (now - startedAt >= config.groundUnrecognizedEnterMs) {
            transitionStartedAt = null
            GroundRecognition.UNRECOGNIZED
        } else {
            GroundRecognition.UNCERTAIN
        }
    }

    private fun unrecognizedRatio(segmentation: SegmentationMask): Float {
        val width = segmentation.width
        val height = segmentation.height
        if (width <= 0 || height <= 0) return 0f

        val bottomStartY = ((1 - config.segmentationBottomRatio) * height).toInt()
        val centerRatio = widthFractionOfLongSide(config.segmentationCenterRatio, width, height)
        val centerStartX = ((1 - centerRatio) / 2 * width).toInt()
        val centerEndX = ((1 + centerRatio) / 2 * width).toInt()

        val classMap = segmentation.classMap
        var total = 0
        var unexplained = 0
        for (y in bottomStartY until height) {
            val rowOffset = y * width
            for (x in centerStartX until centerEndX) {
                val classId = classMap[rowOffset + x]
                total++
                if (classId !in explainsGround.indices || !explainsGround[classId]) {
                    unexplained++
                }
            }
        }
        return if (total > 0) unexplained.toFloat() / total else 0f
    }
}
