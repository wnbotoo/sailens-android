package com.friady.sailens.data.source.ml.semantic

import android.util.Log
import com.friady.sailens.data.source.ml.NativeMlLibrary
import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationAnalysisStats
import com.friady.sailens.domain.model.perception.SegmentationMask

private const val TAG = "NativeSemanticPost"

internal data class SemanticPostprocessResult(
    val mask: SegmentationMask,
    val stats: SegmentationAnalysisStats,
)

class NativeSemanticScorePostprocessor(
    private val config: AnalysisConfig,
    classMapper: ClassMapper,
) {
    private val lookup = SemanticClassLookup.from(classMapper)
    private var hasLoggedBackend = false

    internal fun postprocessScores(
        scores: FloatArray,
        reusableResultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
    ): SemanticPostprocessResult? {
        if (!NativeMlLibrary.isAvailable) return null

        val pixelCount = width * height
        if (width <= 0 ||
            height <= 0 ||
            channels <= 0 ||
            scores.size != pixelCount * channels ||
            reusableResultMask.size != pixelCount
        ) {
            return null
        }

        val wordCount = (pixelCount + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val passableWords = LongArray(wordCount)
        val obstacleWords = LongArray(wordCount)
        val classCounts = IntArray(lookup.classCount)
        val groundTypeCounts = IntArray(GroundType.entries.size)
        val intOutputs = IntArray(INT_OUTPUT_COUNT)

        val nativeSuccess = runCatching {
            nativePostprocessScores(
                scores = scores,
                resultMask = reusableResultMask,
                width = width,
                height = height,
                channels = channels,
                passableLookup = lookup.passable,
                obstacleLookup = lookup.obstacle,
                roadLookup = lookup.road,
                trafficLightLookup = lookup.trafficLight,
                groundTypeLookup = lookup.groundType,
                bottomRatio = config.segmentationBottomRatio,
                centerRatio = config.segmentationCenterRatio,
                navigationRegionRatio = config.segmentationNavigationRegionRatio,
                passableWords = passableWords,
                obstacleWords = obstacleWords,
                classCounts = classCounts,
                groundTypeCounts = groundTypeCounts,
                intOutputs = intOutputs,
            )
        }.getOrDefault(false)

        if (!nativeSuccess) return null

        if (!hasLoggedBackend) {
            Log.i(TAG, "Semantic score postprocess backend: native")
            hasLoggedBackend = true
        }

        val mask = SegmentationMask(width, height, reusableResultMask.clone())
        return SemanticPostprocessResult(
            mask = mask,
            stats = buildStats(
                width = width,
                height = height,
                passableWords = passableWords,
                obstacleWords = obstacleWords,
                classCounts = classCounts,
                groundTypeCounts = groundTypeCounts,
                intOutputs = intOutputs,
            ),
        )
    }

    private fun buildStats(
        width: Int,
        height: Int,
        passableWords: LongArray,
        obstacleWords: LongArray,
        classCounts: IntArray,
        groundTypeCounts: IntArray,
        intOutputs: IntArray,
    ): SegmentationAnalysisStats {
        val pixelCount = width * height
        val bottomCenterTotalPixels = intOutputs[OUT_BOTTOM_CENTER_TOTAL_PIXELS]
        val totalPixels = pixelCount.toFloat()

        return SegmentationAnalysisStats(
            passableMask = BinaryMask.fromPackedBits(width, height, passableWords),
            obstacleMask = BinaryMask.fromPackedBits(width, height, obstacleWords),
            roadRatio = if (totalPixels > 0f) intOutputs[OUT_ROAD_PIXEL_COUNT] / totalPixels else 0f,
            hasTrafficLight = intOutputs[OUT_HAS_TRAFFIC_LIGHT] != 0,
            bottomCenterGroundDistribution = buildBottomCenterGroundDistribution(
                groundTypeCounts = groundTypeCounts,
                bottomCenterTotalPixels = bottomCenterTotalPixels,
            ),
            bottomCenterRoadRatio = bottomCenterTotalPixels
                .takeIf { it > 0 }
                ?.let { intOutputs[OUT_BOTTOM_CENTER_ROAD_PIXELS].toFloat() / it }
                ?: 0f,
            bottomStats = buildBottomStats(
                width = width,
                height = height,
                bottomStartY = ((1 - config.segmentationBottomRatio) * height).toInt(),
                bottomTruePixels = intOutputs[OUT_BOTTOM_TRUE_PIXELS],
                maxRunWidth = intOutputs[OUT_MAX_RUN_WIDTH],
                maxRunRow = intOutputs[OUT_MAX_RUN_ROW],
                maxRunStart = intOutputs[OUT_MAX_RUN_START],
                maxRunEnd = intOutputs[OUT_MAX_RUN_END],
            ),
            passablePixelCount = intOutputs[OUT_PASSABLE_PIXEL_COUNT],
            navigationPassableRatio = intOutputs[OUT_NAVIGATION_TOTAL_PIXELS]
                .takeIf { it > 0 }
                ?.let { intOutputs[OUT_NAVIGATION_PASSABLE_PIXELS].toFloat() / it }
                ?: 0f,
            obstaclePixelCount = intOutputs[OUT_OBSTACLE_PIXEL_COUNT],
            classCounts = classCounts,
        )
    }

    private fun buildBottomStats(
        width: Int,
        height: Int,
        bottomStartY: Int,
        bottomTruePixels: Int,
        maxRunWidth: Int,
        maxRunRow: Int,
        maxRunStart: Int,
        maxRunEnd: Int,
    ): BottomStats {
        val totalBottomPixels = (height - bottomStartY) * width
        return BottomStats(
            coverage = if (totalBottomPixels > 0) {
                bottomTruePixels.toFloat() / totalBottomPixels
            } else {
                0f
            },
            maxRunWidth = maxRunWidth,
            maxRunWidthRatio = maxRunWidth.toFloat() / width,
            maxRunRow = maxRunRow,
            maxRunStart = maxRunStart,
            maxRunEnd = maxRunEnd,
            maxRunCenter = if (maxRunWidth > 0) {
                (maxRunStart + maxRunEnd) / 2f / width
            } else {
                0.5f
            },
        )
    }

    private fun buildBottomCenterGroundDistribution(
        groundTypeCounts: IntArray,
        bottomCenterTotalPixels: Int,
    ): Map<GroundType, Float> {
        if (bottomCenterTotalPixels <= 0) return emptyMap()

        val distribution = mutableMapOf<GroundType, Float>()
        for (index in groundTypeCounts.indices) {
            val count = groundTypeCounts[index]
            if (count > 0) {
                distribution[GroundType.entries[index]] = count.toFloat() / bottomCenterTotalPixels
            }
        }
        return distribution
    }

    private external fun nativePostprocessScores(
        scores: FloatArray,
        resultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        passableLookup: BooleanArray,
        obstacleLookup: BooleanArray,
        roadLookup: BooleanArray,
        trafficLightLookup: BooleanArray,
        groundTypeLookup: IntArray,
        bottomRatio: Float,
        centerRatio: Float,
        navigationRegionRatio: Float,
        passableWords: LongArray,
        obstacleWords: LongArray,
        classCounts: IntArray,
        groundTypeCounts: IntArray,
        intOutputs: IntArray,
    ): Boolean

    private data class SemanticClassLookup(
        val classCount: Int,
        val passable: BooleanArray,
        val obstacle: BooleanArray,
        val road: BooleanArray,
        val trafficLight: BooleanArray,
        val groundType: IntArray,
    ) {
        companion object {
            fun from(classMapper: ClassMapper): SemanticClassLookup {
                val classCount = classMapper.classCount
                return SemanticClassLookup(
                    classCount = classCount,
                    passable = BooleanArray(classCount) { classMapper.isPassable(it) },
                    obstacle = BooleanArray(classCount) { classMapper.isObstacle(it) },
                    road = BooleanArray(classCount) { classMapper.isRoad(it) },
                    trafficLight = BooleanArray(classCount) { classMapper.isTrafficLight(it) },
                    groundType = IntArray(classCount) { index ->
                        classMapper.toGroundType(index).takeIf { it != GroundType.UNKNOWN }?.ordinal ?: UNKNOWN_GROUND
                    },
                )
            }
        }
    }

    private companion object {
        private const val UNKNOWN_GROUND = -1

        private const val OUT_PASSABLE_PIXEL_COUNT = 0
        private const val OUT_OBSTACLE_PIXEL_COUNT = 1
        private const val OUT_ROAD_PIXEL_COUNT = 2
        private const val OUT_HAS_TRAFFIC_LIGHT = 3
        private const val OUT_BOTTOM_CENTER_ROAD_PIXELS = 4
        private const val OUT_BOTTOM_CENTER_TOTAL_PIXELS = 5
        private const val OUT_NAVIGATION_PASSABLE_PIXELS = 6
        private const val OUT_NAVIGATION_TOTAL_PIXELS = 7
        private const val OUT_BOTTOM_TRUE_PIXELS = 8
        private const val OUT_MAX_RUN_WIDTH = 9
        private const val OUT_MAX_RUN_ROW = 10
        private const val OUT_MAX_RUN_START = 11
        private const val OUT_MAX_RUN_END = 12
        private const val INT_OUTPUT_COUNT = 13
    }
}
