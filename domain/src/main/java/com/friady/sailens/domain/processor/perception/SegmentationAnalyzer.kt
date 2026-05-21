package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.model.perception.SegmentationMask
import com.friady.sailens.domain.util.BooleanStabilizer
import com.friady.sailens.domain.util.FloatSmoother

/**
 * 语义分割分析器
 * 单次遍历提取所有信息并稳定化
 */
class SegmentationAnalyzer(
    private val config: AnalysisConfig,
    private val classMapper: ClassMapper,
) {
    // 稳定器
    private val roadRatioSmoother = FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val bottomCenterRoadRatioSmoother =
        FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val navigationPassableRatioSmoother =
        FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val trafficLightStabilizer =
        BooleanStabilizer(requiredFrames = config.trafficLightDebounceFrames)

    companion object {
        private const val BOTTOM_RATIO = 0.2f
        private const val CENTER_RATIO = 0.4f
        private const val NAVIGATION_REGION_RATIO = 0.45f
    }

    /**
     * 单次遍历分析
     */
    fun analyze(segmentation: SegmentationMask): SegmentationAnalysis {
        val width = segmentation.width
        val height = segmentation.height
        val totalPixels = width * height

        // 创建 Mask
        val passableMask = BinaryMask(width, height)
        val obstacleMask = BinaryMask(width, height)

        // 统计变量
        var roadPixelCount = 0
        var passablePixelCount = 0
        var obstaclePixelCount = 0
        var rawHasTrafficLight = false
        val classCounts = IntArray(classMapper.classCount)

        // 底部区域定义
        val bottomStartY = ((1 - BOTTOM_RATIO) * height).toInt()
        val navigationStartY = ((1 - NAVIGATION_REGION_RATIO) * height).toInt()
        val centerStartX = ((1 - CENTER_RATIO) / 2 * width).toInt()
        val centerEndX = ((1 + CENTER_RATIO) / 2 * width).toInt()

        // 底部中央统计
        val groundTypeCounts = mutableMapOf<GroundType, Int>()
        var bottomCenterRoadPixels = 0
        var bottomCenterTotalPixels = 0
        var navigationPassablePixelCount = 0
        var navigationTotalPixels = 0

        // 底部 run 统计
        val bottomRowRuns = mutableMapOf<Int, MutableList<IntRange>>()

        // ===== 单次遍历 =====
        for (y in 0 until height) {
            var currentRunStart = -1

            for (x in 0 until width) {
                val classId = segmentation.getClassId(x, y)
                if (classId in 0 until classCounts.size) {
                    classCounts[classId]++
                }

                // 判断类型
                val isPassable = classMapper.isPassable(classId)
                val isObstacle = classMapper.isObstacle(classId)
                val isRoad = classMapper.isRoad(classId)
                val isTrafficLight = classMapper.isTrafficLight(classId)

                // 1. 设置 Mask
                if (isPassable) {
                    passableMask.set(x, y, true)
                    passablePixelCount++
                }
                if (isObstacle) {
                    obstacleMask.set(x, y, true)
                    obstaclePixelCount++
                }

                // 2.  全图统计
                if (isRoad) {
                    roadPixelCount++
                }
                if (isTrafficLight) {
                    rawHasTrafficLight = true
                }
                if (y >= navigationStartY) {
                    navigationTotalPixels++
                    if (isPassable) {
                        navigationPassablePixelCount++
                    }
                }

                // 3. 底部区域统计
                if (y >= bottomStartY) {
                    // 底部中央区域
                    if (x in centerStartX until centerEndX) {
                        bottomCenterTotalPixels++
                        val groundType = classMapper.toGroundType(classId)
                        if (groundType != GroundType.UNKNOWN) {
                            groundTypeCounts[groundType] =
                                groundTypeCounts.getOrDefault(groundType, 0) + 1
                        }
                        if (isRoad) {
                            bottomCenterRoadPixels++
                        }
                    }

                    // Run 统计
                    if (isPassable && currentRunStart == -1) {
                        currentRunStart = x
                    } else if (!isPassable && currentRunStart != -1) {
                        bottomRowRuns.getOrPut(y) { mutableListOf() }
                            .add(currentRunStart until x)
                        currentRunStart = -1
                    }
                }
            }

            // 行末处理
            if (y >= bottomStartY && currentRunStart != -1) {
                bottomRowRuns.getOrPut(y) { mutableListOf() }
                    .add(currentRunStart until width)
            }
        }

        // 计算统计结果
        val rawRoadRatio = roadPixelCount.toFloat() / totalPixels
        val navigationPassableRatio = if (navigationTotalPixels > 0) {
            navigationPassablePixelCount.toFloat() / navigationTotalPixels
        } else {
            0f
        }

        val bottomCenterGroundDistribution = if (bottomCenterTotalPixels > 0) {
            groundTypeCounts.mapValues { it.value.toFloat() / bottomCenterTotalPixels }
        } else {
            emptyMap()
        }

        val bottomCenterRoadRatio = if (bottomCenterTotalPixels > 0) {
            bottomCenterRoadPixels.toFloat() / bottomCenterTotalPixels
        } else {
            0f
        }

        val bottomStats = computeBottomStats(bottomRowRuns, width, height, bottomStartY)

        // 稳定化
        val stableRoadRatio = roadRatioSmoother.update(rawRoadRatio)
        val stableBottomCenterRoadRatio =
            bottomCenterRoadRatioSmoother.update(bottomCenterRoadRatio)
        val stableNavigationPassableRatio =
            navigationPassableRatioSmoother.update(navigationPassableRatio)
        val stableHasTrafficLight = trafficLightStabilizer.update(rawHasTrafficLight)
        val dominantClassNames = classCounts
            .withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(3)
            .map { indexedValue -> classMapper.getClassName(indexedValue.index) }

        return SegmentationAnalysis(
            passableMask = passableMask,
            obstacleMask = obstacleMask,
            roadRatio = stableRoadRatio,
            hasTrafficLight = stableHasTrafficLight,
            bottomCenterGroundDistribution = bottomCenterGroundDistribution,
            bottomCenterRoadRatio = stableBottomCenterRoadRatio,
            bottomStats = bottomStats,
            passablePixelCount = passablePixelCount,
            navigationPassableRatio = stableNavigationPassableRatio,
            obstaclePixelCount = obstaclePixelCount,
            dominantClassNames = dominantClassNames,
            segmentation = segmentation,
            width = width,
            height = height
        )
    }

    private fun computeBottomStats(
        rowRuns: Map<Int, List<IntRange>>,
        width: Int,
        height: Int,
        bottomStartY: Int,
    ): BottomStats {
        var maxRunWidth = 0
        var maxRunRow = bottomStartY
        var maxRunStart = 0
        var maxRunEnd = 0
        var totalTruePixels = 0
        val totalBottomPixels = (height - bottomStartY) * width

        for ((row, runs) in rowRuns) {
            for (run in runs) {
                val runWidth = run.last - run.first + 1
                totalTruePixels += runWidth
                if (runWidth > maxRunWidth) {
                    maxRunWidth = runWidth
                    maxRunRow = row
                    maxRunStart = run.first
                    maxRunEnd = run.last
                }
            }
        }

        return BottomStats(
            coverage = if (totalBottomPixels > 0) totalTruePixels.toFloat() / totalBottomPixels else 0f,
            maxRunWidth = maxRunWidth,
            maxRunWidthRatio = maxRunWidth.toFloat() / width,
            maxRunRow = maxRunRow,
            maxRunStart = maxRunStart,
            maxRunEnd = maxRunEnd,
            maxRunCenter = if (maxRunWidth > 0) (maxRunStart + maxRunEnd) / 2f / width else 0.5f
        )
    }

    fun reset() {
        roadRatioSmoother.reset()
        bottomCenterRoadRatioSmoother.reset()
        navigationPassableRatioSmoother.reset()
        trafficLightStabilizer.reset()
    }
}
