package com.sailens.guidance.model.perception

import com.sailens.runtime.InputImageMapping
import com.sailens.vision.semantic.SemanticImageMapping

/** Identity of a single perception invocation. Carries no borrowed frame data. */
data class PerceptionFrameIdentity internal constructor(val sequence: Long, val captureNanos: Long)

class PerceptionFrameEvidence internal constructor(
    internal val current: PerceptionFrameIdentity,
    internal val semanticSource: PerceptionFrameIdentity?,
    internal val semanticMapping: SemanticImageMapping?,
    internal val detectionMapping: InputImageMapping?,
)
