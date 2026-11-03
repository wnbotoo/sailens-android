package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

/** A copied cooldown goes its own way, and equal canonical states behave alike from then on. */
class CooldownManagerCopyTest {

    private fun event(random: Random, now: Long) = SceneEvent(
        timestamp = now,
        category = EventCategory.OBSTACLE,
        priority = EventPriority.entries[random.nextInt(EventPriority.entries.size)],
        messageKey = "k",
        expiresAt = now + 2_000,
        dedupeKey = "k",
        cooldownKeys = setOf("key${random.nextInt(3)}"),
    )

    @Test
    fun `the copy and the original do not affect each other`() {
        val original = CooldownManager()
        val first = SceneEvent(timestamp = 0, category = EventCategory.OBSTACLE, priority = EventPriority.HIGH, messageKey = "k", expiresAt = 2_000, dedupeKey = "k")
        original.recordEvent(first, 0)
        val copy = original.copy()
        assertEquals(original.canonicalState(), copy.canonicalState())

        copy.revoke(first.id)
        assertNotEquals(original.canonicalState(), copy.canonicalState())
        assertEquals(1, original.state().emissions.size)
        assertEquals(0, copy.state().emissions.size)

        // The open write's confirmation is the copy's own too.
        val other = original.copy()
        other.confirm(first.id)
        original.revoke(first.id)
        assertEquals(0, original.state().records.size)
        assertEquals(1, other.state().records.size)
    }

    @Test
    fun `equal canonical states filter alike under any later sequence, whatever the event ids were`() {
        val random = Random(11)
        repeat(300) {
            val script = List(20) { i -> i * 300L }
            val a = CooldownManager()
            val b = CooldownManager()
            // The same writes under different ids.
            for (t in script.take(10)) {
                val seed = random.nextLong()
                val ea = event(Random(seed), t)
                val eb = event(Random(seed), t)
                a.recordEvent(ea, t)
                b.recordEvent(eb, t)
                val settle = random.nextInt(3)
                if (settle == 0) {
                    a.confirm(ea.id)
                    b.confirm(eb.id)
                } else if (settle == 1) {
                    a.revoke(ea.id)
                    b.revoke(eb.id)
                }
            }
            assertEquals(a.canonicalState(), b.canonicalState())
            for (t in script.drop(10)) {
                val seed = random.nextLong()
                val ea = event(Random(seed), t)
                val eb = event(Random(seed), t)
                assertEquals(a.filter(listOf(ea), t).size, b.filter(listOf(eb), t).size)
                a.recordEvent(ea, t)
                b.recordEvent(eb, t)
                assertEquals(a.canonicalState(), b.canonicalState())
            }
        }
    }
}
