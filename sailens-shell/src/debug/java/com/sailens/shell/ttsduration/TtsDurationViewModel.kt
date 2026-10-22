package com.sailens.shell.ttsduration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The debug TTS-duration tool (det-first plan D1.7): measures every Guidance sentence in one
 * language at the user's speech rate and writes the table under [outputDir], numbers and the
 * sentences only, for `docs/experiments/`.
 */
internal class TtsDurationViewModel(
    private val probe: TtsDurationProbe,
    private val settings: GuidanceSettingsStore,
    private val outputDir: File,
) : ViewModel() {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Where the last table was written; null before the first run. */
        val savedTo: String? = null,
        val table: String? = null,
        val problem: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun measure(locale: Locale) {
        if (job?.isActive == true) return
        _state.value = State(running = true)
        job = viewModelScope.launch {
            val report = probe.measure(locale, settings.settings.value.speechRate, REPETITIONS) { done, total ->
                _state.update { it.copy(done = done, total = total) }
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.ROOT).format(Date())
            val table = TtsDurationProbe.markdown(report, stamp)
            val file = runCatching {
                outputDir.mkdirs()
                File(outputDir, "tts-durations-${locale.toLanguageTag()}-$stamp.md").apply { writeText(table) }
            }.getOrNull()
            _state.update {
                it.copy(running = false, table = table, savedTo = file?.absolutePath, problem = report.problem ?: if (file == null) "could not write the table" else null)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    companion object {
        const val REPETITIONS: Int = 3
    }
}
