package com.sailens.app

import android.content.Context
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.guidance.semantics.SemanticModelPreflight
import com.sailens.runtime.ModelSource
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelType
import com.sailens.runtime.source
import com.sailens.shell.app.CapabilityExpectations
import com.sailens.shell.app.DescribeSpec
import com.sailens.shell.app.GuidanceSpec
import com.sailens.shell.app.SailensAppSpec
import com.sailens.shell.app.StaticUnavailableReason
import com.sailens.vision.semantic.SemanticModelConfig
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.vlm.SceneDescriber

/**
 * What the Sailens Android reference host offers.
 *
 * Sailens Android is the reference host and ships **no model weights**, so it configures both
 * pipelines but promises neither: a fresh install legitimately lands on the zero-pipeline state
 * and points at the setup docs (architecture.md §5.1). The official Sailens distribution
 * packages weights and sets `guidanceRequired = true`, so a missing model becomes a fatal
 * configuration state rather than a shrug.
 *
 * The Guidance check reads the model's TFLite metadata tables and compares its output class count
 * against the declared taxonomy. That is still cheap (§5.2): a memory-mapped read of a few hundred
 * bytes, no compiled model, no accelerator, no inference. It is also the difference between
 * catching a mismatched model here and catching it after the user has pressed start and begun
 * walking. What it cannot check is channel *order* -- the models carry no labels, so which channel
 * is `person` remains a manual release gate (§6.2, docs/models.md).
 *
 * The check reads the file the semantic runner will load: [modelSourceResolver] is the instance this
 * host binds for the runners, asked for the accelerator [semanticModel] is configured to run on.
 */
fun sailensEditionSpec(
    context: Context,
    sceneDescriber: SceneDescriber,
    modelSourceResolver: ModelSourceResolver,
    semanticModel: SemanticModelConfig,
): SailensAppSpec = SailensAppSpec(
    guidance = GuidanceSpec(
        verifySemanticModel = {
            verifySemanticModel(context, semanticPreflightSource(modelSourceResolver, semanticModel))
        },
    ),
    describe = DescribeSpec(
        verifyEngine = {
            if (sceneDescriber.isAvailable) null else StaticUnavailableReason.EngineUnavailable
        },
    ),
    // The reference host ships zero weights, so it promises nothing. A distribution with packaged
    // models changes this expectation.
    expectations = CapabilityExpectations(
        guidanceRequired = false,
        describeRequired = false,
    ),
)

/** The source the semantic runner loads, so the static check looks at that file and no other. */
internal fun semanticPreflightSource(
    modelSourceResolver: ModelSourceResolver,
    semanticModel: SemanticModelConfig,
): ModelSource = modelSourceResolver.source(ModelType.SEMANTIC_SEGMENTATION, semanticModel.acceleratorBackend)

/**
 * Translates the Guidance pipeline's own verdict into the shell's vocabulary.
 *
 * The shell owns the reasons it can present and speak; what makes a semantic model usable is a
 * Guidance question, answered by Guidance (§6.11).
 */
private fun verifySemanticModel(context: Context, source: ModelSource): StaticUnavailableReason? {
    val result = SemanticModelPreflight.check(
        context = context,
        source = source,
        taxonomy = CityscapesTaxonomy,
        semantics = CityscapesNavigationSemantics,
    )
    return when (result) {
        SemanticModelPreflight.Result.Compatible -> null
        SemanticModelPreflight.Result.ModelSourceMissing -> StaticUnavailableReason.ModelSourceMissing
        is SemanticModelPreflight.Result.SemanticsMismatch -> StaticUnavailableReason.TaxonomyIncompatible
        is SemanticModelPreflight.Result.OutputUnreadable ->
            StaticUnavailableReason.ModelOutputUnreadable(result.detail)

        is SemanticModelPreflight.Result.ClassCountMismatch ->
            StaticUnavailableReason.ModelClassCountMismatch(
                declared = result.declared,
                found = result.candidates,
            )
    }
}
