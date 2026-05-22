# YOLOv26 双模型迁移报告 (v4.0 最终版)

> 代码状态：双模型 wiring、YOLO26-sem 动态 tensor shape、YOLO26-seg end-to-end decoder、核心单测与 debug APK 构建通过 ✅

---

## 一、双模型职责定义

```
┌─────────────────────────────────┐   ┌──────────────────────────────────┐
│       YOLOv26n-sem              │   │       YOLOv26n-seg               │
│   语义分割 (Semantic Seg)        │   │   实例分割 (Instance Seg)         │
├─────────────────────────────────┤   ├──────────────────────────────────┤
│ 职责: 理解可行走区域              │   │ 职责: 识别障碍物                  │
│   "哪里能走"                     │   │   "有什么" + "在哪里"             │
├─────────────────────────────────┤   ├──────────────────────────────────┤
│ 类别: Cityscapes 19 类           │   │ 类别: COCO 80 类                 │
│ 接口: SegmentationModel          │   │ 接口: InstanceSegmentationProvider│
│ 枚举: SemanticProviderType       │   │ 枚举: InstanceProviderType        │
│       .YOLO26_SEM                │   │       .YOLO26_SEG                │
│ 类别映射: CityscapesClassMapper  │   │ 类别映射: CocoClassMapper         │
│ 推理频率: 每帧必跑               │   │ 推理频率: 由 InferenceStrategy 决定│
└─────────────────────────────────┘   └──────────────────────────────────┘
```

---

## 二、InferenceStrategy 可配置推理策略（已实装）

### 两种策略对比

```
SIMULTANEOUS（同时推理）            ALTERNATING（交替推理）[默认]
─────────────────────────────      ─────────────────────────────────
每帧:                              奇数帧:
  sem.segment(frame)  ←常跑          sem.segment(frame)  ←常跑
  seg.detect(frame)   ←常跑          seg.detect(frame)   ←跑
  tracker.update()                   tracker.update()

                                   偶数帧:
                                     sem.segment(frame)  ←常跑
                                     (seg 跳过)
                                     tracker.predict()   ←预测补偿

优点: 障碍物信息最新               优点: seg 频率减半，功耗低  
适用: 高性能旗舰机                 适用: 续航优先场景
```

> **关键规则**: sem（可行走区域）无论哪种策略**每帧都跑**。
> 只有 seg（障碍物识别）的频率受 `inferenceStrategy` 控制。

### 已实装代码

**`Types.kt`（+13行）**:
```kotlin
enum class InferenceStrategy {
    SIMULTANEOUS,  // 每帧同时运行 sem + seg
    ALTERNATING,   // sem 每帧；seg 交替帧，偶数帧 tracker.predict() 补偿
}
```

**`PerceptionConfig.kt`（新增字段）**:
```kotlin
/** 双模型推理策略，仅 mode = COMBINED 时生效 */
val inferenceStrategy: InferenceStrategy = InferenceStrategy.ALTERNATING,
```

**`ProcessFrameUseCase.kt`（替换 handleCombinedMode）**:
```kotlin
return when (perceptionConfig.inferenceStrategy) {

    InferenceStrategy.SIMULTANEOUS -> {
        // sem（每帧）+ seg（每帧）同时推理
        val instances = instanceProvider.detect(frame)
        val rawObstacles = obstacleExtractor.extractFromInstances(instances, depthEstimator)
        obstacleTracker.update(rawObstacles, frame.timestamp)
    }

    InferenceStrategy.ALTERNATING -> {
        if (frameCount % 2 == 1) {
            // 奇数帧：sem + seg 都跑
            val instances = instanceProvider.detect(frame)
            val rawObstacles = obstacleExtractor.extractFromInstances(instances, depthEstimator)
            obstacleTracker.update(rawObstacles, frame.timestamp)
        } else {
            // 偶数帧：只跑 sem，tracker 运动预测补偿障碍物
            obstacleTracker.predict(frame.timestamp)
        }
    }
}
```

---

## 三、代码库真实现状

### ✅ 已完备（无需改动）

| 文件/组件 | 状态 |
|-----------|------|
| `SemanticProviderType.YOLO26_SEM` | ✅ Types.kt 中已有 |
| `InstanceProviderType.YOLO26_SEG` | ✅ Types.kt 中已有 |
| `InferenceStrategy` 枚举 | ✅ **刚实装** |
| `PerceptionConfig.inferenceStrategy` | ✅ **刚实装** |
| `ProcessFrameUseCase` 双策略支持 | ✅ **刚实装** |
| `ClassMapperProviderImpl` YOLO26_SEM/SEG | ✅ 已完备 |
| `ObstacleExtractor.extractFromInstances()` | ✅ 完整实现 |
| `InstanceSegmentationProvider` 接口 | ✅ 已定义 |
| `CocoClassMapper` / `CityscapesClassMapper` | ✅ 完整实现 |
| `StopSceneAnalysisUseCase.release()` | ✅ 已含 `instanceProvider.release()` |

### ✅ 当前已实现

| 组件 | 状态 | 说明 |
|------|------|------|
| `YOLO26SemSegmentationModel.kt` | ✅ | 从 LiteRT tensor layout 读取真实 NHWC 输入/输出尺寸 |
| `YOLO26SemNativePostProcessor.kt` | ✅ | semantic logits argmax 走 C++ native，Kotlin 回退 |
| `YOLO26SegInstanceProvider.kt` | ✅ | 接入 YOLO26-seg TFLite，输入尺寸从 tensor metadata 读取 |
| `YOLO26SegPostProcessor.kt` | ✅ | 支持 `[1,300,38]` end-to-end 输出，bbox/NMS 优先走 C++ native |
| `DomainBindingsModule.kt` 激活 | ✅ | 默认 `COMBINED + YOLO26_SEM + YOLO26_SEG + ALTERNATING` |
| `DataModule.kt` DI 注入 | ✅ | 发布版仅保留 YOLO26-sem + YOLO26-seg provider |

---

## 四、工作包详情

### 工作包 A：YOLOv26-sem（4-6h）— "哪里能走"

发布版只保留 YOLO26-sem 语义模型，模型契约由 `YOLO26SemModelConfig` 描述：

| 参数 | YOLOv26-sem |
|------|-------------|
| assetPath | `yolo26n-sem_int8.tflite` |
| inputWidth/Height | **从 TFLite tensor 元数据读取** |
| outputWidth/Height | **从 TFLite tensor 元数据读取** |
| outputChannels | **19（Cityscapes）** |
| postprocess | **native argmax，Kotlin fallback** |

说明：本地 `yolo26n-sem_int8.tflite` 当前解析为 NHWC float I/O；如果替换为低分辨率语义模型，运行时会按真实 tensor shape 初始化，不再硬编码空间尺寸。

风险：**中等**（主要是语义 mask 分辨率显著提高后的实时性预算）

---

### 工作包 B：YOLOv26-seg（10-14h）— "有什么/在哪里"

最复杂部分。YOLOv26-seg TFLite 输出两个头：

```
Output[0]: [1, 300, 38]                  ← end-to-end 检测头
  38 = 4(xyxy) + 1(confidence) + 1(class_id) + 32(mask系数)

Output[1]: [1, 160, 160, 32]             ← prototype masks (TFLite NHWC)
```

**后处理流程**：
```
① 读取 float I/O 张量（当前本地 asset 不是 int8 I/O）
② detection-major 解码: [300, 38]
③ 置信度过滤: confidence >= threshold
④ 解码 bbox(xyxy) → NormalizedRect（归一化并去除 letterbox padding）
⑤ 读取 class_id → CocoClassMapper → ObstacleCategory
⑥ 主链路默认只返回 bbox/class/confidence，避免每帧重建高成本 mask
⑦ Debug/离线需要 mask 时再用 coeffs[6:38] + prototype NHWC 重建 BinaryMask
```

风险：**中等**（主要是验证输出 shape、bbox 坐标尺度和端侧耗时）

---

### 工作包 C：激活配置（1-2h）— 纯开关

```kotlin
// DomainBindingsModule.kt
single {
    PerceptionConfig(
        mode = PerceptionMode.COMBINED,
        semanticProviderType = SemanticProviderType.YOLO26_SEM,
        instanceProviderType = InstanceProviderType.YOLO26_SEG,
        inferenceStrategy = InferenceStrategy.ALTERNATING,  // 可改为 SIMULTANEOUS
    )
}

// DataModule.kt
single<SegmentationModel> { YOLOv26SemSegmentationModel(androidContext()) }
single<InstanceSegmentationProvider> { YOLOv26SegInstanceProvider(androidContext()) }

// 发布版不再保留旧模型或空 provider fallback。
```

---

## 五、改动量汇总

| 工作包 | 状态 | 新增行 |
|--------|------|--------|
| InferenceStrategy (已实装) | ✅ | ~43 |
| A. YOLOv26-sem 模型实现 | ✅ | 动态 tensor shape |
| B. YOLOv26-seg Provider | ✅ | end-to-end decoder |
| C. 激活配置 | ✅ | Koin wiring |
| 测试代码 | ✅ | domain/data 单测 |
| **合计** | | **~980行** |

**零改动**：Domain 层全部 UseCase、Analyzer、UI/ViewModel、事件决策链

---

## 六、关键行动：第一步先验证 seg 输出

```python
import tensorflow as tf
interpreter = tf.lite.Interpreter(model_path='yolov26n-seg.tflite')
interpreter.allocate_tensors()
for d in interpreter.get_output_details():
    scale, zero_point = d['quantization']
    print(f"Output {d['index']}: shape={list(d['shape'])} "
          f"dtype={d['dtype'].__name__} scale={scale:.6f} zp={zero_point}")

# 期望:
# Output 0: shape=[1, 300, 38] dtype=float32
# Output 1: shape=[1, 160, 160, 32] dtype=float32
```

---

**文档版本**: v4.0  
**日期**: 2026-05-21
