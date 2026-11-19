package com.sailens.guidance.world

import com.sailens.guidance.kernel.NativeGuidanceLibrary
import com.sailens.guidance.kernel.NativeWorldGrid

/** Borrows projection and prefix storage until this frame's snapshot has been filled. */
internal class WorldGridRasterizer(private val preferNative: Boolean) {
    val rows = DoubleArray((LocalGroundGrid.ROWS + 1) * WORLD_ROW_WIDTH)
    private val axes = DoubleArray(WorldSemanticFootprint.AXES_SIZE)
    private val rawBounds = DoubleArray(Long.SIZE_BITS * 4)
    private val corners by lazy(LazyThreadSafetyMode.NONE) { ProjectedGrid() }

    fun classify(
        raw: List<ImageFootprint>,
        semantic: WorldSemanticFootprint?,
        table: WorldPassableTable,
        cells: LocalWorldCells,
    ): Boolean {
        if (preferNative && NativeGuidanceLibrary.isAvailable) {
            for (index in raw.indices) {
                val box = raw[index]
                val at = index * 4
                rawBounds[at] = box.x.low
                rawBounds[at + 1] = box.x.high
                rawBounds[at + 2] = box.y.low
                rawBounds[at + 3] = box.y.high
            }
            semantic?.writeAxes(axes)
            val result = NativeWorldGrid.classify(
                rows, rightEdges, rawBounds, raw.size, axes.takeIf { semantic != null },
                semantic?.width ?: 0, semantic?.height ?: 0, table.prefixSums().takeIf { semantic != null },
                cells.contacts, cells.conflicts, cells.surface,
            )
            if (result >= 0) return result > 0
        }
        corners.projectCorners(rows, rightEdges)
        var projectable = false
        for (row in 0 until LocalGroundGrid.ROWS) {
            for (col in 0 until LocalGroundGrid.COLUMNS) {
                val index = row * LocalGroundGrid.COLUMNS + col
                corners.visitFootprint(row, col) { minX, maxX, minY, maxY ->
                    if (raw.isNotEmpty()) projectable = true
                    var conflicts = 0L
                    for (boxIndex in raw.indices) {
                        val box = raw[boxIndex]
                        if (minX <= box.x.high && box.x.low <= maxX && minY <= box.y.high && box.y.low <= maxY) {
                            conflicts = conflicts or (1L shl boxIndex)
                        }
                    }
                    cells.conflicts[index] = conflicts
                    if (semantic != null && conflicts == 0L && cells.contacts[index] == 0L) {
                        semantic.visitArea(minX, maxX, minY, maxY) { left, top, right, bottom ->
                            projectable = true
                            cells.surface[index] = table.covers(left, top, right, bottom)
                        }
                    }
                }
            }
        }
        return projectable
    }

    private companion object {
        val rightEdges = DoubleArray(LocalGroundGrid.COLUMNS + 1, LocalGroundGrid::rightEdge)
    }
}
