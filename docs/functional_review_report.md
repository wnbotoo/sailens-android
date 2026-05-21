# Sailens 环境感知 App 代码审查报告（2026-05-21）

## 1. 总体判断

`Sailens` 是一个面向视障用户户外行走的端侧环境感知辅助系统。它的主链路与轻量 ADAS 很接近：

`CameraX -> Frame Stream -> Semantic Segmentation -> Scene Analysis -> Event Decision -> UI / TTS / Haptics -> Trace Replay`

当前架构方向是正确的：模块边界基本清晰，`domain` 里承载感知分析与决策，`data` 里承载 LiteRT / OpenCV / 文件服务，`camera` 负责帧入口，`presentation` 负责 Compose 状态、反馈与调试页面。

上一轮文档中提到的早期 P0/P1 问题大多已经完成收敛。本轮 review 的重点不再是推倒主链路，而是把项目从“研发验证版”继续推进到“可外场迭代版”：更可靠的实时决策、更完整的 trace 导出、更明确的性能预算，以及为后续 `YOLO + DDRNet` 融合建立可回放基线。

## 2. 已确认的主链路

### 2.1 实时帧链路

- `ImageFrameAnalyzer` 从 CameraX `ImageAnalysis` 收帧，并通过 `MutableSharedFlow<ImageFrame>` 输出。
- `SharedFlow` 使用 `DROP_OLDEST`，符合“当前帧比历史帧重要”的实时辅助原则。
- `StartSceneAnalysisUseCase` 初始化感知仓库后，对每帧执行：
  - `ProcessFrameUseCase`
  - `AnalyzeSceneUseCase`
  - `DecideEventsUseCase`
  - `TraceService.recordFrame`
- `SceneAnalysisViewModel` 通过 `collectLatest` 消费结果，生成 overlay bitmap，并驱动语音 / 震动反馈。

### 2.2 决策链路

`DecideEventsUseCase` 顺序仍然合理：

1. `EventGenerator`
2. `EventConflictResolver`
3. `EventMerger`
4. `CooldownManager`
5. priority 排序

这个顺序建议继续保持。冲突消解必须早于合并和冷却，否则低价值事件会污染反馈节奏。

## 3. 当前优点

### 3.1 分层有继续演进空间

`camera / data / domain / presentation / app` 的边界总体健康，核心业务没有散落到 Activity 或 Compose 里。后续引入双模型、回放评估、设置项时，不需要大规模重构。

### 3.2 实时策略方向正确

`ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`、`SharedFlow DROP_OLDEST` 和 `collectLatest` 的组合适合视障辅助场景。此类产品不应追求处理所有帧，而应保证最新风险判断尽快到达用户。

### 3.3 Trace / Replay 已经有产品化雏形

项目已经有 session trace、frame trace、session summary、replay report、budget warning 和 Compose report page。这是后续做模型融合、参数调优和外场回归的关键地基。

## 4. 本轮发现并已修复的问题

### P0-1 `CooldownManager` 按事件类别冷却会误抑制新方向障碍【已修复】

**位置**：`domain/.../CooldownManager.kt`

原实现用 `EventCategory` 作为冷却 key。结果是：如果刚播报“左侧障碍”，随后 2 秒内出现“前方障碍”，两者同属 `OBSTACLE`，后者会被冷却过滤。

这对视障辅助是高风险问题，因为“方向变化”本身就是新的决策信息。自动驾驶里同理，目标类别相同不代表风险实体相同，仲裁 key 需要包含空间语义。

**本轮改动**

- 冷却 key 改为优先使用 `SceneEvent.dedupeKey`。
- 保留 category 对应的 cooldown 时长。
- 新增 `CooldownManagerTest` 覆盖：
  - 同 dedupeKey 的重复障碍被抑制
  - 同 category 但 dedupeKey 不同的障碍可以穿透

### P0-2 `IntArrayList.get()` 越界判断包含 `size`【已修复】

**位置**：`domain/.../PrimitiveCollections.kt`

原实现使用 `index in 0..size`，会允许 `index == size`。这在热路径 primitive collection 里属于典型边界 bug，可能读到未定义的旧值。

**本轮改动**

- 改为 `index in 0 until size`。
- 新增单元测试覆盖 `index == size` 必须抛错。

### P1-1 Trace 写盘 flush 与会话切换存在并发窗口【已修复】

**位置**：`data/.../FileTraceService.kt`

`FileTraceService` 有周期 flush 线程，同时 `startSession()` / `finishSession()` 也会 flush。原 `flushToDisk()` 没有串行化，理论上存在会话切换时旧队列内容被写入新文件或并发 writer 交错的风险。

**本轮改动**

- `flushToDisk()` 加 `@Synchronized`。
- `isRunning / activeSessionId / traceFile` 加 `@Volatile`。

### P1-2 Trace report 缺少原始 JSONL 分享入口【已修复】

**位置**：

- `presentation/.../TraceReplayView.kt`
- `presentation/.../SceneAnalysisView.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/trace_file_paths.xml`

之前只能复制 report 摘要，不能把原始 `trace_<sessionId>.jsonl` 分享给电脑、标注工具或评估脚本。

**本轮改动**

- 新增 `Share JSONL` 按钮。
- 新增 `SceneAnalysisUiEffect.ShareTraceFile`。
- 通过 AndroidX `FileProvider` 安全分享 app internal `files/traces/` 下的 JSONL。

### P2-1 Camera 分析线程释放 API 更换为兼容写法【已修复】

**位置**：`camera/.../CameraViewModel.kt`

将 `executor.close()` 改为 `executor.shutdown()`，避免依赖不必要的 Java API 语义，更符合 Android 工程常规写法。

## 5. 本轮追加修复的问题

### P1-3 `ImageFrameAnalyzer` 每帧 `ImageProxy.toBitmap()` 成本偏高【已修复】

**位置**：`camera/.../ImageFrameAnalyzer.kt`

原路径是：

`YUV_420_888 ImageProxy -> Bitmap -> IntArray -> ImageFrame -> Bitmap -> OpenCV Mat`

这条链路有两次像素搬运和至少一次 bitmap 对象参与。即使分析分辨率已降到 `640x360`，长期外场运行仍会增加 CPU、内存带宽、GC 与发热压力。

**本轮改动**

- `ImageFrameAnalyzer` 通过 `YuvToRgbaFrameConverter` 直接从 `ImageProxy.PlaneProxy` 转为 RGBA byte buffer。
- `ImageFrame` 改为平台无关的 `pixelBytes + ImagePixelFormat`。
- `OpenCVImageProcessor` 直接把 RGBA byte buffer 写入 `CV_8UC4 Mat`，不再回建 Bitmap。
- 新增 `FramePreprocessor` 接口，后续可以接 CPU/OpenCV、RenderScript 替代、GPU/NNAPI 友好的预处理实现；本轮未启用 GPU/NNAPI。
- 清理 `ImageFrameAnalyzer` 和 `OpenCVImageProcessor` 中的大段旧注释实现。

### P1-4 ViewModel `onCleared()` 使用 `runBlocking` 释放模型资源【已修复】

**位置**：`presentation/.../SceneAnalysisViewModel.kt`

`runBlocking` 在 ViewModel 清理阶段可能阻塞主线程。如果 LiteRT / GPU delegate 释放耗时，页面退出或配置变化会有卡顿风险。

**本轮改动**

- `onCleared()` 不再直接 `runBlocking`。
- 模型 / OpenCV / LiteRT 释放进入独立 IO release scope。
- release job 完成后自动取消 release scope，避免长期挂起。

### P1-5 运行时性能预算还没有进入 live UI【已修复】

Replay report 已有 `p95TotalPipelineMs` 和 dropped frame warning，但 live 页面还看不到当前会话预算状态。

**本轮改动**

- 新增 `PipelineBudget`，统一 replay 与 live 阈值。
- `StartSceneAnalysisUseCase` 增加最近 30 帧 runtime window。
- `SceneDebugInfo` 增加当前耗时、近期 avg/p95、近期 dropped rate、runtime budget 状态。
- Live debug 面板展示预算状态，不影响正式语音/震动反馈。

### P2-2 Trace parser 使用手写正则解析 JSON【已修复】

**位置**：`domain/.../TraceReplay.kt`

当前 parser 对现有 JSONL 足够工作，但正则 JSON 解析长期会变脆，字段嵌套、转义或格式变化都可能引入隐性 bug。

**本轮改动**

- `domain` 显式依赖 `kotlinx.serialization-json`。
- `TraceReplayParser` 改为 `Json.parseToJsonElement(...).jsonObject` 结构化解析。
- 保留 `navigationPassableRatio` 等新增字段的默认回退。

### P2-3 注释旧代码和未使用 import 需要清理【已修复】

`ImageFrameAnalyzer.kt`、`OpenCVImageProcessor.kt` 里还保留较大段旧实现注释。研发期可以理解，但继续扩展前应清理，避免误导下一轮模型融合实现。

**本轮改动**

- 删除旧 `ImageFrameAnalyzer` 注释实现。
- 删除 `OpenCVImageProcessor` 中旧的注释替代实现。
- 删除未编译的旧 `YOLO11InstanceProvider.kt` 注释文件，后续按 Phase 6 重新实现可测试版本。
- 清理 mapper / presentation 中已废弃的注释代码。

## 6. 自动驾驶式演进建议

`Sailens` 不是车规 ADAS，但它共享同一类系统约束：

- 感知链路必须稳定输出当前态。
- 决策层要抑制低价值噪声，但不能压掉新的高风险空间信息。
- 模型增强必须通过可回放 trace 验证，而不是主观感觉。
- 端侧推理预算要优先服务“下一条可靠提醒”，而不是视觉效果。

后续 `YOLO + DDRNet` 建议定位为融合增强：

- `DDRNet` 每帧输出全局语义，负责可通行区域、道路、人行道、地面结构。
- `YOLO` 低频或异步输出框级目标，负责人、车、骑行者、静态障碍。
- `ObstacleTracker` 在 YOLO 空档帧维持目标连续性。
- Replay A/B 对比必须先于正式默认启用。

## 7. 当前结论

项目不需要重写。它现在最需要的是继续补齐“可观测、可导出、可评估、可预算”的工程闭环。

本轮已把会影响安全提醒的冷却 bug、primitive collection 边界 bug、trace 写盘并发风险、原始 trace 分享、帧转换热路径、非阻塞 release、live runtime budget、结构化 trace parser 和旧注释债务一起收口。

下一步建议进入外场验证：采集多组真实户外 trace，用新的 JSONL 分享入口导出样本，再基于 replay/live budget 判断是否具备引入 `YOLO + DDRNet` 融合的算力空间。
