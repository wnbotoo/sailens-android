package com.sailens.guidance.perception

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.vision.detection.Detection
import com.sailens.vision.taxonomy.TaxonomyId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The §6.3 boundary: a detector reports classes, Guidance decides what they mean.
 *
 * These are the two places that judgement is applied, and both have to agree — an allow-list that
 * admitted a class the mapping then drops would waste NMS work, and one that excluded a class the
 * mapping wants would silently lose obstacles.
 */
class ObstacleSemanticsTest {

    private val semantics = object : NavigationSemantics {
        override val taxonomyId: TaxonomyId = TaxonomyId("test")
        override val classCount: Int = 4

        override fun isPassable(classId: Int): Boolean = false
        override fun isObstacle(classId: Int): Boolean = classId == PERSON || classId == POLE
        override fun isRoad(classId: Int): Boolean = false
        override fun isTrafficLight(classId: Int): Boolean = false
        override fun toGroundType(classId: Int): GroundType = GroundType.UNKNOWN
        override fun toObstacleCategory(classId: Int): ObstacleCategory = when (classId) {
            PERSON -> ObstacleCategory.PERSON
            POLE -> ObstacleCategory.STATIC_OBSTACLE
            else -> ObstacleCategory.UNKNOWN
        }
    }

    @Test
    fun `allow-list admits exactly the classes that map to a category`() {
        assertArrayEquals(intArrayOf(PERSON, POLE), semantics.allowedObstacleClassIds(classCount = 4))
    }

    @Test
    fun `allow-list only considers classes the model can emit`() {
        // A model with two classes cannot produce class id 3, so it must not appear in the list
        // handed to the detector even though the semantics would categorise it.
        assertArrayEquals(intArrayOf(PERSON), semantics.allowedObstacleClassIds(classCount = 2))
    }

    @Test
    fun `mapping attaches the navigation category and keeps the detector's own fields`() {
        val box = NormalizedRect(x = 0.1f, y = 0.2f, width = 0.3f, height = 0.4f)
        val mapped = listOf(
            Detection(classId = PERSON, label = "person", confidence = 0.9f, boundingBox = box),
        ).toObstacleDetections(semantics)

        assertEquals(1, mapped.size)
        assertEquals(PERSON, mapped[0].classId)
        assertEquals("person", mapped[0].className)
        assertEquals(0.9f, mapped[0].confidence, 0f)
        assertEquals(box, mapped[0].boundingBox)
        assertEquals(ObstacleCategory.PERSON, mapped[0].category)
    }

    @Test
    fun `mapping drops a class with no navigation meaning instead of reporting it as unknown`() {
        val box = NormalizedRect(x = 0f, y = 0f, width = 1f, height = 1f)
        val mapped = listOf(
            Detection(classId = SKY, label = "sky", confidence = 0.99f, boundingBox = box),
            Detection(classId = POLE, label = "pole", confidence = 0.5f, boundingBox = box),
        ).toObstacleDetections(semantics)

        assertEquals(listOf(POLE), mapped.map { it.classId })
        assertEquals(ObstacleCategory.STATIC_OBSTACLE, mapped[0].category)
    }

    private companion object {
        const val PERSON = 0
        const val SKY = 1
        const val POLE = 2
    }
}
