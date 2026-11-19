package com.sailens.guidance.world

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.depth.geometry.ScaleBasis
import com.sailens.guidance.geometry.GeometryQuality
import java.util.UUID

/** Reads one cell of the current activity's evidence on its owner thread. */
interface LocalWorldReader {
    fun currentCell(row: Int, col: Int): CurrentCellQuery
}

sealed interface CurrentCellQuery {
    data class Unavailable(val reason: UnavailableReason) : CurrentCellQuery
    data class Observed(
        val frame: WorldFrameReference,
        val evidence: CellEvidence,
        val conflicts: CellConflicts,
        val unlocalizedRisks: List<UnlocalizedRiskEvidence>,
    ) : CurrentCellQuery
}

enum class UnavailableReason { INACTIVE, NO_ANALYSIS_GRANT, NO_FRAME, EXPIRED, OUT_OF_GRID, EVIDENCE_OVERFLOW }

data class WorldFrameReference(
    val run: UUID,
    val sequence: Long,
    val captureNanos: Long,
    val geometryQuality: GeometryQuality,
    val scaleBasis: ScaleBasis?,
)

enum class SurfaceEvidence { UNKNOWN, SEM_SURFACE }

data class CellEvidence(
    val surface: SurfaceEvidence,
    val contacts: List<ContactEvidence>,
) {
    val contactConfidence: Float? get() = contacts.maxOfOrNull { it.confidence }
}

data class ContactEvidence(val trackId: UUID, val confidence: Float)

data class CellConflicts(val imageDetections: List<ImageDetectionEvidence>) {
    val confidence: Float? get() = imageDetections.mapNotNull { it.confidence }.maxOrNull()
}

data class ImageDetectionEvidence(val detectionIndex: Int, val box: NormalizedRect, val confidence: Float?)

sealed interface WorldRiskId {
    data class RawDetection(val index: Int) : WorldRiskId
    data class Track(val id: UUID) : WorldRiskId
}

enum class UnlocalizedReason {
    IMAGE_ONLY, IMAGE_RANGE_UNPROVEN, SPATIAL_GEOMETRY_UNPROVEN, STALE_TRACK, EDGE_VISIBILITY_UNPROVEN,
    GROUND_PROBE_UNPROVEN, CONTACT_ENVELOPE_UNPROVEN, OUT_OF_GRID,
}

data class UnlocalizedRiskEvidence(
    val id: WorldRiskId,
    val reportedBox: NormalizedRect?,
    val confidence: Float?,
    val reason: UnlocalizedReason,
)
