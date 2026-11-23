package com.sailens.guidance.kernel

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.nativeValue
import com.sailens.vision.semantic.SemanticContentRegion
import com.sailens.vision.semantic.SemanticScoreSpec
import com.sailens.vision.semantic.SemanticScores
import com.sailens.vision.semantic.SemanticScoreTiming
import com.sailens.vision.semantic.cropClassMap
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationMaskPool
import com.sailens.guidance.processor.perception.KotlinSegmentationStatsExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Fused semantic score kernel verification (docs/architecture.md §12.2).
 *
 * The fused score kernel does have a production Kotlin twin: [KotlinSegmentationStatsExtractor]
 * is the fallback the pipeline uses when `SegmentationOutput.analysisStats` is null, and it
 * computes the same [com.sailens.guidance.model.perception.SegmentationAnalysisStats] from the
 * argmax mask. So this compares the one-pass native kernel against argmax-then-Kotlin-stats,
 * field by field, which is the differential test §12.2 asks for.
 *
 * The handle tests get real output buffers by compiling synthetic reshape graphs.
 *
 * Needs a physical arm64 device: `./gradlew.bat :sailens-guidance:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NavigationScoreKernelTest {

    private val navigationSemantics = CityscapesNavigationSemantics
    private val analysisConfig = AnalysisConfig()

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "libsailens_guidance.so did not load: ${NativeGuidanceLibrary.loadError}",
            NativeGuidanceLibrary.isAvailable,
        )
    }

    @Test
    fun fusedScoreKernelMatchesArgmaxPlusKotlinStats() {
        val scores = syntheticScores(ImageTensorLayout.NHWC)
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = scores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )
        assertNotNull("native score postprocess returned null", result)
        checkNotNull(result)

        val expectedClassMap = referenceArgmax(scores, ImageTensorLayout.NHWC)
        assertArrayEquals("argmax mask", expectedClassMap, result.mask.classMap)

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics)
            .extract(SegmentationMask(WIDTH, HEIGHT, expectedClassMap))

        // SegmentationAnalysisStats compares masks, ratios, bottom stats and class counts, so one
        // assertion covers every field the fused kernel produces.
        assertEquals(expectedStats, result.stats)
    }

    @Test
    fun fusedScoreKernelOverAContentRegionMatchesTheCroppedArgmaxPlusKotlinStats() {
        // A letterboxed grid: the camera frame occupies the middle columns only. The fused kernel
        // must report exactly what argmax-then-Kotlin-stats reports for the cropped class map --
        // the padding must not reach a single statistic.
        val scores = syntheticScores(ImageTensorLayout.NHWC)
        val content = SemanticContentRegion(x = 11, y = 0, width = WIDTH - 22, height = HEIGHT)
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = scores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
            content = content,
        )
        checkNotNull(result) { "native score postprocess returned null" }

        val cropped = IntArray(content.pixelCount)
        cropClassMap(referenceArgmax(scores, ImageTensorLayout.NHWC), WIDTH, content, cropped)
        assertArrayEquals("cropped argmax mask", cropped, result.mask.classMap)
        assertEquals(content.width, result.mask.width)

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics)
            .extract(SegmentationMask(content.width, content.height, cropped))
        assertEquals(expectedStats, result.stats)
    }

    @Test
    fun fusedInt8ScoreKernelMatchesTheFloatKernel() {
        // The int8 kernel takes argmax over the raw quantised values: dequantisation is monotonic,
        // so the same ordering must produce the same mask and the same statistics.
        val int8Scores = ByteArray(WIDTH * HEIGHT * CLASS_COUNT)
        val floatScores = FloatArray(int8Scores.size)
        val random = Random(SEED)
        for (index in int8Scores.indices) {
            val value = random.nextInt(-128, 128)
            int8Scores[index] = value.toByte()
            floatScores[index] = value.toFloat()
        }

        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val int8Result = postprocessor.postprocessInt8Scores(
            scores = int8Scores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )
        val floatResult = postprocessor.postprocessScores(
            scores = floatScores,
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )

        assertNotNull("native int8 score postprocess returned null", int8Result)
        assertNotNull("native float score postprocess returned null", floatResult)
        checkNotNull(int8Result)
        checkNotNull(floatResult)

        assertArrayEquals(floatResult.mask.classMap, int8Result.mask.classMap)
        assertEquals(floatResult.stats, int8Result.stats)

        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics)
            .extract(SegmentationMask(WIDTH, HEIGHT, int8Result.mask.classMap))
        assertEquals(expectedStats, int8Result.stats)
    }

    @Test
    fun nchwFloatArrayMatchesTheExpectedCroppedMaskAndStats() {
        val scores = croppedScoreFixture(ImageTensorLayout.NCHW)
        val floats = FloatArray(scores.size) { scores[it] * 0.5f }
        assertCroppedScores(SemanticScores.FloatValues(floats), ImageTensorLayout.NCHW, "native_score")
    }

    @Test
    fun nchwSignedInt8ArrayMatchesTheExpectedCroppedMaskAndStats() {
        val scores = croppedScoreFixture(ImageTensorLayout.NCHW)
        assertCroppedScores(SemanticScores.Int8Values(scores), ImageTensorLayout.NCHW, "native_score_int8")
    }

    @Test
    fun signedInt8LiteRtOutputHandleMatchesTheExpectedCropInBothLayouts() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val fixture = File.createTempFile("semantic-int8-", ".tflite", cache)
        try {
            fixture.writeBytes(Base64.decode(INT8_RESHAPE_GRAPH, Base64.DEFAULT))
            CompiledModel.create(fixture.absolutePath, CompiledModel.Options(Accelerator.CPU)).use { model ->
                model.createInputBuffers().single().use { input ->
                    model.createOutputBuffers().single().use { output ->
                        for (layout in ImageTensorLayout.entries) {
                            input.writeInt8(croppedScoreFixture(layout))
                            model.run(listOf(input), listOf(output))
                            assertCroppedScores(SemanticScores.Int8Handle(handleOf(output)), layout, "native_score_int8")
                            val sampled = SemanticScoreTiming(sampleOffset = 0)
                            assertCroppedScores(
                                SemanticScores.Int8Handle(handleOf(output), LongArray(2), sampled),
                                layout,
                                "native_score_int8",
                            )
                            assertTrue(checkNotNull(sampled.fusedKernelNanos) > 0)
                            assertEquals(1L, sampled.sampledPixels)
                        }
                    }
                }
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun neonFloatHandleMatchesScalarOverACroppedTensorIncludingTiesAndTail() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val fixture = File.createTempFile("semantic-float-", ".tflite", cache)
        val wide = SemanticContentRegion(x = 3, y = 2, width = 33, height = HEIGHT - 4)
        val narrow = wide.copy(width = 3)
        data class ScoreOverride(val x: Int, val channel: Int, val value: Float)
        val cases = listOf(
            null,
            ScoreOverride(wide.x + 2, 0, Float.NaN),
            ScoreOverride(wide.x + wide.width - 1, 8, Float.POSITIVE_INFINITY),
            ScoreOverride(wide.x + 2, 8, Float.NEGATIVE_INFINITY),
            ScoreOverride(wide.x + 2, 8, Float.NaN),
            ScoreOverride(wide.x - 1, 8, Float.NaN),
            ScoreOverride(wide.x + wide.width, 8, Float.POSITIVE_INFINITY),
        )
        try {
            fixture.writeBytes(Base64.decode(FLOAT_RESHAPE_GRAPH, Base64.DEFAULT))
            CompiledModel.create(fixture.absolutePath, CompiledModel.Options(Accelerator.CPU)).use { model ->
                model.createInputBuffers().single().use { input ->
                    model.createOutputBuffers().single().use { output ->
                        for (layout in ImageTensorLayout.entries) {
                            for (nonFinite in cases) {
                                val scores = syntheticScores(layout)
                                setTiedScores(scores, layout, wide)
                                if (nonFinite != null) {
                                    val pixel = wide.y * WIDTH + nonFinite.x
                                    scores[scoreIndex(pixel, nonFinite.channel, WIDTH * HEIGHT, layout)] = nonFinite.value
                                }
                                input.writeFloat(scores)
                                model.run(listOf(input), listOf(output))
                                val expected = referenceArgmax(scores, layout)
                                val handle = handleOf(output)
                                if (layout == ImageTensorLayout.NCHW && nonFinite == null) {
                                    assertUntimedFloatKernelSelection(handle, wide)
                                }
                                for (content in listOf(wide, narrow)) {
                                    val containsNonFinite = nonFinite != null &&
                                        nonFinite.x in content.x until content.x + content.width
                                    assertFloatHandleKernel(
                                        handle, layout, content, expected.takeUnless { containsNonFinite },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun pairedNeonMatchesFourColumnsBitForBitAtLiveCropAndRowTails() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val fixture = File.createTempFile("semantic-paired-", ".tflite", cache)
        val size = 640
        val layout = ImageTensorLayout.NCHW
        val content = SemanticContentRegion.forLetterbox(1280, 720, 90, size, size, size, size)
        assertEquals(SemanticContentRegion(140, 0, 360, 640), content)
        val spec = SemanticScoreSpec(size, size, CLASS_COUNT, layout, content)
        val scores = syntheticScores(layout, width = size, height = size)
        val planeSize = size * size
        val tiedColumns = listOf(0, 4, 7, content.width - 1)
        for (column in tiedColumns) {
            val pixel = content.x + column
            for (channel in 0 until CLASS_COUNT) {
                scores[channel * planeSize + pixel] = if (channel == 1 || channel == 18) 8f else 0f
            }
        }

        try {
            fixture.writeBytes(Base64.decode(FLOAT_LIVE_SHAPE_RESHAPE_GRAPH, Base64.DEFAULT))
            CompiledModel.create(fixture.absolutePath, CompiledModel.Options(Accelerator.CPU)).use { model ->
                model.createInputBuffers().single().use { input ->
                    model.createOutputBuffers().single().use { output ->
                        fun loadScores() {
                            input.writeFloat(scores)
                            model.run(listOf(input), listOf(output))
                        }
                        fun compare(
                            crop: SemanticContentRegion,
                            fourColumnKernel: Int,
                            pairedKernel: Int,
                            tensorSpec: SemanticScoreSpec = spec,
                        ): IntArray {
                            val croppedSpec = tensorSpec.copy(content = crop)
                            val baseline = scoreUntimedHandle(handleOf(output), croppedSpec, true, false)
                            val candidate = scoreUntimedHandle(handleOf(output), croppedSpec, true, true)
                            assertEquals("four columns, $crop", fourColumnKernel, baseline.kernel)
                            assertEquals("paired columns, $crop", pairedKernel, candidate.kernel)
                            assertArrayEquals(baseline.classMap, candidate.classMap)
                            assertArrayEquals(baseline.passableWords, candidate.passableWords)
                            assertArrayEquals(baseline.obstacleWords, candidate.obstacleWords)
                            assertArrayEquals(baseline.classCounts, candidate.classCounts)
                            assertArrayEquals(baseline.groundTypeCounts, candidate.groundTypeCounts)
                            assertArrayEquals(baseline.statistics, candidate.statistics)
                            return candidate.classMap
                        }

                        loadScores()
                        val classMap = compare(content, 1, 2)
                        for (column in tiedColumns) assertEquals(1, classMap[column])
                        for (width in 3..15) {
                            val four = if (width >= 4) 1 else 0
                            compare(content.copy(width = width, height = 3), four, if (width >= 8) 2 else four)
                        }
                        // This 10-channel view has the same packed size as the reshape output.
                        compare(content.copy(height = 3), 1, 1, spec.copy(height = 1216, channels = 10))

                        val tail = content.copy(width = 13, height = 3)
                        data class Override(
                            val crop: SemanticContentRegion,
                            val column: Int,
                            val row: Int,
                            val value: Float,
                            val inside: Boolean,
                        )
                        for (invalid in listOf(
                            Override(content, 5, 0, Float.NaN, inside = true),
                            Override(content, 358, 319, Float.NEGATIVE_INFINITY, inside = true),
                            Override(tail, 12, 1, Float.POSITIVE_INFINITY, inside = true),
                            Override(content, -1, 0, Float.NaN, inside = false),
                            Override(tail, 13, 1, Float.POSITIVE_INFINITY, inside = false),
                        )) {
                            val index = 18 * planeSize + invalid.row * size + content.x + invalid.column
                            val original = scores[index]
                            scores[index] = invalid.value
                            loadScores()
                            compare(invalid.crop, if (invalid.inside) 0 else 1, if (invalid.inside) 0 else 2)
                            scores[index] = original
                        }
                    }
                }
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun fusedScoreKernelRejectsAMismatchedScoreCount() {
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
        )

        val result = postprocessor.postprocessScores(
            scores = FloatArray(WIDTH * HEIGHT * CLASS_COUNT - 1),
            width = WIDTH,
            height = HEIGHT,
            channels = CLASS_COUNT,
            scoreLayout = ImageTensorLayout.NHWC,
        )

        assertEquals(null, result)
    }

    @Test
    fun pooledMasksStayIntactUntilTheirLeaseIsClosed() {
        // The ownership pattern ProcessFrameUseCase follows: the previous frame's mask is still
        // held (the cached analysis) while the next run writes, and is given back only after. Each
        // frame's scores differ, so a run that wrote into a held mask would show up in it.
        val pool = SegmentationMaskPool()
        val postprocessor = NavigationScorePostprocessor(
            config = analysisConfig,
            navigationSemantics = navigationSemantics,
            logService = SilentLogService,
            maskPool = pool,
        )
        var held: SemanticPostprocessResult? = null
        var heldExpected: IntArray? = null

        repeat(6) { frame ->
            val scores = syntheticScores(ImageTensorLayout.NHWC, seed = SEED + frame)
            val expected = referenceArgmax(scores, ImageTensorLayout.NHWC)

            val result = checkNotNull(
                postprocessor.postprocessScores(
                    scores = scores,
                    width = WIDTH,
                    height = HEIGHT,
                    channels = CLASS_COUNT,
                    scoreLayout = ImageTensorLayout.NHWC,
                ),
            ) { "native score postprocess returned null on frame $frame" }

            assertArrayEquals("frame $frame mask", expected, result.mask.classMap)
            held?.let { previous ->
                assertArrayEquals("frame ${frame - 1} mask while still held", heldExpected, previous.mask.classMap)
                previous.maskLease.close()
            }
            held = result
            heldExpected = expected
        }

        assertEquals("two masks alternate: the held one and the one being written", 2L, pool.arraysAllocated)
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun setTiedScores(
        scores: FloatArray,
        layout: ImageTensorLayout,
        content: SemanticContentRegion,
    ) {
        fun setPixel(x: Int, score: (Int) -> Float) {
            val pixel = content.y * WIDTH + x
            for (channel in 0 until CLASS_COUNT) {
                scores[scoreIndex(pixel, channel, WIDTH * HEIGHT, layout)] = score(channel)
            }
        }
        // A tie between two non-zero classes in the first vector and at the end of the row.
        for (x in listOf(content.x, content.x + content.width - 1)) {
            setPixel(x) { if (it == 1 || it == 8) 2f else -1f }
        }
        setPixel(content.x + 1) { if (it == 0) -0f else 0f }
    }

    private fun assertFloatHandleKernel(
        handle: Long,
        layout: ImageTensorLayout,
        content: SemanticContentRegion,
        expectedClassMap: IntArray?,
    ) {
        val spec = SemanticScoreSpec(WIDTH, HEIGHT, CLASS_COUNT, layout, content)
        fun run(
            requestNeon: Boolean,
            measure: Boolean,
            measureThreadCpu: Boolean = false,
            requestPairedNeon: Boolean = false,
        ): NavigationSemanticResult {
            val postprocessor = NavigationScorePostprocessor(
                analysisConfig, navigationSemantics, SilentLogService,
                useNeonArgmax = requestNeon,
                usePairedNeonArgmax = requestPairedNeon,
            )
            val timing = if (measure) SemanticScoreTiming(sampleOffset = 0, measureThreadCpu = measureThreadCpu) else null
            val outcome = checkNotNull(postprocessor.postprocessScores(
                SemanticScores.FloatHandle(handle, timing?.let { LongArray(2) }, timing),
                spec,
                IntArray(content.pixelCount),
            ))
            assertEquals("native_score", outcome.backend)
            if (timing != null) {
                val neon = requestNeon && expectedClassMap != null &&
                    layout == ImageTensorLayout.NCHW && content.width >= 4
                val expectedImplementation = when {
                    !neon -> "scalar"
                    requestPairedNeon && content.width >= 8 -> "neon_paired"
                    else -> "neon"
                }
                assertEquals(expectedImplementation, timing.argmaxImplementation)
                if (neon) assertEquals(0L, timing.sampledPixels)
                else assertTrue(timing.sampledPixels > 0L)
                if (neon && measureThreadCpu) {
                    assertTrue(checkNotNull(timing.fusedKernelThreadCpuNanos) > 0)
                    assertTrue(checkNotNull(timing.fusedKernelNanos) > 0)
                } else {
                    assertNull(timing.fusedKernelThreadCpuNanos)
                }
            }
            return outcome.value
        }

        val baseline = run(requestNeon = false, measure = false)
        baseline.maskLease.use {
            // For non-finite scores, preserve the result of the existing fast-math scalar kernel.
            if (expectedClassMap != null) {
                val cropped = IntArray(content.pixelCount)
                cropClassMap(expectedClassMap, WIDTH, content, cropped)
                val expectedMask = SegmentationMask(content.width, content.height, cropped)
                val expectedStats =
                    KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics).extract(expectedMask)
                assertArrayEquals(cropped, baseline.mask.classMap)
                assertEquals(expectedStats, baseline.stats)
            }
            fun assertMatchesScalar(candidate: NavigationSemanticResult) {
                candidate.maskLease.use {
                    assertArrayEquals(baseline.mask.classMap, candidate.mask.classMap)
                    assertEquals(baseline.stats, candidate.stats)
                }
            }

            for ((requestNeon, measure, measureThreadCpu) in listOf(
                Triple(false, true, false),
                Triple(true, false, false),
                Triple(true, true, false),
                Triple(true, true, true),
            )) {
                assertMatchesScalar(run(requestNeon, measure, measureThreadCpu))
            }
            assertMatchesScalar(run(requestNeon = false, measure = true, requestPairedNeon = true))
            assertMatchesScalar(run(requestNeon = true, measure = true, measureThreadCpu = true, requestPairedNeon = true))
        }
    }

    private fun assertUntimedFloatKernelSelection(handle: Long, content: SemanticContentRegion) {
        val spec = SemanticScoreSpec(WIDTH, HEIGHT, CLASS_COUNT, ImageTensorLayout.NCHW, content)
        for ((requestNeon, expected) in listOf(false to 0, true to 1)) {
            assertEquals(expected, scoreUntimedHandle(handle, spec, requestNeon, paired = false).kernel)
        }
    }

    private data class NativeScoreOutputs(
        val kernel: Int,
        val classMap: IntArray,
        val passableWords: LongArray,
        val obstacleWords: LongArray,
        val classCounts: IntArray,
        val groundTypeCounts: IntArray,
        val statistics: IntArray,
    )

    private fun scoreUntimedHandle(
        handle: Long,
        spec: SemanticScoreSpec,
        requestNeon: Boolean,
        paired: Boolean,
    ): NativeScoreOutputs {
        val postprocessor = NavigationScorePostprocessor(analysisConfig, navigationSemantics, SilentLogService)
        val native = NavigationScorePostprocessor::class.java.declaredMethods
            .single { it.name == "nativePostprocessScoresFromHandle" }
            .apply { isAccessible = true }
        val content = spec.content
        val words = (content.pixelCount + 63) / 64
        val initial = if (paired) -1 else -2
        val classMap = IntArray(content.pixelCount) { initial }
        val passableWords = LongArray(words) { initial.toLong() }
        val obstacleWords = LongArray(words) { initial.toLong() }
        val classCounts = IntArray(CLASS_COUNT) { initial }
        val groundTypeCounts = IntArray(GroundType.entries.size) { initial }
        val statistics = IntArray(13) { initial }
        val groundTypes = IntArray(CLASS_COUNT) { index ->
            navigationSemantics.toGroundType(index).takeIf { it != GroundType.UNKNOWN }?.ordinal ?: -1
        }
        // The return identifies the completed kernel while both timing arrays are null.
        val kernel = native.invoke(
            postprocessor,
            handle, classMap,
            spec.width, spec.height, spec.channels, spec.layout.nativeValue,
            BooleanArray(CLASS_COUNT, navigationSemantics::isPassable),
            BooleanArray(CLASS_COUNT, navigationSemantics::isObstacle),
            BooleanArray(CLASS_COUNT, navigationSemantics::isRoad),
            BooleanArray(CLASS_COUNT, navigationSemantics::isTrafficLight),
            groundTypes,
            0.3f, 0.3f, 0.3f,
            passableWords, obstacleWords, classCounts, groundTypeCounts, statistics,
            intArrayOf(content.x, content.y, content.width, content.height),
            requestNeon, paired, -1, null, null,
        ) as Int
        return NativeScoreOutputs(kernel, classMap, passableWords, obstacleWords, classCounts, groundTypeCounts, statistics)
    }

    private fun croppedScoreFixture(layout: ImageTensorLayout): ByteArray {
        val pixelCount = CROP_GRID_WIDTH * CROP_GRID_HEIGHT
        val scores = ByteArray(pixelCount * CLASS_COUNT) { Byte.MIN_VALUE }
        fun set(x: Int, y: Int, channel: Int, score: Byte) {
            val pixel = y * CROP_GRID_WIDTH + x
            scores[scoreIndex(pixel, channel, pixelCount, layout)] = score
        }

        set(0, 1, CityscapesTaxonomy.CAR, Byte.MAX_VALUE)
        set(1, 1, CityscapesTaxonomy.SIDEWALK, -127)
        set(1, 1, CityscapesTaxonomy.TERRAIN, -127)
        set(2, 1, CityscapesTaxonomy.CAR, Byte.MAX_VALUE)
        // Every channel at (3, 1) has Byte.MIN_VALUE. The first class wins the tie.
        set(1, 2, CityscapesTaxonomy.PERSON, 0)
        set(1, 2, CityscapesTaxonomy.SKY, -1)
        set(2, 2, CityscapesTaxonomy.TRAFFIC_LIGHT, Byte.MAX_VALUE)
        set(2, 2, CityscapesTaxonomy.PERSON, Byte.MAX_VALUE)
        set(3, 2, CityscapesTaxonomy.TERRAIN, -63)
        return scores
    }

    private fun assertCroppedScores(scores: SemanticScores, layout: ImageTensorLayout, backend: String) {
        val content = SemanticContentRegion(x = 1, y = 1, width = 3, height = 2)
        val spec = SemanticScoreSpec(CROP_GRID_WIDTH, CROP_GRID_HEIGHT, CLASS_COUNT, layout, content)
        val expectedClassMap = intArrayOf(
            CityscapesTaxonomy.SIDEWALK, CityscapesTaxonomy.CAR, CityscapesTaxonomy.ROAD,
            CityscapesTaxonomy.PERSON, CityscapesTaxonomy.TRAFFIC_LIGHT, CityscapesTaxonomy.TERRAIN,
        )
        val expectedMask = SegmentationMask(content.width, content.height, expectedClassMap)
        val expectedStats = KotlinSegmentationStatsExtractor(analysisConfig, navigationSemantics).extract(expectedMask)
        val postprocessor = NavigationScorePostprocessor(analysisConfig, navigationSemantics, SilentLogService)
        val result = checkNotNull(postprocessor.postprocessScores(scores, spec, IntArray(content.pixelCount))) {
            "Native kernel declined ${scores.javaClass.simpleName} in $layout"
        }
        result.value.maskLease.use {
            assertEquals(backend, result.backend)
            assertEquals(expectedMask, result.value.mask)
            assertEquals(expectedStats, result.value.stats)
        }
    }

    private fun handleOf(buffer: TensorBuffer): Long {
        val getter = Class.forName("com.google.ai.edge.litert.JniHandle")
            .getDeclaredMethod("getHandle\$third_party_odml_litert_litert_kotlin_litert_api_for_maven")
        return getter.invoke(buffer) as Long
    }

    private fun tensorConfig(layout: ImageTensorLayout) = ModelTensorConfig(
        inputWidth = WIDTH,
        inputHeight = HEIGHT,
        outputWidth = WIDTH,
        outputHeight = HEIGHT,
        outputChannels = CLASS_COUNT,
        outputLayout = layout,
        mean = Triple(0f, 0f, 0f),
        std = Triple(1f, 1f, 1f),
    )

    /**
     * Scores with a deliberate structure rather than noise: the bottom rows favour the passable
     * classes and the top rows favour sky and buildings, so the bottom/centre/navigation regions
     * of the statistics are actually populated. A fixed seed keeps it reproducible, and the
     * deterministic tilt keeps ties out of the argmax.
     */
    private fun syntheticScores(
        layout: ImageTensorLayout,
        seed: Int = SEED,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): FloatArray {
        val random = Random(seed)
        val scores = FloatArray(width * height * CLASS_COUNT)
        val pixelCount = width * height

        for (y in 0 until height) {
            val nearGround = y.toFloat() / height
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                for (channel in 0 until CLASS_COUNT) {
                    val bias = when (channel) {
                        CityscapesTaxonomy.ROAD -> 3f * nearGround
                        CityscapesTaxonomy.SIDEWALK -> 2f * nearGround
                        CityscapesTaxonomy.TERRAIN -> 1.5f * nearGround
                        CityscapesTaxonomy.SKY -> 3f * (1f - nearGround)
                        CityscapesTaxonomy.BUILDING -> 2f * (1f - nearGround)
                        CityscapesTaxonomy.PERSON -> if (x % 11 == 0) 2.5f else 0f
                        CityscapesTaxonomy.CAR -> if (x % 17 == 0) 2.5f else 0f
                        CityscapesTaxonomy.TRAFFIC_LIGHT -> if (pixelIndex % 97 == 0) 4f else 0f
                        else -> 0f
                    }
                    // nextFloat() is in [0, 1); the biases are far enough apart that no two
                    // channels land on the same float and make the argmax tie-break observable.
                    val value = random.nextFloat() + bias
                    scores[scoreIndex(pixelIndex, channel, pixelCount, layout)] = value
                }
            }
        }
        return scores
    }

    private fun scoreIndex(
        pixelIndex: Int,
        channel: Int,
        pixelCount: Int,
        layout: ImageTensorLayout,
    ): Int = when (layout) {
        ImageTensorLayout.NHWC -> pixelIndex * CLASS_COUNT + channel
        ImageTensorLayout.NCHW -> channel * pixelCount + pixelIndex
    }

    /** First-maximum-wins argmax, matching the kernel's strict `value > bestScore`. */
    private fun referenceArgmax(scores: FloatArray, layout: ImageTensorLayout): IntArray {
        val pixelCount = WIDTH * HEIGHT
        val mask = IntArray(pixelCount)
        for (pixelIndex in 0 until pixelCount) {
            var bestClass = 0
            var bestScore = scores[scoreIndex(pixelIndex, 0, pixelCount, layout)]
            for (channel in 1 until CLASS_COUNT) {
                val value = scores[scoreIndex(pixelIndex, channel, pixelCount, layout)]
                if (value > bestScore) {
                    bestScore = value
                    bestClass = channel
                }
            }
            mask[pixelIndex] = bestClass
        }
        return mask
    }

    private companion object {
        const val WIDTH = 40
        const val HEIGHT = 32
        const val CLASS_COUNT = 19
        const val SEED = 20260920
        const val CROP_GRID_WIDTH = 5
        const val CROP_GRID_HEIGHT = 4

        // TFLite v3 FLOAT32 RESHAPE from [1,19,640,640] to [7782400] with no weights.
        const val FLOAT_LIVE_SHAPE_RESHAPE_GRAPH =
            "GAAAAFRGTDMAAA4AFAAQAAwACAAAAAQADgAAABAAAAA8AAAAGAAAAAMAAAABAAAACAAAAAQABAAE" +
                "AAAAAQAAABAAAAAMAAwABwAAAAAACAAMAAAAAAAAFhYAAAABAAAAEAAAAAwAFAAQAAwACAAEAAwA" +
                "AAAQAAAASAAAAEwAAABQAAAAAQAAABQAAAAAAA4AFAAAABAADAALAAQADgAAABAAAAAAAAARGAAA" +
                "ABwAAADC////BAAAAAEAAAAAwHYAAQAAAAEAAAABAAAAAAAAAAIAAAAgAAAABAAAAO7///8EAAAA" +
                "AQAAAADAdgAAAAYACAAEAAYAAAAEAAAABAAAAAEAAAATAAAAgAIAAIACAAA="

        // TFLite v3, one FLOAT32 RESHAPE from [1,19,32,40] to [24320], with no weights.
        const val FLOAT_RESHAPE_GRAPH =
            "GAAAAFRGTDMAAA4AFAAQAAwACAAAAAQADgAAABAAAAA8AAAAGAAAAAMAAAABAAAACAAAAAQABAAE" +
                "AAAAAQAAABAAAAAMAAwABwAAAAAACAAMAAAAAAAAFhYAAAABAAAAEAAAAAwAFAAQAAwACAAEAAwA" +
                "AAAQAAAASAAAAEwAAABQAAAAAQAAABQAAAAAAA4AFAAAABAADAALAAQADgAAABAAAAAAAAARGAAA" +
                "ABwAAADC////BAAAAAEAAAAAXwAAAQAAAAEAAAABAAAAAAAAAAIAAAAgAAAABAAAAO7///8EAAAA" +
                "AQAAAABfAAAAAAYACAAEAAYAAAAEAAAABAAAAAEAAAATAAAAIAAAACgAAAA="

        // TFLite v3, one RESHAPE from INT8 [1,19,4,5] to [380]. Both tensors use scale 1
        // and zero point 0. The graph has no weights and preserves every packed byte.
        const val INT8_RESHAPE_GRAPH =
            "HAAAAFRGTDMAAAAAAAAOABQAEAAMAAgAAAAEAA4AAAAQAAAAFAAAABgAAAADAAAAAQAAABgAAAABAAAA" +
                "OAAAAAEAAAAYAAAABAAEAAQAAAAMAAwACwAAAAAABAAMAAAAFgAAAAAAABYMABQAEAAMAAgABAAMAAAA" +
                "HAAAACAAAAAkAAAABAAAAAIAAACUAAAAaAAAAAEAAAAkAAAAAQAAAAEAAAABAAAAAAAAAAAADgAUAAAA" +
                "EAAMAAsABAAOAAAAKAAAAAAAABEIAAAADAAAAAEAAAABAAAAAQAAAAAAAAAAAAYACAAEAAYAAAAEAAAA" +
                "AQAAAHwBAADm////VAAAAAAAAAkEAAAAAQAAAHwBAAAAAA4AEAAMAAsAAAAAAAQADgAAACwAAAAAAAAJ" +
                "BAAAAAQAAAABAAAAEwAAAAQAAAAFAAAADAAMAAAAAAAIAAQADAAAAAgAAAAQAAAAAQAAAAAAAAAAAAAA" +
                "AQAAAAAAgD8="
    }
}
