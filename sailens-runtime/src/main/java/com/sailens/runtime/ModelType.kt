package com.sailens.runtime

/**
 * The logical kind of a model — what role it plays in the perception pipeline, independent of which
 * file backs it. Together with the actual accelerator attempt this is how the rest of the app
 * refers to a model; the file name is resolved through [ModelSourceResolver] and is not known
 * anywhere else.
 */
enum class ModelType {
    OBSTACLE_DETECTION,
    SEMANTIC_SEGMENTATION,
}
