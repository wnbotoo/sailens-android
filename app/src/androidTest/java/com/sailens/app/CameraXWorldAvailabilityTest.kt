package com.sailens.app

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.impl.SessionConfig
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sailens.camera.CameraPreview
import com.sailens.camera.CameraViewModel
import com.sailens.camera.FrameSnapshotProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.guidance.world.CurrentCellQuery
import com.sailens.guidance.world.UnavailableReason
import com.sailens.shell.guidance.screen.SceneAnalysisViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger

/** Real preview, ImageAnalysis and Guidance wiring with deterministic perception models. */
@RunWith(AndroidJUnit4::class)
class CameraXWorldAvailabilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @SuppressLint("RestrictedApi")
    fun surfaceResetAndHostRecreationKeepThePublicReaderUnavailable() {
        val activity = compose.activity
        val app = activity.application
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        }
        val processed = AtomicInteger()
        stopKoin()
        val koin = startKoin {
            androidContext(app)
            allowOverride(true)
            modules(appModule, module {
                single<PerceptionRepository> { FixedSemantic(processed) }
                single<ObstacleProvider>(named("realtimeObstacleProvider")) { EmptyDetector }
            })
        }.koin
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = koin.get(modelClass.kotlin)
        }
        val (activeCamera, guidanceScreen) = compose.runOnIdle {
            activity.keepCameraTestVisible()
            val models = ViewModelProvider(activity, factory)
            models[CameraViewModel::class.java] to models[SceneAnalysisViewModel::class.java]
        }
        compose.setContent { CameraPreview(viewModel = activeCamera) }
        val analysis = CameraViewModel::class.java.getDeclaredField("imageAnalysis").let {
            it.isAccessible = true
            it.get(activeCamera) as ImageAnalysis
        }
        val snapshots = koin.get<FrameSnapshotProvider>()
        val unavailable = CurrentCellQuery.Unavailable(UnavailableReason.NO_ANALYSIS_GRANT)
        val reader = compose.runOnIdle {
            guidanceScreen.toggleAnalysis()
            if (guidanceScreen.isSafetyNoticeShown.value) guidanceScreen.confirmSafetyNotice()
            checkNotNull(guidanceScreen.localWorldReader).also { assertEquals(unavailable, it.currentCell(30, 20)) }
        }
        compose.waitUntil(20_000) { processed.get() > 0 && activeCamera.surfaceRequest.value != null }
        val originalFrame = freshSnapshot(snapshots)
        val before = compose.runOnIdle {
            val session = analysis.sessionConfig
            val surface = session.surfaces.single { !it.isClosed }
            assertEquals(unavailable, reader.currentCell(30, 20))
            checkNotNull(session.errorListener).onError(session, SessionConfig.SessionError.SESSION_ERROR_SURFACE_NEEDS_RESET)
            assertTrue("the error listener must actually close the old analysis surface", surface.isClosed)
            assertTrue(analysis.sessionConfig.surfaces.any { it !== surface && !it.isClosed })
            assertEquals(unavailable, reader.currentCell(30, 20))
            processed.get()
        }
        compose.waitUntil(20_000) { processed.get() > before + 3 }
        val afterReset = freshSnapshot(snapshots, originalFrame.sequenceNumber)
        val (afterResetSurface, resetCount) = compose.runOnIdle {
            assertEquals(unavailable, reader.currentCell(30, 20))
            analysis.sessionConfig.surfaces.single { !it.isClosed } to processed.get()
        }
        Log.i(TAG, "ImageAnalysis reset closed the old surface; capture ${originalFrame.sequenceNumber} -> ${afterReset.sequenceNumber}")

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { recreated ->
            recreated.keepCameraTestVisible()
            assertNotSame(activity, recreated)
            val models = ViewModelProvider(recreated, factory)
            assertSame(activeCamera, models[CameraViewModel::class.java])
            assertSame(guidanceScreen, models[SceneAnalysisViewModel::class.java])
            assertEquals(unavailable, reader.currentCell(30, 20))
            recreated.setContent { CameraPreview(viewModel = activeCamera) }
        }
        compose.waitUntil(20_000) { processed.get() > resetCount + 3 }
        val afterRecreation = freshSnapshot(snapshots, afterReset.sequenceNumber)
        val beforeStop = compose.runOnIdle {
            assertTrue("Activity recreation must close its previous analysis surface", afterResetSurface.isClosed)
            assertTrue(analysis.sessionConfig.surfaces.any { it !== afterResetSurface && !it.isClosed })
            assertEquals(unavailable, reader.currentCell(30, 20))
            guidanceScreen.toggleAnalysis()
            assertEquals(CurrentCellQuery.Unavailable(UnavailableReason.INACTIVE), reader.currentCell(30, 20))
            maxOf(afterRecreation.sequenceNumber, snapshots.currentFrame(1_000)?.sequenceNumber ?: 0)
        }
        val describeFrame = freshSnapshot(snapshots, beforeStop)
        assertTrue(describeFrame.sequenceNumber > afterRecreation.sequenceNumber)
        Log.i(TAG, "Activity recreated and analysis resumed; Guidance ${afterRecreation.sequenceNumber}, " +
            "fresh Describe snapshot ${describeFrame.sequenceNumber} after Guidance stopped")
    }

    private fun ComponentActivity.keepCameraTestVisible() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun freshSnapshot(provider: FrameSnapshotProvider, after: Long = 0): ImageFrame {
        provider.openSnapshotLease().use {
            compose.waitUntil(10_000) { (provider.currentFrame(1_000)?.sequenceNumber ?: 0L) > after }
            return checkNotNull(runBlocking { provider.awaitCurrentFrame(maxAgeMs = 1_000, timeoutMs = 2_000) }).also {
                assertTrue(it.sequenceNumber > after)
                assertTrue(it.width > 0 && it.height > 0)
                assertTrue(it.rotationDegrees in setOf(0, 90, 180, 270))
            }
        }
    }

    private class FixedSemantic(private val processed: AtomicInteger) : PerceptionRepository {
        override val isInitialized = true
        override suspend fun initialize() = Unit
        override suspend fun release() = Unit
        override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
            processed.incrementAndGet()
            return Result.success(SegmentationOutput(SegmentationMask(64, 64, IntArray(64 * 64) { 1 }), 0, 0, 0))
        }
    }

    private object EmptyDetector : ObstacleProvider {
        override val isInitialized = true
        override suspend fun initialize() = Unit
        override suspend fun detect(frame: ImageFrame) = ObstacleModelOutput(emptyList())
        override fun release() = Unit
    }

    private companion object {
        const val TAG = "SailensCameraWorldTest"
    }
}
