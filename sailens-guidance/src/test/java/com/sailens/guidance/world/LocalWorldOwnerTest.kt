package com.sailens.guidance.world

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.depth.geometry.ScaleBasis
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.safety.BaselineQualification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.FutureTask

class LocalWorldOwnerTest {
    private val qualified = BaselineQualification.Qualified
    private var now = ns(1000)
    private val source = Source { now }
    private val owner = LocalWorldOwner(source) { now }.also {
        it.observeSafety(LocalWorldOwner.PublicationSafety(true, true), false)
    }

    @Test
    fun `standard owner stays unavailable across fresh results and phase changes`() {
        val standard = LocalWorldOwner.withoutAnalysisGrant { now }
        standard.observeSafety(LocalWorldOwner.PublicationSafety(true, true), false)
        val result = publication(1, 1001, 1002, standard)
        now = ns(1003)
        standard.finishResult(standard.beginResult(1, result), result, qualified)
        assertEquals(UnavailableReason.NO_ANALYSIS_GRANT, standard.reader.unavailable())
        standard.close()
        assertEquals(UnavailableReason.INACTIVE, standard.reader.unavailable())
    }

    @Test
    fun `receipt watermarks survive a backwards capture and an empty result`() {
        publish(1, 1001, 1003, 1004)
        assertEquals(1L, observed().frame.sequence)
        publish(2, 1000, 1010, 1011)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        // Valid time structure, but an older capture advances only the receipt watermark.
        publish(3, 1002, 1012, 1013)
        publish(4, 1001, 1020, 1021)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        publish(5, 1005, 1019, 1022)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        publish(6, 1006, 1025, 1026, snapshot = false)
        assertEquals(UnavailableReason.EVIDENCE_OVERFLOW, owner.reader.unavailable())
        publish(7, 1007, 1025, 1027)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        publish(8, 1008, 1028, 1029)
        assertEquals(8L, observed().frame.sequence)
    }

    @Test
    fun `expiry is at the capture deadline and a repeated read cannot revive it`() {
        publish(1, 1001, 1003, 1004)
        now = ns(1200)
        assertEquals(1L, observed().frame.sequence)
        now = ns(1201)
        assertEquals(UnavailableReason.EXPIRED, owner.reader.unavailable())
        now = ns(1199)
        assertEquals(UnavailableReason.EXPIRED, owner.reader.unavailable())
    }

    @Test
    fun `publication cannot be restored after reentry or analysis handoff`() {
        publish(1, 1001, 1002, 1003)
        val pending = publication(2, 1010, 1011)
        val admission = owner.beginResult(2, pending)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        publish(3, 1012, 1013, 1014)
        owner.finishResult(admission, pending, qualified)
        assertEquals(3L, observed().frame.sequence)
        assertNull(owner.beginResult(3, pending))

        val oldGrant = source.grant
        source.stop {
            assertEquals(UnavailableReason.NO_ANALYSIS_GRANT, owner.reader.unavailable())
        }
        now = ns(1100)
        source.open()
        publish(4, 1011, 1013, 1104, grant = oldGrant)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        publish(5, 1105, 1108, 1110)
        assertEquals(SurfaceEvidence.UNKNOWN, observed().evidence.surface)
        publish(6, 1355, 1358, 1360)
        assertEquals(SurfaceEvidence.UNKNOWN, observed().evidence.surface)
        publish(7, 1605, 1608, 1610)
        assertEquals(SurfaceEvidence.SEM_SURFACE, observed().evidence.surface)
    }

    @Test
    fun `a stop requires both clocks to span five hundred milliseconds alongside the monitor`() {
        publish(1, 1001, 1002, 1003)
        now = ns(1010)
        owner.observeSafety(LocalWorldOwner.PublicationSafety(true, false, true, true), stopBegan = true)
        owner.observeSafety(LocalWorldOwner.PublicationSafety(true, true, true), stopBegan = false)
        publish(2, 1009, 1015, 1016)
        publish(3, 1050, 1080, 1081)
        publish(4, 1300, 1310, 1311)
        publish(5, 1550, 1565, 1568) // Capture spans 500 ms, receipt spans 485 ms.
        assertEquals(SurfaceEvidence.UNKNOWN, observed().evidence.surface)
        publish(6, 1570, 1580, 1581)
        assertEquals(SurfaceEvidence.UNKNOWN, observed().evidence.surface) // Original hold still active.
        owner.observeSafety(LocalWorldOwner.PublicationSafety(true, true), stopBegan = false)
        assertEquals(SurfaceEvidence.SEM_SURFACE, observed().evidence.surface)
        publish(7, 1569, 1590, 1591)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        publish(8, 1592, 1593, 1594)
        assertEquals(SurfaceEvidence.SEM_SURFACE, observed().evidence.surface)
    }

    @Test
    fun `a hidden continuous grant cannot reset its time watermarks`() {
        publish(1, 1005, 1007, 1008)
        assertEquals(SurfaceEvidence.SEM_SURFACE, observed().evidence.surface)
        source.withHiddenHost {
            assertEquals(UnavailableReason.NO_ANALYSIS_GRANT, owner.reader.unavailable())
        }
        publish(2, 1004, 1010, 1011)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
        publish(3, 1012, 1013, 1014)
        assertEquals(SurfaceEvidence.SEM_SURFACE, observed().evidence.surface)
    }

    @Test
    fun `a grant hidden during callbacks cannot finish the interrupted admission`() {
        publish(1, 1005, 1007, 1008)
        now = ns(1010)
        val pending = publication(2, 1008, 1009)
        val admission = owner.beginResult(2, pending)
        source.withHiddenHost {
            assertEquals(UnavailableReason.NO_ANALYSIS_GRANT, owner.reader.unavailable())
        }
        owner.finishResult(admission, pending, qualified)
        assertEquals(UnavailableReason.NO_FRAME, owner.reader.unavailable())
    }

    @Test
    fun `a reentrant activation cannot grant while the previous analysis is invalidating`() {
        val activity = AnalysisActivity({ now }) { true }
        val first = checkNotNull(activity.grantAfterActivation())
        activity.revokeBeforeInvalidating(first) {
            assertNull(activity.currentGrant())
            assertNull(activity.grantAfterActivation())
        }
        now = ns(1100)
        val next = checkNotNull(activity.grantAfterActivation())
        assertEquals(2L, next.number)
        assertEquals(ns(1100), next.openedAtNanos)
    }

    @Test
    fun `another thread and another run cannot operate the current slot`() {
        publish(1, 1001, 1002, 1003)
        val foreign = LocalWorldOwner(source) { now }
        assertNull(owner.beginResult(2, publication(2, 1003, 1004, foreign)))
        assertEquals(1L, observed().frame.sequence)
        val read = FutureTask { owner.reader.unavailable() }
        Thread(read).start()
        assertEquals(UnavailableReason.INACTIVE, read.get())
        foreign.close()
        assertEquals(1L, observed().frame.sequence)
    }

    private fun publish(
        seq: Long, capture: Long, receipt: Long, admit: Long,
        grant: AnalysisGrant? = source.grant, snapshot: Boolean = true,
    ) {
        now = ns(admit)
        val result = publication(seq, capture, receipt, snapshot = snapshot, grant = grant)
        owner.finishResult(owner.beginResult(seq, result), result, qualified)
    }

    private fun publication(
        seq: Long, capture: Long, receipt: Long, owner: LocalWorldOwner = this.owner,
        grant: AnalysisGrant? = source.grant, snapshot: Boolean = true,
    ): WorldPublication {
        val frame = WorldFrameReference(owner.run.id, seq, ns(capture), GeometryQuality.Ok, ScaleBasis.ASSUMED_PHONE_HEIGHT)
        val cells = LocalWorldCells(emptyList(), emptyList()).also { it.surface[0] = true }
        return WorldPublication(
            owner.run, seq, ns(capture), ns(receipt), TimeBasis.CAMERA_REALTIME, grant,
            BuiltWorld(LocalWorldSnapshot(frame, emptyList(), cells).takeIf { snapshot },
                RejectedDepth(setOf(DepthRejection.NOT_CONFIGURED)), null),
        )
    }

    private fun observed() = owner.reader.currentCell(0, 0) as CurrentCellQuery.Observed
    private fun LocalWorldReader.unavailable() = (currentCell(0, 0) as CurrentCellQuery.Unavailable).reason

    private class Source(now: () -> Long) : WorldAnalysisSource {
        private var hostStarted = true
        val activity = AnalysisActivity(now) { hostStarted }
        val grant get() = activity.currentGrant()
        init { open() }

        fun open() {
            hostStarted = true
            activity.grantAfterActivation()
        }

        override fun currentGrant() = activity.currentGrant()
        override fun evidenceFor(frame: ImageFrame): WorldFrameAttestation? = null

        fun withHiddenHost(read: () -> Unit) {
            hostStarted = false
            try {
                read()
            } finally {
                hostStarted = true
            }
        }

        fun stop(stopEffect: () -> Unit) {
            activity.revokeBeforeInvalidating(checkNotNull(grant)) {
                hostStarted = false
                stopEffect()
            }
        }
    }

    private companion object {
        fun ns(ms: Long): Long = ms * 1_000_000L
    }
}
