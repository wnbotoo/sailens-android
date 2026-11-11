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
backend target 和 pipeline cadence。目前只有一个 profile `standard`：所有模型（含 VLM）都以 GPU 为目标。
曾经的 `ultra` 档（给 VLM 预留 NPU）从未被选中过，已删除。物理模型文件由 `ModelSourceResolver` / `ModelCatalog`
根据 `(ModelType, actual accelerator)` 解析。

与 runtime profile 正交的是**感知挡位**（`PerceptionProfile`，设置页可选，详见 `docs/perception-profiles.zh-CN.md`）：
BASIC 只跑 `sem`，DEFAULT 跑 `sem + det`。调度按各模型目标帧率的时间间隔进行（`PerceptionScheduler`）；
det 未运行的帧由 tracker prediction 补偿，轨迹按 `detectionResultTtlMs` 时间过期。

## 放置模型

```text
app/src/main/assets/sem.tflite     # 语义分割
app/src/main/assets/det.tflite     # 障碍物检测
```

文件名由 `ModelCatalog` 固定；某个模型用哪个文件由宿主决定：宿主绑定一个 `ModelSourceResolver`（本仓库的参考宿主在
`app/ProfileBindingsModule.kt` 里绑定 `CatalogModelSourceResolver`），并把同一个实例交给自己的静态预检，按每个模型配置的加速器取来源，
所以预检读的就是 runner 要加载的文件。平台模块从不绑定、也不默认任何解析器。发行版的打包方式不同（换文件名、下载、按设备选择）时，
提供自己的解析器即可，平台不用改。

输入/输出 tensor 名、输入类型、NHWC/NCHW layout 和量化 scale/zero-point
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

**框的坐标单位靠声明，不靠推断。** `DetectionModelConfig.coordinateSpace` 指明框是 `NORMALIZED`
（模型输入的 [0, 1]；默认值，Ultralytics 导出的 TFLite 就是这种）还是 `MODEL_PIXELS`（640 输入即 0..640）。
输出本身回答不了这个问题：像素坐标模型在左上角输出的一个很小的框，数值上和归一化框一模一样。
填错不会报错，只会让每个框都错位——和类别顺序一样，换 det 模型时要人工确认。

`END_TO_END` 是 DETR 系导出的天然形状。多张量输出（如 boxes / scores / class_idx 分开）**当前不支持**，
需要给 `ObstacleDetectionLayout` 加一档 + 一条解码分支。

runtime 只保留允许的障碍物类别，并用 class-aware NMS 去重。类别顺序硬编码 COCO 80
（`CocoClassMapper`），与 sem 同理：**顺序错会静默乱套**。

```kotlin
DetectionModelConfig(
    classCount = 80,
    coordinateSpace = DetectionCoordinateSpace.NORMALIZED,
)
```

换 det 模型时**必须先确认输出 shape 和框的坐标单位**，避免 bbox / class 维度被解释错。

## depth 契约

可选的第三个模型 `app/src/main/assets/depth.tflite`（和另外两个一样被 git 忽略），用于地面几何（`docs/geometry.md` §6）。没有这个文件
就是没配置深度，别的都不变。换一个符合契约的深度模型只是换文件：代码里不出现模型族名字，也没有按模型的分支。形状和布局从元数据读；
归一化在模型里；输出的比例和平移由 `GroundFitter` 每帧拟合，它的门槛都是深度比。运行选择（加速器、GPU 精度和队列优先级）是宿主运行
profile 里的 `DepthModelConfig`。

```text
输入   一个 float32 张量  [1,3,H,W] 或 [1,H,W,3]   RGB，[0,1]，需要的归一化在模型里
输出   一个 float32 张量  [1,H,W]、[1,1,H,W] 或 [1,H,W,1]   H、W 与输入相同
       相对视差：越大越近，a/Z + b，a、b 每帧未知
```

- **输出与输入同尺寸。** 输出像素 (x, y) 就是输入像素 (x, y)，用输入的变换映射。输出是输入一半或四分之一的模型，预检判为不兼容
  （`OutputSizeMismatch`），不做重映射：那需要先定义它自己的覆盖规则。
- **只支持视差。** 相对深度通常还带一个未知的平移，取倒数之后不再是 `a/Z + b`；这类模型能过预检，但每帧都会被判为 `INVERSE_OUTPUT`。
  公制深度要另立契约。
- **数值有限。** 输出里有 NaN 或无穷的那次运行算失败。
- **缩放方式固定为 `PAD_KEEP_ASPECT`**，即原生 letterbox，原生预处理只有这一种缩放。张量宽高比和帧一致（竖版 9:16）时几乎不补边；
  补边区对地面拟合是未知。张量像素只有在内核往那里写了图像（列 `[ceil(pad), ceil(pad + 内容宽))`）**且**像素中心在内容区内时才算内容。
- **只走原生预处理，不共享缓存。** OpenCV 兜底把尺寸和偏移截成整数，和变换对不上；原生路径处理不了的帧直接报告。
- **归一化在模型里**：输入和另外两个模型一样是 `[0,1]`。模型需要 ImageNet 归一化时，导出时折进去（下面的参考导出就这样做）。letterbox
  补的黑色在折叠前是 0，进模型后仍然是归一化后的黑色。
- **这里没有类别顺序问题**；输出方向反了由拟合逐帧发现。
- **必须通过加速器一致性检查**（下文）。有两种导出在 LiteRT 2.1.5 GPU 上编译或运行都不报错，却算错：一种把位置编码插值留在图里
  （`GATHER_ND`、`BROADCAST_TO`：GPU 编不过）；一种在 `FULLY_CONNECTED` 前后让 token 激活在二维 `[N,C]` 和三维 `[1,N,C]` 之间广播（GPU
  把 N 当成 batch，logcat 每个 block 打一条 `Shape mismatch ... conv_wave_memory`）。[`scripts/depth/patch_rank3.py`](../scripts/depth/patch_rank3.py)
  把它们保持为三维。

**参考模型**：Depth Anything V2 Small（代码和权重都是 Apache-2.0），竖版 392×224，GPU FP16，队列优先级 LOW。SM8850 上与 sem+det 一起以
3 Hz 运行：深度 p95 31.4 ms（LOW 时 50.4 ms，最大 76.5 ms），sem/det p95 +5%（LOW 时 0%/+3%）。用
[`scripts/depth/dav2/export.py`](../scripts/depth/dav2/export.py) 导出：它把归一化和位置编码折进去、做三维修补，并在 CPU 上核对这些都没有改变输出。
Distill-Any-Depth Small 是同一架构，用同样的方式符合契约。

**换深度模型**不改代码，但要做和第一个模型一样的验收：
1. 预检报 `Compatible`；
2. 目标设备上调试版模型一致性检查的深度一项通过；
3. 在设备的预处理上离线量它的地面质量，**包括捂镜头帧**（一个模型可以室外和参考模型相当，室内和捂镜头时却差很多）；
4. 目标设备上达到真机预算。

换模型时**不能**改的：`GroundFitter` 的门槛、运行失败计数、新鲜度、能力表、一致性检查的门槛。某个模型要改这些才能用，就是它不符合契约，
或者契约要先改。每个模型用哪个文件仍由宿主决定（`ModelSourceResolver`，见上文）：按设备打包不同深度模型的宿主在那里做。

## 归一化

⚠️ **`/255` 已经烘进 float 预处理**（`OpenCVImageProcessor` 的 `convertTo(..., 1.0/255.0)`）。

`config.mean/std` 是**叠加在 `[0,1]` 之上**的，当前写死 mean=(0,0,0) / std=(1,1,1)。
接任何需要 per-model 归一化的模型时**别再碰 `/255`，否则双重缩放**。OpenCV 兜底完全不看 `mean/std`，只除以 255，所以这个固定值也是兜底
唯一支持的值；需要归一化的模型把它放在模型里（`depth` 契约就这样要求）。

GPU 精度：sem 和 det 的会话不带 GPU 选项，跑的是 LiteRT 的默认精度，在目前测过的 GPU 上是 FP16（SM8850，LiteRT 2.1.5）。上文的一致性
结果就是这个精度下的。深度模型显式指定精度和队列优先级（`DepthModelConfig`）。

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
会掉回 Kotlin 解码路径。目标设备上的实测状态和测量条件见 [architecture.zh-CN.md](architecture.zh-CN.md) §12.3。

## 🔴 加速器结果一致性

> 🔴 **模型在 GPU 上运行不报错，不代表它在 GPU 上算对了。** 和类别顺序一样，输出看不出任何异常；
> 被误导的是看不见画面的用户。在加速器上使用一个模型之前，先确认它给出的答案和 CPU 一样。

本仓库一直用来开发的那两份 sem 和 det 导出（onnx2tf、float16、NHWC）在 SM8850 和
SM8450 的 LiteRT GPU 加速器上运行没有任何报错，但答案是错的：室内地板和浅色铺装被判成 `building`，
CPU 每帧都以 92% 检出的办公椅从未被检出。强制 FP32、LiteRT 2.3.0、缩放方式、zero-copy 读取都已排除。
同一批模型的另一种导出答案是对的：`ultralytics/yolo-flutter-app` release v0.6.6 的
`yolo26n-sem_w8a32.tflite` 和 `yolo26n_w8a32.tflite`，由 Ultralytics 8.4.104 以 `format=litert`、
`quantize=w8a32` 导出（NCHW）。在 OnePlus CPH2747（SM8850，Android 16，LiteRT 2.1.5 GPU）上，下面的
检查结果是：

| 导出 | sem 像素一致率（平均 / 最低一帧） | det：CPU 检出 → GPU 也检出，多检 | 结果 |
|---|---|---|---|
| Ultralytics w8a32 NCHW | 96.4% / 93.3% | 16 → 16（召回率 1.0），多检 0 | 通过 |
| onnx2tf float16 NHWC | 平均 39.1% | 21 → 0 | 不一致 |

原因在于某个具体的计算图和加速器之间的相互作用，所以换任何新模型文件、LiteRT 版本或设备都可能再次出现。

**检查方法**：用 debug 版，设置 → 诊断 → **模型一致性检查**。拿稳手机，对着地面，并让画面里有物体
（椅子、行人）。每个模型在 8 帧相机画面上各跑两次，一次在配置的加速器上，一次在 CPU 上，然后比对
两边的结果：

| 模型 | 通过 | 不一致 | 其余情况 |
|---|---|---|---|
| sem | 像素一致率平均 ≥ 95%，且每一帧 ≥ 90% | 平均 < 80% | 无法判断：通常是手机晃动或画面模糊（正确的模型在晃动时测得 86–96%）；拿稳后重试 |
| det | CPU 检出的物体中，≥ 90% 在加速器上也检出（同类别、IoU ≥ 0.5），且多检出 ≤ 10% | 任一条不满足 | 什么都没检出时无法判断：请对准物体 |
| depth（配置了时） | 每帧视差相对差异的中位数 ≤ 2%，两边的地面拟合在 8 帧中 ≥ 7 帧有效性一致，且两边都有效时标签一致 ≥ 95% | 任一帧 > 10%，或有朝向数据的帧 ≥ 7 时拟合一致 ≤ 4/8 | 其余无法判断；有朝向数据的帧不足 7 帧时也无法判断：分母始终是 8 |

深度这一项由检查自己持有重力传感器（Guidance 停着），并在取帧时、任何模型运行之前解析这一帧的朝向；两边各用自己的 `GroundFitter`。相对差异是
内容像素上的 `|加速器 − CPU| / max(|CPU|, 5% × max|CPU|)`：正确的导出在 GPU 上 FP32 和 FP16 的中位数是 0.3–0.5%，上面契约里那两种坏导出是
60–97%。没有 `depth.tflite` 的宿主不显示深度这一栏，总结论也不变。

检测按产品阈值下的决策来判定。一个物体只在一边越过阈值时，只有两边置信度相差不超过 0.10（例如 0.41 对
0.36）才算数值抖动；0.90 对 0.26 是加速器在产品里漏掉的物体，判为不一致。必须 8 帧全部完成比对才能
通过：相机超时，或者某帧在比对过程中被改动，模型都判为无法判断（如果已比对的帧已经不一致，仍判不一致）。
检测按整个模型比对，全部类别、每帧最多 100 个，而不是 Guidance 的 10 个。总体"通过"表示每个在加速器上
运行的模型都完成了比对并通过；报告里会写明比对了几个。正确的加速器只在类别
边界上和 CPU 有差异；错误的会整片替换类别，结果里会写出来（例如 `car→building 31%`）。报告写到
`files/diagnostics/consistency/`，带模型哈希，只有数字。

模型文件、LiteRT 版本或目标设备有任何变化时都要跑一遍。发行版如果自带模型，有责任在它支持的每台
设备上跑过。

## 布局：NHWC 更快，但正确性优先

NCHW 导出 + float32 I/O wrapper 会带来每帧的 host transpose，并在 GPU / NPU delegate 上碎片化，所以
NHWC 图通常更快。但速度只有在加速器一致性检查通过之后才有意义：上面那两份快的 NHWC 导出，正是在
GPU 上算错的那两份。

正确的代价：同一台 OnePlus CPH2747（SM8850，Android 16，LiteRT 2.1.5 GPU）、同一个 debug 包，两个模型
都在 GPU 上：

| 导出（sem + det） | 管线中位数（p90） | 每秒输出帧数 | 丢弃的相机帧 | sem 后处理中位数 |
|---|---|---|---|---|
| Ultralytics w8a32 NCHW（结果正确） | 43 ms（58 ms） | 22 | 27% | 26 ms |
| onnx2tf float16 NHWC（在 GPU 上算错） | 28 ms（37 ms） | 29 | 0.3% | 15 ms |

差别在 sem 后处理，它要等 GPU 的结果。

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

视觉模型请求 NPU 目前**不支持（抛异常）**：`ModelCatalog` 里没有 NPU 模型文件。NPU 是未来可能的能力，
不是当前的资源分配。

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
