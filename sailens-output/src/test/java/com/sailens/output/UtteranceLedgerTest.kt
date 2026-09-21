package com.sailens.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun entry(id: String, owner: SpeechOwner?, expiresAtMs: Long? = null) =
        UtteranceLedger.Entry(id = id, text = "text of $id", owner = owner, expiresAtMs = expiresAtMs)
}
