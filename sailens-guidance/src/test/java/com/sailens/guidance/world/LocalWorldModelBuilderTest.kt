package com.sailens.guidance.world

import com.sailens.core.frame.FrameSourceGeometry
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.DefaultDepthRepository
import com.sailens.guidance.depth.DistanceFrame
import com.sailens.guidance.depth.GroundContactDistanceEstimator
import com.sailens.guidance.depth.ImagePositionDepthEstimator
import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.depth.geometry.DepthReadingTimes
import com.sailens.guidance.depth.geometry.DepthCells
import com.sailens.guidance.depth.geometry.DepthGeometrySnapshot
import com.sailens.guidance.depth.geometry.DepthLaneState
import com.sailens.guidance.depth.geometry.DepthViewGeometry
import com.sailens.guidance.depth.geometry.DepthUnit
import com.sailens.guidance.depth.geometry.ObservationId
import com.sailens.guidance.depth.geometry.ScaleBasis
import com.sailens.guidance.geometry.CameraClock
import com.sailens.guidance.geometry.CameraFacts
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorRect
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.ObstacleRunKind
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.ObstacleSourceFrame
import com.sailens.guidance.model.perception.PerceptionFrameEvidence
import com.sailens.guidance.model.perception.PerceptionFrameIdentity
import com.sailens.guidance.model.perception.PerceptionResult
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.motion.MotionState
import com.sailens.guidance.motion.MotionTracker
import com.sailens.guidance.processor.analysis.ObstacleOcclusionAnalyzer
import com.sailens.guidance.processor.perception.ObstacleExtractor
import com.sailens.guidance.processor.perception.ObstacleTracker
import com.sailens.guidance.processor.perception.SegmentationAnalyzer
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.runtime.InputImageMapping
import com.sailens.vision.semantic.SemanticContentRegion
import com.sailens.vision.semantic.SemanticImageMapping
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.capture.toTransform
import com.sailens.guidance.trace.regression.DepthEvidenceReplay
import com.sailens.guidance.trace.regression.DepthReplayRole
import com.sailens.guidance.trace.regression.toGeometry
import com.sailens.vision.depth.DepthKind
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.MeasurementKey
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class LocalWorldModelBuilderTest {
    private val semantics = CityscapesNavigationSemantics
    private val config = PerceptionConfig(trackerMinStableFrames = 1)
    private val analyzer = SegmentationAnalyzer(AnalysisConfig(), semantics)
    private val builder = LocalWorldModelBuilder(semantics)
    private val scratch = LocalWorldModelBuilder.Scratch()
    private val cameraCentre = Any()
    private val origin = WorldImageProof.OpticalCentre(cameraCentre, CameraVector(0.0, 0.0, 0.0))
    private val axes = listOf(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f))
    private val run = UUID.randomUUID()
    private val levelPhone = PhoneGeometrySettings(1.2f, HeightSource.PRESET)
    private val sensorToBuffer = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test
    fun `combined evidence over the retained budget fails closed`() {
        val scene = Scene()
        val detection = scene.detection()
        val track = scene.track(detection)
        val tracks = List(44) { track.copy(id = UUID(0, it.toLong())) }
        val largeConfig = config.copy(maxObstacles = 64)
        val admitted = scene.build(detections = List(44) { detection }, tracks = tracks, perceptionConfig = largeConfig)
        assertTrue(admitted.snapshot != null)
        val overflow = scene.build(detections = List(45) { detection }, tracks = tracks, perceptionConfig = largeConfig)
        assertNull(overflow.snapshot)
    }

    @Test
    fun `physical pixels and centre control the same cell through actual admission`() {
        val scene = Scene()
        val clear = scene.build()
        assertNull(clear.spatial)
        assertEquals(SurfaceEvidence.SEM_SURFACE, clear.cell(30, 25).evidence.surface)
        scene.labels[398 * 640 + 394] = CityscapesTaxonomy.BUILDING
        assertEquals(SurfaceEvidence.UNKNOWN, scene.build().cell(30, 25).evidence.surface)
        scene.labels[398 * 640 + 394] = CityscapesTaxonomy.ROAD
        scene.labels[411 * 640 + 394] = CityscapesTaxonomy.BUILDING

        // The renderer moved only its optical centre; with the unshifted proof this blocker would be missed.
        assertEquals(SurfaceEvidence.SEM_SURFACE, scene.build().cell(30, 25).evidence.surface)
        val moved = WorldImageProof.OpticalCentre(cameraCentre, CameraVector(0.0, -0.10, 0.0))
        val shifted = scene.build(proof = scene.proof(sourceCentre = moved))
        assertEquals(SurfaceEvidence.UNKNOWN, shifted.cell(30, 25).evidence.surface)
        assertEquals(SpatialRejection.OPTICAL_CENTRE_MISMATCH, shifted.spatial)
        assertEquals(SpatialRejection.OPTICAL_CENTRE_UNPROVEN, scene.build(proof = scene.proof(sourceCentre = null)).spatial)
    }

    @Test
    fun `wrong delivery skew mirrored axes and a different ray basis fail independently`() {
        val scene = Scene()
        assertEquals(SpatialRejection.PIXEL_DOMAIN_UNPROVEN, scene.build(proof = scene.proof(frame = scene.frame.copy())).spatial)
        assertEquals(SpatialRejection.CALIBRATION_UNPROVEN, Scene(skew = -Float.MIN_VALUE).build().spatial)
        assertEquals(SpatialRejection.CALIBRATION_UNPROVEN, Scene(skew = 4f).build().spatial)
        val mirror = sensorToBuffer.toMutableList().apply { this[0] = -1f }
        assertEquals(SpatialRejection.AXIS_UNPROVEN, Scene(transform = mirror).build().spatial)
        val yawWithSameDown = listOf(Vec3(0f, 0f, 1f), axes[1], Vec3(1f, 0f, 0f))
        assertEquals(SpatialRejection.RAY_AXES_UNPROVEN, scene.build(proof = scene.proof(axes = yawWithSameDown)).spatial)
    }

    @Test
    fun `nonzero skew must reject the cell even when the pixel domain is authentic`() {
        val ordinary = Scene()
        ordinary.labels[398 * 640 + 334] = CityscapesTaxonomy.BUILDING
        assertEquals(SurfaceEvidence.SEM_SURFACE, ordinary.build().cell(30, 20).evidence.surface)

        val skewed = Scene(skew = 4f)
        skewed.labels[398 * 640 + 334] = CityscapesTaxonomy.BUILDING
        val result = skewed.build()
        assertEquals(SurfaceEvidence.UNKNOWN, result.cell(30, 20).evidence.surface)
        assertEquals(SpatialRejection.CALIBRATION_UNPROVEN, result.spatial)
    }

    @Test
    fun `same dimension scores without placement proof cannot authorize semantic surface`() {
        val shifted = Scene()
        shifted.labels[411 * shifted.mapWidth + 394] = CityscapesTaxonomy.BUILDING
        val unknown = shifted.build(mapping = null)
        assertNull(unknown.spatial)
        assertEquals(SurfaceEvidence.UNKNOWN, unknown.cell(30, 25).evidence.surface)
        assertEquals(SurfaceEvidence.SEM_SURFACE, Scene().build().cell(30, 25).evidence.surface)
        val actual = Scene()
        actual.labels[398 * actual.mapWidth + 394] = CityscapesTaxonomy.BUILDING
        assertEquals(SurfaceEvidence.UNKNOWN, actual.build().cell(30, 25).evidence.surface)
    }

    @Test
    fun `fresh depth cannot shift public evidence after independent translation or yaw`() {
        val capture = 1_010_000_000L
        val measured = 1_000_000_000L
        val key = MeasurementKey(2, 4)
        val depthWidth = 160
        val depthHeight = 120
        val depthX = 98
        val depthY = 99
        val oldU = (depthX + .5) * 4
        val oldV = (depthY + .5) * 4
        val observedForward = 400 * 1.2 / (oldV - 240)
        val observedRight = (oldU - 320) * observedForward / 400
        val depthIndex = depthY * depthWidth + depthX
        for ((translation, turn) in listOf(.50 to 0.0, 0.0 to 10.0)) {
            val scene = Scene(captureNanos = capture)
            val yaw = Math.toRadians(turn)
            val newRight = (observedRight - translation) * cos(yaw) - observedForward * sin(yaw)
            val newForward = (observedRight - translation) * sin(yaw) + observedForward * cos(yaw)
            assertTrue(kotlin.math.abs(newRight - observedRight) > .1)
            val col = ((newRight + 2) * 10).toInt()
            val row = (newForward * 10).toInt()
            val u = (320 + 400 * newRight / newForward).toInt()
            val v = (240 + 400 * 1.2 / newForward).toInt()
            assertTrue(kotlin.math.abs(u - oldU) > 15)
            scene.labels[v * scene.mapWidth + u] = CityscapesTaxonomy.BUILDING

            val tracker = MotionTracker()
            tracker.onGameRotationVector(measured, floatArrayOf(.70710677f, 0f, 0f, .70710677f))
            val halfYaw = -yaw / 2
            val tilt = cos(Math.PI / 4)
            tracker.onGameRotationVector(capture, floatArrayOf(
                (cos(halfYaw) * tilt).toFloat(), (sin(halfYaw) * tilt).toFloat(),
                (sin(halfYaw) * tilt).toFloat(), (cos(halfYaw) * tilt).toFloat(),
            ))
            val from = tracker.state(measured, isStationary = false)
            val to = tracker.state(capture, isStationary = false)

            val view = DepthViewGeometry(checkNotNull(scene.geometry.intrinsics), Vec3(0f, 1f, 0f), depthWidth, depthHeight,
                levelPhone, TimeBasis.CAMERA_REALTIME, GeometryQuality.Ok)
            assertEquals(oldU.toFloat(), view.cellCenterU(depthX), 0f)
            assertEquals(oldV.toFloat(), view.cellCenterV(depthY), 0f)
            val ground = BinaryMask(depthWidth, depthHeight).apply { set(depthX, depthY, true) }
            val fit = GroundObservation(measured, GroundFitValidity.VALID,
                ground, BinaryMask(depthWidth, depthHeight), BinaryMask(depthWidth, depthHeight), 1f, 0f, 1f, true, 1f)
            val cells = DepthCells(DepthUnit.METERS, depthWidth, depthHeight,
                FloatArray(depthWidth * depthHeight) { Float.NaN }.apply { this[depthIndex] = observedForward.toFloat() },
                FloatArray(depthWidth * depthHeight) { Float.NaN }.apply { this[depthIndex] = 0f },
                FloatArray(depthWidth * depthHeight).apply { this[depthIndex] = 1f })
            val reading = DepthReading(CapabilityStatus.Available, DepthKind.METRIC_DEPTH, ScaleBasis.MEASURED,
                ObservationId(1, 1), DepthReadingTimes(measured, measured, key, capture, capture - measured, 200_000_000L),
                view, fit, cells)
            val (ignored, absent) = scene.admit(DepthReading.NOT_CONFIGURED, from, to)
            assertEquals(setOf(DepthRejection.NOT_CONFIGURED), ignored.reasons)
            val (rejection, present) = scene.admit(reading, from, to)
            assertEquals(GroundFitValidity.VALID, rejection.fit)
            assertEquals(key, rejection.key)
            assertEquals(turn.toFloat(), checkNotNull(rejection.headingDeltaDegrees), .001f)
            assertEquals(setOf(DepthRejection.SUPPORT_UNPROVEN, DepthRejection.INPUT_UNPROVEN,
                DepthRejection.TRANSLATION_UNKNOWN, DepthRejection.ROTATION_UNCERTAINTY_UNKNOWN), rejection.reasons)
            assertEquals(SurfaceEvidence.UNKNOWN, absent[row * LocalGroundGrid.COLUMNS + col].evidence.surface)
            assertTrue(absent.any { it.evidence.surface == SurfaceEvidence.SEM_SURFACE })
            assertEquals(absent, present)
        }
    }

    @Test
    fun `actual tensor crop catches an offset blocker and rejects unsupported padding`() {
        val scene = Scene(width = 1280, height = 722, focal = 600f, phone = PhoneGeometrySettings(1.15f, HeightSource.PRESET),
            mapWidth = 640, mapHeight = 361)
        val input = InputImageMapping(
            1280, 722, 640, 640,
            InputImageMapping.Axis(.5, 0.0, 0, 640), InputImageMapping.Axis(.5, 139.5, 140, 501), 0,
        )
        val mapping = SemanticImageMapping(input, 640, 640, SemanticContentRegion(0, 140, 640, 361))
        assertEquals(SurfaceEvidence.SEM_SURFACE, scene.build(mapping = mapping).cell(31, 20).evidence.surface)
        scene.labels[287 * 640 + 325] = CityscapesTaxonomy.BUILDING
        assertEquals(SurfaceEvidence.UNKNOWN, scene.build(mapping = mapping).cell(31, 20).evidence.surface)
        val footprint = checkNotNull(WorldSemanticFootprint.from(mapping, scene.mask))
        assertNull(footprint.mapArea(ImageFootprint(Bound(200.0, 210.0), Bound(719.0, 721.0))))
        val opencv = mapping.copy(input = input.copy(y = InputImageMapping.Axis(.5, 139.0, 139, 500)))
        assertEquals(SurfaceEvidence.UNKNOWN, scene.build(mapping = opencv).cell(31, 20).evidence.surface)
    }

    @Test
    fun `known raw detections veto the footprint even before tracking`() {
        val scene = Scene()
        val detection = scene.detection()
        val known = scene.build(detections = listOf(detection)).cell(30, 20)
        assertEquals(SurfaceEvidence.UNKNOWN, known.evidence.surface)
        assertEquals(listOf(0), known.conflicts.imageDetections.map { it.detectionIndex })
        assertEquals(listOf(WorldRiskId.RawDetection(0)), known.unlocalizedRisks.map { it.id })
        assertEquals(SurfaceEvidence.SEM_SURFACE, scene.build(detections = listOf(detection)).cell(30, 34).evidence.surface)
        val unknownRange = scene.build(detections = listOf(detection), detMapping = null).cell(30, 34)
        assertEquals(SurfaceEvidence.UNKNOWN, unknownRange.evidence.surface)
        assertEquals(UnlocalizedReason.IMAGE_RANGE_UNPROVEN, unknownRange.unlocalizedRisks.single().reason)
    }

    @Test
    fun `contact requires its actual ground probe and retains raw risk when calibration fails`() {
        val scene = Scene()
        val detection = scene.detection()
        val track = scene.track(detection)
        assertEquals(ContactValidity.VALID, track.distanceEstimate?.contact?.validity)
        val current = scene.build(detections = listOf(detection), tracks = listOf(track))
        val contact = current.cell(30, 20)
        assertTrue(contact.evidence.contacts.any { it.trackId == track.id })
        assertTrue(contact.conflicts.imageDetections.isNotEmpty())
        val absent = scene.build(detections = listOf(detection), tracks = listOf(track), proof = null).cell(30, 20)
        assertTrue(absent.evidence.contacts.isEmpty())
        assertTrue(absent.conflicts.imageDetections.isEmpty())
        assertEquals(setOf(WorldRiskId.RawDetection(0), WorldRiskId.Track(track.id)), absent.unlocalizedRisks.map { it.id }.toSet())

        val cached = scene.build(detections = listOf(detection), tracks = listOf(track), cached = true).cell(30, 20)
        assertTrue(cached.evidence.contacts.isEmpty())
        assertEquals(UnlocalizedReason.GROUND_PROBE_UNPROVEN, cached.unlocalizedRisks.last().reason)
        assertTrue(cached.conflicts.imageDetections.isNotEmpty())

        val narrower = scene.build(detections = listOf(detection), tracks = listOf(track.copy(measurement = null))).cell(30, 34)
        assertEquals(SurfaceEvidence.UNKNOWN, narrower.evidence.surface)
        assertTrue(narrower.unlocalizedRisks.any { it.reason == UnlocalizedReason.STALE_TRACK })
    }

    @Test
    fun `consumed table reads the carved mask and old snapshots outlive mask reuse`() {
        val scene = Scene()
        val detect = scene.detection()
        val track = scene.track(detect)
        val clear = scene.build()
        val marked = scene.build(detections = listOf(detect), tracks = listOf(track))
        val area = PixelArea(320, 390, 321, 391)
        assertEquals(0, scratch.passable.accepted(area))
        assertEquals(1, scratch.passable.accepted(PixelArea(320, 450, 321, 451)))
        assertFalse(marked.cell(30, 20).evidence.surface == SurfaceEvidence.SEM_SURFACE)
        assertEquals(SurfaceEvidence.SEM_SURFACE, clear.cell(30, 20).evidence.surface)
    }

    @Test
    fun `valid field fits from occlusion recordings cannot change current cell evidence`() {
        val location = System.getProperty("sailens.replay.depth").orEmpty()
        assumeTrue("set -PreplayDepth to the M11 recordings folder", location.isNotBlank())
        val folder = File(location)
        val budget = File(folder.parentFile, "budget/captures")
        val fieldCases = listOf(
            FieldCase(File(folder, "e103ec99-8669-476e-a62d-d0a757be5495"), 1L, 14_478L),
            FieldCase(File(folder, "4f6ae415-193b-432b-971f-45045e315b2a"), 1L, 16_797L),
            FieldCase(File(folder, "d3bd91c2-441c-486b-94df-0670889b39a5")),
            FieldCase(File(folder, "8324db34-d7f7-4f42-9165-d634c63a20be")),
            FieldCase(File(folder, "cf7e3582-7ad1-4504-bedd-d775212b7552")),
            FieldCase(File(budget, "3b872c68-e417-43b2-8e9a-281d8a4925c2"), valid = false),
            FieldCase(File(budget, "539208ad-bdf5-4f11-a528-6384c9030615"), valid = false),
            FieldCase(File(budget, "e5467dc5-41ef-4f56-aa0b-f4b0e0975841"), valid = false),
        )
        assumeTrue("set -PreplayDepth to the M11 recordings folder", fieldCases.first().path.isDirectory)
        for ((path, runId, seq, valid) in fieldCases) {
            val id = path.name
            val capture = CaptureSessionReader.read(path).orThrow()
            var compared = false
            val replay = DepthEvidenceReplay(observer = { fit ->
                val record = fit.record
                if (!compared && fit.role == DepthReplayRole.EXACT && fit.observation.isValid == valid &&
                    (runId == null || record.runId == runId && record.frameSequence == seq)
                ) {
                    val geometry = record.geometry.toGeometry()
                    val view = DepthViewGeometry(
                        checkNotNull(geometry.intrinsics), checkNotNull(geometry.gravityInCamera),
                        record.gridWidth, record.gridHeight, geometry.phone, geometry.timeBasis, geometry.quality,
                    )
                    val disparity = DisparityMap(record.output.width, record.output.height,
                        CaptureSessionReader.depthOutput(capture, record), record.transform.toTransform())
                    val cells = DepthCells.of(disparity, fit.observation, view)
                    if (valid) checkNotNull(cells)
                    val main = Scene(captureNanos = checkNotNull(geometry.frameTimeNanos))
                    val snapshot = DepthGeometrySnapshot(
                        generation = record.generation, state = DepthLaneState.RUNNING, runId = record.runId,
                        observation = fit.observation, frameSequence = record.frameSequence, frameTimeNanos = geometry.frameTimeNanos,
                        timeBasis = geometry.timeBasis, geometryQuality = geometry.quality, periodMs = 333,
                        kind = DepthKind.RELATIVE_DISPARITY, measurementTimeNanos = geometry.frameTimeNanos,
                        viewTimeNanos = geometry.frameTimeNanos, measurementKey = MeasurementKey(record.generation, record.runId),
                        view = view, cells = cells,
                    )
                    val reading = DepthReading.of(snapshot, CapabilityStatus.Available, main.geometry, maxAgePeriods = 1)
                    val bits = cells?.copyOpticalDepth()?.map(Float::toRawBits)
                    val box = main.detection()
                    val tracks = listOf(main.track(box))
                    val absent = main.build(detections = listOf(box), tracks = tracks)
                    val present = main.build(detections = listOf(box), tracks = tracks, depth = reading)
                    if (valid) {
                        assertTrue(id, present.depth.reasons.contains(DepthRejection.INPUT_UNPROVEN))
                        assertTrue(id, present.depth.reasons.contains(DepthRejection.SUPPORT_UNPROVEN))
                        assertTrue(id, present.depth.reasons.contains(DepthRejection.TRANSLATION_UNKNOWN))
                    } else {
                        assertEquals(id, setOf(DepthRejection.FIT_INVALID), present.depth.reasons)
                    }
                    assertTrue(id, absent.cell(30, 20).evidence.contacts.isNotEmpty())
                    assertEquals(SurfaceEvidence.SEM_SURFACE, absent.cell(30, 34).evidence.surface)
                    for (row in 0 until LocalGroundGrid.ROWS) for (col in 0 until LocalGroundGrid.COLUMNS) {
                        assertEquals(absent.cell(row, col), present.cell(row, col))
                    }
                    assertEquals(bits, cells?.copyOpticalDepth()?.map(Float::toRawBits))
                    compared = true
                }
            })
            val stats = capture.manifest.depthEvidence?.stats
            replay.replay(
                capture.depthEvidence, { CaptureSessionReader.depthOutput(capture, it) },
                recordedRule = capture.manifest.depthEvidence?.groundFitRule,
                firstDropped = stats?.firstDroppedRunId?.let {
                    DepthEvidenceReplay.DroppedRun(checkNotNull(stats.firstDroppedGeneration), it)
                },
            )
            assertTrue("no exact fitted control for $id", compared)
        }
    }

    @Test
    fun `repeated submissions preserve the first actual build and both spatial classifications`() {
        val unknown = Scene().build(proof = null)
        val visible = Scene().build()
        val blocked = Scene().apply { labels.fill(CityscapesTaxonomy.BUILDING) }.build()
        assertFalse(checkNotNull(unknown.snapshot).projectable)
        assertTrue(checkNotNull(blocked.snapshot).projectable)
        assertFalse(checkNotNull(blocked.snapshot).hasSpatialEvidence)
        assertTrue(checkNotNull(visible.snapshot).hasSpatialEvidence)

        val otherRun = UUID.randomUUID()
        fun counts(first: BuiltWorld?, repeated: BuiltWorld?): WorldBuildAccounting.Counts {
            val ledger = WorldBuildAccounting()
            for (id in listOf(run, otherRun)) {
                for (sequence in 1L..50L) {
                    ledger.record(id, sequence, if (id == run && sequence == 1L) first else unknown)
                }
            }
            ledger.record(run, 1L, repeated)
            return ledger.counts()
        }
        assertEquals(WorldBuildAccounting.Counts(101, 100, 100, 0, 0), counts(unknown, null))
        assertEquals(WorldBuildAccounting.Counts(101, 100, 99, 0, 0), counts(null, unknown))
        assertEquals(WorldBuildAccounting.Counts(101, 100, 100, 0, 0), counts(unknown, blocked))
        assertEquals(WorldBuildAccounting.Counts(101, 100, 100, 1, 0), counts(blocked, unknown))
        assertEquals(WorldBuildAccounting.Counts(101, 100, 100, 0, 0), counts(unknown, visible))
        assertEquals(WorldBuildAccounting.Counts(101, 100, 100, 1, 1), counts(visible, unknown))
    }

    private data class FieldCase(val path: File, val runId: Long? = null, val seq: Long? = null, val valid: Boolean = true)

    private fun BuiltWorld.cell(row: Int, col: Int) =
        checkNotNull(snapshot).read(row * LocalGroundGrid.COLUMNS + col, allowSurface = true)

    private inner class Scene(
        val width: Int = 640,
        val height: Int = 480,
        val focal: Float = 400f,
        val phone: PhoneGeometrySettings = levelPhone,
        val mapWidth: Int = width,
        val mapHeight: Int = height,
        val skew: Float = 0f,
        transform: List<Float> = sensorToBuffer,
        captureNanos: Long = 1_010_000_000L,
    ) {
        val frame = ImageFrame(width, height, byteArrayOf(), ImagePixelFormat.RGBA_8888, captureNanos, 0, 1,
            receivedElapsedRealtimeNanos = captureNanos + 1_000_000L, sourceGeometry = FrameSourceGeometry(transform, 0, 0, width, height))
        val labels = IntArray(mapWidth * mapHeight) { CityscapesTaxonomy.ROAD }
        val mask = SegmentationMask(mapWidth, mapHeight, labels)
        val facts = FrameFacts.of(frame)
        val camera = CameraFacts(0, CameraClock.REALTIME, listOf(focal, focal, width / 2f, height / 2f, skew),
            SensorRect(0, 0, width, height), null, width, height, null, null, emptyList())
        val geometry = CameraGeometryResolver().resolve(camera, facts, { SensorSample(it, Vec3(0f, 1f, 0f)) }, phone)
        val identity = PerceptionFrameIdentity(frame.sequenceNumber, frame.timestamp)
        val input = InputImageMapping(width, height, width, height,
            InputImageMapping.Axis(1.0, 0.0, 0, width), InputImageMapping.Axis(1.0, 0.0, 0, height), 0)

        fun proof(
            frame: ImageFrame = this.frame,
            axes: List<Vec3> = this@LocalWorldModelBuilderTest.axes,
            sourceCentre: WorldImageProof.OpticalCentre? = origin,
        ) = WorldImageProof(frame, camera, true, WorldImageProof.Pinhole(width, height, focal, focal, width / 2f, height / 2f, skew),
            true, axes, sourceCentre, origin)

        fun detection() = ObstacleDetection(0, "person", .88f, NormalizedRect(.42f, .53f, .16f, .30f), ObstacleCategory.PERSON)

        fun track(detection: ObstacleDetection): DetectedObstacle {
            val frameContext = DistanceFrame(geometry, segmentation = mask, semanticSource = identity)
            val depth = DefaultDepthRepository(ImagePositionDepthEstimator(), GroundContactDistanceEstimator(semantics))
            val raw = ObstacleExtractor(config, semantics).extractFromDetections(listOf(detection)) { box, category ->
                depth.estimateDistance(box, category, frameContext)
            }
            return ObstacleTracker(config).update(raw, 1_020L, ObstacleSourceFrame(1, frame.timestamp, identity)).single()
        }

        fun build(
            proof: WorldImageProof? = proof(),
            mapping: SemanticImageMapping? = SemanticImageMapping(input, width, height, SemanticContentRegion(0, 0, width, height)),
            detections: List<ObstacleDetection> = emptyList(),
            tracks: List<DetectedObstacle> = emptyList(),
            detMapping: InputImageMapping? = input,
            cached: Boolean = false,
            depth: DepthReading = DepthReading.NOT_CONFIGURED,
            viewMotion: MotionState? = null,
            frameMotion: MotionState? = null,
            runId: UUID = run,
            perceptionConfig: PerceptionConfig = config,
        ): BuiltWorld {
            val analysis = analyzer.analyze(mask, stats = null)
            val semSource = if (cached) PerceptionFrameIdentity(0, frame.timestamp - 10) else identity
            val evidence = PerceptionFrameEvidence(identity, semSource, mapping, detMapping)
            val perception = PerceptionResult(frame.timestamp, analysis.passableMask, analysis.obstacleMask, tracks,
                detections, analysis.bottomStats, analysis, 0L, obstacleRunKind = ObstacleRunKind.DETECTION, frameEvidence = evidence)
            val occlusion = ObstacleOcclusionAnalyzer(perceptionConfig).analyze(analysis.passableMask, tracks)
            return builder.build(runId, frame, facts, camera, geometry, proof, perception, occlusion.effectivePassableMask,
                perceptionConfig, BaselineQualification.Qualified, depth, viewMotion, frameMotion, scratch)
        }

        fun admit(
            reading: DepthReading,
            from: MotionState,
            to: MotionState,
        ): Pair<RejectedDepth, List<CurrentCellQuery.Observed>> {
            val activity = AnalysisActivity({ frame.timestamp - 20_000_000L }) { true }
            val grant = checkNotNull(activity.grantAfterActivation())
            val source = object : WorldAnalysisSource {
                override fun currentGrant() = activity.currentGrant()
                override fun evidenceFor(frame: ImageFrame) = WorldFrameAttestation(frame, grant, proof())
            }
            val owner = LocalWorldOwner(source) { frame.receivedElapsedRealtimeNanos + 1_000_000L }
            val built = build(depth = reading, viewMotion = from, frameMotion = to, runId = owner.run.id)
            val publication = WorldPublication(owner.run, frame.sequenceNumber, frame.timestamp,
                frame.receivedElapsedRealtimeNanos, geometry.timeBasis, grant, built)
            owner.observeSafety(LocalWorldOwner.PublicationSafety(true, true), stopBegan = false)
            owner.finishResult(owner.beginResult(frame.sequenceNumber, publication), publication, BaselineQualification.Qualified)
            val cells = List(LocalGroundGrid.ROWS * LocalGroundGrid.COLUMNS) { index ->
                val cell = owner.reader.currentCell(index / LocalGroundGrid.COLUMNS, index % LocalGroundGrid.COLUMNS)
                    as CurrentCellQuery.Observed
                cell.copy(frame = cell.frame.copy(run = run))
            }
            return built.depth to cells
        }
    }
}
