package com.sailens.output

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenCallbacksTest {

    private val heard = mutableListOf<String>()
    private val callbacks = SpokenCallbacks(requeueSuffix = "~requeued_")

    @Test
    fun `onDone of the announcement calls its caller once`() {
        callbacks.register("a") { heard += "a" }
        callbacks.done("a")
        callbacks.done("a")
        assertEquals(listOf("a"), heard)
        assertEquals(0, callbacks.size())
    }

    @Test
    fun `a handed-back submission is still the same announcement, however many times it was handed back`() {
        callbacks.register("a") { heard += "a" }
        callbacks.register("b") { heard += "b" }
        callbacks.done("b~requeued_3~requeued_7")
        callbacks.done("a~requeued_4")
        assertEquals(listOf("b", "a"), heard)
    }

    @Test
    fun `unknown and missing ids call nobody`() {
        callbacks.register("a") { heard += "a" }
        callbacks.done(null)
        callbacks.done("system_notice_1")
        callbacks.done("ab")
        assertEquals(emptyList<String>(), heard)
        assertEquals(1, callbacks.size())
    }

    @Test
    fun `beyond the bound the oldest waiting caller is forgotten`() {
        val bounded = SpokenCallbacks(requeueSuffix = "~requeued_", capacity = 2)
        for (id in listOf("a", "b", "c")) bounded.register(id) { heard += id }
        for (id in listOf("a", "b", "c")) bounded.done(id)
        assertEquals(listOf("b", "c"), heard)
    }

    @Test
    fun `a caller that throws does not reach the speech path, and clear forgets everyone`() {
        callbacks.register("a") { error("bookkeeping bug") }
        callbacks.done("a")
        callbacks.register("b") { heard += "b" }
        callbacks.clear()
        callbacks.done("b")
        assertEquals(emptyList<String>(), heard)
    }
}
