[English](models.md) | **简体中文**

# 模型契约与 Backend 配置

> **本仓库不附带任何模型权重。** 应用是 bring-your-own-model：你提供满足下文契约的 TFLite 图，
> 放到约定路径即可运行。`app/src/main/assets/*.tflite` 已在 `.gitignore` 中，本地工作区可以
> 放权重而不会进任何提交。
> 没有权重时不会报错：preflight 发现没有模型，而这个版本没有承诺任何能力，所以应用会停在
> zero-pipeline 屏，并指向这里。声明了导航为必需的 edition 则把缺失或不匹配的模型当作配置失败——
> 进入 fatal 屏，先震动，再把原因说出来（architecture.zh-CN.md §5.2）。模型通过了 preflight、却在
> 某台设备上起不来，属于运行时失败，显示可重试的「开始分析失败」。

Sailens 按盲人辅助导航场景拆成两类模型：

- `sem`：语义可行走区域模型，负责哪里能走、道路边界和地面类别。
- `det`：实时障碍物模型，负责 bbox 检测。障碍物形状/遮挡由 det 框与 sem mask 交叉验证派生
  （见 `ObstacleOcclusionAnalyzer`），不依赖独立的实例分割模型。

运行 profile 在 `app/src/main/java/com/sailens/app/SailensRuntimeProfile.kt` 定义；它决定模型角色参数、
backend target 和 pipeline cadence。当前发布档位是 `standard` / `ultra`：`standard` 两个视觉模型都跑 GPU，
`ultra` 预留 NPU 给未来 VLM、视觉模型仍跑 GPU。物理模型文件由 `ModelSourceResolver` / `ModelCatalog`
根据 `(ModelType, actual accelerator)` 解析。

与 backend 档位正交的是**感知挡位**（`PerceptionProfile`，设置页可选，详见 `docs/perception-profiles.zh-CN.md`）：
BASIC 只跑 `sem`，DEFAULT 跑 `sem + det`。调度按各模型目标帧率的时间间隔进行（`PerceptionScheduler`）；
det 未运行的帧由 tracker prediction 补偿，轨迹按 `detectionResultTtlMs` 时间过期。

## 放置模型

```text
app/src/main/assets/sem.tflite     # 语义分割
app/src/main/assets/det.tflite     # 障碍物检测
```

文件名由 `ModelCatalog` 固定。输入/输出 tensor 名、输入类型、NHWC/NCHW layout 和量化 scale/zero-point
**都会从 TFLite metadata 自动读取**——tensor 名不是配置项，代码按契约的 shape/role 解析输入输出，
用 LiteRT 的 list-based buffer API 执行，不把导出器生成的内部名字写进配置。

所以换模型通常**不需要改代码**：满足契约的图丢进去就能跑。只有换契约本身（输入几何、类别集合）
才需要动 `SailensRuntimeProfile.kt` 的 `resizeFilter` / `acceleratorBackend`，或加解码分支。

## sem 契约

**输出必须是 19 通道稠密分数，App 自己做 argmax。** NHWC / NCHW 自动识别。

```text
输入   [1, H, W, 3]
输出   [1, h, w, 19]  或  [1, 19, h, w]     # 19 = Cityscapes trainId 类别数
```

已经 argmax 过的 label map **不被支持**，会在 init 干净报错（找不到 19 通道张量）。

> 🔴 **类别顺序只校验通道数、不校验语义。** 顺序错不会报错，会**静默乱套**——模型会把「人行道」
> 当成「马路」讲给一个看不见的人听。这是导航安全隐患，不是格式洁癖。

通道顺序必须是 Cityscapes trainId（`CityscapesClassMapper` 硬编码）：

```text
0  road          5  pole         10 sky          15 bus
1  sidewalk      6  traffic light 11 person      16 train
2  building      7  traffic sign  12 rider       17 motorcycle
3  wall          8  vegetation    13 car         18 bicycle
4  fence         9  terrain       14 truck
```

接新 sem 模型时，**先用一张已知场景验证 argmax 结果的语义**，再谈精度。

## det 契约

**单张输出张量**，两种布局按输出 shape 自动解析（`ObstacleDetectionLayout`）：

```text
RAW_TRANSPOSED   [1, 4 + classCount, N]     前 4 个是 cx, cy, w, h；其后每类分数（attribute-major）
END_TO_END       [1, N, 6]                  x1, y1, x2, y2, conf, classId
```

`END_TO_END` 是 DETR 系导出的天然形状。多张量输出（如 boxes / scores / class_idx 分开）**当前不支持**，
需要给 `ObstacleDetectionLayout` 加一档 + 一条解码分支。

runtime 只保留允许的障碍物类别，并用 class-aware NMS 去重。类别顺序硬编码 COCO 80
（`CocoClassMapper`），与 sem 同理：**顺序错会静默乱套**。

```kotlin
ObstacleModelConfig(
    classCount = 80,
)
```

换 det 模型时**必须先确认输出 shape**，避免 bbox / class 维度被解释错。

## 归一化

⚠️ **`/255` 已经烘进 float 预处理**（`OpenCVImageProcessor` 的 `convertTo(..., 1.0/255.0)`）。

`config.mean/std` 是**叠加在 `[0,1]` 之上**的，当前写死 mean=(0,0,0) / std=(1,1,1)。
接任何需要 per-model 归一化的模型时**别再碰 `/255`，否则双重缩放**。

## int8 / uint8 / float32

默认 `inputDataType = ModelInputDataType.AUTO`，从 TFLite 输入 tensor 类型推断。metadata 可靠时优先用 `AUTO`。

归一化和写入格式跟**模型输入 tensor 类型**绑定，不跟 backend 绑定：FLOAT32 输入写 `FloatArray`，
INT8 / UINT8 输入写量化后的 `ByteArray`。scale / zero-point 从 metadata 读取，只有 metadata 缺失时
才用 config 里的 `inputQuantization` fallback。

> LiteRT 的 buffer API 只有 `writeInt8 / readInt8 / writeFloat / readFloat`，**没有 uint8 专用方法**。
> uint8 走原始字节，signedness 在 Kotlin / native 侧解释。uint8 路径**刻意绕开 native int8 快路径**：
> signed / unsigned 在 128 处变号，会毁掉 argmax。

文件名里的 `int8` / `quant` 不可信——输入输出 tensor 可能仍是 FLOAT32，**以 metadata 为准**。

metadata 不可靠时才显式指定：

```kotlin
ObstacleModelConfig(
    inputDataType = ModelInputDataType.INT8,
    inputQuantization = ModelInputQuantization(scale = 1f / 255f, zeroPoint = -128),
)
```

`sem` 同理，在 `SemanticModelConfig` 设置 `inputDataType` 和 `acceleratorBackend`。

## 🔴 性能红线

sem 和 det 都有 **zero-copy native handle 快路径**，跳过每帧约 30MB 的 `readFloat()` 分配。
trace 里这两个指标就是回归闸：

```text
sem:  postprocessBackend = "native_score"                 且  outputReadTimeMs ≈ 0
det:  postprocessBackend = "native_bbox_nms_float_handle"
```

**任何改动都不应破坏它们。** 注意这两条只在 **float 单张量**契约下成立：uint8 输出或多张量输出
会掉回 Kotlin 解码路径。

## 布局：优先 NHWC

NCHW 导出 + float32 I/O wrapper 会带来每帧的 host transpose，并在 GPU / NPU delegate 上碎片化。
优先 NHWC 图。

## Backend 选择

每个模型在 runtime profile 里直接指定 preferred backend，不做 benchmark 自动选择：

```kotlin
ModelAcceleratorBackend.CPU
ModelAcceleratorBackend.GPU
ModelAcceleratorBackend.NPU
```

默认 `acceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT`：只尝试指定 backend，
初始化失败就失败。LiteRT 的 NPU 编译路径可能在模型内部做 CPU 分区/fallback；应用层不会把 NPU
静默切成 GPU/CPU。**调试模型/backend 兼容性时不要启用 fallback**，否则会掩盖真正的失败点。

视觉模型请求 NPU 目前**不支持（抛异常）**——NPU 留给未来的 VLM 路径。

如果以后启用 `PREFER_BACKEND` / `FIRST_AVAILABLE`，`LiteRtSessionFactory` 会对每个 actual accelerator
attempt 调用 `ModelSourceResolver`，因此 fallback 到另一个 accelerator 时会重新选择模型文件，
并从最终 source 读取 metadata。

## 预处理采样

```kotlin
resizeFilter = ResizeFilter.NEAREST     // 默认：实时路径性能优先
resizeFilter = ResizeFilter.BILINEAR    // 对比模型质量时临时切换
```

对 640×640 输入来说，`NEAREST` 避免每个输出像素在 Y/U/V 三个平面上做双线性插值。
`resizeFilter` 会进入同帧预处理 cache key，不同采样策略之间不会误复用输入。

## 启用 / 诊断 Qualcomm NPU

NPU(Hexagon HTP)的调用链路、运行时交付(dynamic feature / JIT vs AOT)、本地构建安装、诊断手段，
以及一路踩坑的 case study，整理在单独文档：**[npu-litert-qnn.zh-CN.md](npu-litert-qnn.zh-CN.md)**。

## UI 与 Trace 怎么看

实时 debug 面板和 trace replay 会展示：

- semantic provider / obstacle provider
- accelerator: `CPU` / `GPU` / `NPU`
- accelerator selection: `explicit[...]` 或 `prefer_backend[...]`
- preprocess backend: 例如 `native_yuv`、`cached|native_yuv`、`shared_native_yuv`、`shared_quantized_native_yuv`
- postprocess backend: 例如 `native_score`、`native_score_int8`、`native_bbox_nms`、`native_bbox_nms_int8`

trace 文本报告中：

```text
runFps=sem:... obs:... runs=sem:... obs:...
backend=sem:... obs:...
logicMs=analyze:... decide:... total:...
semMs=total:... pre:... infer:... read:... post:...
obsMs=total:... pre:... infer:... read:... post:...
```

`obs` 表示本帧的障碍物模型（det）槽位。

## 检查一个模型

```bash
py scripts/inspect_tflite.py <path>
```

打印输入/输出 tensor 的 shape、dtype、量化参数和算子列表——接新模型前先跑它对照上面的契约。
