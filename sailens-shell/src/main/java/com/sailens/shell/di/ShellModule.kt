package com.sailens.shell.di

import com.sailens.core.log.LogService
import com.sailens.shell.FileLogService
import com.sailens.shell.diagnostics.GuidanceDiagnosticsStore
import com.sailens.output.AccessibilityStatusProvider
import com.sailens.shell.device.HapticManager
import com.sailens.shell.app.ConfigurationFailureSignal
import com.sailens.shell.device.GuidanceAnnouncements
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.GuidanceNoticeText
import com.sailens.shell.device.SceneEventTextResolver
import com.sailens.shell.device.ScreenReaderAnnouncer
import com.sailens.shell.device.SharedEngineOwner
import com.sailens.shell.device.SpokenChannels
import com.sailens.output.SpeechManager
import com.sailens.shell.describe.DescribeViewModel
import com.sailens.shell.describe.DescribeVoice
import com.sailens.shell.describe.SceneDescriptionCoordinator
import com.sailens.shell.describe.SpeechManagerDescribeVoice
import com.sailens.shell.guidance.screen.SceneAnalysisViewModel
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import com.sailens.shell.guidance.settings.PerceptionSettingsStore
import com.sailens.shell.settings.SettingsViewModel
import com.sailens.vlm.SceneDescriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * A scope that lives as long as the app, for shell-wide owners that must outlive any one screen:
 * the output channel, and the one description in flight. Main-thread, like the output they drive.
 */
private val ShellScope = named("shellScope")

val shellModule = module {
    // FileLogService writes to app-internal files, so it is an application-platform implementation
    // rather than a shared contract (architecture.md §6.7). The interface lives in sailens-core.
    single<LogService> { FileLogService(androidContext()) }
    single { HapticManager(androidContext()) }
    single { SceneEventTextResolver(androidContext()) }
    single { SpeechManager(androidContext(), get()) }
    single { GuidanceAnnouncements(textResolver = get()) }
    single { GuidanceNoticeText(androidContext()) }
    single {
        ConfigurationFailureSignal(
            speechManager = get(),
            hapticManager = get(),
            logService = get(),
        )
    }
    single { AccessibilityStatusProvider(androidContext()) }
    single { GuidanceSettingsStore(androidContext()) }
    single { PerceptionSettingsStore(androidContext()) }
    single { GuidanceDiagnosticsStore(sceneOverlayConfig = get(), uiFeatureFlags = get()) }

    // Shared output ownership (architecture.md §6.6). Every screen that speaks goes through these
    // single instances; none of them belongs to a screen.
    single(ShellScope) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single { ScreenReaderAnnouncer() }
    single {
        SpokenChannels(
            settingsStore = get(),
            accessibilityStatusProvider = get(),
            scope = get(ShellScope),
        )
    }
    single {
        val speechManager = get<SpeechManager>()
        val sceneDescriber = get<SceneDescriber>()
        val scope = get<CoroutineScope>(ShellScope)
        val logService = get<LogService>()
        SharedEngineOwner(
            releaseEngines = {
                speechManager.stop()
                speechManager.release()
                // Loaded lazily, so for most sessions this is a no-op; once loaded, the model
                // outweighs sem and det together and must not leak into the next launch.
                scope.launch {
                    runCatching { sceneDescriber.release() }
                        .onFailure { logService.error("ShellModule", "Error releasing the VLM", it) }
                }
            },
        )
    }
    single<DescribeVoice> { SpeechManagerDescribeVoice(speechManager = get()) }
    // One coordinator for the whole shell: the guidance screen preempts the same description the
    // Describe screen is running. Two of these is the bug this exists to prevent.
    single {
        val hapticManager = get<HapticManager>()
        SceneDescriptionCoordinator(
            describeScene = get(),
            voice = get(),
            announcer = get(),
            // The existing "assistance is not working" symbol. The haptic vocabulary is a closed
            // set awaiting blind-user testing, so no new symbol is introduced for this.
            signalFailureWithoutSpeech = { hapticManager.play(GuidanceHaptic.INTERRUPTED) },
            channel = get<SpokenChannels>().channel,
            scope = get(ShellScope),
            logService = get(),
        )
    }

    viewModel {
        SceneAnalysisViewModel(
            frameSource = get(),
            startSceneAnalysisUseCase = get(),
            stopSceneAnalysisUseCase = get(),
            sceneDescriptionCoordinator = get(),
            screenReaderAnnouncer = get(),
            sharedEngineOwner = get(),
            hapticManager = get(),
            speechManager = get(),
            logger = get(),
            traceService = get(),
            sceneOverlayConfig = get(),
            guidanceSettingsStore = get(),
            guidanceDiagnosticsStore = get(),
            accessibilityStatusProvider = get(),
            textResolver = get(),
            announcements = get(),
            revokeUndeliveredEvent = get(),
            noticeText = get(),
        )
    }
    viewModel {
        DescribeViewModel(
            coordinator = get(),
            speechManager = get(),
            sharedEngineOwner = get(),
            spokenChannels = get(),
            guidanceSettingsStore = get(),
            logger = get(),
        )
    }
    viewModel {
        SettingsViewModel(
            appInfo = get(),
            guidanceSettingsStore = get(),
            perceptionSettingsStore = get(),
            perceptionProfileManager = get(),
            guidanceDiagnosticsStore = get(),
            speechManager = get(),
            hapticManager = get(),
        )
    }
}
