package com.sailens.app

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.guidance.semantics.NavigationSemanticsBinding
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.ModelType
import com.sailens.shell.app.CapabilityExpectations
import com.sailens.shell.app.DescribeSpec
import com.sailens.shell.app.GuidanceSpec
import com.sailens.shell.app.SailensAppSpec
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.vlm.SceneDescriber

/**
 * What this edition of Sailens offers.
 *
 * A (sailens-android) is the reference host and ships **no model weights**, so it configures both
 * pipelines but promises neither: a fresh install legitimately lands on the zero-pipeline state
 * and points at the setup docs (architecture.md §5.1). An edition that does package weights --
 * sailens-yolo -- sets `guidanceRequired = true`, and then a missing model becomes a fatal
 * configuration state rather than a shrug.
 *
 * Every check here is cheap on purpose (§5.2): it resolves a model source and closes the stream,
 * and asks the semantics whether they were written for the taxonomy. Nothing is compiled and no
 * accelerator is touched.
 */
fun sailensEditionSpec(
    context: Context,
    sceneDescriber: SceneDescriber,
): SailensAppSpec = SailensAppSpec(
    guidance = GuidanceSpec(
        semanticModelPresent = {
            CatalogModelSourceResolver
                .source(ModelType.SEMANTIC_SEGMENTATION, Accelerator.GPU)
                .exists(context)
        },
        taxonomyCompatible = {
            NavigationSemanticsBinding.validate(
                taxonomy = CityscapesTaxonomy,
                semantics = CityscapesNavigationSemantics,
            ) == NavigationSemanticsBinding.Result.Compatible
        },
    ),
    describe = DescribeSpec(
        engineAvailable = { sceneDescriber.isAvailable },
    ),
    // A ships zero weights, so it promises nothing. This is the line an edition with packaged
    // models changes.
    expectations = CapabilityExpectations(
        guidanceRequired = false,
        describeRequired = false,
    ),
)
