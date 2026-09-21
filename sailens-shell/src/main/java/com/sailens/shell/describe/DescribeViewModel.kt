package com.sailens.shell.describe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sailens.core.log.LogService
import com.sailens.describe.DescribeSceneUseCase
import com.sailens.output.AccessibilityStatusProvider
import com.sailens.output.SpeechClauseBuffer
import com.sailens.output.SpeechEngineState
import com.sailens.output.SpeechManager
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticManager
import com.sailens.shell.guidance.screen.SceneDescriptionSession
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import com.sailens.vlm.SceneDescriptionChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "DescribeViewModel"

/**
 * The Describe pipeline on its own screen.
 *
 * A Describe-only edition has no navigation session to ride on: there is no start button, no
 * continuous analysis, and nothing that would otherwise be holding the camera open. One question,
 * one answer. The frame comes from [com.sailens.camera.FrameSnapshotProvider], which opens its own
 * capture demand for the length of the request (architecture.md §6.1).
 *
 * Everything the user is told, they are told through a channel they can perceive: the text is on
 * screen for a sighted helper, spoken through the app's TTS, or handed to the screen reader. A
 * failure is spoken too — the person pressed a button, and silence reads to them exactly like
 * "there is nothing in front of you".
 */
class DescribeViewModel(
    private val describeSceneUseCase: DescribeSceneUseCase,
    private val speechManager: SpeechManager,
    private val hapticManager: HapticManager,
    private val accessibilityStatusProvider: AccessibilityStatusProvider,
    private val guidanceSettingsStore: GuidanceSettingsStore,
    private val logger: LogService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DescribeUiState(
            isScreenReaderActive = accessibilityStatusProvider.isScreenReaderActive.value,
            isSpeechEnabled = guidanceSettingsStore.settings.value.speechEnabled,
        )
    )
    val uiState: StateFlow<DescribeUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<DescribeUiEffect>()
    val uiEffect: SharedFlow<DescribeUiEffect> = _uiEffect.asSharedFlow()

    private val session = SceneDescriptionSession()

    init {
        if (shouldUseOwnSpeech()) initializeSpeech()
        viewModelScope.launch {
            accessibilityStatusProvider.isScreenReaderActive.collect { active ->
                _uiState.update { it.copy(isScreenReaderActive = active) }
                // The screen reader and our own engine are mutually exclusive channels: running
                // both means every answer is spoken twice, over itself.
                if (active) speechManager.release() else initializeSpeech()
            }
        }
        viewModelScope.launch {
            guidanceSettingsStore.settings.collect { settings ->
                _uiState.update { it.copy(isSpeechEnabled = settings.speechEnabled) }
                speechManager.setSpeechRate(settings.speechRate)
            }
        }
        viewModelScope.launch {
            speechManager.state.collect { engineState ->
                _uiState.update { it.copy(speechEngineState = engineState) }
            }
        }
    }

    /**
     * @param failureNotice already-localised text spoken when the request fails. The ViewModel does
     *   not touch resources (§6.11).
     */
    fun describe(failureNotice: String) {
        if (_uiState.value.isDescribing) return

        session.start(viewModelScope) { describeToken ->
            _uiState.update {
                it.copy(isDescribing = true, description = "", isComplete = false, errorMessage = null)
            }
            val screenReaderActive = _uiState.value.isScreenReaderActive
            val speakAloud = _uiState.value.isSpeechEnabled && !screenReaderActive
            val clauseBuffer = SpeechClauseBuffer()
            var spokeAnyClause = false
            try {
                describeSceneUseCase().collect { chunk ->
                    if (!session.isCurrent(describeToken)) return@collect
                    when (chunk) {
                        is SceneDescriptionChunk.Delta -> {
                            _uiState.update { it.copy(description = it.description + chunk.text) }
                            if (!speakAloud) return@collect
                            clauseBuffer.append(chunk.text)?.let { clause ->
                                speechManager.speakSystemNotice(clause)
                                spokeAnyClause = true
                            }
                        }

                        is SceneDescriptionChunk.Completed -> {
                            val text = chunk.description.text
                            _uiState.update { it.copy(description = text, isComplete = true) }
                            logger.info(
                                TAG,
                                "Scene description completed",
                                mapOf(
                                    "timeToFirstTokenMs" to chunk.description.timeToFirstTokenMs,
                                    "latencyMs" to chunk.description.latencyMs,
                                    "backend" to chunk.description.backend,
                                    "streamed" to spokeAnyClause,
                                ),
                            )
                            if (text.isBlank()) return@collect
                            if (screenReaderActive) {
                                _uiEffect.emit(DescribeUiEffect.Announce(text))
                                return@collect
                            }
                            if (!speakAloud) return@collect
                            clauseBuffer.drain()?.let { tail ->
                                speechManager.speakSystemNotice(tail)
                                spokeAnyClause = true
                            }
                            // No Delta ever arrived: a runtime that cannot stream returns the whole
                            // text here and nowhere else.
                            if (!spokeAnyClause) speechManager.speakSystemNotice(text)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.isCurrent(describeToken)) {
                    logger.error(TAG, "Scene description failed", e)
                    reportFailure(failureNotice)
                }
            } finally {
                if (session.isCurrent(describeToken)) {
                    _uiState.update { it.copy(isDescribing = false) }
                }
            }
        }
    }

    /** The user changed their mind, or the view is going away. */
    fun cancel() {
        if (!session.cancel("cancelled by user")) return
        speechManager.stop()
        _uiState.update { it.copy(isDescribing = false) }
    }

    override fun onCleared() {
        session.cancel("view model cleared")
        speechManager.stop()
        hapticManager.cancel()
        speechManager.release()
        super.onCleared()
    }

    private suspend fun reportFailure(failureNotice: String) {
        _uiState.update { it.copy(errorMessage = failureNotice, isComplete = false) }
        when {
            _uiState.value.isScreenReaderActive -> _uiEffect.emit(DescribeUiEffect.Announce(failureNotice))
            _uiState.value.isSpeechEnabled && speechManager.isReady ->
                speechManager.speakSystemNotice(failureNotice)
            // Nothing can speak. The user asked a question and is waiting; a message they cannot
            // hear is the same as no answer, so fall back to the one channel that is left. This is
            // the existing "assistance is not running" symbol, not a new one -- the haptic
            // vocabulary is a closed set awaiting blind-user testing.
            else -> hapticManager.play(GuidanceHaptic.INTERRUPTED)
        }
    }

    private fun shouldUseOwnSpeech(): Boolean =
        !accessibilityStatusProvider.isScreenReaderActive.value &&
            guidanceSettingsStore.settings.value.speechEnabled

    private fun initializeSpeech() {
        if (!shouldUseOwnSpeech()) return
        speechManager.setSpeechRate(guidanceSettingsStore.settings.value.speechRate)
        speechManager.initialize { logger.debug(TAG, "SpeechManager initialized") }
    }
}

data class DescribeUiState(
    val isDescribing: Boolean = false,
    /** Streamed so far, or the final text once [isComplete]. */
    val description: String = "",
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
    val isScreenReaderActive: Boolean = false,
    val isSpeechEnabled: Boolean = true,
    val speechEngineState: SpeechEngineState = SpeechEngineState.IDLE,
)

sealed interface DescribeUiEffect {
    /** Hand the text to the screen reader; it owns the channel while it is running. */
    data class Announce(val text: String) : DescribeUiEffect
}
