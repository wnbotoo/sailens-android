package com.sailens.guidance.kernel

/** The input and output buffers belong to the calling run. */
internal object NativeWorldGrid {
    external fun fill(
        width: Int,
        height: Int,
        labels: IntArray,
        effectiveWords: LongArray,
        passable: BooleanArray,
        sums: IntArray,
    ): Boolean

    /** Fills conflict and semantic cells. Returns -1 on invalid input, or 0/1 for projectability. */
    external fun classify(
        rows: DoubleArray,
        rightEdges: DoubleArray,
        rawBounds: DoubleArray,
        rawCount: Int,
        semanticAxes: DoubleArray?,
        width: Int,
        height: Int,
        sums: IntArray?,
        contacts: LongArray,
        conflicts: LongArray,
        surface: BooleanArray,
    ): Int
}
