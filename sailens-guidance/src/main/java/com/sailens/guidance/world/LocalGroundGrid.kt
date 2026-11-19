package com.sailens.guidance.world

internal data class LocalGroundCellBounds(
    val minForward: Double,
    val maxForward: Double,
    val minRight: Double,
    val maxRight: Double,
) {
    val centreForward: Double get() = (minForward + maxForward) / 2.0
    val centreRight: Double get() = (minRight + maxRight) / 2.0
}

internal object LocalGroundGrid {
    const val ROWS = 60
    const val COLUMNS = 40

    private val forwardEdges = DoubleArray(ROWS + 1) { it / 10.0 }
    private val rightEdges = DoubleArray(COLUMNS + 1) { (it - 20) / 10.0 }

    fun forwardEdge(row: Int): Double = forwardEdges[row]
    fun rightEdge(col: Int): Double = rightEdges[col]

    fun indexOf(forwardMeters: Double, rightMeters: Double): Int? {
        val row = axisIndex(forwardMeters, forwardEdges) ?: return null
        val col = axisIndex(rightMeters, rightEdges) ?: return null
        return row * COLUMNS + col
    }

    fun boundsAt(row: Int, col: Int): LocalGroundCellBounds? {
        if (row !in 0 until ROWS || col !in 0 until COLUMNS) return null
        return LocalGroundCellBounds(forwardEdges[row], forwardEdges[row + 1], rightEdges[col], rightEdges[col + 1])
    }

    private fun axisIndex(value: Double, edges: DoubleArray): Int? {
        if (!value.isFinite() || value < edges.first() || value >= edges.last()) return null
        var lower = 0
        var upper = edges.lastIndex
        while (lower + 1 < upper) {
            val middle = (lower + upper) / 2
            if (edges[middle] <= value) {
                lower = middle
            } else {
                upper = middle
            }
        }
        return lower
    }
}
