package com.sailens.shell.ttsduration

import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.shell.device.GuidanceNotice
import com.sailens.shell.device.GuidanceNoticeText
import com.sailens.shell.device.SceneEventText
import com.sailens.shell.device.SceneEventTextResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * How long each Guidance sentence takes to say (det-first plan D1.7): the speaker model's
 * durations and the time budget of every prompt rest on this table.
 *
 * Every Guidance message, with and without the NEAR prefix where it can carry one, plus the status
 * notices, is spoken through an engine of its own -- configured as SpeechManager configures its
 * engine (accessibility usage, speech content, the user's speech rate) -- and timed from the
 * engine's own `onStart` to `onDone`, on the engine's thread. Each sentence is spoken [repetitions]
 * times; the table keeps every duration so the spread is visible.
 *
 * A measuring tool, nothing more: it never touches the shared SpeechManager.
 */
internal class TtsDurationProbe(private val context: Context) {

    data class Sentence(val key: String, val near: Boolean, val text: String)

    data class Measurement(
        val sentence: Sentence,
        /** `onStart` − the `speak()` call, per repetition, ms. */
        val startLatenciesMs: List<Double>,
        /** `onDone` − `onStart`, per repetition, ms; an entry is missing where the engine did not finish. */
        val durationsMs: List<Double>,
        val failures: Int,
    )

    class Report(val locale: Locale, val engine: String?, val speechRate: Float, val measurements: List<Measurement>, val problem: String?)

    /** Every sentence Guidance can say in [locale]. */
    fun sentences(locale: Locale): List<Sentence> {
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        val resolver = SceneEventTextResolver(localized)
        val notices = GuidanceNoticeText(localized)
        return buildList {
            for (key in SceneEventMessageKeys.all) {
                add(Sentence(key, near = false, text = resolver.resolve(SceneEventText(key))))
                // Only obstacle prompts carry a distance, so only they can take the NEAR prefix.
                if (key.startsWith(OBSTACLE_KEY_PREFIX)) add(Sentence(key, near = true, text = resolver.resolve(SceneEventText(key, isNear = true))))
            }
            for (notice in GuidanceNotice.entries) add(Sentence("notice_${notice.name.lowercase()}", near = false, text = notices.resolve(notice)))
        }
    }

    suspend fun measure(locale: Locale, speechRate: Float, repetitions: Int, onProgress: (done: Int, total: Int) -> Unit): Report {
        val sentences = sentences(locale)
        val starts = ConcurrentHashMap<String, Long>()
        val ends = ConcurrentHashMap<String, CompletableDeferred<Long?>>()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                starts[utteranceId] = SystemClock.elapsedRealtimeNanos()
            }

            override fun onDone(utteranceId: String) {
                ends[utteranceId]?.complete(SystemClock.elapsedRealtimeNanos())
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                ends[utteranceId]?.complete(null)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                ends[utteranceId]?.complete(null)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                ends[utteranceId]?.complete(null)
            }
        }
        val engine = start() ?: return Report(locale, null, speechRate, emptyList(), "the TTS engine did not start")
        try {
            if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                return Report(locale, engine.defaultEngine, speechRate, emptyList(), "the engine has no voice for ${locale.toLanguageTag()}")
            }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setSpeechRate(speechRate)
            engine.setOnUtteranceProgressListener(listener)

            val total = sentences.size * repetitions
            var done = 0
            val measurements = sentences.mapIndexed { index, sentence ->
                val latencies = mutableListOf<Double>()
                val durations = mutableListOf<Double>()
                var failures = 0
                repeat(repetitions) { rep ->
                    val id = "tts_duration_${index}_$rep"
                    val end = CompletableDeferred<Long?>().also { ends[id] = it }
                    val requestedAt = SystemClock.elapsedRealtimeNanos()
                    if (engine.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
                        failures++
                    } else {
                        val endAt = withTimeoutOrNull(SENTENCE_TIMEOUT_MS) { end.await() }
                        val startAt = starts[id]
                        if (endAt == null || startAt == null) {
                            failures++
                        } else {
                            latencies += (startAt - requestedAt) / 1e6
                            durations += (endAt - startAt) / 1e6
                        }
                    }
                    onProgress(++done, total)
                }
                Measurement(sentence, latencies, durations, failures)
            }
            return Report(locale, engine.defaultEngine, speechRate, measurements, null)
        } finally {
            engine.stop()
            engine.shutdown()
        }
    }

    private suspend fun start(): TextToSpeech? = suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (continuation.isActive) continuation.resume(engine.takeIf { status == TextToSpeech.SUCCESS })
        }
        continuation.invokeOnCancellation { engine.shutdown() }
    }

    companion object {
        const val SENTENCE_TIMEOUT_MS: Long = 15_000
        private const val OBSTACLE_KEY_PREFIX = "event_obstacle_"

        /** The table, as committed to `docs/experiments/`: numbers and the sentences only. */
        fun markdown(report: Report, wallTime: String): String = buildString {
            appendLine("## TTS durations, ${report.locale.toLanguageTag()}")
            appendLine()
            appendLine("- measured $wallTime on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- engine `${report.engine ?: "?"}`, speech rate ${String.format(Locale.ROOT, "%.2f", report.speechRate)}")
            appendLine("- duration = engine `onStart` to `onDone`, on the engine thread; latency = `speak()` to `onStart`")
            report.problem?.let { appendLine("- **not measured: $it**") }
            appendLine()
            appendLine("| key | NEAR | text | duration ms (each) | median | latency ms median | failed |")
            appendLine("|---|---|---|---|---|---|---|")
            for (m in report.measurements) {
                val durations = m.durationsMs.joinToString(" / ") { String.format(Locale.ROOT, "%.0f", it) }
                appendLine(
                    "| `${m.sentence.key}` | ${if (m.sentence.near) "yes" else ""} | ${m.sentence.text} | $durations | " +
                        "${median(m.durationsMs)} | ${median(m.startLatenciesMs)} | ${m.failures} |",
                )
            }
        }

        private fun median(values: List<Double>): String =
            if (values.isEmpty()) "-" else String.format(Locale.ROOT, "%.0f", values.sorted()[values.size / 2])
    }
}
