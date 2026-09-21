[English](vlm-asr-assistant-plan.md) | **简体中文**

# VLM + ASR Assistant Plan

本文记录 Sailens 后续接入端侧 VLM 与 ASR 的应用侧方案。它不固定具体模型转换链路,也不改变当前实时 sem/det 避障管线;重点是代码结构、触发策略、ASR 选择和第一版体验边界。

## 1. 基线决策

- **最低目标设备改为 Snapdragon 8 Gen 2 / SM8550 级别**。8 Gen 1 可以继续作为现有视觉/NPU 路径的实验设备,但不作为 VLM/ASR assistant 的产品下限。
- **VLM 参数量控制在 1B 以内**,优先选择能在 Qualcomm NPU 路径上稳定运行的模型/格式。FastVLM-0.5B、SmolVLM-500M 这类模型可以做候选,但最终以 Android 端 NPU runtime、量化格式、延迟和功耗实测为准。
- **VLM 是低频辅助路径,不是实时安全路径**。实时避障仍由当前 sem/det 管线负责;VLM 只回答“当前画面是什么 / 用户问的画面问题”。
- **第一版先做手动快捷触发**,用户按屏幕按钮或硬件快捷键即可总结当前画面,不经过 ASR。
- **ASR 第一版使用 Android 系统 `SpeechRecognizer`**,封装在接口后面。不要把 LLM 当 ASR 用;LLM/VLM 应该消费 ASR 后的文本,而不是直接识别音频。

## 2. 当前仓库状态

已有的结构可以保留:

- `domain/repository/SceneDescriber.kt`: 已经定义按需场景描述接口。
- `data/source/ml/vlm/*`: 已经有 `LiteRtVlmEngine`、`VlmRuntimeFactory`、`UnavailableVlmRuntimeFactory` 这层 runtime seam。
- `SceneAnalysisViewModel`: 当前持有 `latestSceneResult`,负责消费实时分析结果并触发 TTS / haptics。
- `SpeechManager`: 当前是 TTS 管理器,负责播报 `SceneEvent`。

目前缺的不是把 VLM 塞进 `ProcessFrameUseCase`,而是:

- 一个可被 assistant 读取的**最新画面快照层**。
- 一个独立的**assistant 编排层**。
- 一个 ASR 抽象与 Android 系统 ASR 实现。
- 一个能承接生成式文本的低优先级 TTS 通道。

## 3. 目标代码结构

建议保持实时 pipeline 不动,在旁边增加 assistant 竖切:

```text
domain
  model/assistant/
    AssistantTrigger.kt
    SceneSnapshot.kt
    SceneAssistantResult.kt
    VoiceCommand.kt
  repository/
    SceneDescriber.kt                 // 已有,保留
    SceneSnapshotProvider.kt          // 新增: 提供最近 frame + 轻量实时分析摘要
    VoiceCommandRecognizer.kt         // 新增: ASR 抽象
  usecase/assistant/
    DescribeCurrentSceneUseCase.kt    // 快捷触发: snapshot -> VLM -> result
    AnswerSceneQuestionUseCase.kt     // ASR 文本问题: snapshot + question -> VLM -> result

data
  source/ml/vlm/
    LiteRtVlmEngine.kt                // 已有,后续替换真实 VlmRuntimeFactory
    <Qualcomm/LiteRtLm>VlmRuntime.kt  // 后续接真实模型时新增

presentation
  assistant/
    SceneAssistantViewModel.kt        // 新增: UI/生命周期/取消/状态
    SceneAssistantController.kt       // 可选: 触发源汇聚和单飞调度
  device/asr/
    AndroidSpeechRecognizerSource.kt  // 新增: SpeechRecognizer 实现
  device/input/
    AssistantShortcutTrigger.kt       // 新增: 屏幕按钮/硬件键统一成 trigger
  device/
    SpeechManager.kt                  // 扩展低优先级 speakText(),不要复用 SceneEvent.messageKey
```

模块边界:

- `sailens-vlm` 只放引擎契约:帧 + 完整 prompt -> 流式文本,不含产品策略。
- `sailens-describe` 负责 Describe 的 prompt、快照新鲜度和请求调度。
- `sailens-runtime` 负责模型来源、session 和加速器选择,不管最终落地哪个 runtime。
- `sailens-shell` 负责 UI、权限、系统 ASR、硬件键输入;TTS 仲裁在 `sailens-output`。
- `:app` 继续做 Koin 绑定,决定某个 edition 启用哪些具体实现。

## 4. SceneSnapshotProvider

VLM 每次只需要一张“当前画面”和一份轻量上下文,不需要持有完整 `SceneResult` 的 mask/bitmap。

建议新增:

```kotlin
data class SceneSnapshot(
    val frame: ImageFrame,
    val summary: SceneSnapshotSummary?,
    val capturedAtMs: Long,
)

data class SceneSnapshotSummary(
    val sequenceNumber: Long,
    val events: List<SceneEvent>,
    val obstacleCount: Int,
    val primaryObstacleLabel: String?,
    val passableRatio: Float?,
    val semanticBackend: String?,
    val obstacleBackend: String?,
)

interface SceneSnapshotProvider {
    fun currentSnapshot(
        maxFrameAgeMs: Long = 800,
        maxSceneAgeMs: Long = 1500,
    ): SceneSnapshot?
}
```

实现建议:

- 从 `ImageFrameProvider.frames` 和实时分析输出中维护最近一帧与最近分析摘要。
- 不把 `SceneAnalysisViewModel.latestSceneResult` 继续作为 assistant 的唯一数据源;可以把“保存最新分析摘要”抽成小 store,ViewModel 和 assistant 都读它。
- 快照过期时直接失败并提示“当前画面不可用”,不要用旧画面生成描述。
- 只保存轻量 summary,避免长期持有大 mask 或额外 bitmap。

## 5. 触发策略

第一版触发源分三类,优先级从高到低:

```kotlin
enum class AssistantTriggerKind {
    QUICK_DESCRIBE,     // 一键总结当前画面,不经过 ASR
    VOICE_QUESTION,     // ASR 识别到用户问题
    AUTO_OPPORTUNISTIC, // 后续可选: 低频自动补充描述
}
```

### 5.1 快捷触发(MVP 必做)

目标:用户按一下就听到当前画面摘要。

- 屏幕按钮先做成稳定入口,放在实时分析主界面的可触达位置。
- 硬件键作为同一个 trigger 的输入适配器,可先支持耳机/蓝牙媒体键或一个可配置快捷键。
- 快捷触发不启动 ASR,直接使用 `SceneDescriber.describe(userPrompt = null)`。
- 输出控制在 1-2 句中文,只讲正前方最重要的信息:可走区域、明显障碍、行人/车辆/台阶/门口等。
- 触发后给一个短 haptic 或 UI loading 状态,避免用户不知道是否已按到。

硬件键注意事项:

- 屏幕按钮是第一优先,因为系统对耳机媒体键的分发可能受当前前台应用、媒体会话、厂商系统和耳机型号影响。
- 如果接媒体键,在 app 前台且分析运行时启用;不要试图全局劫持媒体控制。
- 把硬件键映射封装成 `AssistantShortcutTrigger`,最终只向上游发 `QUICK_DESCRIBE`。

### 5.2 ASR 问答(第二步)

目标:用户按住/点按语音按钮后,问“前面是不是有台阶?”、“左边有没有人?”,系统用当前画面回答。

- 使用 press-to-talk 或 tap-to-talk,不要做常驻唤醒词。
- ASR 只负责音频转文本。
- 文本意图解析先用规则即可:
  - 空文本或低置信:提示重试。
  - 与画面相关的问题:走 `AnswerSceneQuestionUseCase`。
  - 非画面问题:第一版不处理或提示当前只支持画面相关问题。
- 后续如需更复杂的意图解析,可以接一个小 LLM,但它仍然在 ASR 之后。

### 5.3 自动触发(后续可选)

自动 VLM 要非常克制,否则会打断实时安全提示:

- 用户显式开启后才启用。
- 实时安全事件安静一段时间后再触发,例如最近 8-10 秒没有高优先级障碍/blocked 提示。
- 场景相对稳定、用户移动速度低、当前没有正在播报的安全事件。
- 全局冷却建议从 30-60 秒起步。
- 自动输出必须是低优先级,遇到实时安全事件立即取消或被打断。

## 6. 调度和仲裁

VLM 生成是秒级任务,需要单飞和可取消:

- assistant 同一时间只允许一个 VLM 请求运行。
- 新的 `QUICK_DESCRIBE` 可以取消旧的 assistant 请求并使用最新 snapshot 重跑。
- 实时安全事件优先级最高:当 `SceneEvent` 到来时,assistant 播报应被打断或延后。
- VLM 请求完成时检查 snapshot 是否已经过旧;如果完成太晚,丢弃结果或标注“刚才画面”。
- VLM 不参与 `collectLatest` 的实时帧处理,也不阻塞 `StartSceneAnalysisUseCase`。

建议的 TTS 优先级:

```text
P0 realtime safety events: person / vehicle / blocked / path danger
P1 explicit quick describe result
P2 ASR/VLM answer
P3 automatic/opportunistic describe
```

`SpeechManager` 可以扩展一个生成式文本入口,例如:

```kotlin
suspend fun speakText(
    text: String,
    priority: SpeechPriority,
    utteranceKind: String,
)
```

不要把 VLM 生成文本伪装成 `SceneEvent.messageKey`,否则会污染事件合并、字符串资源和 trace 语义。

## 7. ASR 选择

第一版推荐:

- 使用 Android 系统 `SpeechRecognizer`,封装为 `VoiceCommandRecognizer`。
- 优先尝试 on-device recognizer;不可用时再使用系统默认 recognizer。
- 使用 `checkRecognitionSupport()` / model download 能力判断设备是否支持当前语言与离线模型。
- 只做短句识别,限制一次识别窗口,避免常驻录音和耗电。
- 所有 Android API 细节留在 `sailens-shell`(`device/asr` 包),pipeline 模块只看文本结果。

接口草案:

```kotlin
interface VoiceCommandRecognizer {
    val state: StateFlow<VoiceRecognitionState>
    suspend fun listenOnce(languageTag: String = "zh-CN"): Result<VoiceCommand>
    fun cancel()
}

data class VoiceCommand(
    val text: String,
    val confidence: Float?,
    val source: VoiceCommandSource,
)
```

为什么不直接用 LLM 做 ASR:

- 音频 ASR 和文本/视觉推理是不同任务;把 LLM 放在音频前端会增加延迟、功耗和模型复杂度。
- Android 系统 ASR 能更快给出可用 MVP,并且可以被接口替换。
- 后续如果系统 ASR 在目标设备上不稳定,再新增本地 Whisper-like / Parakeet-like ASR 实现即可,上层不变。

## 8. VLM 模型选择原则

候选模型不要只看参数量,还要看端侧链路:

- 是否能转换到目标 runtime 支持的格式,例如 `.litertlm` / 其它 Qualcomm NPU 可加载格式。
- 是否支持图像输入路径,以及图像 encoder 是否也能落到目标加速器。
- INT4/INT8 量化后回答质量是否仍够用。
- 8 Gen 2 上的一次描述延迟、峰值内存和连续触发温升。
- 中文短描述和视觉问答能力是否稳定。

候选方向:

- **FastVLM-0.5B**: 值得 spike,但官方重点在 Apple 生态和研究实现,Android NPU 落地需要验证转换/runtime。
- **SmolVLM-500M-Instruct**: 体量合适,适合作为开放模型候选,仍需验证 Android NPU 转换和端侧质量。
- **LiteRT-LM / Qualcomm NPU 官方示例模型**: 更适合先证明 NPU runtime、交付和性能,即使未必是最终 VLM。

第一版应用侧不绑定具体模型,只要求实现一个真实 `VlmRuntimeFactory`。

## 9. 分阶段落地

### Phase 1: 快捷总结当前画面

- 新增 `SceneSnapshotProvider` 和轻量 snapshot store。
- 新增 `DescribeCurrentSceneUseCase`。
- 新增 `SceneAssistantViewModel` 或 `SceneAssistantController`。
- 在主界面加“总结当前画面”按钮。
- 扩展 `SpeechManager.speakText()` 和低优先级播报仲裁。
- VLM runtime 可先保持 unavailable;UI 根据 `SceneDescriber.isReady` 隐藏或禁用入口。

验收:

- 没有 VLM 模型时 app 正常运行,入口禁用或提示不可用。
- 有 VLM 模型时,手动触发不影响实时障碍提示。
- 连续点击不会并发跑多个 VLM。
- 触发、取消、耗时、backend 进入日志。

### Phase 2: ASR 问答

- 新增 `VoiceCommandRecognizer` 和 `AndroidSpeechRecognizerSource`。
- 增加麦克风权限和 press-to-talk UI。
- 新增 `AnswerSceneQuestionUseCase`。
- 复用同一个 `SceneSnapshotProvider` 和 VLM 调度器。
- ASR 失败、空文本、非画面问题都有明确提示。

验收:

- 无网络/无系统离线模型时有可解释 fallback。
- ASR 识别期间实时安全提示仍可打断。
- 问题和回答不写入 `SceneEvent.messageKey`。

### Phase 3: 硬件快捷键

- 新增 `AssistantShortcutTrigger`。
- 前台运行时接屏幕外触发,统一发 `QUICK_DESCRIBE`。
- 先做设备兼容日志,再决定是否作为正式入口。

验收:

- 屏幕按钮与硬件键走同一条 use case。
- 不影响系统媒体播放控制的基本预期。
- 按键抖动有 debounce。

### Phase 4: 低频自动描述

- 增加用户开关。
- 增加安静窗口、冷却、移动状态和事件优先级门槛。
- 自动结果低优先级播报,遇到安全事件取消。

验收:

- 自动描述不会抢实时避障播报。
- trace/log 能解释每次触发或 suppress 的原因。

## 10. 需要避免的重构

- 不要把 VLM 调用放进 `ProcessFrameUseCase` 或 `StartSceneAnalysisUseCase` 的每帧 flow。
- 不要为了 VLM 改动现有 obstacle/semantic 模型配置结构。
- 不要让 `sailens-guidance` 或 `sailens-describe` 依赖 Android `SpeechRecognizer`、`MediaSession`、`KeyEvent`。
- 不要用 `SceneEvent` 承载生成式回答文本。
- 不要把 ASR、VLM、TTS 都塞回 `SceneAnalysisViewModel`;它已经承担实时分析 UI,assistant 应该独立成 controller/viewmodel。

## 11. 参考资料

- LiteRT Qualcomm NPU: https://developers.google.com/edge/litert/next/qualcomm
- LiteRT-LM NPU: https://developers.google.com/edge/litert/next/litert_lm_npu
- Android `SpeechRecognizer`: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android media button handling: https://developer.android.com/media/legacy/media-buttons
- FastVLM: https://github.com/apple/ml-fastvlm
- SmolVLM-500M-Instruct: https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct
