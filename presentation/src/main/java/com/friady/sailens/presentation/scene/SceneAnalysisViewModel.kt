package com.friady.sailens.presentation.scene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friady.sailens.camera.ImageFrameProvider
import com.friady.sailens.domain.model.scene.SceneEvent
import com.friady.sailens.domain.service.LogService
import com.friady.sailens.domain.usecase.scene.StartSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.scene.StopSceneAnalysisUseCase
import com.friady.sailens.presentation.device.HapticManager
import com.friady.sailens.presentation.device.SpeechManager
import com.friady.sailens.presentation.ext.visualize
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "SceneAnalysisViewModel"

class SceneAnalysisViewModel(
    private val imageFrameProvider: ImageFrameProvider,
    private val startSceneAnalysisUseCase: StartSceneAnalysisUseCase,
    private val stopSceneAnalysisUseCase: StopSceneAnalysisUseCase,
    private val hapticManager: HapticManager,
    private val speechManager: SpeechManager,
    private val logger: LogService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SceneAnalysisUiState())
    val uiState: StateFlow<SceneAnalysisUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<SceneAnalysisUiEffect>()
    val uiEffect: SharedFlow<SceneAnalysisUiEffect> = _uiEffect.asSharedFlow()

    private var analysisJob: Job? = null
    private var frameCount: Long = 0

    init {
        speechManager.initialize {
            logger.debug(TAG, "SpeechManager initialized")
            _uiState.update { it.copy(isSpeechReady = true, errorMessage = null) }
        }
    }

    fun toggleAnalysis() {
        if (_uiState.value.isRunning) {
            stopSceneAnalysis()
        } else {
            _uiState.update { it.copy(isLoading = true) }
            startSceneAnalysis()
        }
    }

    fun setSpeechEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isSpeechEnabled = enabled) }
        if (!enabled) {
            speechManager.stop()
        } else {
            speechManager.initialize {
                _uiState.update { state -> state.copy(isSpeechReady = true) }
            }
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isHapticsEnabled = enabled) }
        if (!enabled) {
            hapticManager.cancel()
        }
    }

    private fun startSceneAnalysis() {
        frameCount = 0
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            // collectLatest is often used for high-frequency data, discarding previous incomplete processing
            startSceneAnalysisUseCase(imageFrameProvider.frames).onStart {
                _uiState.update {
                    it.copy(isInitializing = false, isRunning = true, isLoading = false)
                }
                logger.info(TAG, "Scene analysis started")
            }.catch { e ->
                logger.error(TAG, "Error in scene analysis", e)
                speechManager.stop()
                hapticManager.cancel()
                _uiState.update {
                    it.copy(
                        isRunning = false, isLoading = false, errorMessage = e.message ?: "Unknown error"
                    )
                }
                _uiEffect.emit(SceneAnalysisUiEffect.ShowToast(e.message ?: "Unknown error"))
            }.collectLatest { result ->
                frameCount++
                val mask = result.passableMask?.visualize()
                val events = result.events
                _uiState.update {
                    it.copy(
                        segMask = mask,
                        lastEvents = events,
                        frameCount = frameCount,
                        eventCount = it.eventCount + events.size
                    )
                }
                onSceneEvents(events)
            }
        }
    }

    private fun onSceneEvents(events: List<SceneEvent>) {
        if (events.isEmpty()) return
        val primaryEvent = events.first()
        val state = _uiState.value

        logger.debug(TAG, "Scene events generated, ${primaryEvent.messageKey}", mapOf("count" to events.size))

        if (state.isSpeechEnabled) {
            speechManager.speak(primaryEvent)
        }

        if (state.isHapticsEnabled) {
            hapticManager.trigger(primaryEvent)
        }
    }

    private fun stopSceneAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        stopSceneAnalysisUseCase()
        speechManager.stop()
        hapticManager.cancel()
        _uiState.update {
            it.copy(isRunning = false, isInitializing = false, isLoading = false, segMask = null)
        }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        analysisJob = null
        stopSceneAnalysisUseCase()
        speechManager.stop()
        hapticManager.cancel()
        runBlocking {
            runCatching {
                stopSceneAnalysisUseCase.release()
            }.onFailure { error ->
                logger.error(TAG, "Error releasing scene analysis resources", error)
            }
        }
        speechManager.release()
        super.onCleared()
    }
}
