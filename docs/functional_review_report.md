# Sailens 环境感知 App 代码审查报告（2026-05-20）

## 1. 项目定位与总体判断

`Sailens` 本质上是一个 **面向视障用户的端侧环境感知与主动提醒系统**。从工程形态上看，它与轻量级 ADAS/主动安全辅助链路高度相似：

`CameraX -> Frame Stream -> Semantic Perception -> Scene Analysis -> Event Decision -> UI/TTS/Haptics`

当前项目已经具备较清晰的模块分层：
- `:camera`：CameraX、帧采集、分析流入口
- `:data`：ML 模型、OpenCV、深度与日志实现
- `:domain`：感知/分析/决策核心逻辑
- `:presentation`：Compose UI、状态机、语音/振动适配
- `:app`：Koin 装配与入口壳层
- `:ux`：通用 UI 资源

本报告后续的性能判断，默认以 **`Snapdragon 8 Gen 3` 及以上 Android 旗舰 SoC** 作为主要目标设备预算，而 `S22` 只作为早期保守参考样本，不再作为上限假设。

**总体评价：架构方向正确，实时视觉辅助主链路已成型；2026-05-20 这轮审查中列出的 P0 / P1 / P2 问题已在当前代码中完成收敛。**

当前项目依然更接近“研发验证版”而非“稳定可交付版”，但主要矛盾已经从**主链路正确性/热路径明显缺陷**，转移为：
1. **观测、回放与评估已具备正式 session list / report 入口，但导出/分享与批量对比能力仍不完整**
2. **性能预算已可在 replay 报告中判读，但仍缺主链路 runtime 告警与更正式的可视化入口**
3. **后续 `YOLO + DDRNet` 融合前，还需要继续补齐 Phase 5 的数据基线与评估闭环**

### 1.1 状态更新（2026-05-21）

- 本报告第 4~6 节列出的 P0 / P1 / P2 问题，现已全部在代码层面落地修复。
- 已验证到的落地点包括：
  - `LiteRTSegmenter` 输出 mask 快照化
  - `EventConflictResolver` 全量抑制冲突事件并补齐测试
  - `SceneAnalysisView` preview / overlay 共用同一 `contentScale`
  - `CameraViewModel` 预览与分析分辨率解耦（`1920x1080` vs `640x360`）
  - `BinaryMask.visualize()` 改为批量 `setPixels()`
  - `StopSceneAnalysisUseCase.release()` + `SceneAnalysisViewModel.onCleared()` 完整释放资源
  - `ImageFrame` 改为平台无关 `IntArray` 像素缓冲
  - `ObstacleExtractor` / `ConnectivityChecker` 热路径完成 primitive 化
  - 域层 Koin 装配已移至 `app/.../DomainBindingsModule.kt`
  - `SceneAnalysisView` 已具备正式 trace replay 页面入口（session list / report page / latest report / copy summary）

---

## 2. 我确认过的关键运行链路

### 2.1 视觉链路
- `ImageFrameAnalyzer` 将 `ImageAnalysis` 帧转为 `ImageFrame` 并写入 `SharedFlow`
- Flow 设置为 `extraBufferCapacity = 8` 且 `DROP_OLDEST`
- `StartSceneAnalysisUseCase` 在启动时初始化分割模型，然后对每帧执行：
  - `ProcessFrameUseCase`
  - `AnalyzeSceneUseCase`
  - `DecideEventsUseCase`
- `SceneAnalysisViewModel` 用 `collectLatest` 消费结果并更新 UI

这条链路对于“高频视觉 + 允许丢旧帧”的场景是合理的，思路符合实时辅助系统而不是离线批处理系统。

### 2.2 决策链路
`DecideEventsUseCase` 的处理顺序是：
1. `EventGenerator`
2. `EventConflictResolver`
3. `EventMerger`
4. `CooldownManager`
5. 最终按优先级排序

这个顺序是合理的，**不建议随意重排**。冲突消解必须发生在合并和冷却之前，否则会让低价值事件穿透到最终播报层。

---

## 3. 当前代码中的优点

### 3.1 分层与职责划分基本清晰
- `:camera` 没有污染业务决策
- `:data` 通过 `PerceptionRepository` / `DepthRepository` 等接口对接 `:domain`
- `:presentation` 没有承载感知算法本体

### 3.2 采用 `collectLatest` 与 `DROP_OLDEST` 是正确的实时策略
对于视障辅助这类“当前帧比旧帧更重要”的应用，保留最新状态优先于处理所有历史帧，这一点设计正确。

### 3.3 分析与决策模块可继续演进
`ConnectivityChecker`、`RoadSafetyAnalyzer`、`GroundTypeDetector`、`EventGenerator` 的拆分方式是对的，后续很适合做参数调优、测试补强和多策略 A/B 对比。

---

## 4. P0 级问题（应优先修复）

### P0-1 分割结果缓冲区存在共享可变引用风险【已修复】
**位置**：`data/.../LiteRTSegmenter.kt`

**当前状态（2026-05-21）**：已修复。`LiteRTSegmenter` 在构造 `SegmentationMask` 前对 `cachedResultMask` 执行 `clone()`，保证下游读取的是稳定快照而不是复用写缓冲。

当前 `cachedResultMask` 是复用数组；若直接把它包装到 `SegmentationMask` 后下游继续读取，而下一帧推理又复写同一数组，就会出现：
- UI 遮罩抖动/撕裂
- 分析模块读取到被下一帧污染的数据
- 事件判断出现随机误报/漏报

**结论**：这是典型的实时流水线数据别名问题，必须保证每帧输出对下游是“只读快照”。

### P0-2 事件冲突消解逻辑只处理首个命中对象【已修复】
**位置**：`domain/.../EventConflictResolver.kt`

**当前状态（2026-05-21）**：已修复。当前实现使用 `removeAll` 清理所有满足规则的 suppressed 事件；并已新增 `EventConflictResolverTest` 覆盖 `BLOCKED + 多个中心障碍` 场景。

当前实现使用 `find` 找 `suppressed` 事件，只会检查并移除第一个命中项。
结果是：当 `BLOCKED` 存在时，多条中心区域障碍事件可能只被过滤一条，其余仍会泄漏到播报层。

**结论**：这会直接降低提示系统的可信度，并增加认知负荷。

### P0-3 摄像头预览与分割遮罩缩放策略不一致【已修复】
**位置**：`presentation/.../SceneAnalysisView.kt`

**当前状态（2026-05-21）**：已修复。`CaptureView` 同时把同一个 `contentScale` 传给 `CameraView` 与遮罩 `Image`，横竖屏下 overlay 与预览已在代码层面对齐。

预览 `CameraView` 的 `contentScale` 在横竖屏可变化，但 `segMask` 叠加层写死为 `ContentScale.Fit`。

**结果**：横屏时遮罩与真实画面空间不对齐，视觉反馈失真。

**结论**：对于环境辅助产品，这是安全级 UX 问题，不只是显示小瑕疵。

---

## 5. P1 级问题（高收益、建议尽快推进）

### P1-1 `ImageAnalysis` 与 `Preview` 共用 1080p 配置，浪费算力【已修复】
**位置**：`camera/.../CameraViewModel.kt`

**当前状态（2026-05-21）**：已修复。`CameraViewModel` 已拆分 `PREVIEW_SIZE = 1920x1080` 与 `ANALYSIS_SIZE = 640x360`，并分别配置自己的 `ResolutionSelector`。

当前 `Preview` 和 `ImageAnalysis` 都走同一套 `1920x1080` 偏好分辨率。
但下游模型最终输出只有 `256x128` mask，分析流没必要吃 1080p。

**影响**：
- `ImageProxy -> Bitmap` 转换成本过高
- OpenCV 预处理输入尺寸过大
- 更容易掉帧、发热、拉高电耗

**建议**：预览保持高分辨率；分析流降到 `640x360` 或 `640x480`。

### P1-2 `BinaryMask.visualize()` 存在严重 JNI 热点【已修复】
**位置**：`presentation/.../ext/Image.kt`

**当前状态（2026-05-21）**：已修复。实现已改为先填充 `IntArray`，再一次性调用 `bitmap.setPixels()`，不再逐像素 JNI 写入。

当前逐像素调用 `bitmap[x, y] = color`，本质上是大量 `setPixel` JNI 往返。

**影响**：32,768 次像素写入/帧，容易让 UI 热路径出现卡顿。

**建议**：改为本地 `IntArray` 填充后一次性 `bitmap.setPixels()`。

### P1-3 启停生命周期不完全对称【已修复】
**位置**：`StartSceneAnalysisUseCase.kt` / `StopSceneAnalysisUseCase.kt` / `SceneAnalysisViewModel.kt`

**当前状态（2026-05-21）**：已修复。`StopSceneAnalysisUseCase.invoke()` 负责 stop/reset，`release()` 负责释放 `PerceptionRepository` / `InstanceSegmentationProvider`；`SceneAnalysisViewModel.onCleared()` 已统一调用释放链路。

当前启动阶段会初始化感知模型；停止阶段主要是 `reset()` 分析器和跟踪器，但 `PerceptionRepository.release()` 没有纳入主停机链路。

**风险**：
- GPU / LiteRT 资源释放不完整
- 多次启动停止后出现资源残留
- 后续若引入更复杂 delegate，更容易出问题

**建议**：在 stop/release 语义上明确区分：
- `stop()`：停止业务流并重置状态
- `release()`：释放模型/委托/硬件资源

---

## 6. P2 级问题（结构债务，建议分阶段治理）

### P2-1 `:domain` 仍然依赖 Android 类型【已修复】
**位置**：`domain/.../model/perception/ImageFrame.kt`

**当前状态（2026-05-21）**：已修复。`ImageFrame` 已从 `Bitmap` 改为平台无关的 `IntArray` 像素缓冲，域层不再直接依赖 Android 图像类型。

`ImageFrame` 当前持有 `android.graphics.Bitmap`。这会导致：
- `:domain` 失去纯 Kotlin 领域层属性
- 单元测试与 KMM/纯 JVM 复用能力变差
- 上层 Android 图像模型与业务模型强耦合

**建议方向**：
- 把 Android 图像载体收敛到 `:camera` / `:data`
- `:domain` 只接收平台无关像素缓冲、tensor 输入或抽象图像接口

### P2-2 DI 装配仍然混入 `:domain`【已修复】
`domain/di/DomainModule.kt` 已移除，当前 Koin 装配集中在外层 `app/.../DomainBindingsModule.kt`。

### P2-3 Flood Fill / Connected Component 热路径对象分配偏多【已修复】
**位置**：`ObstacleExtractor.kt`、`ConnectivityChecker.kt`

**当前状态（2026-05-21）**：已修复。热路径已切换为 packed coordinate + `IntArrayQueue` / `IntArrayList`，代码中已不再使用 `Pair<Int, Int>` 的 BFS/FloodFill 方案。

BFS/FloodFill 仍在大量使用 `Pair<Int, Int>` 和 `MutableList`。
在连续帧场景下，这会放大 GC 压力。

**建议方向**：
- packed coordinate（`Int` 打包 x/y）
- primitive queue / primitive list
- 逐步改造成零额外对象分配热路径

---

## 7. 产品能力层面的判断（从“自动驾驶辅助思路”看）

虽然这是面向行人的辅助 App，而不是车规 ADAS，但从系统工程视角，两者共享以下核心约束：

### 7.1 必须优先保证“当前态正确”而不是“历史帧完整”
你的 Flow 策略已经符合这一点，这是对的。

### 7.2 感知链路的目标不是像素级完美，而是“足够稳定的决策输入”
因此：
- 稳定器（debounce / smoother）是必要的
- 决策链路必须抑制低价值噪音
- UI/TTS/Haptics 必须以**高优先级事件优先**为原则

### 7.3 视觉辅助类产品的第一性能原则是：
**把 CPU/GPU 用在“提高下一条决策的正确率”上，而不是浪费在无意义的像素搬运与对象分配上。**

上一轮最值得立刻优化的点——分析流分辨率、mask 可视化方式、flood fill / connected component 数据结构——已经在当前代码中完成落地；下一轮重点应转向 replay / report / 预算告警。

---

## 8. 当前建议的实施优先级（已从“修旧问题”切到 Phase 5）

### 第一阶段：观测与回放入口补齐
1. 增加 trace 导出/分享能力
2. 增加多 session / A-B 对比入口
3. 视需要补开发者菜单 / 更丰富的调试导航

### 第二阶段：性能预算显式化
4. 为 `p95TotalPipelineMs`、`droppedFrames` 等指标增加正式阈值告警
5. 增加最小 report 展示页或导出摘要

### 第三阶段：为双模型融合做基线
6. 建立真实场景 trace 基线
7. 再引入 `YOLO + DDRNet` 并做 replay A/B 对比

---

## 9. 当前验收结果（针对本报告列出的问题）

### 正确性
- [x] 连续处理多帧时，分割 mask 不再出现数据污染
- [x] `BLOCKED` 存在时，相关低优先级障碍/收窄事件被稳定抑制
- [x] 横屏/竖屏下 overlay 与预览严格对齐

### 性能
- [x] 分析流分辨率已独立降到 `640x360`
- [x] `visualize()` 已不再使用逐像素 JNI 写入模式

### 工程质量
- [x] 已新增反映业务冲突消解逻辑的单元测试
- [x] 文档中的问题清单、优先级和实施计划已更新到当前状态

---

## 10. 结论

这不是一个“推倒重来”的项目，恰恰相反：**它已经有了一个值得继续投资的骨架。**

截至 2026-05-21，上一轮审查中点名的问题已经完成收敛；当前最需要做的，不再是反复修正主链路，而是把已有链路升级为**可观测、可回放、可评估**：
- 把 replay/report 接到实际调试入口
- 把性能预算做成可告警、可比较的输出
- 在此基础上再推进 `YOLO + DDRNet` 双模型融合

如果按这个顺序推进，`Sailens` 的下一阶段重点将从“修主链路”切换到“建立可验证的演进体系”。
