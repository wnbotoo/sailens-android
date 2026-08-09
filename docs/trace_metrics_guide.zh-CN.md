[English](trace_metrics_guide.md) | **简体中文**

# Trace Metrics Guide

本文说明 Sailens trace / replay report 中各项指标的含义、计算方式和调优价值。目标是让新人能根据一份 report 快速判断瓶颈在 Camera 输入、模型推理、后处理、决策逻辑，还是 UI mask 渲染。

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
- 此时优先看 `perceptionMs`、`semMs`、`obsMs`、`logicMs`，再考虑降低分析分辨率、共享预处理，或调低感知挡位 / 各模型目标帧率（`semanticTargetFps` / `detectionTargetFps`）。

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

含义：sem 模型实际运行频率。

计算方式：

```text
semanticRunFps = semanticRunCount * 1000 / durationMs
```

用途：确认 semantic frame skipping 是否生效。

调优判断：

- 如果 `enableSemanticFrameSkipping=false`，它应接近 `pipelineOutputFps`。
- 如果开启隔帧，通常约为 `pipelineOutputFps / semanticFrameInterval`。
- 道路 mask 抖动明显时，不建议盲目降低该值。

### `semMs`

含义：sem 模型实际运行帧的端到端分段耗时。

报告格式：

```text
semMs=total:<pre+infer+read+post> pre:<avgSemanticPreprocessMs> infer:<avgSemanticInferenceMs> read:<avgSemanticOutputReadMs> post:<avgSemanticPostprocessMs>
```

这些平均值只统计实际运行 semantic 的帧，不把跳过帧的 0ms 混进去。`infer` 是纯 `model.run()` 耗时，不能单独代表端到端性能；GPU/NPU 常见瓶颈会落在 `pre`、`read` 或 `post`。

用途：判断 sem 瓶颈到底在预处理、模型推理、输出读回还是后处理。

调优判断：

- `pre` 高：优先优化 native YUV、共享预处理、相机分析分辨率或模型输入尺寸。
- `infer` 高：优先考虑低分辨率 sem 模型、量化、delegate、输出降采样。
- `read` 高：瓶颈在输出 tensor 读取，常见于大尺寸语义输出。
- `post` 高：优先确认 native fused score/stat 后处理是否生效。

### `obstacleRunFps`

含义：det 障碍检测模型的实际运行频率。

计算方式：

```text
obstacleRunFps = obstacleRunCount * 1000 / durationMs
```

用途：确认 obstacle 槽位（det 周期运行）的实际运行频率是否符合预期。判定依据是每帧的
`obstacleRunKind` 字段（`det` / `none`）：任意非 `none` 值都算一次运行（含历史 trace 里
已退役的 `seg`）；旧 trace 无该字段时回退到分段耗时启发式。

调优判断：

- 默认不限频时应接近 `pipelineOutputFps`；配置了 `detectionTargetFps > 0` 时应接近
  `min(detectionTargetFps, pipelineOutputFps)`，持续高于上限说明调度间隔没生效。
- 动态障碍物漏报明显时，先看 `obstacleRunFps` 是否被 pipeline 吞吐压得太低（需要降输入
  尺寸或分辨率，见 `obsMs`）。

### `obsMs`

含义：实际运行 obstacle provider 的端到端分段耗时。

报告格式：

```text
obsMs=total:<pre+infer+read+post> pre:<avgObstaclePreprocessMs> infer:<avgObstacleInferenceMs> read:<avgObstacleOutputReadMs> post:<avgObstaclePostprocessMs>
```

用途：判断 det 槽位的瓶颈到底在预处理、模型推理、输出读回还是 bbox 后处理。

调优判断：

- `pre` 高：优先优化 native YUV、共享预处理或降低输入尺寸。
- `infer` 高：降低 obstacle 输入尺寸、量化、或换更轻的 det 模型。
- `read` 高：det 走 zero-copy handle 路径时 `read` 应接近 0；显著为正说明 handle 反射失败
  回退到了 `readFloat()`，检查 LiteRT 版本与 JniHandle 反射。
- `post` 高：确认 native bbox 后处理是否生效，减少 `maxDetections` 或提高 confidence threshold。

### `maskRenderFps`

含义：UI 层成功生成并更新 mask bitmap 的 FPS。

计算方式：

```text
maskRenderFps = maskRenderCount * 1000 / durationMs
```

其中 `maskRenderCount` 只统计 `bitmapRendered=true` 的 overlay render 事件。

相关字段：

- `avgMaskRenderMs`：生成一张 overlay bitmap 的平均耗时。
- `sourceAgeAvg/sourceAgeMax`：overlay bitmap 完成渲染时，距离该 `SceneResult` 的 pipeline 完成时间已经过去多久。

用途：判断 overlay 渲染是否拖慢 UI、导致画面更新慢，或是否真的在显示陈旧的 pipeline 结果。

调优判断：

- 当前 overlay 有节流，默认 `bitmapRenderIntervalMs = 100ms`，理论上最高约 10 FPS。
- `maskRenderFps` 明显低于 10 且 `avgMaskRenderMs` 高：mask bitmap 生成成本高，应优化 `visualizeForAspect` / `visualizeSemanticClassesForAspect`。
- `maskRenderFps` 低但 `avgMaskRenderMs` 很低：通常是节流或没有可渲染 mask，不是性能瓶颈。
- `sourceAgeMax` 达到秒级：UI overlay 可能在显示旧结果，优先检查 render job 取消、UI 主线程阻塞和 overlay 请求覆盖逻辑。

## Pipeline 耗时指标

### `avgProcessFrameMs`

含义：从一帧进入 `StartSceneAnalysisUseCase` 到 `ProcessFrameUseCase` 完成的平均耗时。

包含：

- semantic segment
- segmentation analyze
- obstacle detect
- obstacle extraction
- tracking

用途：感知主链路总耗时。

调优判断：

- 高于 100ms：很难稳定实时。
- 先拆看 `semMs`、`obsMs`，确认是哪一段拖慢。

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
semMs=total:<avgSemanticTotalMs> pre:<avgSemanticPreprocessMs> infer:<avgSemanticInferenceMs> read:<avgSemanticOutputReadMs> post:<avgSemanticPostprocessMs>
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

## Obstacle 阶段耗时

Report 中显示：

```text
obsMs=total:<avgObstacleTotalMs> pre:<avgObstaclePreprocessMs> infer:<avgObstacleInferenceMs> read:<avgObstacleOutputReadMs> post:<avgObstaclePostprocessMs>
```

这些平均值只统计实际运行 obstacle provider 的帧；det 未到调度间隔而由 tracker prediction 补偿的帧不会拉低平均值。

### `avgObstaclePreprocessMs`

含义：从相机帧转换成 obstacle 模型输入 tensor 的耗时。

调优判断：

- 高：检查 YUV-native 是否生效，或者 obstacle 输入尺寸是否过高。
- 如果 sem 和 obstacle 使用相同输入几何，当前会尝试复用 latest-frame 预处理缓存；命中时 backend 会显示 `shared_native_yuv` 或 `shared_quantized_native_yuv`。

### `avgObstacleInferenceMs`

含义：det 模型 `model.run()` 耗时。

调优判断：

- 高：降低 det 输入尺寸、量化、或换更轻的检测模型。
- 如果它远低于 sem，不应优先优化 det。

### `avgObstacleOutputReadMs`

含义：读取 obstacle detection tensor 的耗时。

调优判断：

- 高：检查 detection tensor 是否过大，或输出反量化是否落在 Kotlin 层。
- 当前主链路以 bbox/class/confidence 为主，不读取 prototype tensor。

### `avgObstaclePostprocessMs`

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

计算上已经做透视归一化：越靠近地平线，期望宽度越小，避免把远端自然变窄直接当成 width loss。该值会 clamp 到 `0..1`，所以 report 中应显示 `0..100%`。

注意：该指标不应单独驱动“道路正在收窄”提示，只适合作为 debug 信号；判断 blocked 时还需要结合 `verticalReach` 和 `floodReach`。

## 稳定性指标

### `stabilityDelta`

含义：相邻已处理帧之间核心识别指标的绝对变化量。Live debug 面板显示当前帧 delta，trace replay report 显示 avg/max。

包含：

```text
navPassableDelta
roadRatioDelta
blockageConfidenceDelta
verticalReachDelta
floodReachDelta
widthRetentionP25Delta
```

用途：定位道路 / 可行走区域是否在闪烁、断裂或反复触发 blocked。

调优判断：

- `navPassableDelta` 或 `roadRatioDelta` 高：优先怀疑 sem mask 抖动、semantic skipping 复用间隔过长、道路类别映射不稳。
- `verticalReachDelta` / `floodReachDelta` 高：可行走区域可能被细小断裂切开，优先查看 semantic class overlay 与 passable mask overlay。
- `blockageConfidenceDelta` 高但 `navPassableDelta` 低：可能是连通性阈值或障碍物遮挡扣除逻辑在边界附近跳变。
- max 明显高于 avg：通常是偶发单帧错误；avg 和 max 都高：更像持续场景、模型或阈值问题。

### `obstacleStability`

含义：障碍物检测数量在相邻已处理帧之间的变化。

包含：

```text
avgRawObstacleDetectionCount
avgRawObstacleDetectionCountDelta
maxRawObstacleDetectionCountDelta
avgObstacleCountDelta
maxObstacleCountDelta
```

用途：判断 det 是否 flicker，以及 tracker 是否把 raw 检测抖动吸收掉。

注意：`avgRawObstacleDetectionCount` 与 raw delta 只统计实际运行 obstacle provider 的帧，不把
`tracker_predict` 跳过帧当作 raw=0 参与变化量计算。det 按目标帧率降频运行时这很重要，否则指标会把
调度性跳帧误报成检测 flicker。

调优判断：

- raw delta 高、tracked delta 低：模型输出抖动存在，但 tracker 正在吸收，事件层通常较稳。
- raw delta 高、tracked delta 也高：需要考虑检测置信度滞回、类别/区域投票、IoU 阈值或更保守的事件门槛。
- raw 平均数量长期很高：可能有背景静态物体误检，需要检查透视走廊 (`navigationCorridorFarWidth` / `navigationCorridorHorizonY`)、corridor overlap、bottom Y、类别过滤或 NMS 阈值。
- raw 平均数量接近 0 但现场有障碍：优先检查模型 asset、backend、输入 shape/quant、class mapping 和 confidence threshold。

### `rawObstacleClasses` / `trackedObstacleCategories`

含义：

- `rawObstacleClasses`：obstacle provider 原始检测类别分布，例如 `car:60%, person:20%, stop_sign:20%`。
- `trackedObstacleCategories`：进入 tracker / event 侧后的领域类别分布，例如 `VEHICLE:70%, PERSON:30%`。

用途：不用看日志就能判断障碍物提示来自什么。比如路边电线杆、拒马、矮墙这类道路装置是否进入提示链路，应该先看 raw class 是否有对应或近似类别，再看 tracked category 是否变成 `STATIC_OBSTACLE`。

调优判断：

- raw class 是 `car` / `person`，但现场不是车辆/行人：优先处理 obstacle model 误检、NMS 或事件门槛。
- raw class 是 `stop_sign` / `bench` / `chair` / `potted_plant` 且 tracked 为 `STATIC_OBSTACLE`：静态物体链路正在生效，继续看 bottom Y、透视走廊和 cooldown。
- raw class 没有对应类别，但 semantic dominant classes 出现 `pole` / `wall` / `fence`：当前更可能影响 passable/connectivity，而不是被 obstacle det 作为可播报物体。
- `wall` / `fence` / `building` 不应被粗暴映射为 obstacle event，否则容易把路边背景墙、建筑外立面变成高频误报。

### `occludedPassable`

含义：`ObstacleOcclusionAnalyzer` 从 passable mask 中扣掉的可通行像素比例——把跟踪障碍物
bbox 接地带（det∩sem 交叉验证）从 sem 可行走区抠除后的占比。Report 显示 avg/max 以及相邻帧 delta avg/max。

用途：解释 blocked spike、`widthRetentionP25` 跳变，以及 det 障碍框接地带是否过度遮挡可行走区。

调优判断：

- `occludedPassable` max 高且与 blocked spike 同时出现：检查 `occlusionMinBottomY` /
  `occlusionBandHeightRatio` 是否过激（接地带过高把远处可行走区也抠掉），以及 det 是否有误检框。
- `occludedPassable` 长期接近 0：当前 blocked 主要来自 semantic passable mask / connectivity，
  而不是 det 障碍框扣除——通常因为可行走区本就很小，或 det 障碍未落在可行走区上。
- delta 高但 avg 低：通常是障碍物短暂进入走廊接地带造成的单帧遮挡冲击，结合 overlay 看框是否过大。

### `blockageReasons`

含义：blocked 帧的主要连通性原因分布。可能值包括：

- `vertical`：分层扫描向前可达层不足。
- `flood`：从底部可行走区域向前洪泛连通不足。
- `width`：可行走宽度保持太低。
- 组合值如 `vertical+flood` / `flood+width`。
- `suppressed_road_connected:*`：原始 blocked 边缘触发，但 CrossValidator 在道路语境下用强连通证据压下。

用途：定位 blocked 是语义 mask 断裂、底部种子问题、宽度阈值问题，还是道路语境下的边缘误报。

调优判断：

- `flood` 高：优先看 passable mask 是否被细小断裂切开，或 flood seed 是否落在错误底部 run。
- `width` 高：优先看透视/宽度保持阈值，避免把远端自然变窄当成不可通行。
- `vertical+flood` 高：通常是 passable mask 连续性真的差，优先查 semantic 类别。
- `suppressed_road_connected` 多：CrossValidator 正在兜住道路语境的边缘 blocked，可考虑进一步调低上游敏感度。

### `roadVehicleSources`

含义：触发 road warning vehicle 的车辆证据来源分布。

- `raw`：来自当前 obstacle provider 的原始车辆检测，并通过 raw vehicle debounce。
- `tracked`：来自 tracker 输出的稳定车辆。
- `raw_and_tracked`：两者同时支持。

用途：解释 `event_road_warning_vehicle` 是否由远处 raw 检测、稳定 tracked 车辆，还是两者共同触发。

注意：当前默认用户播报策略已经关闭 road warning / road exit 这类车道语义提示。`roadVehicleSources`、`roadVehicleGeometry` 仍保留为 debug 指标，用来判断道路车辆识别是否稳定，但不代表一定会播报 `event_road_warning_vehicle`。

相关字段：

- `roadVehicleGeometry.reasons`：几何放行原因分布，`near_bottom` 表示车辆 bbox 已接近画面底部，`center_band` 表示车辆还不算近但与导航中心带重叠足够高。
- `bottomY`：触发 road warning 的车辆 bbox 底部平均位置，越接近 `100%` 越靠近用户。
- `centerOverlap`：车辆 bbox 与中心导航带的平均横向重叠比例，用于判断是不是路中/正前方车辆。
- `area`：触发车辆 bbox 的平均面积占比，用于区分远处小目标和近处大目标。

调优判断：

- `raw` 占比高且误报多：优先调 `rawVehicleOnRoadMinConfidence`、`rawVehicleOnRoadMinAreaRatio`、`rawVehicleOnRoadMinBottomY`、`rawVehicleOnRoadDebounceFrames`。
- `tracked` 占比高且误报多：优先调 `trackedVehicleOnRoadMinStableFrames`、tracker 匹配 / missed frames，或 tracked vehicle 的面积、bottomY、center-band overlap 门槛。
- `raw_and_tracked` 高：模型和 tracker 都认为车辆在道路上，误报更可能来自 semantic road base sample 或车辆类别误检。
- `center_band` 高且 `bottomY` 低：提示多半来自远处但居中的车辆，若体感打扰再考虑提高中心带 overlap 或 min bottomY。
- `near_bottom` 高且 `area` 高：提示更可能来自近处车辆，不建议只靠阈值压掉，应结合真实场景确认。

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
- `event_blocked` 现在只用于高确定性严重阻断；如果连通性 blocked 但证据不足以断言无法通行，事件层会转成 `event_path_complex`，避免把复杂路况播成“前方无法通行”。

### `danger`

含义：道路危险帧比例，主要来自 road safety / vehicle road warning。

调优判断：

- 车辆经过时漏报：降低 vehicle confidence threshold、减少 tracker stable frames、提高 det run fps。
- 误报太多：提高 road-warning confidence 或延长 cooldown。

### `messageKeys`

含义：本次 session 出现过的提示 key。

用途：判断提示是否单调，或某些事件是否完全没有触发。

调优判断：

- 只有 `event_obstacle_center`：场景决策过于集中，可能需要更丰富的事件类别或更细的障碍物位置判断。
- 出现 `event_obstacle_center_person` / `event_obstacle_center_vehicle`：表示障碍物提示保留了类别。现在只剩这两个类别后缀——播报类别按**用户要做的动作**归并，而不是按模型类别：
  - `_bicycle` 已移除，自行车/摩托车统一播成 `_vehicle`。两者的处置动作都是停下让行，而模型分不清一辆自行车是停着还是正骑过来，归到更保守的那一侧。感知侧的 `ObstacleCategory.BICYCLE` 仍然保留，因为 `RoadSafetyAnalyzer` 依赖它——停在路边的自行车不该触发机动车道警告。
  - `_static` 已移除，静态与未知障碍不带类别后缀（`event_obstacle_center`）。原先的 `_static` 那组文案与无后缀组逐字相同。
- 出现 `event_obstacle_center_right_person` / `event_obstacle_left_center_vehicle` 这类多方向类别 key：表示合并后的多个方向属于同一类别，会继续播报类别。
- 前方行人提示比侧边行人更保守：正前方 person 需要更近或更大才播，左右 / 左前 / 右前 person 有独立的较早 side gate，并允许中等紧急度的侧边 person 通过，避免“前方提前、侧边太晚”的体感。
- 前方 / 左前 / 右前 vehicle 会升为 `CRITICAL`，并且同一区域内 vehicle 与 person / bicycle 同紧急度竞争时优先选择 vehicle；混合 vehicle 与其他类别时不会合并成泛化 key，以免把车辆语义吞掉。
- 出现 `event_obstacle_center_right` / `event_obstacle_multiple` 这类泛化 key：通常表示合并的多个方向类别不一致，或上游事件没有类别后缀。
- 出现 `event_path_complex`：表示前方连通性证据复杂或不稳定，但还没有达到高确定性 `event_blocked`。此时应优先结合具体障碍物 key 判断现场，例如同时出现 `event_obstacle_center_person`。
- 默认不应再出现 `event_road_warning_vehicle` / `event_ground_to_*`。这些车道和路面变化语义容易误导，当前保留在显式配置开关后面，日常体验优先依赖具体障碍物、复杂路况和路口提示。
- `event_road_exit` 与 `event_suggest_*` 已删除：前者在用户走过之后才触发，纯噪音；后者依赖的 `suggestedBias` 没有建议侧连通性证据，因此当前不生成可执行方向后缀。
- **所有事件当前都不带方向后缀。** `suggestedBias` 是每个扫描层里最宽通行段中心相对中线的加权偏移（`ConnectivityStatsExtractor.computeDirectionBias`），而 `layer.isValid` 是宽度检查不是连通性检查——几段互不相连的通行区域偏左，同样会得出 `bias = LEFT`。全局 `floodReachRatio` 合格也只能证明某处存在前向通道，不能证明 bias 指向的那一侧连通。
  *后续能力：* 需要对左右候选路线分别做洪泛连通性与安全性判定，让方向指向一条真的走得通的路。`SceneEvent.directionHint` 在此之前仅作为 trace/replay 兼容字段保留。
- 出现 `event_camera_blocked` / `event_low_light`：表示输入帧不可用（镜头被遮挡、环境过暗），该帧会抑制其余全部事件——此时任何基于画面的判断都不可信，而保持沉默会被用户理解成"前方安全"。一次运行里这类事件占比不低时，先复查采集本身，其余指标才有意义。
- 出现 `event_intersection`：表示 scene element 判断为路口。文案不再用“疑似”——盲人无法验证这份不确定性，而“疑似有路口”和“有路口”对应的动作完全一样，含糊措辞只多占播报时长。确定性现在由阈值表达而不是措辞：当前发布 profile 不启用 traffic-light/road-ratio fallback，因为该 fallback 误报偏多；只有显式可靠的路口信号才应进入用户播报。
- 出现 `event_traffic_light`：表示语义分割稳定检测到红绿灯，但没有把它升级成“疑似路口”。该提示是低优先级、长冷却的宽泛交通提示，应排在车辆、行人、blocked / path-complex 之后；触发前需要满足 traffic-light 像素占比、道路语境和稳定帧门槛，避免远处小目标或单帧误分割过早播报。
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
obsMs
```

判断：

- `cameraInputFps` 正常、`pipelineOutputFps` 低：pipeline 算不过来。
- `semMs.infer` 高：换低分辨率 sem 或优化 delegate。
- `semMs.read` 高：sem 输出太大，优先考虑低分辨率输出或 native output 后处理。
- `obsMs.infer` 高：降低 obstacle 输入尺寸，或提高 det 调度间隔（`detectionTargetFps`）。

### 道路 mask 抖动

先看：

```text
semanticRunFps
avgSemanticInferenceMs
navPassable
stabilityDelta
verticalReach
floodReach
```

判断：

- semanticRunFps 太低：减少 semantic frame skipping。
- `stabilityDelta` 中 nav/road/vertical/flood 大幅波动：需要 mask 时序平滑、morphology 或降低 semantic skipping。
- floodReach 低：连通性被断裂影响。

### 车辆有时提示、有时不提示

先看：

```text
obstacleRunFps
obsMs
danger
obstacleStability
messageKeys
```

判断：

- obstacleRunFps 低：动态目标可能刚好出现在 obstacle provider 跳过帧。
- obstacleStability raw delta 高：det 输出本身在 flicker，优先检查 confidence、NMS、区域过滤和 tracker 匹配阈值。
- `obsMs.total` 可接受：可以提高 `detectionTargetFps`。
- danger 很低但 obstacle detections 有：RoadSafetyAnalyzer 阈值或 event cooldown 可能过严。

### UI mask 更新慢

先看：

```text
maskRenderFps
avgMaskRenderMs
sourceAgeAvg/sourceAgeMax
pipelineOutputFps
```

判断：

- pipelineOutputFps 高但 maskRenderFps 低：UI overlay 节流或渲染成本问题。
- avgMaskRenderMs 高：优化 bitmap 生成，减少 per-pixel Kotlin 循环或缓存调色板。
- sourceAgeMax 达到 1000ms 以上：这不是正常低刷新率，而是 overlay 或 UI 更新链路显示了陈旧结果。
- sourceAgeAvg 很低但肉眼仍觉得拖影：通常是 mask 刷新率低于相机预览造成的视觉滞后，不是模型 pipeline 排队。
- maskRenderCount 为 0：当前 overlay mode 没有可渲染 mask，或 pipeline 没产生 mask。

## 发布前建议阈值

这些不是绝对标准，但适合作为回归检查：

- `droppedFrameRate < 50%`：当前高分辨率 sem 下的阶段性目标。
- `pipelineOutputFps >= 3`：最低可用。
- `pipelineOutputFps >= 5`：户外避障更稳。
- `p95PipelineMs < 300ms`：动态障碍物提示开始有实用性。
- `avgSemanticOutputReadMs` 不应接近或超过 `avgSemanticInferenceMs`，否则输出读取是明显瓶颈。
- `maskRenderFps` 接近 `10 FPS`：符合当前 100ms overlay 节流上限。
- `sourceAgeMax < 1000ms`：overlay 没有出现秒级陈旧帧。
