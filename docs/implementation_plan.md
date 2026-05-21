# Sailens 改进实施计划（2026-05-21）

## 0. 当前目标

本阶段目标是把 `Sailens` 从“主链路可跑”推进到“外场可迭代”：

1. 实时提醒不能漏掉新的高风险空间信息。
2. Trace 必须能从设备导出，支持离线评估与 A/B 对比。
3. 端侧性能预算要能在 replay 和 live 两条路径上被观测。
4. 后续 `YOLO + DDRNet` 必须建立在可回放基线之上。

## 1. 本轮已完成（2026-05-21）

### 1.1 决策冷却从 category 改为 dedupeKey

**文件**

- `domain/src/main/java/com/friady/sailens/domain/processor/decision/CooldownManager.kt`
- `domain/src/test/java/com/friady/sailens/domain/processor/decision/CooldownManagerTest.kt`

**结果**

- 同一事件会继续受冷却保护，避免重复播报。
- 同类别但空间语义不同的事件可以及时穿透，例如“左侧障碍”后出现“前方障碍”。

**验收**

- `CooldownManagerTest` 覆盖同 dedupeKey 抑制和不同 dedupeKey 穿透。

### 1.2 修复 primitive collection 边界问题

**文件**

- `domain/src/main/java/com/friady/sailens/domain/util/PrimitiveCollections.kt`
- `domain/src/test/java/com/friady/sailens/domain/util/PrimitiveCollectionsTest.kt`

**结果**

- `IntArrayList[index]` 不再允许 `index == size`。
- 热路径 primitive collection 的边界语义更安全。

### 1.3 Trace JSONL 分享入口

**文件**

- `presentation/src/main/java/com/friady/sailens/presentation/scene/SceneAnalysisUiEffect.kt`
- `presentation/src/main/java/com/friady/sailens/presentation/scene/SceneAnalysisViewModel.kt`
- `presentation/src/main/java/com/friady/sailens/presentation/scene/SceneAnalysisView.kt`
- `presentation/src/main/java/com/friady/sailens/presentation/scene/TraceReplayView.kt`
- `presentation/src/main/res/values/strings.xml`
- `presentation/src/main/res/values-zh/strings.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/trace_file_paths.xml`

**结果**

- Trace report 页面新增 `Share JSONL`。
- 使用 AndroidX `FileProvider` 分享 app internal `files/traces/trace_<sessionId>.jsonl`。
- 可直接把原始 trace 发到评估脚本、电脑或标注流程。

### 1.4 Trace 写盘并发收口

**文件**

- `data/src/main/java/com/friady/sailens/data/service/FileTraceService.kt`

**结果**

- `flushToDisk()` 串行化。
- 会话状态字段加 `@Volatile`，降低周期 flush 与 start/finish session 交错风险。

### 1.5 Camera analyzer executor 释放兼容化

**文件**

- `camera/src/main/java/com/friady/sailens/camera/CameraViewModel.kt`

**结果**

- 使用 `executor.shutdown()` 替代 `executor.close()`。

### 1.6 帧转换热路径重构

**文件**

- `camera/src/main/java/com/friady/sailens/camera/ImageFrameAnalyzer.kt`
- `domain/src/main/java/com/friady/sailens/domain/model/perception/ImageFrame.kt`
- `data/src/main/java/com/friady/sailens/data/source/ml/OpenCVImageProcessor.kt`
- `data/src/main/java/com/friady/sailens/data/source/ml/FramePreprocessor.kt`

**结果**

- Camera 侧直接 `YUV_420_888 -> RGBA_8888 ByteArray`。
- `ImageFrame` 承载平台无关 RGBA byte buffer 和 `ImagePixelFormat`。
- OpenCV 侧直接从 byte buffer 写入 `CV_8UC4 Mat`，不再经过 Bitmap。
- 预留 `FramePreprocessor` 接口，后续可替换为更贴近硬件 delegate 的预处理实现；本轮不引入 GPU/NNAPI。

### 1.7 Live runtime budget

**文件**

- `domain/src/main/java/com/friady/sailens/domain/config/PipelineBudget.kt`
- `domain/src/main/java/com/friady/sailens/domain/usecase/scene/StartSceneAnalysisUseCase.kt`
- `domain/src/main/java/com/friady/sailens/domain/model/scene/SceneDebugInfo.kt`
- `presentation/src/main/java/com/friady/sailens/presentation/scene/SceneAnalysisView.kt`

**结果**

- 最近 30 帧统计 avg/p95 total pipeline 和 dropped frame rate。
- live debug 面板展示当前 pipeline breakdown、近期预算状态。
- replay 与 live 共用 `PipelineBudget` 阈值。

### 1.8 非阻塞 release

**文件**

- `presentation/src/main/java/com/friady/sailens/presentation/scene/SceneAnalysisViewModel.kt`

**结果**

- `onCleared()` 不再使用 `runBlocking`。
- 模型资源释放进入 IO release job，完成后取消 release scope。

### 1.9 Trace parser 结构化 JSON

**文件**

- `domain/build.gradle.kts`
- `domain/src/main/java/com/friady/sailens/domain/model/trace/TraceReplay.kt`

**结果**

- `TraceReplayParser` 改为 `kotlinx.serialization.json` 结构化解析。
- 保留旧 trace 中新增指标缺省为 0 的兼容行为。

### 1.10 清理旧注释实现

**结果**

- 删除 `ImageFrameAnalyzer`、`OpenCVImageProcessor` 中的大段旧实现注释。
- 删除旧的注释版 `YOLO11InstanceProvider.kt`，后续 Phase 6 重新按可测试实现接入。

## 2. Phase 5A：本轮验证

### 必跑命令

```powershell
.\gradlew.bat --no-daemon :domain:test
.\gradlew.bat --no-daemon :app:assembleDebug
```

### 手工验证

1. 启动 App，进入实时分析。
2. 采集一小段 trace 后停止。
3. 打开 `Replay reports`。
4. 加载 latest report。
5. 点击 `Copy report`，确认摘要可复制。
6. 点击 `Share JSONL`，确认系统分享面板能打开并携带 `trace_<sessionId>.jsonl`。

## 3. Phase 5B：高优先级改进状态

### 3.1 重构 `ImageFrameAnalyzer` 像素转换链路【已完成】

**优先级：P1**

**问题**

原分析链路是：

`ImageProxy -> Bitmap -> IntArray -> ImageFrame -> Bitmap -> Mat`

这对端侧实时感知是明显的内存带宽浪费。

**已完成方案**

1. 新增 `ImageFrameConverter`，把转换逻辑从 analyzer 中抽出。
2. 删除 `ImageFrameAnalyzer.kt` 里的旧注释实现。
3. 改为直接输出 RGBA byte buffer，减少 bitmap 往返。
4. 新增 `FramePreprocessor`，为后续硬件友好预处理预留扩展点。

**验收**

- 主链路输出尺寸、rotation、sequenceNumber 保持一致。
- analyzer 文件不再包含大段旧实现注释。
- replay report 中 `avgProcessFrameMs` 或 `avgInferenceMs` 不回退。

### 3.2 Live runtime budget 面板【已完成】

**优先级：P1**

**已完成方案**

1. 在 domain 增加最近 30 帧 runtime stats。
2. 展示 recent avg / p95 total pipeline。
3. 展示 dropped frames 和 over-budget 状态。
4. 使用和 replay 一致的阈值：`p95TotalPipelineMs <= 85ms`，`droppedFrameRate <= 10%`。

**验收**

- Live 页面能看到当前会话是否超预算。
- 预算信息只在调试面板出现，不干扰正式语音/震动反馈。

### 3.3 去掉 ViewModel `runBlocking` release【已完成】

**优先级：P1**

**已完成方案**

1. 引入非阻塞 release job。
2. release 在 IO scope 执行。
3. `onCleared()` 只执行同步 stop/reset，不等待 LiteRT/OpenCV 释放完成。

**验收**

- `onCleared()` 不再直接 `runBlocking`。
- 多次 start/stop 不泄漏 LiteRT / OpenCV 资源。

### 3.4 Trace parser 改为结构化 JSON【已完成】

**优先级：P2**

**已完成方案**

1. 给 `:domain` 显式引入 `kotlinx.serialization-json`。
2. 用 Json DOM 结构化解析每一行 JSONL。
3. 保持缺省字段兼容旧 trace。
4. 继续由现有 replay tests 锁住行为。

**验收**

- 不再使用正则解析 JSON。
- 旧 JSONL 测试样本继续通过。

## 4. Phase 6：YOLO + DDRNet 融合

**前置条件**

- 至少有 10 组真实户外 trace。
- 已能导出原始 JSONL。
- Replay report 能对比 baseline 与 experiment。
- Live runtime budget 能观察双模型对实时性的影响。

**推荐架构**

1. `DDRNet` 每帧低分辨率跑全局语义。
2. `YOLO` 每 2 到 3 帧或独立异步低频跑检测。
3. `ObstacleTracker` 在检测空档帧补预测。
4. `EventGenerator` 融合语义通行性、目标类别、空间区域和稳定性。
5. 只在高风险/高不确定 ROI 做二次精化，不要让 YOLO ROI 取代 DDRNet 全局语义。

**验收**

- `SEMANTIC_ONLY` 与 `COMBINED` 可通过相同 trace 做 A/B。
- 双模型模式不能让 p95 pipeline 超预算。
- 事件命中率、误报率和用户提示负担要有可比较指标。

## 5. Phase 7：产品闭环

### 5.1 正式设置项

- 语音开关
- 震动开关
- Overlay 模式
- 调试面板开关
- 模型模式：semantic only / combined

### 5.2 外场调参

- 不同光照
- 人行道 / 路口 / 非铺装路
- 静态障碍 / 行人 / 车辆
- 热降频后的长时间运行

### 5.3 交付配置

- 默认配置：普通用户使用，少打扰。
- 调试配置：开发者看指标和 overlay。
- 实验配置：用于模型融合与阈值 A/B。

## 6. 当前优先级

1. 跑通本轮验证命令。
2. 手工验证 trace JSONL 分享。
3. 真机验证新的 `YUV -> RGBA -> OpenCV Mat` 路径画面方向与 overlay 对齐。
4. 采集至少 10 组户外 trace。
5. 基于 replay/live budget 决定是否进入 YOLO + DDRNet。

一句话原则：先让系统可测，再让模型变强，最后让体验变得安静可靠。
