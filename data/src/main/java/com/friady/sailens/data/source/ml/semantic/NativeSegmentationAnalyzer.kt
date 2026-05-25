package com.friady.sailens.data.source.ml.semantic

import android.util.Log
import com.friady.sailens.data.source.ml.NativeMlLibrary
import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.model.perception.SegmentationMask
import com.friady.sailens.domain.processor.perception.SegmentationAnalysisProcessor
import com.friady.sailens.domain.processor.perception.SegmentationAnalyzer
import com.friady.sailens.domain.util.BooleanStabilizer
import com.friady.sailens.domain.util.FloatSmoother

private const val TAG = "NativeSegAnalyzer"

class NativeSegmentationAnalyzer(
    private val config: AnalysisConfig,
    private val classMapper: ClassMapper,
) : SegmentationAnalysisProcessor {
    private val fallbackAnalyzer = SegmentationAnalyzer(config, classMapper)
    private val lookup = SemanticClassLookup.from(classMapper)

    private val roadRatioSmoother = FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val bottomCenterRoadRatioSmoother =
        FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val navigationPassableRatioSmoother =
        FloatSmoother(windowSize = config.roadRatioSmoothWindow)
    private val trafficLightStabilizer =
        BooleanStabilizer(requiredFrames = config.trafficLightDebounceFrames)
    private var hasLoggedBackend = false

    fun analyzeScores(
        scores: FloatArray,
        reusableResultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
    ): SegmentationAnalysis? {
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
        val classCounts = IntArray(classMapper.classCount)
        val groundTypeCounts = IntArray(GroundType.entries.size)
        val intOutputs = IntArray(INT_OUTPUT_COUNT)

        val nativeSuccess = runCatching {
            nativeAnalyzeScores(
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
                bottomRatio = BOTTOM_RATIO,
                centerRatio = CENTER_RATIO,
                navigationRegionRatio = NAVIGATION_REGION_RATIO,
                passableWords = passableWords,
                obstacleWords = obstacleWords,
                classCounts = classCounts,
                groundTypeCounts = groundTypeCounts,
                intOutputs = intOutputs,
            )
        }.getOrDefault(false)

        if (!nativeSuccess) return null

        logBackend("native fused score analysis")
        return buildAnalysis(
            segmentation = SegmentationMask(width, height, reusableResultMask.clone()),
            passableWords = passableWords,
            obstacleWords = obstacleWords,
            classCounts = classCounts,
            groundTypeCounts = groundTypeCounts,
            intOutputs = intOutputs,
        )
    }

    override fun analyze(segmentation: SegmentationMask): SegmentationAnalysis {
        if (!NativeMlLibrary.isAvailable) {
            logBackend("Kotlin fallback; native library unavailable")
            return fallbackAnalyzer.analyze(segmentation)
        }

        val width = segmentation.width
        val height = segmentation.height
        val pixelCount = width * height
        if (pixelCount <= 0 || segmentation.classMap.size != pixelCount) {
            logBackend("Kotlin fallback; invalid mask ${width}x$height size=${segmentation.classMap.size}")
            return fallbackAnalyzer.analyze(segmentation)
        }

        val wordCount = (pixelCount + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val passableWords = LongArray(wordCount)
        val obstacleWords = LongArray(wordCount)
        val classCounts = IntArray(classMapper.classCount)
        val groundTypeCounts = IntArray(GroundType.entries.size)
        val intOutputs = IntArray(INT_OUTPUT_COUNT)

        val nativeSuccess = runCatching {
            nativeAnalyze(
                classMap = segmentation.classMap,
                width = width,
                height = height,
                passableLookup = lookup.passable,
                obstacleLookup = lookup.obstacle,
                roadLookup = lookup.road,
                trafficLightLookup = lookup.trafficLight,
                groundTypeLookup = lookup.groundType,
                bottomRatio = BOTTOM_RATIO,
                centerRatio = CENTER_RATIO,
                navigationRegionRatio = NAVIGATION_REGION_RATIO,
                passableWords = passableWords,
                obstacleWords = obstacleWords,
                classCounts = classCounts,
                groundTypeCounts = groundTypeCounts,
                intOutputs = intOutputs,
            )
        }.getOrDefault(false)

        if (!nativeSuccess) {
            logBackend("Kotlin fallback; native analysis failed")
            return fallbackAnalyzer.analyze(segmentation)
        }
        logBackend("native")

        return buildAnalysis(
            segmentation = segmentation,
            passableWords = passableWords,
            obstacleWords = obstacleWords,
            classCounts = classCounts,
            groundTypeCounts = groundTypeCounts,
            intOutputs = intOutputs,
        )
    }

    private fun buildAnalysis(
        segmentation: SegmentationMask,
        passableWords: LongArray,
        obstacleWords: LongArray,
        classCounts: IntArray,
        groundTypeCounts: IntArray,
        intOutputs: IntArray,
    ): SegmentationAnalysis {
        val width = segmentation.width
        val height = segmentation.height
        val pixelCount = width * height
        val bottomCenterTotalPixels = intOutputs[OUT_BOTTOM_CENTER_TOTAL_PIXELS]
        val bottomCenterGroundDistribution = buildBottomCenterGroundDistribution(
            groundTypeCounts = groundTypeCounts,
            bottomCenterTotalPixels = bottomCenterTotalPixels,
        )

        val totalPixels = pixelCount.toFloat()
        val rawRoadRatio = if (totalPixels > 0f) intOutputs[OUT_ROAD_PIXEL_COUNT] / totalPixels else 0f
        val rawNavigationPassableRatio = intOutputs[OUT_NAVIGATION_TOTAL_PIXELS]
            .takeIf { it > 0 }
            ?.let { intOutputs[OUT_NAVIGATION_PASSABLE_PIXELS].toFloat() / it }
            ?: 0f
        val rawBottomCenterRoadRatio = bottomCenterTotalPixels
            .takeIf { it > 0 }
            ?.let { intOutputs[OUT_BOTTOM_CENTER_ROAD_PIXELS].toFloat() / it }
            ?: 0f

        return SegmentationAnalysis(
            passableMask = BinaryMask.fromPackedBits(width, height, passableWords),
            obstacleMask = BinaryMask.fromPackedBits(width, height, obstacleWords),
            roadRatio = roadRatioSmoother.update(rawRoadRatio),
            hasTrafficLight = trafficLightStabilizer.update(intOutputs[OUT_HAS_TRAFFIC_LIGHT] != 0),
            bottomCenterGroundDistribution = bottomCenterGroundDistribution,
            bottomCenterRoadRatio = bottomCenterRoadRatioSmoother.update(rawBottomCenterRoadRatio),
            bottomStats = buildBottomStats(
                width = width,
                height = height,
                bottomStartY = ((1 - BOTTOM_RATIO) * height).toInt(),
                bottomTruePixels = intOutputs[OUT_BOTTOM_TRUE_PIXELS],
                maxRunWidth = intOutputs[OUT_MAX_RUN_WIDTH],
                maxRunRow = intOutputs[OUT_MAX_RUN_ROW],
                maxRunStart = intOutputs[OUT_MAX_RUN_START],
                maxRunEnd = intOutputs[OUT_MAX_RUN_END],
            ),
            passablePixelCount = intOutputs[OUT_PASSABLE_PIXEL_COUNT],
            navigationPassableRatio = navigationPassableRatioSmoother.update(rawNavigationPassableRatio),
            obstaclePixelCount = intOutputs[OUT_OBSTACLE_PIXEL_COUNT],
            dominantClassNames = dominantClassNames(classCounts),
            segmentation = segmentation,
            width = width,
            height = height,
        )
    }

    override fun reset() {
        fallbackAnalyzer.reset()
        roadRatioSmoother.reset()
        bottomCenterRoadRatioSmoother.reset()
        navigationPassableRatioSmoother.reset()
        trafficLightStabilizer.reset()
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

    private fun dominantClassNames(classCounts: IntArray): List<String> {
        return classCounts
            .withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(3)
            .map { classMapper.getClassName(it.index) }
    }

    private fun logBackend(message: String) {
        if (hasLoggedBackend) return
        Log.i(TAG, "Segmentation analyzer backend: $message")
        hasLoggedBackend = true
    }

    private external fun nativeAnalyze(
        classMap: IntArray,
        width: Int,
        height: Int,
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

    private external fun nativeAnalyzeScores(
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
        private const val BOTTOM_RATIO = 0.2f
        private const val CENTER_RATIO = 0.4f
        private const val NAVIGATION_REGION_RATIO = 0.45f
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
