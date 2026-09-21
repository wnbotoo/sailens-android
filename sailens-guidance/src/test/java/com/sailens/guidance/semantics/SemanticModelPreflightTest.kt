package com.sailens.guidance.semantics

import android.content.ContextWrapper
import com.sailens.runtime.ModelSource
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.TfliteTensorMetadata
import com.sailens.vision.taxonomy.Taxonomy
import com.sailens.vision.taxonomy.TaxonomyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The static model check that "does the asset exist" never made.
 *
 * A 21-class model dropped into a 19-class build passes an existence test. It then either fails at
 * session start — after the person has pressed start and begun walking — or, worse, runs and reads
 * the scene through shifted class ids, confidently describing a road as a wall to someone who
 * cannot check.
 *
 * What these tests do **not** claim: that a pass means the semantics are right. Channel order is
 * not verifiable without labels and stays a manual release gate (§6.2).
 */
class SemanticModelPreflightTest {

    @Test
    fun `an NHWC output with the declared class count passes`() {
        val result = SemanticModelPreflight.checkOutputTensor(
            metadata = metadata(shape = listOf(1, 160, 160, 19)),
            taxonomy = taxonomy(classCount = 19),
        )

        assertEquals(SemanticModelPreflight.Result.Compatible, result)
    }

    @Test
    fun `an NCHW output with the declared class count passes`() {
        val result = SemanticModelPreflight.checkOutputTensor(
            metadata = metadata(shape = listOf(1, 19, 160, 160)),
            taxonomy = taxonomy(classCount = 19),
        )

        assertEquals(SemanticModelPreflight.Result.Compatible, result)
    }

    @Test
    fun `a model with more classes than the taxonomy declares is rejected`() {
        val result = SemanticModelPreflight.checkOutputTensor(
            metadata = metadata(shape = listOf(1, 160, 160, 21)),
            taxonomy = taxonomy(classCount = 19),
        )

        val mismatch = result as SemanticModelPreflight.Result.ClassCountMismatch
        assertEquals(19, mismatch.declared)
        assertTrue(
            "the report must say what the model actually offered: ${mismatch.candidates}",
            mismatch.candidates.contains(21),
        )
    }

    @Test
    fun `a model with fewer classes than the taxonomy declares is rejected`() {
        val result = SemanticModelPreflight.checkOutputTensor(
            metadata = metadata(shape = listOf(1, 160, 160, 12)),
            taxonomy = taxonomy(classCount = 19),
        )

        assertTrue(result is SemanticModelPreflight.Result.ClassCountMismatch)
    }

    @Test
    fun `an output that is not a 4-D image tensor is reported as unreadable, not as a class mismatch`() {
        val result = SemanticModelPreflight.checkOutputTensor(
            metadata = metadata(shape = listOf(1, 25200, 85)),
            taxonomy = taxonomy(classCount = 19),
        )

        assertTrue(
            "a detection-shaped output is a different problem than a miscounted one: $result",
            result is SemanticModelPreflight.Result.OutputUnreadable,
        )
    }

    @Test
    fun `a model with several outputs is reported as unreadable`() {
        val metadata = TfliteModelMetadata(
            inputs = emptyList(),
            outputs = listOf(
                tensor(name = "scores", shape = listOf(1, 160, 160, 19)),
                tensor(name = "proto", shape = listOf(1, 160, 160, 32)),
            ),
            signatures = emptyList(),
        )

        val result = SemanticModelPreflight.checkOutputTensor(metadata, taxonomy(classCount = 19))

        assertTrue(result is SemanticModelPreflight.Result.OutputUnreadable)
    }

    @Test
    fun `a model with no outputs at all is reported as unreadable`() {
        val metadata = TfliteModelMetadata(emptyList(), emptyList(), emptyList())

        assertTrue(
            SemanticModelPreflight.checkOutputTensor(metadata, taxonomy(classCount = 19))
                is SemanticModelPreflight.Result.OutputUnreadable,
        )
    }

    @Test
    fun `semantics written for another taxonomy are caught before the model is opened`() {
        // NavigationSemanticsBinding is the half that needs no file; check() runs it first so a
        // mismatched build fails without touching storage.
        val result = NavigationSemanticsBinding.validate(
            taxonomy = taxonomy(id = "cityscapes", classCount = 19),
            semantics = semantics(id = "coco", classCount = 19),
        )

        assertTrue(result is NavigationSemanticsBinding.Result.TaxonomyMismatch)
    }

    /**
     * Preflight against a real file, on the JVM.
     *
     * That it runs at all is the point: a unit test has no `libLiteRt.so`, so a preflight that
     * reached for a compiled model or an accelerator would fail with UnsatisfiedLinkError rather
     * than return a verdict. Passing here is evidence for the §5.2 rule that preflight initialises
     * nothing.
     */
    @Test
    fun `a missing file is reported as a missing model source`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "sailens-no-such-model.tflite")
        missing.delete()

        val result = SemanticModelPreflight.check(
            context = ContextWrapper(null),
            source = ModelSource.File(missing),
            taxonomy = taxonomy(classCount = 19, id = "test"),
            semantics = semantics(id = "test", classCount = 19),
        )

        assertEquals(SemanticModelPreflight.Result.ModelSourceMissing, result)
    }

    @Test
    fun `a file that is not a TFLite model is reported, never thrown`() {
        // Preflight runs inside composition. An exception here is a crash on a mispackaged build,
        // which is the failure mode §5.2 exists to replace with something the user can hear.
        val garbage = File.createTempFile("sailens-garbage", ".tflite")
        garbage.deleteOnExit()
        garbage.writeBytes(ByteArray(64) { it.toByte() })

        val result = SemanticModelPreflight.check(
            context = ContextWrapper(null),
            source = ModelSource.File(garbage),
            taxonomy = taxonomy(classCount = 19, id = "test"),
            semantics = semantics(id = "test", classCount = 19),
        )

        assertTrue("expected a verdict, got $result", result is SemanticModelPreflight.Result.OutputUnreadable)
    }

    @Test
    fun `mismatched semantics are caught before the file is opened`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "sailens-no-such-model.tflite")
        missing.delete()

        val result = SemanticModelPreflight.check(
            context = ContextWrapper(null),
            source = ModelSource.File(missing),
            taxonomy = taxonomy(classCount = 19, id = "cityscapes"),
            semantics = semantics(id = "coco", classCount = 19),
        )

        assertTrue(
            "a build wired to the wrong semantics is broken whether or not a model is present",
            result is SemanticModelPreflight.Result.SemanticsMismatch,
        )
    }

    private fun metadata(shape: List<Int>) = TfliteModelMetadata(
        inputs = listOf(tensor(name = "images", shape = listOf(1, 640, 640, 3))),
        outputs = listOf(tensor(name = "output0", shape = shape)),
        signatures = emptyList(),
    )

    private fun tensor(name: String, shape: List<Int>) = TfliteTensorMetadata(
        index = 0,
        name = name,
        shape = shape,
        elementType = TfliteTensorElementType.FLOAT32,
        quantization = null,
    )

    private fun taxonomy(classCount: Int, id: String = "test") = object : Taxonomy {
        override val id: TaxonomyId = TaxonomyId(id)
        override val classCount: Int = classCount
        override fun label(classId: Int): String = "class$classId"
    }

    private fun semantics(id: String, classCount: Int) = object : NavigationSemantics {
        override val taxonomyId: TaxonomyId = TaxonomyId(id)
        override val classCount: Int = classCount
        override fun isPassable(classId: Int): Boolean = false
        override fun isObstacle(classId: Int): Boolean = false
        override fun isRoad(classId: Int): Boolean = false
        override fun isTrafficLight(classId: Int): Boolean = false
        override fun toGroundType(classId: Int) = com.sailens.guidance.model.common.GroundType.UNKNOWN
        override fun toObstacleCategory(classId: Int) =
            com.sailens.guidance.model.common.ObstacleCategory.UNKNOWN
    }
}
