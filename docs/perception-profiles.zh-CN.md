[English](perception-profiles.md) | **简体中文**

# 感知挡位与模型调度（As-Built）

本文描述当前代码中已落地的感知挡位（PerceptionProfile）架构与两个视觉模型的调度方式。
任何地方与本文不一致，以本文（和代码）为准。

> 历史：曾有第三个挡位 HIGH_ACCURACY + 独立实例分割模型（seg）做高风险精修。经真机 trace
> 评估，seg 在实测 workload 里对决策层零贡献却持续消耗读回/解码耗时，已整体移除（模型、
> RiskAnalyzer、seg 触发调度、mask 解码）。障碍物形状/遮挡改由 det 框与 sem mask 交叉验证
> 派生，见 §2 的 ObstacleOcclusionAnalyzer。

## 1. 两个挡位

```text
BASIC:   sem only
DEFAULT: sem + det
```

核心原则：sem 是导航底线（可通行区域/道路方向），优先级最高；det 是障碍物召回。

**频率哲学：挡位只决定模型组合，跑多快由设备尽力而为。**管线逐帧串行、相机侧
`KEEP_ONLY_LATEST` 自适应丢帧，天然自限流，不会失控。`PerceptionConfig` 的
`semanticTargetFps` / `detectionTargetFps` 默认全为 0（不限频），正值上限是留给
**动态降级 / 按设备调参**的旋钮（对 `forProfile` 返回值 copy 覆盖即可），不作为产品默认限定。
`detectionResultTtlMs`（500ms）仍生效：det 未运行帧的轨迹按时间过期。

## 2. 调度模型

旧的 `frameIndex % 2` 奇偶交替已删除（连同 `PerceptionMode` / `InferenceStrategy` 枚举）。
现在每个处理帧独立判定：

```text
Camera Frame
  ↓
PerceptionProfileManager.activeConfig（会话内不变）
  ↓
PerceptionScheduler：sem 到间隔了吗？det 到间隔了吗？
  ↓
sem ∥ obstacle 槽位（det；async 并行，各自 limitedParallelism(1)）
  ↓
ObstacleTracker（det 输出更新轨迹；未运行帧用预测补偿，轨迹按 TTL 过期）
  ↓
AnalyzeSceneUseCase：ObstacleOcclusionAnalyzer（det∩sem 交叉验证）→ 连通性 / 分析 / 决策 / 播报
```

关键实现点：

- **调度器**（`PerceptionScheduler`）默认放行（不限频）；对应 `*Fps` 字段 > 0 时按
  `nowMs - lastRun >= 1000/fps` 限频，`markXxxRun` 在模型完成后打点，反映真实吞吐。
  时钟由 app 层注入 **`SystemClock.elapsedRealtime()`（单调）**——不用墙钟
  （`currentTimeMillis` 会被校时跳变，回跳会让轨迹 TTL 失效、持续播报已消失的障碍），
  也不用 `frame.timestamp`（CameraX 是纳秒时间戳）。
- **障碍物遮挡（det∩sem 交叉验证）**：`ObstacleOcclusionAnalyzer` 把跟踪障碍物 bbox 的
  接地带（框底部一段，`occlusionBandHeightRatio`）从 sem 可行走 mask 里单向抠掉，再算连通性。
  价值场景是 sem 把障碍脚下误判成可行走、而 det 抓到的障碍：抠除接地带修正这种 false clear，
  障碍挡路会自然体现为 blockage 上升。仅对足够近的障碍生效（bbox 底边 ≥ `occlusionMinBottomY`），
  只取接地带而非整框以免过度清除远处可行走区。设计单向只减、绝不新增可行走像素，因此只可能
  提高警觉、不会降低安全性。输入用 tracker 输出的稳定轨迹（det 间隔帧由预测补偿，信号逐帧连续）。
- **轨迹 TTL**（`ObstacleTracker.predict`）：det 未运行期间轨迹按 `detectionResultTtlMs`
  时间过期，不按帧计数（det 降频后帧计数会误杀轨迹）；`missedFrames` 只在 det 真正
  运行的 update 帧累加。
- **BASIC 挡位**：障碍物从 semantic mask 连通域提取（`ObstacleExtractor.extractFromSemantic`），
  不初始化 det provider。

## 3. 挡位选择与生效路径

```text
设置页（SettingsScreen，单选组）
  ↓ setPerceptionProfile
PerceptionSettingsStore（SharedPreferences 持久化，presentation）
PerceptionProfileManager.selectProfile（domain，运行时选择）
  ↓ 下次导航会话开始
StartSceneAnalysisUseCase → profileManager.activateSelected()
  （在 obstacle provider 初始化之前，把选择落成本次会话的 activeConfig）
  ↓
ProcessFrameUseCase 逐帧读取 activeConfig
```

- 切换统一收口到**会话边界**：provider 是按挡位在会话开始时初始化的。DEFAULT→BASIC 中途
  切换本身安全，但收口到会话边界可避免会话内配置漂移。设置页有"下次开始导航时生效"提示。
- 应用启动时 `profileBindingsModule` 从 `PerceptionSettingsStore` 读初始挡位装配
  `SailensRuntimeProfile`。当前持久化默认值为 **DEFAULT**（`PerceptionSettingsStore.DEFAULT_PROFILE`）。
- 感知挡位与 runtime profile（决定各模型的加速器，目前只有 `standard`）正交。

## 4. 观测

- 实时 debug 面板显示"感知挡位"（`SceneDebugInfo.perceptionProfile`）。
- trace 的 `pipelineMode` 记录挡位名小写（`basic` / `default`）。
  旧 trace 文件中该字段可能是 `combined` / `semantic_only` / `high_accuracy`。
- 每帧 trace 记录 `obstacleRunKind`（`det` / `none`），回放报告的 `obstacleRunFps` 是 det 的
  真实运行频率；默认不限频时应接近 `pipelineOutputFps`（配置了上限则为 `min(上限, pipelineOutputFps)`）。
  历史 trace 里出现过的 `seg` 会作为一次障碍运行计入总数。指标口径见 [`trace_metrics_guide.zh-CN.md`](trace_metrics_guide.zh-CN.md)。

## 5. 后续计划（未实现，按优先级）

1. **occlusion 参数标定**：多份实地 trace 下调 `occlusionMinBottomY` / `occlusionBandHeightRatio`，
   目标是减少误报（抠错非障碍的可行走像素）而非压制真提醒。
2. **动态降级**：按 thermal state / 电量 / 推理延迟 / 丢帧率自动降挡（DEFAULT→BASIC），带切换冷却。
3. **imgsz 调优**：输入尺寸是当前推理耗时的下一个杠杆（见 [`models.zh-CN.md`](models.zh-CN.md)）。
4. **0.2 模型交付**：VLM（NPU）+ 模型下载器，见 [`vlm-asr-assistant-plan.zh-CN.md`](vlm-asr-assistant-plan.zh-CN.md)。
