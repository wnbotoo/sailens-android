package com.sailens.guidance.kernel

import com.sailens.core.log.LogService
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.runtime.ImageTensorLayout
import com.sailens.vision.semantic.SemanticScoreSpec
import com.sailens.vision.semantic.SemanticScores
import com.sailens.vision.taxonomy.TaxonomyId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sailens-vision seam (architecture.md §6.5), exercised on the JVM where the native library
 * is absent.
 *
 * That absence is the point: every fused path must decline rather than throw, because declining is
 * what makes SegmentationRunner fall through to a plain argmax and keep guiding. The device-side
 * behaviour of the native kernels themselves is NavigationScoreKernelTest's job.
 */
class NavigationScorePostprocessorSeamTest {

    private val spec = SemanticScoreSpec(
        width = 2,
        height = 2,
        channels = 2,
        layout = ImageTensorLayout.NHWC,
    )

    private fun postprocessor() = NavigationScorePostprocessor(
        config = AnalysisConfig(),
        navigationSemantics = semantics,
        logService = SilentLogService,
    )

    @Test
    fun `every score source declines when the native library is unavailable`() {
        val subject = postprocessor()
        val mask = IntArray(4)
        val sources = listOf(
            SemanticScores.FloatHandle(1L),
            SemanticScores.Int8Handle(1L),
            SemanticScores.FloatValues(FloatArray(8)),
            SemanticScores.Int8Values(ByteArray(8)),
        )

        sources.forEach { source ->
            assertNull(
                "$source must decline, not throw, when the native kernel cannot run",
                subject.postprocessScores(source, spec, mask),
            )
        }
    }

    @Test
    fun `a zero handle declines without reaching the kernel`() {
        assertNull(
            postprocessor().postprocessScores(SemanticScores.FloatHandle(0L), spec, IntArray(4)),
        )
    }

    @Test
    fun `fromClassMap snapshots the runner's reusable buffer`() {
        val subject = postprocessor()
        val reusable = intArrayOf(1, 0, 1, 1)

        val result = subject.fromClassMap(reusable, spec)
        // The runner reuses this array on the next frame; the result must not alias it.
        reusable[0] = 9

        assertEquals(2, result.mask.width)
        assertEquals(2, result.mask.height)
        assertArrayEquals(intArrayOf(1, 0, 1, 1), result.mask.classMap)
    }

    @Test
    fun `fromClassMap reports no stats so the analyzer recomputes them in Kotlin`() {
        assertNull(postprocessor().fromClassMap(IntArray(4), spec).stats)
    }

    private companion object {
        val semantics = object : NavigationSemantics {
            override val taxonomyId: TaxonomyId = TaxonomyId("test")
            override val classCount: Int = 2
            override fun isPassable(classId: Int): Boolean = classId == 0
            override fun isObstacle(classId: Int): Boolean = classId == 1
            override fun isRoad(classId: Int): Boolean = classId == 0
            override fun isTrafficLight(classId: Int): Boolean = false
            override fun toGroundType(classId: Int): GroundType =
                if (classId == 0) GroundType.ROAD else GroundType.UNKNOWN
            override fun toObstacleCategory(classId: Int): ObstacleCategory =
                if (classId == 1) ObstacleCategory.STATIC_OBSTACLE else ObstacleCategory.UNKNOWN
        }

        val SilentLogService = object : LogService {
            override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
            override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
            override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
            override fun error(tag: String, message: String, throwable: Throwable?) = Unit
        }
    }
}
