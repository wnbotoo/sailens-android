package com.sailens.domain.semantics

import com.sailens.domain.model.common.GroundType
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.vision.taxonomy.CocoTaxonomy
import com.sailens.vision.taxonomy.Taxonomy
import com.sailens.vision.taxonomy.TaxonomyId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The static taxonomy check from architecture.md §6.2.
 *
 * What it is for: catching an edition that packages a model built on one class definition and
 * semantics written for another. What it is *not* for is proving that channel 11 means `person` --
 * see the class docs and docs/models.md. These tests deliberately include that limit, so nobody
 * reads a green suite as "the semantics are verified".
 */
class NavigationSemanticsBindingTest {

    @Test
    fun `shipped semantics match their taxonomy`() {
        assertEquals(
            NavigationSemanticsBinding.Result.Compatible,
            NavigationSemanticsBinding.validate(CityscapesTaxonomy, CityscapesNavigationSemantics),
        )
        assertEquals(
            NavigationSemanticsBinding.Result.Compatible,
            NavigationSemanticsBinding.validate(CocoTaxonomy, CocoNavigationSemantics),
        )
    }

    @Test
    fun `semantics written for another dataset are rejected`() {
        val result = NavigationSemanticsBinding.validate(CocoTaxonomy, CityscapesNavigationSemantics)

        assertEquals(
            NavigationSemanticsBinding.Result.TaxonomyMismatch(
                declared = "coco-80",
                expected = "cityscapes-19",
            ),
            result,
        )
    }

    @Test
    fun `a taxonomy with the right name but the wrong class count is rejected`() {
        // A retrained model that dropped a class is the realistic version of this: same dataset
        // name, different head. Reading 19 classes out of an 18-class tensor would run.
        val shrunk = object : Taxonomy {
            override val id: TaxonomyId = CityscapesTaxonomy.id
            override val classCount: Int = CityscapesTaxonomy.classCount - 1
            override fun label(classId: Int): String = CityscapesTaxonomy.label(classId)
        }

        val result = NavigationSemanticsBinding.validate(shrunk, CityscapesNavigationSemantics)

        assertEquals(
            NavigationSemanticsBinding.Result.ClassCountMismatch(declared = 18, expected = 19),
            result,
        )
    }

    @Test
    fun `matching id and class count does not mean the channel order is right`() {
        // Semantics that agree on the id and the count but invert every judgement. The binding
        // passes, because nothing observable contradicts it -- this is the manual release gate
        // that docs/models.md describes, written down as an executable reminder.
        val inverted = object : NavigationSemantics {
            override val taxonomyId: TaxonomyId = CityscapesTaxonomy.id
            override val classCount: Int = CityscapesTaxonomy.classCount
            override fun isPassable(classId: Int): Boolean =
                !CityscapesNavigationSemantics.isPassable(classId)

            override fun isObstacle(classId: Int): Boolean =
                !CityscapesNavigationSemantics.isObstacle(classId)

            override fun isRoad(classId: Int): Boolean = false
            override fun isTrafficLight(classId: Int): Boolean = false
            override fun toGroundType(classId: Int): GroundType = GroundType.UNKNOWN
            override fun toObstacleCategory(classId: Int): ObstacleCategory = ObstacleCategory.UNKNOWN
        }

        assertEquals(
            NavigationSemanticsBinding.Result.Compatible,
            NavigationSemanticsBinding.validate(CityscapesTaxonomy, inverted),
        )
    }
}
