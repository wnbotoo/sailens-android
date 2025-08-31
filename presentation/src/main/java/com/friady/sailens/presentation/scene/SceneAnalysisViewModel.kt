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

//    init {
//        speechManager.initialize {
//            logger.debug(TAG, "SpeechManager initialized")
//            _uiState.update { it.copy(isInitializing = true, errorMessage = null) }
//        }
//    }

    fun toggleAnalysis() {
        if (_uiState.value.isRunning) {
            stopSceneAnalysis()
        } else {
            _uiState.update { it.copy(isLoading = true) }
            startSceneAnalysis()
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
        logger.debug(TAG, "Scene events generated, ${events.first().messageKey}", mapOf("count" to events.size))

        // 语音播报最高优先级事件
//        events.firstOrNull()?.let { event ->
//            speechManager.speak(event)
//            hapticManager.trigger(event)
//        }
    }

    private fun stopSceneAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        stopSceneAnalysisUseCase()
        _uiState.update {
            it.copy(isRunning = false, isInitializing = false, isLoading = false, segMask = null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        analysisJob?.cancel()
        analysisJob = null
    }
}
