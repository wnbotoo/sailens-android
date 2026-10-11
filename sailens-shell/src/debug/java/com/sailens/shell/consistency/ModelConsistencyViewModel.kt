package com.sailens.shell.consistency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sailens.core.log.LogService
import com.sailens.shell.app.BuildIdentity
import com.sailens.shell.capture.GuidanceSessionMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ConsistencyPhase {
    data object Idle : ConsistencyPhase
    data class Running(val done: Int, val total: Int) : ConsistencyPhase
    data class Done(val result: ConsistencyRunResult, val reportPath: String?) : ConsistencyPhase
    data class Failed(val message: String) : ConsistencyPhase
}

internal data class ModelConsistencyState(
    val phase: ConsistencyPhase = ConsistencyPhase.Idle,
    val guidanceActive: Boolean = false,
) {
    val canRun: Boolean get() = phase !is ConsistencyPhase.Running && !guidanceActive
}

internal class ModelConsistencyViewModel(
    private val checker: ModelConsistencyChecker,
    private val sessions: GuidanceSessionMonitor,
    private val writer: ModelConsistencyReportWriter,
    private val build: BuildIdentity?,
    val semanticLabel: (Int) -> String,
    private val log: LogService,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val phase = MutableStateFlow<ConsistencyPhase>(ConsistencyPhase.Idle)
    private var job: Job? = null

    val state: StateFlow<ModelConsistencyState> = combine(phase, sessions.active) { p, active ->
        ModelConsistencyState(phase = p, guidanceActive = active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelConsistencyState())

    fun run() {
        // Guidance must not share the camera and GPU with the check; the button is disabled too.
        if (!state.value.canRun || job?.isActive == true || sessions.active.value) return
        phase.value = ConsistencyPhase.Running(0, ModelConsistencyChecker.DEFAULT_FRAME_COUNT)
        job = viewModelScope.launch {
            try {
                val result = checker.run { done, total -> phase.update { ConsistencyPhase.Running(done, total) } }
                val path = withContext(Dispatchers.IO) {
                    runCatching { writer.write(result.toReport(wallClock(), build, semanticLabel)).path }
                        .onFailure { log.warning(TAG, "Could not write the consistency report", throwable = it) }
                        .getOrNull()
                }
                log.info(TAG, "Model consistency: overall=${result.overall} report=$path")
                phase.value = ConsistencyPhase.Done(result, path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warning(TAG, "Model consistency check failed", throwable = error)
                phase.value = ConsistencyPhase.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val TAG = "ModelConsistency"
    }
}
