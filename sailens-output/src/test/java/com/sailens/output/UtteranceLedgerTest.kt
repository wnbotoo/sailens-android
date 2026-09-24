package com.sailens.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping behind [SpeechManager.withdraw]: one owner can take back its own speech, and
 * nothing else.
 *
 * The rule under test is an ownership one. Describe queues clauses behind whatever is playing; if
 * the only way to stop them were to flush the engine, cancelling a description could cut off a
 * navigation warning that happened to be speaking at the same moment.
 */
class UtteranceLedgerTest {

    private val describe = SpeechOwner("describe")
    private val other = SpeechOwner("other")
    private val ledger = UtteranceLedger()

    @Test
    fun `withdrawing hands back everyone else's speech in its original order`() {
        ledger.add(entry("guidance-1", owner = null))
        ledger.add(entry("describe-1", owner = describe))
        ledger.add(entry("guidance-2", owner = null))
        ledger.add(entry("describe-2", owner = describe))

        val survivors = ledger.withdraw(describe, nowMs = 0)

        assertEquals(listOf("guidance-1", "guidance-2"), survivors.map { it.id })
    }

    @Test
    fun `an owner never takes back another owner's speech`() {
        ledger.add(entry("other-1", owner = other))
        ledger.add(entry("describe-1", owner = describe))

        val survivors = ledger.withdraw(describe, nowMs = 0)

        assertEquals(listOf("other-1"), survivors.map { it.id })
    }

    @Test
    fun `owners are compared by identity, not by name`() {
        val lookalike = SpeechOwner("describe")
        ledger.add(entry("describe-1", owner = describe))

        assertFalse("a different owner with the same name must not match", ledger.has(lookalike))
        assertEquals(1, ledger.withdraw(lookalike, nowMs = 0).size)
    }

    @Test
    fun `speech past its deadline is dropped rather than handed back`() {
        ledger.add(entry("stale-warning", owner = null, expiresAtMs = 1_000))
        ledger.add(entry("fresh-warning", owner = null, expiresAtMs = 5_000))
        ledger.add(entry("notice", owner = null, expiresAtMs = null))
        ledger.add(entry("describe-1", owner = describe))

        val survivors = ledger.withdraw(describe, nowMs = 2_000)

        assertEquals(listOf("fresh-warning", "notice"), survivors.map { it.id })
    }

    @Test
    fun `a flush forgets everything queued before it`() {
        ledger.add(entry("describe-1", owner = describe))
        ledger.flushAndAdd(entry("warning", owner = null))

        assertFalse(ledger.has(describe))
        assertEquals(1, ledger.size())
    }

    @Test
    fun `finished utterances leave, and late callbacks for flushed ones are ignored`() {
        ledger.add(entry("describe-1", owner = describe))
        ledger.flushAndAdd(entry("warning", owner = null))

        // describe-1 was flushed; its stop callback arrives after the warning replaced it.
        ledger.finished("describe-1")
        assertEquals(1, ledger.size())

        ledger.finished("warning")
        assertEquals(0, ledger.size())
    }

    @Test
    fun `has reports only what is still queued`() {
        ledger.add(entry("describe-1", owner = describe))
        assertTrue(ledger.has(describe))

        ledger.finished("describe-1")

        assertFalse(ledger.has(describe))
    }

    @Test
    fun `withdrawing leaves the ledger empty until the survivors are re-added`() {
        ledger.add(entry("guidance-1", owner = null))
        ledger.add(entry("describe-1", owner = describe))

        ledger.withdraw(describe, nowMs = 0)

        assertEquals(0, ledger.size())
    }

    // ---- Handing back: only what the engine accepted can end the queue ----------------------------

    @Test
    fun `an utterance the engine refused to take back does not become the end of the queue`() {
        // The first survivor goes back in; the engine refuses the second. The first one's end is
        // what gives the audio focus back. If the refused one were recorded as the end, nothing
        // would ever release the focus and other apps' audio would stay ducked.
        val last = ledger.handBack(
            listOf(entry("warning", owner = null), entry("notice", owner = null)),
        ) { it.id == "warning" }

        assertEquals("warning", last)
        assertEquals("a refused utterance is not queued", 1, ledger.size())
    }

    @Test
    fun `when the engine takes nothing back there is nothing to wait for`() {
        val last = ledger.handBack(listOf(entry("warning", owner = null))) { false }

        assertNull("the caller must give the focus back itself", last)
        assertEquals(0, ledger.size())
    }

    @Test
    fun `handing back keeps the original order`() {
        val submitted = mutableListOf<String>()

        val last = ledger.handBack(listOf(entry("first", owner = null), entry("second", owner = null))) {
            submitted += it.id
            true
        }

        assertEquals(listOf("first", "second"), submitted)
        assertEquals("second", last)
    }

    @Test
    fun `the highest live priority is what newer speech has to outrank`() {
        ledger.flushAndAdd(entry("critical", owner = null, expiresAtMs = 5_000, priority = 3))
        ledger.add(entry("notice", owner = null))

        assertEquals(3, ledger.highestLivePriority(nowMs = 1_000))
    }

    @Test
    fun `finished or expired speech no longer outranks anything`() {
        ledger.flushAndAdd(entry("high", owner = null, expiresAtMs = 5_000, priority = 2))
        assertEquals(2, ledger.highestLivePriority(nowMs = 4_000))
        // A lost done-callback must not leave it outranking everything forever.
        assertNull(ledger.highestLivePriority(nowMs = 5_001))

        ledger.flushAndAdd(entry("medium", owner = null, expiresAtMs = 9_000, priority = 1))
        ledger.finished("medium")
        assertNull(ledger.highestLivePriority(nowMs = 6_000))
    }

    @Test
    fun `speech without a priority never outranks an announcement`() {
        ledger.add(entry("describe-1", owner = describe))
        ledger.add(entry("notice", owner = null))

        assertNull(ledger.highestLivePriority(nowMs = 0))
    }

    @Test
    fun `a prioritized status notice holds off lower and equal announcements until it expires`() {
        // "Guidance stopped" queued with a priority: ordinary guidance must not FLUSH it.
        ledger.add(entry("notice", owner = null, expiresAtMs = 15_000, priority = Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, ledger.highestLivePriority(nowMs = 1_000))
        // Bounded: a lost done-callback cannot hold guidance off forever.
        assertNull(ledger.highestLivePriority(nowMs = 15_001))
    }

    private fun entry(id: String, owner: SpeechOwner?, expiresAtMs: Long? = null, priority: Int? = null) =
        UtteranceLedger.Entry(id = id, text = "text of $id", owner = owner, expiresAtMs = expiresAtMs, priority = priority)
}
