package com.sailens.describe

import com.sailens.camera.FrameSnapshotProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.log.LogService
import com.sailens.vlm.SceneDescriber
import com.sailens.vlm.SceneDescription
import com.sailens.vlm.SceneDescriptionChunk
import com.sailens.vlm.SceneDescriptionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Describe's frame policy (architecture.md §6.1, §6.4).
 *
 * The rule that matters: it takes the *current* view, bounded by freshness, and fails loudly when
 * there isn't one. Describing a frame from several seconds ago is worse than refusing, because the
 * person asking cannot see that the answer is stale -- they will act on it as if it were now. And
 * a silent failure is equally bad: they pressed a button and are standing there waiting.
 */
class DescribeSceneUseCaseTest {

    @Test
    fun `describes the current frame when it is fresh enough`() = runBlocking {
        val describer = FakeSceneDescriber(ready = true)
        val useCase = DescribeSceneUseCase(
            sceneDescriber = describer,
            frameSnapshots = FrameSnapshots(frame(sequenceNumber = 7)),
            logService = SilentLog,
        )

        val chunks = useCase().toList()

        assertEquals(7L, describer.requestedFrame?.sequenceNumber)
        assertEquals(2, chunks.size)
    }

    @Test
    fun `fails when no frame is fresh enough, rather than describing a stale one`() = runBlocking {
        val useCase = DescribeSceneUseCase(
            sceneDescriber = FakeSceneDescriber(ready = true),
            frameSnapshots = FrameSnapshots(null),
            logService = SilentLog,
        )

        val error = runCatching { useCase().toList() }.exceptionOrNull()

        assertTrue("expected a thrown failure, got $error", error is IllegalStateException)
        assertTrue(
            "the failure should name the freshness bound: ${error?.message}",
            error?.message?.contains("newer than") == true,
        )
    }

    @Test
    fun `passes the caller's freshness bound through to the snapshot`() = runBlocking {
        val snapshots = FrameSnapshots(frame(sequenceNumber = 1))
        val useCase = DescribeSceneUseCase(
            sceneDescriber = FakeSceneDescriber(ready = true),
            frameSnapshots = snapshots,
            logService = SilentLog,
        )

        useCase(maxFrameAgeMs = 250).toList()

        assertEquals(250L, snapshots.requestedMaxAgeMs)
    }

    @Test
    fun `loads the model on first use only`() = runBlocking {
        val describer = FakeSceneDescriber(ready = false)
        val useCase = DescribeSceneUseCase(
            sceneDescriber = describer,
            frameSnapshots = FrameSnapshots(frame(sequenceNumber = 1)),
            logService = SilentLog,
        )

        useCase().toList()

        assertEquals(1, describer.initializeCount)
    }

    private fun frame(sequenceNumber: Long) = ImageFrame(
        width = 4,
        height = 4,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.YUV_420_888,
        timestamp = sequenceNumber,
        rotationDegrees = 0,
        sequenceNumber = sequenceNumber,
    )

    private class FrameSnapshots(private val frame: ImageFrame?) : FrameSnapshotProvider {
        var requestedMaxAgeMs: Long? = null

        override fun currentFrame(maxAgeMs: Long): ImageFrame? {
            requestedMaxAgeMs = maxAgeMs
            return frame
        }
    }

    private class FakeSceneDescriber(ready: Boolean) : SceneDescriber {
        override val isAvailable: Boolean = true
        override var isReady: Boolean = ready
            private set

        var initializeCount = 0
        var requestedFrame: ImageFrame? = null

        override suspend fun initialize() {
            initializeCount++
            isReady = true
        }

        override fun describe(request: SceneDescriptionRequest): Flow<SceneDescriptionChunk> {
            requestedFrame = request.frame
            return flowOf(
                SceneDescriptionChunk.Delta("a "),
                SceneDescriptionChunk.Completed(
                    SceneDescription(
                        text = "a door",
                        backend = "fake",
                        latencyMs = 1,
                        timeToFirstTokenMs = 1,
                    )
                ),
            )
        }

        override suspend fun release() = Unit
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(
            tag: String,
            message: String,
            data: Map<String, Any>?,
            throwable: Throwable?,
        ) = Unit

        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
