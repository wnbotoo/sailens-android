# Trace Metrics Guide

本文说明 Sailens trace / replay report 中各项指标的含义、计算方式和调优价值。目标是让新人能根据一份 report 快速判断瓶颈在 Camera 输入、YOLO 推理、后处理、决策逻辑，还是 UI mask 渲染。

## Trace 数据来源

Trace 是 JSONL 文件，每一行是一个事件：

- `session_start`：一次导航会话开始。
- `frame`：一帧完整 pipeline 结果。
- `overlay_render`：UI 层完成一次 mask overlay 渲染。
- `session_summary`：会话结束后的聚合摘要。
- `error`：pipeline 或 trace 中出现异常。

运行时写入位置由 `FileTraceService` 管理，replay report 由 `TraceReplayParser` 和 `BuildTraceReplayReportUseCase` 从 JSONL 重新计算。

## Frame 与丢帧

### `frames`

含义：pipeline 实际完成处理的帧数。

计算方式：`session_summary.totalFrames`，也就是成功记录的 `frame` 事件数量。

用途：衡量 pipeline 实际产出多少帧结果。

调优判断：

- `frames` 很少但测试时长很长：pipeline 过慢，通常是模型推理或输出读取阻塞。
- `frames` 正常但提示很少：决策层阈值、事件 cooldown 或语义/实例识别质量需要检查。

### `observed`

含义：根据 sequence number 推断相机分析流中出现过的帧数。

计算方式：

```text
observed = frames + droppedFrames
```

用途：估计 CameraX 输入侧实际送来了多少帧。

调优判断：

- `observed` 很高、`frames` 很低：Camera 输入速度远高于 pipeline 消化能力。
- 此时优先看 `avgInferenceMs`、`semMs`、`segMs`，再考虑降低分析分辨率、语义跳帧、seg 交替运行。

### `dropped` / `droppedFrameRate`

含义：由于 `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` 和 Flow backpressure，pipeline 没来得及处理的帧。

计算方式：

```text
droppedFramesSinceLast = currentSequenceNumber - previousSequenceNumber - 1
droppedFrameRate = droppedFrames / observed
```

用途：衡量实时性。避障应用不要求处理每一帧，但高丢帧会导致动态目标提示不稳定。

调优判断：

- `< 10%`：理想。
- `10% - 50%`：可用但需要关注动态障碍物。
- `> 50%`：pipeline 明显过载。优先优化模型尺寸、输出读取、native 预处理和推理策略。

## FPS 指标

### `cameraInputFps`

含义：CameraX 分析流的输入 FPS。

计算方式：

```text
cameraDurationMs = lastFrameTimestamp - firstFrameTimestamp
observedIntervals = processedFrameIntervals + droppedFrames
cameraInputFps = observedIntervals * 1000 / cameraDurationMs
```

说明：CameraX 的 `imageInfo.timestamp` 通常是纳秒，report 会转换成毫秒。测试用例或旧 trace 如果写入的是毫秒，也会兼容。

用途：确认相机侧是否按预期输出，例如 30 FPS、24 FPS 或更低。

调优判断：

- `cameraInputFps` 高、`pipelineOutputFps` 低：pipeline 是瓶颈。
- `cameraInputFps` 本身低：检查 CameraX 分辨率、曝光、设备温控、后台负载。

### `pipelineOutputFps`

含义：pipeline 实际产出 SceneResult 的 FPS。

计算方式：

```text
pipelineOutputFps = (frames - 1) * 1000 / (lastPipelineCompletedAt - firstPipelineCompletedAt)
```

用途：这是用户实际感受到的环境理解刷新率。

调优判断：

- 对辅助避障，`5-10 FPS` 已有可用价值，但动态目标会抖。
- `< 3 FPS` 时，提示容易滞后，车辆/行人经过时会出现“有时提示、有时不提示”。

### `pipelineThroughputFps`

含义：按平均感知耗时反推的理论 pipeline 吞吐。

计算方式：

```text
pipelineThroughputFps = 1000 / avgInferenceMs
```

注意：这里的 `avgInferenceMs` 是 `ProcessFrameUseCase` 的总感知耗时，包含语义分割、实例分割、输出读取、后处理和跟踪，不等于单个模型的纯推理时间。

用途：快速判断 pipeline 是否有能力追上相机输入。

调优判断：

- `pipelineThroughputFps` 远低于 `cameraInputFps`：必然丢帧。
- 如果 `pipelineThroughputFps` 与 `pipelineOutputFps` 差距大，说明调度、collectLatest、UI 或其他异步环节可能有额外损耗。

### `semanticRunFps`

含义：YOLO26-sem 实际运行频率。

计算方式：

```text
semanticRunFps = semanticRunCount * 1000 / durationMs
```

用途：确认 semantic frame skipping 是否生效。

调优判断：

- 如果 `enableSemanticFrameSkipping=false`，它应接近 `pipelineOutputFps`。
- 如果开启隔帧，通常约为 `pipelineOutputFps / semanticFrameInterval`。
- 道路 mask 抖动明显时，不建议盲目降低该值。

### `semanticModelFps`

含义：只按 YOLO26-sem 模型运行耗时反推的模型吞吐。

计算方式：

```text
semanticModelFps = 1000 / avgSemanticInferenceMs
```

其中 `avgSemanticInferenceMs` 只统计实际运行 semantic 的帧，不把跳过帧的 0ms 混进去。

用途：判断 sem 模型本身是否过重。

调优判断：

- `semanticModelFps` 很低：优先考虑低分辨率 sem 模型、量化、delegate、输出降采样。
- 如果 `semanticModelFps` 还可以，但 `avgSemanticOutputReadMs` 高：瓶颈在输出 tensor 读取或后处理。

### `instanceRunFps`

含义：YOLO26-seg 实际运行频率。

计算方式：

```text
instanceRunFps = instanceRunCount * 1000 / durationMs
```

用途：确认 `SIMULTANEOUS` / `ALTERNATING` 是否符合预期。

调优判断：

- `ALTERNATING` 下应约为 `pipelineOutputFps / 2`。
- 动态障碍物漏报明显时，可以提高 seg 运行频率，但要看是否会拖垮 sem 与总 pipeline。

### `instanceModelFps`

含义：只按 YOLO26-seg 模型运行耗时反推的模型吞吐。

计算方式：

```text
instanceModelFps = 1000 / avgInstanceInferenceMs
```

用途：判断实例模型本身是否是瓶颈。

调优判断：

- 如果 `instanceModelFps` 明显高于 sem，而动态障碍物仍漏报，问题可能在 tracker 稳定帧阈值、confidence threshold、类别映射或 cooldown。
- 如果 `instanceModelFps` 也很低，优先降低 seg 输入尺寸或保持 alternating。

### `maskRenderFps`

含义：UI 层成功生成并更新 mask bitmap 的 FPS。

计算方式：

```text
maskRenderFps = maskRenderCount * 1000 / durationMs
```

其中 `maskRenderCount` 只统计 `bitmapRendered=true` 的 overlay render 事件。

用途：判断 overlay 渲染是否拖慢 UI 或导致画面更新慢。

调优判断：

- 当前 overlay 有节流，默认 `OVERLAY_RENDER_INTERVAL_MS = 250ms`，理论上最高约 4 FPS。
- `maskRenderFps` 明显低于 4 且 `avgMaskRenderMs` 高：mask bitmap 生成成本高，应优化 `visualizeForAspect` / `visualizeSemanticClassesForAspect`。
- `maskRenderFps` 低但 `avgMaskRenderMs` 很低：通常是节流或没有可渲染 mask，不是性能瓶颈。

## Pipeline 耗时指标

### `avgProcessFrameMs`

含义：从一帧进入 `StartSceneAnalysisUseCase` 到 `ProcessFrameUseCase` 完成的平均耗时。

包含：

- semantic segment
- segmentation analyze
- instance detect
- obstacle extraction
- tracking

用途：感知主链路总耗时。

调优判断：

- 高于 100ms：很难稳定实时。
- 先拆看 `semMs`、`segMs`，确认是哪一段拖慢。

### `avgInferenceMs`

含义：当前与 `ProcessFrameUseCase` 的总感知耗时一致，名字保留为历史兼容。

用途：用于旧 report 和预算检查。

调优判断：

- 如果它接近 `avgProcessFrameMs`，瓶颈主要在感知。
- 如果它明显低于 `avgTotalPipelineMs`，瓶颈在分析、决策、trace、UI collect 或调度。

### `avgPipelineMs`

含义：完整一帧 pipeline 的平均耗时。

包含：

- `processFrameMs`
- `analyzeSceneMs`
- `decideEventsMs`
- trace record 之前的同步路径

用途：判断端到端处理压力。

### `p95PipelineMs`

含义：95 分位完整 pipeline 耗时。

计算方式：对所有 `totalPipelineMs` 排序，取 95% 位置。

用途：实时系统更关注尾延迟，而不是平均值。视障避障场景里，偶发长延迟比平均慢更危险。

调优判断：

- 平均值可接受但 p95 很高：可能有 GC、tensor allocation、模型 delegate 抖动、日志/trace IO、UI 争用。
- 优先查是否有 per-frame allocation、Bitmap 重建、输出 tensor 大量 copy。

## Semantic 阶段耗时

Report 中显示：

```text
semMs=pre:<avgSemanticPreprocessMs> infer:<avgSemanticInferenceMs> read:<avgSemanticOutputReadMs> post:<avgSemanticPostprocessMs>
```

这些平均值只统计实际耗时大于 0 的 semantic 运行帧，不把 semantic skipping 的复用帧算入平均。

### `avgSemanticPreprocessMs`

含义：从相机帧转换成 sem 模型输入 tensor 的耗时。

当前主路径：

```text
YUV planes -> native rotation + letterbox + resize + normalize/quantize -> TensorBuffer
```

调优判断：

- 高：检查 YUV-native 是否生效、输入尺寸是否过大、是否 fallback 到 OpenCV。
- 如果 fallback 到 OpenCV，会出现 YUV -> RGBA -> Mat -> RGB -> resize -> float 的额外成本。

### `avgSemanticInferenceMs`

含义：sem 模型 `model.run()` 的耗时。

调优判断：

- 高：sem 模型尺寸/分辨率是主瓶颈。
- 对 1024x1024 sem，8 Gen1 上高耗时是预期现象。低分辨率 sem 或输出降采样会更有效。

### `avgSemanticOutputReadMs`

含义：从 LiteRT output buffer 读取 sem 输出到 JVM 数组的耗时。

调优判断：

- 高：通常说明输出 tensor 太大，例如 `1024 * 1024 * 19`。
- 可优化方向：低分辨率输出、native 后处理直接处理 output buffer、减少 JVM 大数组 copy。

### `avgSemanticPostprocessMs`

含义：sem 输出 argmax 成 class mask 的耗时。

调优判断：

- 高：优先确认 native argmax 是否生效。
- 如果 output read 高于 postprocess，优化 argmax 帮助有限，应优先减少输出读取成本。

## Instance 阶段耗时

Report 中显示：

```text
segMs=pre:<avgInstancePreprocessMs> infer:<avgInstanceInferenceMs> read:<avgInstanceOutputReadMs> post:<avgInstancePostprocessMs>
```

这些平均值只统计实际运行 seg 的帧。`ALTERNATING` 模式下，跳过帧不会拉低平均值。

### `avgInstancePreprocessMs`

含义：从相机帧转换成 seg 模型输入 tensor 的耗时。

调优判断：

- 高：检查 YUV-native 是否生效，或者 seg 输入尺寸是否过高。
- 如果 sem 和 seg 都运行，每个模型当前仍各自生成自己的输入 tensor，因为输入尺寸不同。

### `avgInstanceInferenceMs`

含义：seg 模型 `model.run()` 耗时。

调优判断：

- 高：降低 seg 输入尺寸、保持 alternating、降低 max detections。
- 如果它远低于 sem，不应优先优化 seg。

### `avgInstanceOutputReadMs`

含义：读取 seg detection/prototype tensor 的耗时。

调优判断：

- 高：检查是否读取了不必要的 prototype mask。
- 当前主链路以 bbox/class/confidence 为主，mask reconstruction 默认关闭。

### `avgInstancePostprocessMs`

含义：bbox decode、confidence filter、NMS、class mapping 的耗时。

调优判断：

- 高：确认 native postprocessor 是否生效。
- 可减少 `maxDetections`、提高 confidence threshold、减少 allowed class。

## 场景理解指标

### `navPassable`

含义：导航 corridor 内可通行区域比例。

用途：判断道路/人行道识别是否稳定。

调优判断：

- 长期偏低但画面能走：class mapper、passable class、corridor 区域或 sem 模型输出需要检查。
- 抖动大：可考虑时序平滑、mask morphology、降低 semantic skipping。

### `blockageConfidence`

含义：连通性分析认为前方被阻挡的置信度。

调优判断：

- 过高导致误报“不可通行”：检查障碍物 mask、连通性阈值、bottom coverage。
- 过低导致漏报：检查障碍物类别映射和 corridor overlap。

### `verticalReach`

含义：可通行区域从底部向远处延伸的比例。

用途：判断“脚下到远处是否连通”。

调优判断：

- 高：通常表示前方通路连贯。
- 低：可能是道路断裂、遮挡、mask 抖动或地面类别错分。

### `floodReach`

含义：从底部种子区域 flood fill 后可到达的通行区域比例。

用途：比单纯 road ratio 更关注连通路径。

调优判断：

- `verticalReach` 高但 `floodReach` 低：通行区域可能被细小断裂切断，可考虑 morphology close 或 bridge tolerance。

### `widthRetentionP25`

含义：通行区域宽度保留的低分位指标。

用途：判断通道是否持续变窄。

注意：透视会让远端道路自然变窄，因此该指标不应直接驱动“道路正在收窄”提示，只适合作为 debug 信号。

## 事件指标

### `events`

含义：本次 session 产生的事件总数。

调优判断：

- 过多：cooldown 太短、事件合并不足、阈值过敏。
- 过少：confidence threshold 太高、tracker 稳定帧太高、事件被 conflict resolver 过滤。

### `blocked`

含义：被判定为 blocked 的帧比例。

调优判断：

- 高但实际可通行：连通性阈值过严、passable class 缺失、障碍物 mask 过大。

### `danger`

含义：道路危险帧比例，主要来自 road safety / vehicle road warning。

调优判断：

- 车辆经过时漏报：降低 vehicle confidence threshold、减少 tracker stable frames、提高 seg run fps。
- 误报太多：提高 road-warning confidence 或延长 cooldown。

### `messageKeys`

含义：本次 session 出现过的提示 key。

用途：判断提示是否单调，或某些事件是否完全没有触发。

调优判断：

- 只有 `event_obstacle_center`：场景决策过于集中，可能需要更丰富的事件类别或更细的障碍物位置判断。
- 出现已禁用策略类提示：检查 EventGenerator / EventMerger 配置。

## 快速诊断路径

### 丢帧高

先看：

```text
droppedFrameRate
cameraInputFps
pipelineOutputFps
avgPipelineMs
semMs
segMs
```

判断：

- `cameraInputFps` 正常、`pipelineOutputFps` 低：pipeline 算不过来。
- `semMs.infer` 高：换低分辨率 sem 或优化 delegate。
- `semMs.read` 高：sem 输出太大，优先考虑低分辨率输出或 native output 后处理。
- `segMs.infer` 高：seg alternating、降低 seg 输入尺寸。

### 道路 mask 抖动

先看：

```text
semanticRunFps
avgSemanticInferenceMs
navPassable
verticalReach
floodReach
```

判断：

- semanticRunFps 太低：减少 semantic frame skipping。
- navPassable / verticalReach 大幅波动：需要 mask 时序平滑或 morphology。
- floodReach 低：连通性被断裂影响。

### 车辆有时提示、有时不提示

先看：

```text
instanceRunFps
instanceModelFps
segMs
danger
messageKeys
```

判断：

- instanceRunFps 低：动态目标可能刚好出现在 seg 跳过帧。
- instanceModelFps 可接受：可以考虑 `SIMULTANEOUS` 或降低 alternating 间隔。
- danger 很低但 instances 有：RoadSafetyAnalyzer 阈值或 event cooldown 可能过严。

### UI mask 更新慢

先看：

```text
maskRenderFps
avgMaskRenderMs
pipelineOutputFps
```

判断：

- pipelineOutputFps 高但 maskRenderFps 低：UI overlay 节流或渲染成本问题。
- avgMaskRenderMs 高：优化 bitmap 生成，减少 per-pixel Kotlin 循环或缓存调色板。
- maskRenderCount 为 0：当前 overlay mode 没有可渲染 mask，或 pipeline 没产生 mask。

## 发布前建议阈值

这些不是绝对标准，但适合作为回归检查：

- `droppedFrameRate < 50%`：当前高分辨率 sem 下的阶段性目标。
- `pipelineOutputFps >= 3`：最低可用。
- `pipelineOutputFps >= 5`：户外避障更稳。
- `p95PipelineMs < 300ms`：动态障碍物提示开始有实用性。
- `avgSemanticOutputReadMs` 不应接近或超过 `avgSemanticInferenceMs`，否则输出读取是明显瓶颈。
- `maskRenderFps` 接近 `4 FPS`：符合当前 250ms overlay 节流上限。

