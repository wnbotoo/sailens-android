package com.sailens.guidance.kernel

import com.sailens.runtime.ImageTensorLayout
import com.sailens.vision.semantic.SemanticPostprocessOutcome
import com.sailens.vision.semantic.SemanticPostprocessor
import com.sailens.vision.semantic.SemanticContentRegion
import com.sailens.vision.semantic.SemanticScoreSpec
import com.sailens.vision.semantic.SemanticScores
import com.sailens.runtime.nativeValue
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.widthFractionOfLongSide
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.guidance.model.perception.SegmentationAnalysisStats
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationMaskLease
import com.sailens.guidance.model.perception.SegmentationMaskPool
import com.sailens.core.log.LogService

private const val TAG = "NativeSemanticPost"

/** @property maskLease owns [mask]'s class map; see [SegmentationMaskLease]. */
data class SemanticPostprocessResult(
    val maskLease: SegmentationMaskLease,
    val stats: SegmentationAnalysisStats,
) {
    val mask: SegmentationMask get() = maskLease.mask
}

/**
 * What Guidance gets back from one semantic frame.
 *
 * [stats] is null when the fused native pass did not run and the class map came from a plain
 * argmax; SegmentationAnalyzer then extracts the same statistics in Kotlin.
 *
 * [maskLease] owns [mask]'s class map. Whoever keeps the mask closes the lease once nothing will
 * read it again, and the array is then reused for a later frame.
 */
data class NavigationSemanticResult(
    val mask: SegmentationMask,
    val stats: SegmentationAnalysisStats?,
    val maskLease: SegmentationMaskLease? = null,
)

class NavigationScorePostprocessor(
    private val config: AnalysisConfig,
    navigationSemantics: NavigationSemantics,
    private val logService: LogService,
    private val maskPool: SegmentationMaskPool = SegmentationMaskPool(),
) : SemanticPostprocessor<NavigationSemanticResult> {
    private val lookup = SemanticClassLookup.from(navigationSemantics)
    private var hasLoggedBackend = false
    private var reusablePassableWords = LongArray(0)
    private var reusableObstacleWords = LongArray(0)
    private var reusableClassCounts = IntArray(0)
    private var reusableGroundTypeCounts = IntArray(0)
    private var reusableIntOutputs = IntArray(0)

    fun postprocessScores(
        scores: FloatArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
        content: SemanticContentRegion = SemanticContentRegion(0, 0, width, height),
    ): SemanticPostprocessResult? {
        val pixelCount = width * height
        if (width <= 0 ||
            height <= 0 ||
            channels <= 0 ||
            scores.size != pixelCount * channels ||
            !content.fitsIn(width, height)
        ) {
            return null
        }

        return postprocessPrepared(
            content = content,
        ) { scratch ->
            nativePostprocessScores(
                scores = scores,
                resultMask = scratch.classMap,
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
                centerRatio = widthFractionOfLongSide(config.segmentationCenterRatio, content.width, content.height),
                navigationRegionRatio = config.segmentationNavigationRegionRatio,
                passableWords = scratch.passableWords,
                obstacleWords = scratch.obstacleWords,
                classCounts = scratch.classCounts,
                groundTypeCounts = scratch.groundTypeCounts,
                intOutputs = scratch.intOutputs,
                contentRegion = scratch.contentRegion,
            )
        }
    }

    fun postprocessInt8Scores(
        scores: ByteArray,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
        content: SemanticContentRegion = SemanticContentRegion(0, 0, width, height),
    ): SemanticPostprocessResult? {
        val pixelCount = width * height
        if (width <= 0 ||
            height <= 0 ||
            channels <= 0 ||
            scores.size != pixelCount * channels ||
            !content.fitsIn(width, height)
        ) {
            return null
        }

        return postprocessPrepared(
            content = content,
        ) { scratch ->
            nativePostprocessInt8Scores(
                scores = scores,
                resultMask = scratch.classMap,
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
                centerRatio = widthFractionOfLongSide(config.segmentationCenterRatio, content.width, content.height),
                navigationRegionRatio = config.segmentationNavigationRegionRatio,
                passableWords = scratch.passableWords,
                obstacleWords = scratch.obstacleWords,
                classCounts = scratch.classCounts,
                groundTypeCounts = scratch.groundTypeCounts,
                intOutputs = scratch.intOutputs,
                contentRegion = scratch.contentRegion,
            )
        }
    }

    private fun postprocessPrepared(
        content: SemanticContentRegion,
        nativeCall: (SemanticScratch) -> Boolean,
    ): SemanticPostprocessResult? {
        if (!NativeGuidanceLibrary.isAvailable) return null
        val width = content.width
        val height = content.height
        val pixelCount = content.pixelCount

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

        // The kernel writes the class map straight into a pooled array; the lease hands its
        // ownership to whoever keeps the result.
        val maskLease = maskPool.lease(width, height)
        val scratch = SemanticScratch(
            classMap = maskLease.mask.classMap,
            passableWords = passableWords,
            obstacleWords = obstacleWords,
            classCounts = classCounts,
            groundTypeCounts = groundTypeCounts,
            intOutputs = intOutputs,
            contentRegion = intArrayOf(content.x, content.y, content.width, content.height),
        )
        val nativeSuccess = runCatching {
            nativeCall(scratch)
        }.getOrDefault(false)

        if (!nativeSuccess) {
            // Nobody has seen this mask, and a partial write in it is worthless.
            maskLease.close()
            return null
        }

        if (!hasLoggedBackend) {
            logService.info(TAG, "Semantic score postprocess backend: native")
            hasLoggedBackend = true
        }

        return SemanticPostprocessResult(
            maskLease = maskLease,
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
        /** Receives the argmax class ids of the content region. */
        val classMap: IntArray,
        val passableWords: LongArray,
        val obstacleWords: LongArray,
        val classCounts: IntArray,
        val groundTypeCounts: IntArray,
        val intOutputs: IntArray,
        /** x, y, width, height of the camera-frame region of the score grid. */
        val contentRegion: IntArray,
    )

    // Zero-copy variant for FLOAT32 output: skips readFloat() by reading the model
    // output tensor directly via its native LiteRtTensorBuffer* handle. Falls back
    // to postprocessScores() at the call site if this returns null.
    fun postprocessScoresFromHandle(
        tensorBufferHandle: Long,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
        content: SemanticContentRegion = SemanticContentRegion(0, 0, width, height),
    ): SemanticPostprocessResult? {
        if (tensorBufferHandle == 0L) return null
        if (width <= 0 || height <= 0 || channels <= 0) return null
        if (!content.fitsIn(width, height)) return null
        return postprocessPrepared(
            content = content,
        ) { scratch ->
            nativePostprocessScoresFromHandle(
                 tensorBufferHandle = tensorBufferHandle,
                 resultMask = scratch.classMap,
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
                 centerRatio = widthFractionOfLongSide(config.segmentationCenterRatio, content.width, content.height),
                 navigationRegionRatio = config.segmentationNavigationRegionRatio,
                 passableWords = scratch.passableWords,
                 obstacleWords = scratch.obstacleWords,
                 classCounts = scratch.classCounts,
                 groundTypeCounts = scratch.groundTypeCounts,
                 intOutputs = scratch.intOutputs,
                contentRegion = scratch.contentRegion,
            )
        }
    }

    // Zero-copy variant for INT8 output: same as postprocessScoresFromHandle() but
    // the locked buffer is interpreted as int8_t*, matching full-integer-quant models.
    fun postprocessInt8ScoresFromHandle(
        tensorBufferHandle: Long,
        width: Int,
        height: Int,
        channels: Int,
        scoreLayout: ImageTensorLayout,
        content: SemanticContentRegion = SemanticContentRegion(0, 0, width, height),
    ): SemanticPostprocessResult? {
        if (tensorBufferHandle == 0L) return null
        if (width <= 0 || height <= 0 || channels <= 0) return null
        if (!content.fitsIn(width, height)) return null
        return postprocessPrepared(
            content = content,
        ) { scratch ->
            nativePostprocessInt8ScoresFromHandle(
                tensorBufferHandle = tensorBufferHandle,
                resultMask = scratch.classMap,
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
                centerRatio = widthFractionOfLongSide(config.segmentationCenterRatio, content.width, content.height),
                navigationRegionRatio = config.segmentationNavigationRegionRatio,
                passableWords = scratch.passableWords,
                obstacleWords = scratch.obstacleWords,
                classCounts = scratch.classCounts,
                groundTypeCounts = scratch.groundTypeCounts,
                intOutputs = scratch.intOutputs,
                contentRegion = scratch.contentRegion,
            )
        }
    }

    /**
     * Generic seam (architecture.md 6.5): sailens-vision hands over the scores, this decides what
     * they mean. Every variant routes into the fused native pass above, so the tensor is scanned
     * once and the navigation statistics come out of the same traversal as the argmax.
     *
     * Returning null declines the frame; the runner then computes a plain argmax and calls
     * [fromClassMap].
     *
     * [reusableClassMap] is not used: the kernel writes the class map into a pooled array whose
     * ownership travels with the result, so it never has to be copied out of the runner's buffer.
     */
    override fun postprocessScores(
        scores: SemanticScores,
        spec: SemanticScoreSpec,
        reusableClassMap: IntArray,
    ): SemanticPostprocessOutcome<NavigationSemanticResult>? {
        val result = when (scores) {
            is SemanticScores.FloatHandle -> postprocessScoresFromHandle(
                tensorBufferHandle = scores.tensorBufferHandle,
                width = spec.width,
                height = spec.height,
                channels = spec.channels,
                scoreLayout = spec.layout,
                content = spec.content,
            )
            is SemanticScores.Int8Handle -> postprocessInt8ScoresFromHandle(
                tensorBufferHandle = scores.tensorBufferHandle,
                width = spec.width,
                height = spec.height,
                channels = spec.channels,
                scoreLayout = spec.layout,
                content = spec.content,
            )
            is SemanticScores.FloatValues -> postprocessScores(
                scores = scores.values,
                width = spec.width,
                height = spec.height,
                channels = spec.channels,
                scoreLayout = spec.layout,
                content = spec.content,
            )
            is SemanticScores.Int8Values -> postprocessInt8Scores(
                scores = scores.values,
                width = spec.width,
                height = spec.height,
                channels = spec.channels,
                scoreLayout = spec.layout,
                content = spec.content,
            )
        } ?: return null

        val backend = when (scores) {
            is SemanticScores.Int8Handle, is SemanticScores.Int8Values -> BACKEND_NATIVE_SCORE_INT8
            is SemanticScores.FloatHandle, is SemanticScores.FloatValues -> BACKEND_NATIVE_SCORE
        }
        return SemanticPostprocessOutcome(
            value = NavigationSemanticResult(mask = result.mask, stats = result.stats, maskLease = result.maskLease),
            backend = backend,
        )
    }

    /**
     * Fallback wrap: the runner already did a plain argmax, so there are no navigation statistics
     * for this frame and SegmentationAnalyzer will extract them from the mask in Kotlin instead.
     *
     * The runner reuses [classMap] on the next frame, so it is copied -- into a pooled array rather
     * than a new one.
     */
    override fun fromClassMap(
        classMap: IntArray,
        spec: SemanticScoreSpec,
    ): NavigationSemanticResult {
        val maskLease = maskPool.lease(spec.content.width, spec.content.height)
        classMap.copyInto(maskLease.mask.classMap, endIndex = spec.content.pixelCount)
        return NavigationSemanticResult(mask = maskLease.mask, stats = null, maskLease = maskLease)
    }

    /** Lets go of idle mask arrays once Guidance's models are released. */
    fun releaseBuffers() {
        maskPool.trim()
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
        contentRegion: IntArray,
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
        contentRegion: IntArray,
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
        contentRegion: IntArray,
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
        contentRegion: IntArray,
    ): Boolean

    private fun SemanticContentRegion.fitsIn(gridWidth: Int, gridHeight: Int): Boolean =
        x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= gridWidth && y + height <= gridHeight

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

    companion object {
        // Trace names for the fused paths. Part of the performance contract (architecture.md 12.3).
        const val BACKEND_NATIVE_SCORE: String = "native_score"
        const val BACKEND_NATIVE_SCORE_INT8: String = "native_score_int8"

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
