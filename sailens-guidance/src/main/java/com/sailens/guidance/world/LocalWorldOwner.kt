package com.sailens.guidance.world

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.safety.BaselineQualification
import java.lang.ref.WeakReference
import java.util.UUID

/** Producer-side capability. A new object identifies each continuous analysis interval. */
internal class AnalysisGrant(val openedAtNanos: Long, val number: Long = 1L)

internal class WorldFrameAttestation(frame: ImageFrame, val grant: AnalysisGrant?, val proof: WorldImageProof?) {
    private val sourceFrame = WeakReference(frame)
    fun belongsTo(frame: ImageFrame) = sourceFrame.get() === frame
}

/** A source able to revoke its grant synchronously before giving up analysis. */
internal interface WorldAnalysisSource {
    fun currentGrant(): AnalysisGrant?
    fun evidenceFor(frame: ImageFrame): WorldFrameAttestation?
}

/** Private handoff for the one official result of a run. */
class WorldPublication internal constructor(
    internal val run: LocalWorldRun,
    internal val sequence: Long,
    internal val capture: Long,
    internal val receipt: Long,
    internal val timeBasis: TimeBasis,
    internal val grant: AnalysisGrant?,
    internal val built: BuiltWorld?,
)

/** Handle a Guidance owner gives to the pipeline for this run. */
class LocalWorldRun internal constructor(internal val source: WorldAnalysisSource?) {
    internal val id: UUID = UUID.randomUUID()
    internal val scratch = LocalWorldModelBuilder.Scratch()
}

/** Owns the publication slot and the capture and receipt watermarks on the shell thread. */
class LocalWorldOwner internal constructor(
    source: WorldAnalysisSource?,
    private val nowNanos: () -> Long,
) {
    val run = LocalWorldRun(source)
    val reader = object : LocalWorldReader {
        override fun currentCell(row: Int, col: Int): CurrentCellQuery = readCell(row, col)
    }

    private val ownerThread = Thread.currentThread()
    private var closed = false
    private var grant: AnalysisGrant? = null
    private var grantVisibilityVersion = 0L
    private var watermarkGrant: AnalysisGrant? = null
    private var lastSequence: Long? = null
    private var captureWatermark = 0L
    private var receiptWatermark = 0L
    private var admitWatermark = 0L
    private var slot: LocalWorldSnapshot? = null
    private var unavailable = UnavailableReason.NO_FRAME
    private var safety = PublicationSafety()
    private var stopBoundary = 0L
    private var recovery: Recovery? = null

    /** What the existing monitor permits for the current cell; this never feeds the monitor. */
    class PublicationSafety(
        val running: Boolean = false,
        val clearView: Boolean = false,
        val hold: Boolean = false,
        val stopping: Boolean = false,
    )

    class Admission internal constructor(
        internal val owner: LocalWorldOwner,
        internal val sequence: Long,
        internal val grant: AnalysisGrant?,
        internal val grantVisibilityVersion: Long,
    )

    fun observeSafety(value: PublicationSafety, stopBegan: Boolean) {
        if (!ownsThread()) return
        safety = value
        if (stopBegan && value.stopping) {
            stopBoundary = maxOf(stopBoundary, nowNanos())
            recovery = Recovery(maxOf(stopBoundary, grant?.openedAtNanos ?: 0))
        }
    }

    fun close() {
        if (!ownsThread()) return
        closed = true
        slot = null
        grant = null
    }

    /** Clears the previous result before the monitor handles this result. */
    fun beginResult(sequence: Long, publication: WorldPublication?): Admission? {
        if (!ownsThread() || closed) return null
        if (publication != null && (publication.run !== run || publication.sequence != sequence)) return null
        if (lastSequence?.let { sequence <= it } == true) return null

        val active = syncGrant()
        lastSequence = sequence
        slot = null
        unavailable = UnavailableReason.NO_FRAME
        return Admission(this, sequence, active, grantVisibilityVersion)
    }

    /** Rechecks the owner, grant and sequence after the monitor and its callbacks. */
    fun finishResult(admission: Admission?, publication: WorldPublication?, baseline: BaselineQualification) {
        if (!ownsThread() || closed || admission == null || admission.owner !== this) return
        val active = syncGrant() ?: return
        val result = publication ?: return
        if (active !== admission.grant || active !== result.grant || result.run !== run ||
            grantVisibilityVersion != admission.grantVisibilityVersion ||
            lastSequence != admission.sequence || result.sequence != admission.sequence
        ) return
        val admit = nowNanos()
        val capture = result.capture
        val receipt = result.receipt
        if (result.timeBasis != TimeBasis.CAMERA_REALTIME || active.openedAtNanos <= 0L ||
            capture <= active.openedAtNanos || receipt < capture || admit < receipt || admit < admitWatermark
        ) return

        val advancing = capture > captureWatermark && receipt > receiptWatermark
        captureWatermark = maxOf(captureWatermark, capture)
        receiptWatermark = maxOf(receiptWatermark, receipt)
        admitWatermark = admit
        if (!advancing) return
        if (admit - capture >= MAX_AGE_NANOS) {
            unavailable = UnavailableReason.EXPIRED
            return
        }
        val snapshot = result.built?.snapshot
        if (snapshot == null) {
            unavailable = if (result.built == null) UnavailableReason.NO_FRAME else UnavailableReason.EVIDENCE_OVERFLOW
            return
        }
        if (snapshot.frame.run != run.id || snapshot.frame.sequence != result.sequence ||
            snapshot.frame.captureNanos != capture
        ) return
        slot = snapshot
        slotBaseline = baseline
        if (safety.running && safety.clearView && !safety.stopping && baseline == BaselineQualification.Qualified) {
            recovery?.count(capture, receipt)
        }
    }

    private var slotBaseline: BaselineQualification? = null

    private fun readCell(row: Int, col: Int): CurrentCellQuery {
        if (!ownsThread() || closed) return CurrentCellQuery.Unavailable(UnavailableReason.INACTIVE)
        val active = syncGrant() ?: return CurrentCellQuery.Unavailable(UnavailableReason.NO_ANALYSIS_GRANT)
        if (row !in 0 until LocalGroundGrid.ROWS || col !in 0 until LocalGroundGrid.COLUMNS) {
            return CurrentCellQuery.Unavailable(UnavailableReason.OUT_OF_GRID)
        }
        val snapshot = slot ?: return CurrentCellQuery.Unavailable(unavailable)
        val now = nowNanos()
        val capture = snapshot.frame.captureNanos
        if (now < capture || now - capture >= MAX_AGE_NANOS) {
            slot = null
            unavailable = UnavailableReason.EXPIRED
            return CurrentCellQuery.Unavailable(unavailable)
        }
        val proof = recovery
        val surface = safety.running && safety.clearView && !safety.hold &&
            slotBaseline == BaselineQualification.Qualified && capture > active.openedAtNanos &&
            (proof == null || (proof.ready && capture > proof.boundary))
        return snapshot.read(row * LocalGroundGrid.COLUMNS + col, surface)
    }

    private fun syncGrant(): AnalysisGrant? {
        val current = run.source?.currentGrant()
        if (current === grant) return current
        grant = current
        grantVisibilityVersion++
        slot = null
        unavailable = UnavailableReason.NO_FRAME
        // A transient host state can hide the same grant without stopping its analysis.
        if (current != null && current !== watermarkGrant) {
            captureWatermark = 0L
            receiptWatermark = 0L
            admitWatermark = 0L
            recovery = if (watermarkGrant != null || current.number > 1L || stopBoundary > 0L) {
                Recovery(maxOf(current.openedAtNanos, stopBoundary))
            } else null
            watermarkGrant = current
        }
        return current
    }

    private fun ownsThread() = ownerThread === Thread.currentThread()

    private class Recovery(val boundary: Long) {
        var ready = false
            private set
        private var frames = 0
        private var firstCapture = 0L
        private var firstReceipt = 0L

        fun count(capture: Long, receipt: Long) {
            if (ready || capture <= boundary || receipt <= boundary) return
            if (frames == 0) {
                firstCapture = capture
                firstReceipt = receipt
            }
            frames = minOf(3, frames + 1)
            ready = frames == 3 && capture - firstCapture >= RECOVERY_NANOS && receipt - firstReceipt >= RECOVERY_NANOS
        }
    }

    companion object {
        private const val MAX_AGE_NANOS = 200_000_000L
        private const val RECOVERY_NANOS = 500_000_000L

        /** Default CameraX composition starts without a continuous-analysis grant source. */
        fun withoutAnalysisGrant(nowNanos: () -> Long): LocalWorldOwner = LocalWorldOwner(null, nowNanos)
    }
}
