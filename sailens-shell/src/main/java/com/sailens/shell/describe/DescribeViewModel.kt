package com.sailens.shell.describe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sailens.core.log.LogService
import com.sailens.output.SpeechManager
import com.sailens.shell.device.SharedEngineOwner
import com.sailens.shell.device.SpokenChannel
import com.sailens.shell.device.SpokenChannels
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "DescribeViewModel"

/**
 * The Describe screen's view of [SceneDescriptionCoordinator].
 *
 * It owns nothing that another screen also uses. The description itself — its job, its token, the
 * speech it queued — belongs to the shell-wide coordinator, so Guidance can preempt the very
 * description this screen is showing. The shared engines belong to [SharedEngineOwner], so closing
 * this screen can never release the speech engine Guidance is speaking through, or stop a warning
 * it is in the middle of.
 *
 * What stays here: asking for the engine to be started when this screen needs it, and translating
 * the coordinator's state for the UI.
 */
class DescribeViewModel(
    private val coordinator: SceneDescriptionCoordinator,
    private val speechManager: SpeechManager,
    sharedEngineOwner: SharedEngineOwner,
    spokenChannels: SpokenChannels,
    guidanceSettingsStore: GuidanceSettingsStore,
    private val logger: LogService,
) : ViewModel() {

    private val engineLease = sharedEngineOwner.acquire()

    val uiState: StateFlow<DescribeUiState> = combine(
        coordinator.state,
        spokenChannels.channel,
    ) { description, channel ->
        DescribeUiState(
            isDescribing = description.isDescribing,
            description = description.text,
            isComplete = description.isComplete,
            errorMessage = description.errorMessage,
            isScreenReaderActive = channel == SpokenChannel.SCREEN_READER,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DescribeUiState(
            isScreenReaderActive = spokenChannels.channel.value == SpokenChannel.SCREEN_READER,
        ),
    )

    init {
        viewModelScope.launch {
            // Starting the engine is idempotent, so asking whenever this screen needs it is safe even
            // with Guidance asking too. Without this, a Describe-only edition that had speech turned
            // on in Settings would keep an engine that was never started, and drop every clause.
            spokenChannels.channel.collect { channel ->
                if (channel == SpokenChannel.OWN_TTS) {
                    speechManager.initialize { logger.debug(TAG, "Speech engine ready for Describe") }
                }
            }
        }
        viewModelScope.launch {
            guidanceSettingsStore.settings.collect { settings ->
                speechManager.setSpeechRate(settings.speechRate)
            }
        }
    }

    /**
     * @param failureNotice already-localised text spoken when the request fails. The ViewModel does
     *   not touch resources (§6.11).
     */
    fun describe(failureNotice: String) {
        coordinator.describe(failureNotice)
    }

    /** The user changed their mind, or the screen is going away. */
    fun cancel() {
        coordinator.cancel("cancelled from the Describe screen")
    }

    override fun onCleared() {
        coordinator.cancel("Describe screen closed")
        // The engine is shared. Give back this screen's claim on it; whether it is released is the
        // owner's decision, made when nobody else holds one.
        engineLease.close()
        super.onCleared()
    }
}

data class DescribeUiState(
    val isDescribing: Boolean = false,
    /** Streamed so far, or the final text once [isComplete]. */
    val description: String = "",
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
    val isScreenReaderActive: Boolean = false,
)
