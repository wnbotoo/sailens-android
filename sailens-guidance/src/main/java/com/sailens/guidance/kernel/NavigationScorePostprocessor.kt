package com.sailens.guidance.kernel

import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.nativeValue
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.guidance.model.perception.SegmentationAnalysisStats
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.core.log.LogService

private const val TAG = "NativeSemanticPost"

data class SemanticPostprocessResult(
    val mask: SegmentationMask,
    val stats: SegmentationAnalysisStats,
)

class NavigationScorePostprocessor(
    private val config: AnalysisConfig,
    navigationSemantics: NavigationSemantics,
    private val logService: LogService,
) {
    private val lookup = SemanticClassLookup.from(navigationSemantics)
    private var hasLoggedBackend = false
    private var reusablePassableWords = LongArray(0)
    private var reusableObstacleWords = LongArray(0)
    private var reusableClassCounts = IntArray(0)
    private var reusableGroundTypeCounts = IntArray(0)
    private var reusableIntOutputs = IntArray(0)

    fun postprocessScores(
        scores: FloatArray,
        reusableResultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
    ): SemanticPostprocessResult? {
        val pixelCount = width * height
        if (width <= 0 ||
            height <= 0 ||
            channels <= 0 ||
            scores.size != pixelCount * channels ||
            reusableResultMask.size != pixelCount
        ) {
            return null
        }

        return postprocessPrepared(
            reusableResultMask = reusableResultMask,
            width = width,
            height = height,
            pixelCount = pixelCount,
        ) { scratch ->
            nativePostprocessScores(
                scores = scores,
                resultMask = reusableResultMask,
                width = width,
                height = height,
                channels = channels,
                scoreLayout = scoreLayout.nativeValue,
                passableLookup = lookup.passable,
                obstacleLookup = lookup.obstacle,
                roadLookup = lookup.road,
                trafficLightLookup = lookup.trafficLight,
                groundTypeLookup = lookup.groundType,
                bottomRatio = config.segmentationBottomRatio,
                centerRatio = config.segmentationCenterRatio,
                navigationRegionRatio = config.segmentationNavigationRegionRatio,
                passableWords = scratch.passableWords,
                obstacleWords = scratch.obstacleWords,
                classCounts = scratch.classCounts,
                groundTypeCounts = scratch.groundTypeCounts,
                intOutputs = scratch.intOutputs,
            )
        }
    }

    fun postprocessInt8Scores(
        scores: ByteArray,
        reusableResultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
    ): SemanticPostprocessResult? {
        val pixelCount = width * height
        if (width <= 0 ||
            height <= 0 ||
            channels <= 0 ||
            scores.size != pixelCount * channels ||
            reusableResultMask.size != pixelCount
        ) {
            return null
        }

        return postprocessPrepared(
            reusableResultMask = reusableResultMask,
            width = width,
            height = height,
            pixelCount = pixelCount,
        ) { scratch ->
            nativePostprocessInt8Scores(
                scores = scores,
                resultMask = reusableResultMask,
                width = width,
                height = height,
                channels = channels,
                scoreLayout = scoreLayout.nativeValue,
                passableLookup = lookup.passable,
                obstacleLookup = lookup.obstacle,
                roadLookup = lookup.road,
                trafficLightLookup = lookup.trafficLight,
                groundTypeLookup = lookup.groundType,
                bottomRatio = config.segmentationBottomRatio,
                centerRatio = config.segmentationCenterRatio,
                navigationRegionRatio = config.segmentationNavigationRegionRatio,
                passableWords = scratch.passableWords,
                obstacleWords = scratch.obstacleWords,
                classCounts = scratch.classCounts,
                groundTypeCounts = scratch.groundTypeCounts,
                intOutputs = scratch.intOutputs,
            )
        }
    }

    private fun postprocessPrepared(
        reusableResultMask: IntArray,
        width: Int,
        height: Int,
        pixelCount: Int,
        nativeCall: (SemanticScratch) -> Boolean,
    ): SemanticPostprocessResult? {
        if (!NativeGuidanceLibrary.isAvailable) return null

        val wordCount = (pixelCount + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val passableWords = reusablePassableWords.withMinSize(wordCount).also {
            reusablePassableWords = it
        }
        val obstacleWords = reusableObstacleWords.withMinSize(wordCount).also {
            reusableObstacleWords = it
        }
        val classCounts = reusableClassCounts.withMinSize(lookup.classCount).also {
            reusableClassCounts = it
        }
        val groundTypeCounts = reusableGroundTypeCounts.withMinSize(GroundType.entries.size).also {
            reusableGroundTypeCounts = it
        }
        val intOutputs = reusableIntOutputs.withMinSize(INT_OUTPUT_COUNT).also {
            reusableIntOutputs = it
        }

        val scratch = SemanticScratch(
            passableWords = passableWords,
            obstacleWords = obstacleWords,
            classCounts = classCounts,
            groundTypeCounts = groundTypeCounts,
            intOutputs = intOutputs,
        )
        val nativeSuccess = runCatching {
            nativeCall(scratch)
        }.getOrDefault(false)

        if (!nativeSuccess) return null

        if (!hasLoggedBackend) {
            logService.info(TAG, "Semantic score postprocess backend: native")
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

    private data class SemanticScratch(
        val passableWords: LongArray,
        val obstacleWords: LongArray,
        val classCounts: IntArray,
        val groundTypeCounts: IntArray,
        val intOutputs: IntArray,
    )

    // Zero-copy variant for FLOAT32 output: skips readFloat() by reading the model
    // output tensor directly via its native LiteRtTensorBuffer* handle. Falls back
    // to postprocessScores() at the call site if this returns null.
    fun postprocessScoresFromHandle(
        tensorBufferHandle: Long,
        reusableResultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
    ): SemanticPostprocessResult? {
        if (tensorBufferHandle == 0L) return null
        val pixelCount = width * height
        if (width <= 0 || height <= 0 || channels <= 0 || reusableResultMask.size != pixelCount) return null
        return postprocessPrepared(
            reusableResultMask = reusableResultMask,
            width = width,
            height = height,
            pixelCount = pixelCount,
        ) { scratch ->
            nativePostprocessScoresFromHandle(
                 tensorBufferHandle = tensorBufferHandle,
                 resultMask = reusableResultMask,
                 width = width,
                 height = height,
                 channels = channels,
                 scoreLayout = scoreLayout.nativeValue,
                 passableLookup = lookup.passable,
                 obstacleLookup = lookup.obstacle,
                 roadLookup = lookup.road,
                 trafficLightLookup = lookup.trafficLight,
                 groundTypeLookup = lookup.groundType,
                 bottomRatio = config.segmentationBottomRatio,
                 centerRatio = config.segmentationCenterRatio,
                 navigationRegionRatio = config.segmentationNavigationRegionRatio,
                 passableWords = scratch.passableWords,
                 obstacleWords = scratch.obstacleWords,
                 classCounts = scratch.classCounts,
                 groundTypeCounts = scratch.groundTypeCounts,
                 intOutputs = scratch.intOutputs,
            )
        }
    }

    // Zero-copy variant for INT8 output: same as postprocessScoresFromHandle() but
    // the locked buffer is interpreted as int8_t*, matching full-integer-quant models.
    fun postprocessInt8ScoresFromHandle(
        tensorBufferHandle: Long,
        reusableResultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
    ): SemanticPostprocessResult? {
        if (tensorBufferHandle == 0L) return null
        val pixelCount = width * height
        if (width <= 0 || height <= 0 || channels <= 0 || reusableResultMask.size != pixelCount) return null
        return postprocessPrepared(
            reusableResultMask = reusableResultMask,
            width = width,
            height = height,
            pixelCount = pixelCount,
        ) { scratch ->
            nativePostprocessInt8ScoresFromHandle(
                tensorBufferHandle = tensorBufferHandle,
                resultMask = reusableResultMask,
                width = width,
                height = height,
                channels = channels,
                scoreLayout = scoreLayout.nativeValue,
                passableLookup = lookup.passable,
                obstacleLookup = lookup.obstacle,
                roadLookup = lookup.road,
                trafficLightLookup = lookup.trafficLight,
                groundTypeLookup = lookup.groundType,
                bottomRatio = config.segmentationBottomRatio,
                centerRatio = config.segmentationCenterRatio,
                navigationRegionRatio = config.segmentationNavigationRegionRatio,
                passableWords = scratch.passableWords,
                obstacleWords = scratch.obstacleWords,
                classCounts = scratch.classCounts,
                groundTypeCounts = scratch.groundTypeCounts,
                intOutputs = scratch.intOutputs,
            )
        }
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
        scoreLayout: Int,
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

    private external fun nativePostprocessInt8Scores(
        scores: ByteArray,
        resultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: Int,
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

    private external fun nativePostprocessScoresFromHandle(
        tensorBufferHandle: Long,
        resultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: Int,
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

    private external fun nativePostprocessInt8ScoresFromHandle(
        tensorBufferHandle: Long,
        resultMask: IntArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: Int,
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

    private fun LongArray.withMinSize(size: Int): LongArray {
        return if (this.size >= size) this else LongArray(size)
    }

    private fun IntArray.withMinSize(size: Int): IntArray {
        return if (this.size >= size) this else IntArray(size)
    }

    private data class SemanticClassLookup(
        val classCount: Int,
        val passable: BooleanArray,
        val obstacle: BooleanArray,
        val road: BooleanArray,
        val trafficLight: BooleanArray,
        val groundType: IntArray,
    ) {
        companion object {
            fun from(navigationSemantics: NavigationSemantics): SemanticClassLookup {
                val classCount = navigationSemantics.classCount
                return SemanticClassLookup(
                    classCount = classCount,
                    passable = BooleanArray(classCount) { navigationSemantics.isPassable(it) },
                    obstacle = BooleanArray(classCount) { navigationSemantics.isObstacle(it) },
                    road = BooleanArray(classCount) { navigationSemantics.isRoad(it) },
                    trafficLight = BooleanArray(classCount) { navigationSemantics.isTrafficLight(it) },
                    groundType = IntArray(classCount) { index ->
                        navigationSemantics.toGroundType(index).takeIf { it != GroundType.UNKNOWN }?.ordinal ?: UNKNOWN_GROUND
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
