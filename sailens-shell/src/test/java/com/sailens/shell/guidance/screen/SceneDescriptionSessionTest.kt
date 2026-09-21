package com.sailens.shell.guidance.screen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Do not walk into that" outranks "what is that".
 *
 * The regression this guards is the one that makes the priority real rather than nominal. Flushing
 * the TTS queue is not enough on its own: the VLM keeps decoding, so after the obstacle warning
 * finishes the description resumes from wherever it got to, and the person hears half a sentence
 * about scenery arriving right behind a safety alert with no way to tell which one is current.
 */
class SceneDescriptionSessionTest {

    private val session = SceneDescriptionSession()

    @Test
    fun `a running description owns the output channel`() = runBlocking<Unit> {
        var token = -1L
        val started = CompletableDeferred<Unit>()
        session.start(this) { describeToken ->
            token = describeToken
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()

        assertTrue(session.isDescribing)
        assertTrue(session.isCurrent(token))

        session.cancel()
    }

    @Test
    fun `a clause decoded before the preemption still cannot speak after it`() = runBlocking<Unit> {
        var token = -1L
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        session.start(this) { describeToken ->
            token = describeToken
            started.complete(Unit)
            // Stands in for a chunk already decoded and dispatched: the body is mid-flight when
            // the preemption happens, so cancellation alone cannot stop it from running.
            release.await()
        }
        started.await()

        session.cancel("obstacle ahead")

        assertFalse(
            "a description that no longer owns the channel must refuse to speak",
            session.isCurrent(token),
        )
        release.complete(Unit)
    }

    @Test
    fun `cancelling reaches the description coroutine`() = runBlocking<Unit> {
        var observedCancellation = false
        val started = CompletableDeferred<Unit>()
        val job = session.start(this) { _ ->
            try {
                started.complete(Unit)
                awaitCancellation()
            } catch (e: CancellationException) {
                observedCancellation = true
                throw e
            }
        }
        started.await()

        session.cancel("obstacle ahead")
        job.join()

        assertTrue("cancellation must reach the decode, not just the speech queue", observedCancellation)
        assertFalse(session.isDescribing)
    }

    @Test
    fun `cancelling reports whether anything was actually running`() = runBlocking<Unit> {
        assertFalse("nothing to preempt, so nothing to flush", session.cancel())

        val started = CompletableDeferred<Unit>()
        session.start(this) { _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()

        assertTrue(session.cancel())
        assertFalse("a second preemption has nothing left to take", session.cancel())
    }

    @Test
    fun `a new description invalidates the one it replaces`() = runBlocking<Unit> {
        var first = -1L
        var second = -1L
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        session.start(this) { token ->
            first = token
            firstStarted.complete(Unit)
            awaitCancellation()
        }
        firstStarted.await()

        session.start(this) { token ->
            second = token
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        secondStarted.await()

        assertFalse("the replaced description must not keep speaking", session.isCurrent(first))
        assertTrue(session.isCurrent(second))

        session.cancel()
    }

    @Test
    fun `tokens are never reused, so a stale description can always tell`() = runBlocking<Unit> {
        val seen = mutableListOf<Long>()
        repeat(3) {
            val started = CompletableDeferred<Unit>()
            session.start(this) { token ->
                seen += token
                started.complete(Unit)
                awaitCancellation()
            }
            started.await()
            yield()
        }
        session.cancel()

        assertEquals(seen.size, seen.distinct().size)
    }
}
