package com.friady.sailens.presentation.device

import android.content.Context
import android.speech.tts.TextToSpeech
import com.friady.sailens.domain.model.common.EventPriority
import com.friady.sailens.domain.model.scene.SceneEvent
import java.util.IllegalFormatException
import java.util.Locale

/**
 * Android TTS 服务实现
 */
class SpeechManager(
    private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var _isReady = false

    val isReady: Boolean get() = _isReady

    fun initialize(onReady: (() -> Unit)? = null) {
        if (_isReady) {
            onReady?.invoke()
            return
        }

        if (tts != null) return

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.0f)
                _isReady = true
                onReady?.invoke()
            } else {
                _isReady = false
            }
        }
    }

    /**
     * 播报 SceneEvent 中的文案（通过 strings.xml 的资源 ID）
     */
    fun speak(event: SceneEvent) {
        if (!_isReady) return

        val text = resolveMessage(event)
        val queueMode = if (event.priority == EventPriority.CRITICAL) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        tts?.speak(text, queueMode, null, event.id.toString())
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        _isReady = false
    }

    @Suppress("DiscouragedApi")
    private fun resolveMessage(event: SceneEvent): String {
        val resId = context.resources.getIdentifier(event.messageKey, "string", context.packageName)
        if (resId == 0) return event.messageKey

        if (event.messageParams.isEmpty()) {
            return context.getString(resId)
        }

        val args = event.messageParams.toSortedMap().values.toTypedArray()
        return try {
            context.getString(resId, *args)
        } catch (_: IllegalFormatException) {
            context.getString(resId)
        }
    }
}
