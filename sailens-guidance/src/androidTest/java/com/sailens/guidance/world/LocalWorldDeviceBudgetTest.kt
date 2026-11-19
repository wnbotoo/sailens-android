package com.sailens.guidance.world

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.core.frame.FrameSourceGeometry
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.DefaultDepthRepository
import com.sailens.guidance.depth.DistanceFrame
import com.sailens.guidance.depth.GroundContactDistanceEstimator
import com.sailens.guidance.depth.ImagePositionDepthEstimator
import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.geometry.CameraClock
import com.sailens.guidance.geometry.CameraFacts
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorRect
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.kernel.NativeConnectivityStatsExtractor
import com.sailens.guidance.kernel.NativeGuidanceLibrary
import com.sailens.guidance.kernel.SilentLogService
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.ObstacleRunKind
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.ObstacleSourceFrame
import com.sailens.guidance.model.perception.PerceptionFrameEvidence
import com.sailens.guidance.model.perception.PerceptionFrameIdentity
import com.sailens.guidance.model.perception.PerceptionResult
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.processor.analysis.ObstacleOcclusionAnalyzer
import com.sailens.guidance.processor.perception.ObstacleExtractor
import com.sailens.guidance.processor.perception.ObstacleTracker
import com.sailens.guidance.processor.perception.SegmentationAnalyzer
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.runtime.InputImageMapping
import com.sailens.vision.semantic.SemanticContentRegion
import com.sailens.vision.semantic.SemanticImageMapping
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalWorldDeviceBudgetTest {
    @Test
    fun fullCurrentDetectionsAndContactsAt640x480() {
        measure(w = 640, h = 480, oldCohorts = 0)
    }

    @Test
    fun fullCurrentDetectionsAndContactsAtCameraResolution() {
        measure(w = 960, h = 540, oldCohorts = 0)
    }

    @Test
    fun fullCurrentAndRetainedDetectionsAtCameraResolution() {
        // A track's birth uses one miss until its first match. Four cohorts can mature on
        // this cadence with the default tracker policy.
        measure(w = 960, h = 540, oldCohorts = PerceptionConfig().trackerMaxMissedFrames - 2)
    }

    @Test
    fun fullTrackCapacityWithOneFrameTracks() {
        val config = PerceptionConfig(trackerMinStableFrames = 1)
        measure(w = 960, h = 540, oldCohorts = config.trackerMaxMissedFrames - 1, config, primeFirstCohort = true)
    }

    @Test
    fun fragmentedTerrainAndRoadWithFullContactWorkAtCameraResolution() {
        measure(w = 960, h = 540, oldCohorts = 0, terrainBlockPixels = 4)
    }

    @Test
    fun alternatingPixelLabelsStressMaskPackingAtCameraResolution() {
        measure(w = 960, h = 540, oldCohorts = 0, terrainBlockPixels = 1)
    }

    private fun measure(
        w: Int,
        h: Int,
        oldCohorts: Int,
        config: PerceptionConfig = PerceptionConfig(),
        primeFirstCohort: Boolean = false,
        terrainBlockPixels: Int = 0,
    ) {
        assertTrue("the device must load the ARM64 world kernel", NativeGuidanceLibrary.isAvailable)
        val semantics = CityscapesNavigationSemantics
        val now = SystemClock.elapsedRealtimeNanos()
        val cycleLength = (oldCohorts + 1) * config.trackerMinStableFrames
        val sequence = (cycleLength + if (primeFirstCohort) 1 else 0).toLong()
        val identity = PerceptionFrameIdentity(sequence, now)
        val frame = ImageFrame(w, h, byteArrayOf(), ImagePixelFormat.RGBA_8888, now, 0, sequence,
            receivedElapsedRealtimeNanos = now, sourceGeometry = FrameSourceGeometry(
                listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 0, 0, w, h,
            ))
        val fx = w * .625f
        val fy = h * (5f / 6f)
        val cx = w / 2f
        val cy = h / 2f
        val camera = CameraFacts(0, CameraClock.REALTIME, listOf(fx, fy, cx, cy, 0f),
            SensorRect(0, 0, w, h), null, w, h, null, null, emptyList())
        val facts = FrameFacts.of(frame)
        val geometry = CameraGeometryResolver().resolve(camera, facts, { SensorSample(it, Vec3(0f, 1f, 0f)) },
            PhoneGeometrySettings(1.2f, HeightSource.PRESET))
        val centre = WorldImageProof.OpticalCentre(Any(), CameraVector(0.0, 0.0, 0.0))
        val proof = WorldImageProof(frame, camera, true, WorldImageProof.Pinhole(w, h, fx, fy, cx, cy, 0f), true,
            listOf(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f)), centre, centre)
        val input = InputImageMapping(w, h, w, h, InputImageMapping.Axis(1.0, 0.0, 0, w),
            InputImageMapping.Axis(1.0, 0.0, 0, h), 0)
        // Finely mixed road and terrain remain known ground for the contact probes. Leave a
        // wider approach in the middle so the positive surface path is also exercised.
        val labels = SegmentationMask(w, h, IntArray(w * h) { pixel ->
            val x = pixel % w
            val y = pixel / w
            if (terrainBlockPixels > 0 && x !in w * 2 / 5 until w * 3 / 5 &&
                (x / terrainBlockPixels + y / terrainBlockPixels) % 2 == 0
            ) CityscapesTaxonomy.TERRAIN else CityscapesTaxonomy.ROAD
        })
        val analysisConfig = AnalysisConfig()
        val analysis = SegmentationAnalyzer(analysisConfig, semantics).analyze(labels, stats = null)
        fun detectionsAt(y: Float, height: Float) = List(config.maxObstacles) { i ->
            ObstacleDetection(0, "person", .9f, NormalizedRect(.08f + .08f * i, y, .039f, height), ObstacleCategory.PERSON)
        }
        val detections = detectionsAt(.55f, .22f)
        val distances = DefaultDepthRepository(ImagePositionDepthEstimator(), GroundContactDistanceEstimator(semantics))
        val extractor = ObstacleExtractor(config, semantics)
        val tracker = ObstacleTracker(config)
        val cohorts = List(oldCohorts) { detectionsAt(.45f + .025f * it, .019f) } + listOf(detections)
        val cadence = 100_000_000L
        var tracks = emptyList<DetectedObstacle>()
        for (seq in 1..sequence) {
            val captured = now - (sequence - seq) * cadence
            val source = if (seq == sequence) identity else PerceptionFrameIdentity(seq, captured)
            val groundContext = DistanceFrame(geometry, segmentation = labels, semanticSource = source)
            val cycleIndex = if (primeFirstCohort) (seq - 2).coerceAtLeast(0) else seq - 1
            val raw = extractor.extractFromDetections(cohorts[(cycleIndex % cohorts.size).toInt()]) { box, category ->
                distances.estimateDistance(box, category, groundContext)
            }
            assertEquals(config.maxObstacles, raw.size)
            tracks = tracker.update(raw, captured / 1_000_000, ObstacleSourceFrame(seq, captured, source))
        }
        val evidence = PerceptionFrameEvidence(identity, identity,
            SemanticImageMapping(input, w, h, SemanticContentRegion(0, 0, w, h)), input)
        val perception = PerceptionResult(now, analysis.passableMask, analysis.obstacleMask, tracks, detections,
            analysis.bottomStats, analysis, 0L, obstacleRunKind = ObstacleRunKind.DETECTION, frameEvidence = evidence)
        val effective = ObstacleOcclusionAnalyzer(config).analyze(analysis.passableMask, tracks).effectivePassableMask
        // This is the same effective mask and native connectivity stage the frame passes through
        // before world construction. The builder can borrow its already materialized bits.
        checkNotNull(NativeConnectivityStatsExtractor(analysisConfig, SilentLogService).extract(effective))
        var runs = 0
        var previous = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val passable = effective.get(x, y)
                if (passable && !previous) runs++
                previous = passable
            }
        }
        if (terrainBlockPixels > 0) assertTrue("the effective mask must stay fragmented", runs > w * h / (4 * terrainBlockPixels))
        val scratch = LocalWorldModelBuilder.Scratch()
        val builder = LocalWorldModelBuilder(semantics)
        val run = UUID.randomUUID()

        fun step(proof: WorldImageProof?, work: LocalWorldModelBuilder.Scratch = scratch) = checkNotNull(builder.build(
            run, frame, facts, camera, geometry, proof, perception, effective, config, BaselineQualification.Qualified,
            DepthReading.NOT_CONFIGURED, null, null, work).snapshot)

        assertEquals(config.maxObstacles, detections.size)
        val fresh = tracks.filter { it.measurement?.frameIdentity === identity }
        val unknown = tracks.count { it.measurement?.frameIdentity !== identity }
        assertEquals(config.maxObstacles, fresh.size)
        assertEquals(oldCohorts * config.maxObstacles, unknown)
        val firstStart = SystemClock.elapsedRealtimeNanos()
        val firstCpu = Debug.threadCpuTimeNanos()
        val qualified = step(proof)
        val firstCpuDuration = Debug.threadCpuTimeNanos() - firstCpu
        val firstWall = SystemClock.elapsedRealtimeNanos() - firstStart
        val reported = buildSet {
            for (index in 0 until LocalGroundGrid.ROWS * LocalGroundGrid.COLUMNS) {
                addAll(qualified.read(index, allowSurface = false).evidence.contacts.map { it.trackId })
            }
        }
        assertEquals(fresh.map { it.id }.toSet(), reported)
        assertEquals(unknown, qualified.read(0, allowSurface = false).unlocalizedRisks.count { it.reason == UnlocalizedReason.STALE_TRACK })
        if (terrainBlockPixels > 0) assertTrue((0 until LocalGroundGrid.ROWS * LocalGroundGrid.COLUMNS).any {
            qualified.read(it, allowSurface = true).evidence.surface == SurfaceEvidence.SEM_SURFACE
        })
        val snapshotBound = WorldSnapshotBudget.retainedUpperBound(detections.size, tracks.size)
        assertTrue(snapshotBound <= WorldSnapshotBudget.LIMIT)
        val storage = scratch.passable.prefixSums()
        val rowStorage = scratch.grid.rows
        repeat(240) { step(if (it % 2 == 0) proof else null) }
        assertSame(storage, scratch.passable.prefixSums())
        assertSame(rowStorage, scratch.grid.rows)
        val workload = "${w}x$h D=${detections.size} T=${reported.size} U=$unknown " +
            "terrainBlockPx=$terrainBlockPixels effectiveRuns=$runs"

        val eligibleWall = LongArray(160)
        val eligibleCpu = LongArray(160)
        val deniedWall = LongArray(160)
        val deniedCpu = LongArray(160)
        fun timed(index: Int, enabled: Boolean) {
            val wallStart = SystemClock.elapsedRealtimeNanos()
            val cpuStart = Debug.threadCpuTimeNanos()
            step(if (enabled) proof else null)
            val cpu = Debug.threadCpuTimeNanos() - cpuStart
            val wall = SystemClock.elapsedRealtimeNanos() - wallStart
            (if (enabled) eligibleWall else deniedWall)[index] = wall
            (if (enabled) eligibleCpu else deniedCpu)[index] = cpu
        }
        for (i in eligibleWall.indices) timed(i, true)
        android.util.Log.i(TAG, "builder $workload firstBuild wall=${firstWall}ns cpu=${firstCpuDuration}ns " +
            "warmed wall=${summary(eligibleWall)} cpu=${summary(eligibleCpu)} snapshotMax=${snapshotBound}B")
        val warmed95 = percentile95(eligibleWall)
        val beforeBytes = allocatedBytes()
        repeat(ALLOCATION_BUILDS) { step(proof) }
        val afterBytes = allocatedBytes()
        android.util.Log.i(TAG, "allocation $workload warmed artProcessBytesPerBuild=${perOperation(beforeBytes, afterBytes, ALLOCATION_BUILDS)}")
        for (i in eligibleWall.indices) {
            timed(i, i % 2 == 0)
            timed(i, i % 2 != 0)
        }
        android.util.Log.i(TAG,
            "builder $workload paired " +
                "qualified wall=${summary(eligibleWall)} cpu=${summary(eligibleCpu)} " +
                "withoutSpatialProof wall=${summary(deniedWall)} cpu=${summary(deniedCpu)}")
        val phaseWall = Array(WorldBuildPhase.entries.size) { LongArray(120) }
        val phaseCpu = Array(WorldBuildPhase.entries.size) { LongArray(120) }
        var stageSample = -1
        var lastWall = 0L
        var lastCpu = 0L
        val profiled = LocalWorldModelBuilder.Scratch { phase ->
            val wall = SystemClock.elapsedRealtimeNanos()
            val cpu = Debug.threadCpuTimeNanos()
            if (phase != WorldBuildPhase.BEGIN && stageSample >= 0) {
                phaseWall[phase.ordinal][stageSample] = wall - lastWall
                phaseCpu[phase.ordinal][stageSample] = cpu - lastCpu
            }
            lastWall = wall
            lastCpu = cpu
        }
        repeat(20) { step(proof, profiled) }
        repeat(120) {
            stageSample = it
            step(proof, profiled)
        }
        for (phase in WorldBuildPhase.entries.drop(1)) {
            android.util.Log.i(TAG,
                "stage $workload ${phase.name} wall=${summary(phaseWall[phase.ordinal])} cpu=${summary(phaseCpu[phase.ordinal])}")
        }
        assertTrue("$workload warmed wall p95 ${warmed95}ns exceeds 2ms", warmed95 <= 2_000_000)
        assertTrue("$workload paired wall p95 ${percentile95(eligibleWall)}ns exceeds 2ms", percentile95(eligibleWall) <= 2_000_000)
    }

    @Test
    fun measureOwnerAdmissionAndCurrentCell() {
        var now = SystemClock.elapsedRealtimeNanos()
        val grant = AnalysisGrant(now)
        val source = object : WorldAnalysisSource {
            override fun currentGrant() = grant
            override fun evidenceFor(frame: ImageFrame): WorldFrameAttestation? = null
        }
        val owner = LocalWorldOwner(source) { now }
        owner.observeSafety(LocalWorldOwner.PublicationSafety(running = true, clearView = true), stopBegan = false)
        val admissions = LongArray(160)
        val admitCpu = LongArray(admissions.size)
        val queries = LongArray(admissions.size)
        val queryCpu = LongArray(admissions.size)
        val row = 30
        val col = 20
        val cells = LocalWorldCells(emptyList(), emptyList()).also { it.surface[row * LocalGroundGrid.COLUMNS + col] = true }
        val depth = RejectedDepth(setOf(DepthRejection.NOT_CONFIGURED))
        val beforeBytes = allocatedBytes()
        repeat(100 + admissions.size) { sample ->
            val sequence = sample.toLong() + 1
            now += 33_000_000
            val capture = now - 5_000_000
            val reference = WorldFrameReference(owner.run.id, sequence, capture, GeometryQuality.Ok, null)
            val built = BuiltWorld(LocalWorldSnapshot(reference, emptyList(), cells, projectable = true), depth, null)
            val update = WorldPublication(owner.run, sequence, capture, capture + 3_000_000, TimeBasis.CAMERA_REALTIME, grant, built)
            val start = SystemClock.elapsedRealtimeNanos()
            val cpu = Debug.threadCpuTimeNanos()
            val admission = owner.beginResult(sequence, update)
            owner.finishResult(admission, update, BaselineQualification.Qualified)
            val admitCpuTime = Debug.threadCpuTimeNanos() - cpu
            val admitWallTime = SystemClock.elapsedRealtimeNanos() - start
            val queryStart = SystemClock.elapsedRealtimeNanos()
            val startCpu = Debug.threadCpuTimeNanos()
            val result = owner.reader.currentCell(row, col)
            val queryCpuTime = Debug.threadCpuTimeNanos() - startCpu
            val queryWallTime = SystemClock.elapsedRealtimeNanos() - queryStart
            assertEquals(SurfaceEvidence.SEM_SURFACE, (result as CurrentCellQuery.Observed).evidence.surface)
            if (sample >= 100) {
                val i = sample - 100
                admissions[i] = admitWallTime
                admitCpu[i] = admitCpuTime
                queries[i] = queryWallTime
                queryCpu[i] = queryCpuTime
            }
        }
        val afterBytes = allocatedBytes()
        android.util.Log.i(TAG, "owner wall=${summary(admissions)} cpu=${summary(admitCpu)} " +
            "query wall=${summary(queries)} cpu=${summary(queryCpu)} " +
            "artProcessBytesPerFixtureStep=${perOperation(beforeBytes, afterBytes, 100 + admissions.size)}")
        owner.close()
    }

    private fun allocatedBytes() = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()

    private fun perOperation(before: Long?, after: Long?, count: Int): Long? =
        if (before != null && after != null) (after - before) / count else null

    private fun summary(samples: LongArray): String {
        samples.sort()
        return "[p50=${samples[samples.size / 2]} p95=${percentile95(samples)} max=${samples.last()}]ns"
    }

    private fun percentile95(sorted: LongArray) = sorted[sorted.size * 95 / 100 - 1]

    private companion object {
        const val TAG = "SailensWorldBudget"
        const val ALLOCATION_BUILDS = 160
    }
}
