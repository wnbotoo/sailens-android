package com.sailens.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sailens.core.log.LogService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 语音引擎对外可见的状态。
 *
 * [UNAVAILABLE] 必须能传到 UI：TTS 起不来时，用户听不到任何提示，而"听不到提示"和
 * "前方没有危险"在盲人那里是同一种体验。以前这个失败只写进日志，用户完全无从察觉。
 */
public enum class SpeechEngineState {
    IDLE,
    INITIALIZING,
    READY,
    UNAVAILABLE,
}

/**
 * Android TTS 服务实现。
 *
 * 三条与"边走边听"直接相关的策略，和普通 App 的 TTS 用法不同：
 *
 * 1. **默认 QUEUE_FLUSH 而不是 QUEUE_ADD**。实时避障里，最新的一句永远比排队的旧句有价值。
 *    排队会让用户听到 2–4 秒前的场景——按 1.2 m/s 步速，那已经是身后好几米的事了。
 * 2. **播报前检查 expiresAt**。引擎初始化、耳机切换等都会引入延迟，过期的提示宁可丢掉。
 * 3. **USAGE_ASSISTANCE_ACCESSIBILITY + 瞬时降低音量的音频焦点**。目标用户几乎总在同时听
 *    TalkBack、导航或播客；不申请焦点的话两路声音会直接叠在一起，谁也听不清。
 */
public class SpeechManager(
    private val context: Context,
    private val logger: LogService,

) {
    private var tts: TextToSpeech? = null
    @Volatile
    private var _isReady = false
    private var isInitializing = false
    private var initAttempt = 0
    private var initFailureCount = 0
    private var nextInitRetryAtMs = 0L
    private var initTimeoutRunnable: Runnable? = null
    private var pendingSpeech: PendingSpeech? = null
    private val onReadyCallbacks = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    /**
     * 最后一次请求播报的 utterance id。只有它结束时才归还音频焦点。
     *
     * 不用"在播条数"计数：QUEUE_FLUSH 打断上一条时，被打断那条的 onStop 会晚于新一条的
     * speak() 到达，计数法会因此在新一条还在播的时候就把焦点还回去，别人的音频瞬间恢复原音量
     * 盖住提示。比对 id 天然不受回调顺序影响。
     */
    private var latestUtteranceId: String? = null
    private var nextSystemNoticeId = 0L

    /**
     * Everything handed to the engine and not finished yet, with who asked for it. The engine can
     * only flush its whole queue, so this is what lets [withdraw] take back one owner's speech and
     * hand everyone else's back.
     */
    private val ledger = UtteranceLedger()
    private var nextResubmitId = 0L

    private var speechRate = DEFAULT_SPEECH_RATE

    private val _state = MutableStateFlow(SpeechEngineState.IDLE)
    public val state: StateFlow<SpeechEngineState> = _state.asStateFlow()

    public val isReady: Boolean get() = _isReady

    /**
     * 设置语速（相对正常语速的倍率）。
     *
     * 盲人 TalkBack 重度用户的常用语速在 1.5–3x；固定 1.0 对他们不只是慢，更直接等于每条提示
     * 多占 0.5–1 秒的响应延迟。引擎已就绪时立即生效，否则等初始化完成时一并应用。
     */
    public fun setSpeechRate(rate: Float) {
        runOnMain {
            speechRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
            tts?.takeIf { _isReady }?.setSpeechRate(speechRate)
        }
    }

    public fun initialize(onReady: (() -> Unit)? = null) {
        runOnMain {
            initializeOnMain(onReady = onReady, forceRetry = true)
        }
    }

    private fun initializeOnMain(
        onReady: (() -> Unit)? = null,
        forceRetry: Boolean = false,
    ) {
        if (_isReady) {
            onReady?.invoke()
            return
        }

        onReady?.let { onReadyCallbacks += it }

        if (isInitializing) {
            logger.debug(TAG, "TTS initialization already in progress")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!forceRetry) {
            if (initFailureCount >= MAX_AUTOMATIC_INIT_FAILURES) {
                logger.warning(
                    TAG,
                    "TTS automatic initialization suppressed after repeated failures",
                    mapOf("failureCount" to initFailureCount)
                )
                clearPendingSpeech("automatic retry limit reached")
                return
            }

            if (now < nextInitRetryAtMs) {
                logger.debug(
                    TAG,
                    "TTS initialization retry delayed",
                    mapOf(
                        "retryInMs" to (nextInitRetryAtMs - now),
                        "failureCount" to initFailureCount,
                    )
                )
                return
            }
        }

        if (tts != null) {
            logger.warning(TAG, "Discarding stale TTS instance before retry")
            tts?.shutdown()
            tts = null
        }

        isInitializing = true
        _state.value = SpeechEngineState.INITIALIZING
        val attempt = ++initAttempt
        logger.info(
            TAG,
            "Initializing TTS",
            mapOf(
                "locale" to Locale.getDefault().toLanguageTag(),
            )
        )

        var createdEngine: TextToSpeech? = null
        createdEngine = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                handleInitCallback(
                    attempt = attempt,
                    createdEngine = createdEngine,
                    status = status,
                )
            }
        }
        tts = createdEngine
        scheduleInitTimeout(attempt)
    }

    /**
     * 播报一条已经解析好文案的 [Announcement]。文案由上层决定，这里只负责怎么说出去。
     */
    public fun speak(announcement: Announcement) {
        runOnMain {
            speakOnMain(announcement)
        }
    }

    private fun speakOnMain(announcement: Announcement) {
        if (announcement.isExpired(System.currentTimeMillis())) {
            logger.debug(
                TAG,
                "Dropping expired scene event before speaking",
                mapOf("key" to announcement.key)
            )
            return
        }

        if (!_isReady) {
            logger.warning(
                TAG,
                "TTS speak queued because engine is not ready",
                mapOf("key" to announcement.key)
            )
            storePendingSpeech(announcement)
            if (!isInitializing) {
                initializeOnMain(forceRetry = false)
            }
            return
        }

        speakReadyAnnouncement(announcement)
    }

    private fun speakReadyAnnouncement(announcement: Announcement) {
        if (!_isReady) return
        // 引擎就绪前排队的事件也要再验一次时效：pending 到就绪之间可能又过去了几百毫秒。
        if (announcement.isExpired(System.currentTimeMillis())) return

        val text = announcement.text
        // 一律 FLUSH。唯一的例外是"辅助已停止/已恢复"这类系统状态提示，它们必须说完，
        // 但那条路径走的是 speakSystemNotice()，不经过这里。
        val queueMode = TextToSpeech.QUEUE_FLUSH

        val utteranceId = announcement.id
        requestAudioFocus()
        latestUtteranceId = utteranceId
        val result = tts?.speak(text, queueMode, null, utteranceId) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            // A flush-mode request that failed may or may not have emptied the engine. Forget the
            // queue rather than risk handing already-dropped speech back to it later.
            ledger.clear()
            // speak() 直接失败时不会有任何 utterance 回调，必须就地归还焦点，
            // 否则别人的音频会一直被压着。
            releaseAudioFocus()
            logger.warning(
                TAG,
                "TTS speak failed",
                mapOf(
                    "key" to announcement.key,
                    "queueMode" to queueMode,
                    "textLength" to text.length,
                )
            )
        } else {
            ledger.flushAndAdd(
                UtteranceLedger.Entry(
                    id = utteranceId,
                    text = text,
                    owner = null,
                    expiresAtMs = announcement.expiresAtMs,
                )
            )
            logger.debug(
                TAG,
                "TTS speak requested",
                mapOf(
                    "key" to announcement.key,
                    "queueMode" to queueMode,
                )
            )
        }
    }

    /**
     * 播报一条系统状态提示（辅助中断/恢复、语音不可用等）。
     *
     * 与场景事件的区别：它没有时效，也不该被下一条场景提示打断——用户必须完整听到
     * "辅助已停止"，否则就会继续举着一个不工作的手机往前走。所以这里用 QUEUE_ADD。
     *
     * @param owner who is queueing this, so they can later [withdraw] it — and only it. Null
     *   for speech that no caller may take back.
     */
    public fun speakSystemNotice(text: String, owner: SpeechOwner? = null) {
        runOnMain {
            if (!_isReady) {
                logger.warning(TAG, "Dropping system notice because TTS is not ready")
                return@runOnMain
            }
            val utteranceId = "$SYSTEM_NOTICE_UTTERANCE_ID_PREFIX${nextSystemNoticeId++}"
            requestAudioFocus()
            latestUtteranceId = utteranceId
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
                ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) {
                releaseAudioFocus()
                logger.warning(TAG, "TTS system notice failed")
            } else {
                ledger.add(
                    UtteranceLedger.Entry(id = utteranceId, text = text, owner = owner, expiresAtMs = null)
                )
            }
        }
    }

    /**
     * Takes back everything [owner] has queued or is speaking, and nothing anyone else queued.
     *
     * The engine can only flush its whole queue, so this flushes and then hands back, in their
     * original order, the utterances that belonged to others — dropping any whose deadline has
     * passed in the meantime, as [speak] would have. One consequence is deliberate: another
     * owner's sentence that was mid-way through is restarted from its beginning rather than lost.
     * Restarting a sentence costs a second; losing one the person needed to hear is the failure
     * this module exists to prevent.
     *
     * A no-op when [owner] has nothing queued, so an owner that stops "just in case" cannot
     * interrupt anyone.
     */
    public fun withdraw(owner: SpeechOwner) {
        runOnMain {
            if (!ledger.has(owner)) return@runOnMain

            val survivors = ledger.withdraw(owner, System.currentTimeMillis())
            if (_isReady) tts?.stop()
            if (survivors.isEmpty()) {
                // stop() does not promise a callback for every dropped utterance.
                releaseAudioFocus()
                return@runOnMain
            }

            logger.debug(
                TAG,
                "Re-queueing speech after withdrawing one owner",
                mapOf("owner" to owner.name, "requeued" to survivors.size),
            )
            // Keep the audio focus we already hold: releasing it here would let other apps' audio
            // swell for a moment between the flush and the hand-back.
            val lastHandedBack = ledger.handBack(survivors.map(::withFreshId), ::submitAgain)
            if (lastHandedBack == null) {
                // The engine took none of it back, so nothing will finish and give the focus back.
                // Other apps' audio would stay ducked until the next stop or release.
                releaseAudioFocus()
                return@runOnMain
            }
            // Only an utterance the engine accepted can finish. Pointing at one it refused would
            // hold the focus for good, even after the accepted ones before it had finished.
            latestUtteranceId = lastHandedBack
        }
    }

    /** Whether [owner] has anything queued or speaking right now. Safe from any thread. */
    public fun hasQueued(owner: SpeechOwner): Boolean = ledger.has(owner)

    /**
     * The same utterance under a fresh id. Reusing the old id would let the late stop callback of
     * the flushed original release audio focus under the replacement.
     */
    private fun withFreshId(entry: UtteranceLedger.Entry): UtteranceLedger.Entry =
        entry.copy(id = "${entry.id}$REQUEUE_UTTERANCE_ID_SUFFIX${nextResubmitId++}")

    /** Hands one utterance back to the engine. Returns whether the engine accepted it. */
    private fun submitAgain(entry: UtteranceLedger.Entry): Boolean {
        requestAudioFocus()
        val result = tts?.speak(entry.text, TextToSpeech.QUEUE_ADD, null, entry.id)
            ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            logger.warning(TAG, "TTS re-queue failed", mapOf("utteranceId" to entry.id))
            return false
        }
        return true
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (audioFocusRequest != null) return

        // TRANSIENT_MAY_DUCK：让音乐/播客/导航自动降到背景音量而不是暂停。对同时开着
        // TalkBack 的用户尤其重要——完全打断会让他丢失正在听的上下文。
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(guidanceAudioAttributes())
            .setWillPauseWhenDucked(false)
            .build()
        val result = manager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
        } else {
            // 拿不到焦点也照常播：提示的重要性高于礼貌。
            logger.warning(TAG, "Audio focus request was not granted", mapOf("result" to result))
        }
    }

    private fun releaseAudioFocus() {
        latestUtteranceId = null
        val manager = audioManager ?: return
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        manager.abandonAudioFocusRequest(request)
    }

    private fun guidanceAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            // ASSISTANCE_ACCESSIBILITY 让系统按"无障碍播报"处理：跟随无障碍音量、
            // 在勿扰模式下仍可发声，且不会被当成媒体播放。
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    public fun stop() {
        runOnMain {
            stopOnMain()
        }
    }

    private fun stopOnMain() {
        ledger.clear()
        if (_isReady) {
            tts?.stop()
        } else {
            logger.debug(TAG, "Ignoring TTS stop before engine is ready")
        }
        // stop() 之后不保证每条被取消的 utterance 都回调，所以这里强制归还焦点。
        releaseAudioFocus()
    }

    public fun release() {
        runOnMain {
            releaseOnMain()
        }
    }

    private fun releaseOnMain() {
        mainHandler.removeCallbacksAndMessages(null)
        initTimeoutRunnable = null
        stopOnMain()
        tts?.shutdown()
        tts = null
        _isReady = false
        isInitializing = false
        initFailureCount = 0
        nextInitRetryAtMs = 0L
        pendingSpeech = null
        onReadyCallbacks.clear()
        initAttempt++
        _state.value = SpeechEngineState.IDLE
    }

    private fun scheduleInitTimeout(attempt: Int) {
        clearInitTimeout()
        val timeoutRunnable = Runnable {
            if (attempt != initAttempt || !isInitializing) return@Runnable

            recordInitFailure(
                message = "TTS initialization timed out",
                data = mapOf(
                    "timeoutMs" to INIT_TIMEOUT_MS,
                    "locale" to Locale.getDefault().toLanguageTag(),
                ),
                engine = tts,
            )
        }
        initTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, INIT_TIMEOUT_MS)
    }

    private fun clearInitTimeout() {
        initTimeoutRunnable?.let(mainHandler::removeCallbacks)
        initTimeoutRunnable = null
    }

    private fun handleInitCallback(
        attempt: Int,
        createdEngine: TextToSpeech?,
        status: Int,
    ) {
        if (attempt != initAttempt) {
            logger.debug(
                TAG,
                "Ignoring stale TTS init callback",
                mapOf("status" to status)
            )
            createdEngine?.shutdown()
            return
        }

        isInitializing = false
        val engine = createdEngine ?: tts
        if (status == TextToSpeech.SUCCESS) {
            if (engine == null) {
                recordInitFailure(
                    message = "TTS init callback completed but engine was never assigned",
                    data = mapOf("status" to status),
                    engine = null,
                )
                return
            }

            configureEngine(engine)
        } else {
            recordInitFailure(
                message = "TTS initialization failed",
                data = mapOf("status" to status),
                engine = engine,
            )
        }
    }

    private fun configureEngine(engine: TextToSpeech) {
        clearInitTimeout()
        isInitializing = false
        tts = engine
        val selectedLocale = selectSupportedLocale(engine)
        if (selectedLocale == null) {
            recordInitFailure(
                message = "No supported TTS language found",
                data = mapOf(
                    "locale" to Locale.getDefault().toLanguageTag(),
                    "defaultEngine" to (engine.defaultEngine ?: "unknown"),
                ),
                engine = engine,
            )
            return
        }

        val languageResult = engine.setLanguage(selectedLocale)
        if (!isLanguageResultSupported(languageResult)) {
            recordInitFailure(
                message = "TTS language selection failed",
                data = mapOf(
                    "locale" to selectedLocale.toLanguageTag(),
                    "result" to languageResult,
                    "defaultEngine" to (engine.defaultEngine ?: "unknown"),
                ),
                engine = engine,
            )
            return
        }

        engine.setAudioAttributes(guidanceAudioAttributes())
        engine.setSpeechRate(speechRate)
        engine.setOnUtteranceProgressListener(createUtteranceListener())
        _isReady = true
        initFailureCount = 0
        nextInitRetryAtMs = 0L
        _state.value = SpeechEngineState.READY
        logger.info(
            TAG,
            "TTS initialized",
            mapOf(
                "locale" to selectedLocale.toLanguageTag(),
                "languageResult" to languageResult,
                "defaultEngine" to (engine.defaultEngine ?: "unknown"),
            )
        )
        notifyReadyCallbacks()
        speakPendingSpeechIfFresh()
    }

    private fun recordInitFailure(
        message: String,
        data: Map<String, Any>,
        engine: TextToSpeech?,
    ) {
        clearInitTimeout()
        initAttempt++
        isInitializing = false
        _isReady = false
        onReadyCallbacks.clear()

        if (engine != null) {
            if (tts === engine) {
                tts = null
            }
            engine.shutdown()
        } else {
            tts?.shutdown()
            tts = null
        }

        releaseAudioFocus()

        initFailureCount++
        val retryDelayMs = retryDelayForFailure(initFailureCount)
        nextInitRetryAtMs = SystemClock.elapsedRealtime() + retryDelayMs
        val automaticRetriesPaused = initFailureCount >= MAX_AUTOMATIC_INIT_FAILURES
        // 只有放弃自动重试时才对外报 UNAVAILABLE：中间的几次失败还会自己恢复，
        // 过早报错会让用户白白关掉一个其实能用的功能。
        _state.value = if (automaticRetriesPaused) {
            SpeechEngineState.UNAVAILABLE
        } else {
            SpeechEngineState.INITIALIZING
        }

        logger.warning(
            TAG,
            message,
            data + mapOf(
                "failureCount" to initFailureCount,
                "retryDelayMs" to retryDelayMs,
                "automaticRetriesPaused" to automaticRetriesPaused,
            )
        )

        if (automaticRetriesPaused) {
            clearPendingSpeech("automatic retry limit reached")
        }
    }

    private fun retryDelayForFailure(failureCount: Int): Long {
        val shift = (failureCount - 1).coerceIn(0, 5)
        return minOf(MAX_INIT_RETRY_DELAY_MS, INITIAL_INIT_RETRY_DELAY_MS * (1L shl shift))
    }

    private fun storePendingSpeech(announcement: Announcement) {
        val now = SystemClock.elapsedRealtime()
        val pending = pendingSpeech
        if (
            pending == null ||
            now - pending.enqueuedAtMs > PENDING_SPEECH_MAX_AGE_MS ||
            announcement.priority >= pending.announcement.priority
        ) {
            pendingSpeech = PendingSpeech(announcement = announcement, enqueuedAtMs = now)
        }
    }

    private fun speakPendingSpeechIfFresh() {
        val pending = pendingSpeech ?: return
        pendingSpeech = null

        val ageMs = SystemClock.elapsedRealtime() - pending.enqueuedAtMs
        if (ageMs > PENDING_SPEECH_MAX_AGE_MS) {
            logger.warning(
                TAG,
                "Dropping stale pending TTS event",
                mapOf(
                    "key" to pending.announcement.key,
                    "ageMs" to ageMs,
                )
            )
            return
        }

        speakReadyAnnouncement(pending.announcement)
    }

    private fun clearPendingSpeech(reason: String) {
        val pending = pendingSpeech ?: return
        pendingSpeech = null
        logger.warning(
            TAG,
            "Dropping pending TTS event",
            mapOf(
                "key" to pending.announcement.key,
                "reason" to reason,
            )
        )
    }

    private fun notifyReadyCallbacks() {
        val callbacks = onReadyCallbacks.toList()
        onReadyCallbacks.clear()
        callbacks.forEach { it.invoke() }
    }

    private fun selectSupportedLocale(engine: TextToSpeech): Locale? {
        return languageCandidates().firstOrNull { locale ->
            isLanguageResultSupported(engine.isLanguageAvailable(locale))
        }
    }

    /**
     * 语音候选语言，必须跟随**应用实际解析到的** locale，而不是 `Locale.getDefault()`。
     *
     * 播报文本来自 strings.xml，而 strings.xml 用哪一份是由资源解析结果决定的。原先的候选表是
     * `默认 locale → 简体中文 → 中文`：系统语言是英文、而设备恰好没装英文 TTS 时，会退到中文
     * 引擎去念英文文案，输出完全无法听懂——比不出声更糟，因为用户不会意识到出了问题。
     *
     * 现在只退到"同语言的宽松变体"（zh-Hans-CN → zh）。跨语言的兜底一律不做：宁可报
     * [SpeechEngineState.UNAVAILABLE]，让用户看到/听到"语音无法启动、请检查系统语音设置"，
     * 也不要给他一段读不通的声音。
     */
    private fun languageCandidates(): List<Locale> {
        val appLocale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        return listOfNotNull(
            appLocale,
            appLocale.language.takeIf { it.isNotEmpty() }?.let(::Locale),
        ).distinctBy { it.toLanguageTag() }
    }

    private fun isLanguageResultSupported(result: Int): Boolean {
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    private fun createUtteranceListener(): UtteranceProgressListener {
        // 回调来自引擎线程，而焦点/计数状态都只在主线程上改，所以统一 post 回主线程。
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                logger.debug(TAG, "TTS utterance started", mapOf("utteranceId" to (utteranceId ?: "unknown")))
            }

            override fun onDone(utteranceId: String?) {
                logger.debug(TAG, "TTS utterance completed", mapOf("utteranceId" to (utteranceId ?: "unknown")))
                onUtteranceFinished(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // QUEUE_FLUSH 打断上一条时走这里。不减计数会让焦点永远归还不了。
                onUtteranceFinished(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                logger.warning(TAG, "TTS utterance failed", mapOf("utteranceId" to (utteranceId ?: "unknown")))
                onUtteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                logger.warning(
                    TAG,
                    "TTS utterance failed",
                    mapOf(
                        "utteranceId" to (utteranceId ?: "unknown"),
                        "errorCode" to errorCode,
                    )
                )
                onUtteranceFinished(utteranceId)
            }
        }
    }

    /**
     * 只有最后请求的那条播完，才说明队列真的空了。被 FLUSH 掉的旧条目也会走到这里，
     * 但它的 id 已经不是最新的，直接忽略。
     */
    private fun onUtteranceFinished(utteranceId: String?) {
        runOnMain {
            ledger.finished(utteranceId)
            if (utteranceId != null && utteranceId != latestUtteranceId) return@runOnMain
            releaseAudioFocus()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private data class PendingSpeech(
        val announcement: Announcement,
        val enqueuedAtMs: Long,
    )

    public companion object {
        public const val DEFAULT_SPEECH_RATE: Float = 1.3f
        public const val MIN_SPEECH_RATE: Float = 0.7f
        public const val MAX_SPEECH_RATE: Float = 3.0f

        private const val TAG = "SpeechManager"
        private const val SYSTEM_NOTICE_UTTERANCE_ID_PREFIX = "system_notice_"
        private const val REQUEUE_UTTERANCE_ID_SUFFIX = "~requeued_"
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val INITIAL_INIT_RETRY_DELAY_MS = 1_000L
        private const val MAX_INIT_RETRY_DELAY_MS = 30_000L
        private const val MAX_AUTOMATIC_INIT_FAILURES = 5
        private const val PENDING_SPEECH_MAX_AGE_MS = 3_000L
    }
}
