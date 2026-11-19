package com.sailens.guidance.world

import com.sailens.core.frame.ImageFrame
import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.GroundContactEstimate
import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.depth.geometry.ScaleBasis
import com.sailens.guidance.geometry.CameraFacts
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleRunKind
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleSource
import com.sailens.guidance.model.perception.PerceptionResult
import com.sailens.guidance.motion.MotionState
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.semantics.NavigationSemantics
import java.util.Collections
import java.util.UUID

/** Builds one independent observation from the borrowed perception inputs of the current frame. */
class LocalWorldModelBuilder(semantics: NavigationSemantics) {
    private val passable = BooleanArray(semantics.classCount) { semantics.isPassable(it) }
    private val ground = BooleanArray(semantics.classCount) { passable[it] || semantics.toGroundType(it) != GroundType.UNKNOWN }

    internal class Scratch(
        preferNative: Boolean = true,
        private val reportPhase: ((WorldBuildPhase) -> Unit)? = null,
    ) {
        val passable = WorldPassableTable(preferNative)
        val grid by lazy(LazyThreadSafetyMode.NONE) { WorldGridRasterizer(preferNative) }

        fun reached(phase: WorldBuildPhase) {
            reportPhase?.invoke(phase)
        }
    }

    internal fun build(
        run: UUID,
        frame: ImageFrame,
        frameFacts: FrameFacts,
        camera: CameraFacts?,
        geometry: CameraGeometry,
        proof: WorldImageProof?,
        perception: PerceptionResult,
        effectivePassableMask: BinaryMask,
        config: PerceptionConfig,
        baseline: BaselineQualification,
        depth: DepthReading,
        viewMotion: MotionState?,
        frameMotion: MotionState?,
        scratch: Scratch,
    ): BuiltWorld {
        scratch.reached(WorldBuildPhase.BEGIN)
        val rejectedDepth = DepthContributionPolicy.assess(depth, geometry, baseline, viewMotion, frameMotion)
        val spatialIssue = spatialRejection(frame, frameFacts, camera, geometry, proof)
        val nominal = (LocalGroundFrame.resolve(geometry) as? LocalGroundFrame.Result.Resolved)?.frame
        val projection = if (spatialIssue == null && nominal != null) WorldProjection(nominal) else null
        val reference = WorldFrameReference(
            run, frame.sequenceNumber, frame.timestamp, geometry.quality,
            ScaleBasis.ASSUMED_PHONE_HEIGHT.takeIf { projection != null },
        )
        val built = buildEvidence(reference, frame, perception, effectivePassableMask, config, baseline, projection, scratch)
        scratch.reached(WorldBuildPhase.SNAPSHOT)
        return BuiltWorld(built, rejectedDepth, spatialIssue ?: SpatialRejection.NOMINAL_GEOMETRY.takeIf { nominal == null })
    }

    private fun buildEvidence(
        reference: WorldFrameReference,
        frame: ImageFrame,
        perception: PerceptionResult,
        effective: BinaryMask,
        config: PerceptionConfig,
        baseline: BaselineQualification,
        projection: WorldProjection?,
        scratch: Scratch,
    ): LocalWorldSnapshot? {
        val raw = perception.obstacleDetections.takeIf { perception.obstacleRunKind == ObstacleRunKind.DETECTION }.orEmpty()
        val tracks = perception.obstacles
        val trackedLimit = config.maxObstacles.toLong() * config.trackerMaxMissedFrames.coerceAtLeast(1)
        if (raw.size > config.maxObstacles || tracks.size > trackedLimit || raw.size > MAX_POSITIONED ||
            WorldSnapshotBudget.retainedUpperBound(raw.size, tracks.size) > WorldSnapshotBudget.LIMIT
        ) return null

        val sources = perception.frameEvidence?.takeIf {
            it.current.sequence == frame.sequenceNumber && it.current.captureNanos == frame.timestamp
        }
        val k = projection?.intrinsics
        val semantic = sources?.semanticMapping?.takeIf {
            sources.semanticSource === sources.current && k != null &&
                it.input.imageWidth == k.width && it.input.imageHeight == k.height &&
                it.input.rotationDegrees == frame.rotationDegrees
        }?.let { WorldSemanticFootprint.from(it, perception.analysis.segmentation) }
        val detCoordinates = sources?.detectionMapping?.let { mapping ->
            val sideways = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
            mapping.imageWidth == (if (sideways) frame.height else frame.width) &&
                mapping.imageHeight == (if (sideways) frame.width else frame.height) &&
                mapping.rotationDegrees == frame.rotationDegrees
        } == true

        val imageRegions = ArrayList<ImageFootprint>(raw.size)
        val imageEvidence = ArrayList<ImageDetectionEvidence>(raw.size)
        val contactRegions = ArrayList<ImageFootprint>()
        val contactEvidence = ArrayList<ContactEvidence>()
        val risks = ArrayList<UnlocalizedRiskEvidence>()
        var unknownRisk = false

        raw.forEachIndexed { index, item ->
            if (item.confidence.isFinite() && item.confidence < config.minObstacleConfidence) return@forEachIndexed
            val confidence = item.confidence.validConfidence()
            val box = item.boundingBox
            val bounds = if (detCoordinates && confidence != null) box.imageBounds() else null
            val reason = when {
                bounds == null -> {
                    unknownRisk = true
                    UnlocalizedReason.IMAGE_RANGE_UNPROVEN
                }
                k == null -> UnlocalizedReason.SPATIAL_GEOMETRY_UNPROVEN
                else -> {
                    imageRegions += bounds.toPixels(k.width, k.height)
                    imageEvidence += ImageDetectionEvidence(index, box, confidence)
                    UnlocalizedReason.IMAGE_ONLY
                }
            }
            risks += UnlocalizedRiskEvidence(WorldRiskId.RawDetection(index), box.takeIf { it.hasFiniteFields() }, confidence, reason)
        }
        scratch.reached(WorldBuildPhase.RAW_REGIONS)

        for (track in tracks) {
            if (track.source != ObstacleSource.DETECTION) continue
            val contact = track.distanceEstimate?.contact
            val probe = contact?.groundProbe
            val fresh = sources != null && track.measurement?.frameIdentity === sources.current &&
                perception.obstacleRunKind == ObstacleRunKind.DETECTION
            val box = probe?.box
            val confidence = track.confidence.validConfidence()
            val failure = when {
                !fresh -> {
                    unknownRisk = true
                    UnlocalizedReason.STALE_TRACK
                }
                !detCoordinates || confidence == null || box == null || box.imageBounds() == null -> {
                    // A matched frame with a certified batch still has the visible raw box above.
                    if (!detCoordinates || confidence == null) unknownRisk = true
                    UnlocalizedReason.EDGE_VISIBILITY_UNPROVEN
                }
                projection == null -> UnlocalizedReason.SPATIAL_GEOMETRY_UNPROVEN
                !track.isStable(config.trackerMinStableFrames) || contact.validity != ContactValidity.VALID ||
                    probe.semanticSource !== sources.current || semantic == null -> UnlocalizedReason.GROUND_PROBE_UNPROVEN
                else -> {
                    val envelope = contactRegion(box, projection, config, semantic, contact)
                    if (envelope.region != null) {
                        if (contactEvidence.size >= MAX_POSITIONED) return null
                        contactRegions += envelope.region
                        contactEvidence += ContactEvidence(track.id, confidence)
                    }
                    envelope.reason
                }
            }
            if (failure != null) {
                val reported = box ?: track.boundingBox
                risks += UnlocalizedRiskEvidence(WorldRiskId.Track(track.id), reported.takeIf { it.hasFiniteFields() }, confidence, failure)
            }
        }
        scratch.reached(WorldBuildPhase.CONTACT_ENVELOPES)
        val frozenRisks = Collections.unmodifiableList(risks)
        if (projection == null) return LocalWorldSnapshot(reference, frozenRisks)

        val semUsable = baseline == BaselineQualification.Qualified && !unknownRisk && semantic != null
        if (semUsable && !scratch.passable.fill(effective, perception.analysis.segmentation, passable)) return null
        scratch.reached(WorldBuildPhase.SUMMED_AREA)
        if (!semUsable && imageRegions.isEmpty() && contactRegions.isEmpty()) {
            return LocalWorldSnapshot(reference, frozenRisks)
        }

        val cells = LocalWorldCells(imageEvidence, contactEvidence)
        val scanImage = semUsable || imageRegions.isNotEmpty()
        if (scanImage) projection.projectRowsInto(scratch.grid.rows)
        scratch.reached(WorldBuildPhase.PROJECTION)
        markContactCells(contactRegions, cells.contacts)
        val imageProjectable = scanImage && scratch.grid.classify(imageRegions, semantic.takeIf { semUsable }, scratch.passable, cells)
        scratch.reached(WorldBuildPhase.CELLS)
        return LocalWorldSnapshot(reference, frozenRisks, cells, imageProjectable || contactRegions.isNotEmpty())
    }

    private fun markContactCells(regions: List<ImageFootprint>, cells: LongArray) {
        for (index in regions.indices) {
            val region = regions[index]
            val bit = 1L shl index
            var firstColumn = 0
            while (firstColumn < LocalGroundGrid.COLUMNS &&
                LocalGroundGrid.rightEdge(firstColumn + 1) < region.y.low
            ) firstColumn++
            var afterLastColumn = firstColumn
            while (afterLastColumn < LocalGroundGrid.COLUMNS &&
                LocalGroundGrid.rightEdge(afterLastColumn) <= region.y.high
            ) afterLastColumn++
            for (row in 0 until LocalGroundGrid.ROWS) {
                if (LocalGroundGrid.forwardEdge(row + 1) < region.x.low || LocalGroundGrid.forwardEdge(row) > region.x.high) continue
                val offset = row * LocalGroundGrid.COLUMNS
                for (col in firstColumn until afterLastColumn) {
                    cells[offset + col] = cells[offset + col] or bit
                }
            }
        }
    }

    private fun contactRegion(
        box: NormalizedRect,
        projection: WorldProjection,
        config: PerceptionConfig,
        semantic: WorldSemanticFootprint,
        contact: GroundContactEstimate,
    ): Envelope {
        val width = projection.intrinsics.width
        val height = projection.intrinsics.height
        val region = checkNotNull(box.imageBounds())
        val left = box.x.toDouble()
        val right = left + box.width
        val bottom = box.y.toDouble() + box.height
        val gx = 1.0 / width + BOX_ROUNDING
        val gy = 1.0 / height + BOX_ROUNDING
        if (left <= gx || right >= 1 - gx || bottom >= 1 - gy) return Envelope(UnlocalizedReason.EDGE_VISIBILITY_UNPROVEN)
        val groundConfig = config.groundContact
        val foot = Bound.enclosing(bottom - BOX_ROUNDING, bottom + BOX_ROUNDING)
        val strip = ImageFootprint(
            Bound.enclosing(left + box.width * 0.25 - BOX_ROUNDING, right - box.width * 0.25 + BOX_ROUNDING),
            Bound.enclosing(foot.low, foot.high + groundConfig.groundProbeHeight),
        )
        val area = semantic.mapArea(strip.toPixels(width, height))
        if (area == null || !semantic.isGround(area, ground)) return Envelope(UnlocalizedReason.GROUND_PROBE_UNPROVEN)
        val lower = contact.lowerM
        val upper = contact.upperM
        if (lower == null || upper == null || !lower.isFinite() || !upper.isFinite() || lower <= 0 || upper < lower) {
            return Envelope(UnlocalizedReason.CONTACT_ENVELOPE_UNPROVEN)
        }
        val phone = projection.phone
        val tolerance = if (phone.source == HeightSource.CALIBRATED) {
            groundConfig.calibratedHeightTolerance
        } else {
            groundConfig.presetHeightTolerance
        }
        val envelope = projection.contactEnvelope(
            region.toPixels(width, height), foot * Bound(height.toDouble()), phone.heightMeters.toDouble(),
            tolerance.toDouble(), groundConfig.gravityToleranceDeg.toDouble(), groundConfig.minDepressionDeg.toDouble(),
        ) ?: return Envelope(UnlocalizedReason.CONTACT_ENVELOPE_UNPROVEN)
        val outside = envelope.x.low < 0 || envelope.x.high >= 6 || envelope.y.low < -2 || envelope.y.high >= 2
        val overlaps = envelope.x.low <= 6 && envelope.x.high >= 0 && envelope.y.low <= 2 && envelope.y.high >= -2
        return Envelope(UnlocalizedReason.OUT_OF_GRID.takeIf { outside }, envelope.takeIf { overlaps })
    }

    private data class Envelope(val reason: UnlocalizedReason?, val region: ImageFootprint? = null)

    private companion object {
        const val MAX_POSITIONED = Long.SIZE_BITS
    }
}

internal object WorldSnapshotBudget {
    const val LIMIT = 64 * 1024
    private const val CELL_BYTES = LocalGroundGrid.ROWS * LocalGroundGrid.COLUMNS * (1 + 2 * Long.SIZE_BYTES)
    private const val FRAME_OVERHEAD = 2048
    private const val PER_CANDIDATE = 256

    // Include headers, 64-bit references, list spare capacity and both possible evidence
    // records for each candidate. Borrowed masks, projection and sums never reach a snapshot.
    fun retainedUpperBound(raw: Int, tracks: Int): Long = CELL_BYTES + FRAME_OVERHEAD +
        (raw.toLong() + tracks) * PER_CANDIDATE
}

internal enum class WorldBuildPhase { BEGIN, RAW_REGIONS, CONTACT_ENVELOPES, SUMMED_AREA, PROJECTION, CELLS, SNAPSHOT }

internal data class BuiltWorld(val snapshot: LocalWorldSnapshot?, val depth: RejectedDepth, val spatial: SpatialRejection?)

internal class LocalWorldCells(
    private val imageEvidence: List<ImageDetectionEvidence>,
    private val contactEvidence: List<ContactEvidence>,
) {
    val surface = BooleanArray(LocalGroundGrid.ROWS * LocalGroundGrid.COLUMNS)
    val conflicts = LongArray(surface.size)
    val contacts = LongArray(surface.size)

    fun conflictsAt(index: Int): List<ImageDetectionEvidence> = imageEvidence.valuesIn(conflicts[index])
    fun contactsAt(index: Int): List<ContactEvidence> = contactEvidence.valuesIn(contacts[index])
    val hasSpatialEvidence: Boolean get() = surface.any { it } || contacts.any { it != 0L } || conflicts.any { it != 0L }

    private fun <T> List<T>.valuesIn(bits: Long): List<T> {
        if (bits == 0L) return emptyList()
        val matches = ArrayList<T>(java.lang.Long.bitCount(bits))
        var remaining = bits
        while (remaining != 0L) {
            matches += this[java.lang.Long.numberOfTrailingZeros(remaining)]
            remaining = remaining and (remaining - 1)
        }
        return Collections.unmodifiableList(matches)
    }
}

internal class LocalWorldSnapshot(
    val frame: WorldFrameReference,
    private val risks: List<UnlocalizedRiskEvidence>,
    private val cells: LocalWorldCells? = null,
    val projectable: Boolean = false,
) {
    val hasSpatialEvidence: Boolean get() = cells?.hasSpatialEvidence == true

    fun read(index: Int, allowSurface: Boolean): CurrentCellQuery.Observed {
        val surface = if (allowSurface && cells?.surface?.get(index) == true) SurfaceEvidence.SEM_SURFACE else SurfaceEvidence.UNKNOWN
        return CurrentCellQuery.Observed(
            frame, CellEvidence(surface, cells?.contactsAt(index).orEmpty()),
            CellConflicts(cells?.conflictsAt(index).orEmpty()), risks,
        )
    }
}

private fun Float.validConfidence(): Float? = takeIf { it.isFinite() && it in 0f..1f }

private fun NormalizedRect.hasFiniteFields() = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()

private fun NormalizedRect.imageBounds(): ImageFootprint? {
    if (!hasFiniteFields() || x !in 0f..1f || y !in 0f..1f || width <= 0f || height <= 0f) return null
    val right = x.toDouble() + width
    val bottom = y.toDouble() + height
    if (right > 1 + BOX_ROUNDING || bottom > 1 + BOX_ROUNDING) return null
    return ImageFootprint(
        Bound(maxOf(0.0, Math.nextDown(x - BOX_ROUNDING)), minOf(1.0, Math.nextUp(right + BOX_ROUNDING))),
        Bound(maxOf(0.0, Math.nextDown(y - BOX_ROUNDING)), minOf(1.0, Math.nextUp(bottom + BOX_ROUNDING))),
    )
}

private fun ImageFootprint.toPixels(width: Int, height: Int) =
    ImageFootprint(x * Bound(width.toDouble()), y * Bound(height.toDouble()))
