package com.sailens.runtime

import com.google.ai.edge.litert.Accelerator

/**
 * One physical model for every accelerator attempt.
 *
 * [ModelSourceResolver] may give each accelerator its own file (a per-accelerator export, a
 * download). A comparison between accelerators must not let it: two different files would turn a
 * model difference into an apparent accelerator difference. This resolves [type] once, for
 * [backend], and answers with that same source whatever accelerator is asked for.
 */
class PinnedModelSourceResolver private constructor(
    private val type: ModelType,
    val pinned: ModelSource,
) : ModelSourceResolver {

    override fun source(type: ModelType, accelerator: Accelerator): ModelSource {
        require(type == this.type) { "pinned to ${this.type}, asked for $type" }
        return pinned
    }

    companion object {
        fun pin(resolver: ModelSourceResolver, type: ModelType, backend: ModelAcceleratorBackend): PinnedModelSourceResolver =
            PinnedModelSourceResolver(type, resolver.source(type, backend.toLiteRtAccelerator()))
    }
}
