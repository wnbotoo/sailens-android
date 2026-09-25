[English](local-navigation-implementation.md) | **简体中文**

# 局部导航实施方案

> 状态：**提案，先评审**。与 [`local-navigation-roadmap.zh-CN.md`](local-navigation-roadmap.zh-CN.md)
> 配套：路线图讲*为什么*和*顺序*，本文按里程碑讲*怎么做*：契约、放在哪里、算法、回退、trace 字段
> 和测试。类型草图只是示意，名字和字段在各里程碑的评审中确定。实施某个里程碑前要重新读当前代
> 码——代码和本文不一致时，以代码为准，并修正本文。

## 1. 放在哪里

不新增 Gradle 模块。在现有模块里加包，遵守 [`architecture.zh-CN.md`](architecture.zh-CN.md) §4.2 的
依赖方向。

| 关注点 | 模块 | 包（提案） | 理由 |
|---|---|---|---|
| 帧采集元数据（传感器时间戳基准、内参） | `sailens-camera` | `com.sailens.camera` | 只有相机模块接触 CameraX/Camera2 |
| 姿态传感器、`MotionTracker` | `sailens-guidance` | `…guidance.motion`（吸收 `sensors/`） | 属于导航含义；无 UI |
| 相机几何数学（射线、地平面、接地距离） | `sailens-guidance` | `…guidance.geometry` | Guidance 专用的通用数学 |
| 深度模型 runner | `sailens-vision` | `…vision.depth` | 模型输出本身不含导航含义，和 sem/det 一样 |
| 深度 `ModelType`、catalog 条目、预处理 | `sailens-runtime` | 现有包 | 模型来源和预处理都在这里 |
| 地面拟合、地面观测 | `sailens-guidance` | `…guidance.geometry` | 属于导航含义 |
| 局部世界模型 | `sailens-guidance` | `…guidance.world` | |
| 资格（Guidance 能否指引） | `sailens-guidance` | `…guidance.safety` | 只知道感知/几何/运动的健康状况 |
| 安全状态（再加上卡死和输出健康） | `sailens-shell` | `…shell.guidance.safety` | 只有 shell 同时看得到 Guidance 和输出 |
| 手机几何设置（高度、放置方式、校准） | `sailens-shell`（存储）→ 注入 Guidance | `…shell.settings` | 这是用户/设备设置，不是运行参数预设 |
| 仿真器 | `sailens-guidance` 测试夹具 | `…guidance.simulation`（测试源集） | JVM，确定性 |
| 现场采集写入器 | `sailens-shell` debug 源集 | `…shell.debug.capture` | 仅 debug，和 trace UI 一样 |
| 现场采集格式 + 读取器 | `sailens-guidance` | `…guidance.trace.capture` | 能在 JVM 上读，供回放和仿真使用 |
| 规划器、控制信号（M7） | `sailens-guidance` | `…guidance.planning`、`…guidance.control` | |

`sailens-core` 不新增任何东西，除非某个类型确实被 Guidance 以下的两个模块共用（候选：如果
`sailens-vision` 做深度裁剪映射需要，就加一个小的 `CameraIntrinsics` 值类型）。

## 2. 坐标系与时间（所有里程碑通用）

- **分析图像坐标系**：竖直方向正确的分析图像，语义掩码和检测框已经共用这个坐标空间（letterbox
  由 `SemanticContentRegion` 裁掉）。所有新的几何计算都在这个坐标系里做。内参必须从传感器有效像
  素阵列，经过 CameraX 的裁剪和分析旋转（`AnalysisRotation`），映射到这个坐标系。
- **相机坐标系**：x 向右，y 向下，z 向前，与上面的分析图像坐标系对应。
- **重力**：相机坐标系下指向下方的单位向量 `g`。由设备坐标系下的重力/旋转传感器，经过后置摄像
  头的固定朝向（`SENSOR_ORIENTATION`）和分析旋转换算得到。
- **局部地面坐标系** `LocalGroundFrame(t)`——所有地面网格（M4、M6）和所有 `TranslationEstimate` 都用
  它表达：
  - 原点：时刻 `t` 的相机位置在地平面上的投影；
  - 上：`−g`（与重力相反）；
  - 前：时刻 `t` 的相机光轴投影到地平面后归一化——是*相机*的前方，不是行走方向；
  - 右：`forward × up`（右手系）。
  - 退化情况：光轴投影长度小于阈值（相机几乎垂直朝下或朝上）时，前向沿用上一帧的前向并按陀螺仪的偏
    航变化旋转，同时把该坐标系的质量标为下降。
- **时间**：Guidance 全程使用 `SystemClock.elapsedRealtime()`。`SensorEvent.timestamp` 就是
  elapsed-realtime 基准。`ImageProxy.imageInfo.timestamp` 只有在
  `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME` 时才是这个基准。否则按帧到达时间打时间戳；由此产生的
  对齐误差在 M0 按设备实测之前是**未知的**，并记入 trace（`timestampSource`）。误差未测或过大时，
  几何使用方按质量下降处理。

## 3. 契约（草图）

```kotlin
// sailens-camera：随分析器发出的 ImageFrame 一起传递（每个订阅者的拷贝也保留它）。
data class FrameCaptureInfo(
    val sensorTimestampNanos: Long,
    val timestampSource: TimestampSource,          // REALTIME | ARRIVAL
    val intrinsics: CameraIntrinsics?,             // 分析图像像素单位；未知则为 null
    val intrinsicsSource: IntrinsicsSource,        // CALIBRATION | FOCAL_LENGTH_ESTIMATE | NONE
)

data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float,
                            val width: Int, val height: Int)

// 由 shell 持有的存储注入 Guidance；运行参数预设只提供默认值。
data class PhoneGeometrySettings(
    val heightMeters: Float,                       // 初始值 1.3
    val source: HeightSource,                      // DEFAULT_SEED | PRESET(placement) | CALIBRATED
    val placement: Placement?,                     // 如 CHEST_LANYARD、HANDHELD_CHEST、WAIST
)

// sailens-guidance.geometry
data class CameraGeometry(               // 每帧一个
    val frameTimestampMs: Long,
    val intrinsics: CameraIntrinsics,
    val gravityInCamera: Vec3,           // 单位向量，向下
    val gravityAgeMs: Long,              // 帧时间与最近一次传感器采样的间隔
    val phone: PhoneGeometrySettings,
    val quality: GeometryQuality,        // OK | DEGRADED(reason) | UNAVAILABLE(reason)
)

data class GroundContactDistance(
    val validity: GroundContactValidity, // VALID | CLIPPED | OCCLUDED | NEAR_HORIZON | IMPLAUSIBLE_BOX
    val lowerBoundMeters: Float?,        // 障碍物可能达到的最近距离
    val upperBoundMeters: Float?,
)

data class GroundObservation(            // M3b，来自一帧深度
    val frameTimestampMs: Long,
    val groundMask: BinaryMask,          // 分析图像坐标系，和语义掩码同一空间
    val aboveGroundMask: BinaryMask,
    val belowGroundMask: BinaryMask,
    val disparityScale: Float, val disparityShift: Float,   // 拟合出的 A、t
    val inlierRatio: Float,
    val planeAgreesWithGravity: Boolean,
    val confidence: Float,
)

data class MotionState(                  // M3o：只含旋转
    val timestampMs: Long,
    val headingRad: Float,               // 机身朝向，不是行走方向
    val yawRateRadPerS: Float,
    val isStationary: Boolean,
    val orientationQuality: Quality,
)

// M5b：两种独立能力；有其一不代表有其二。
data class MovementDirectionEstimate(    // 用户往哪走
    val timestampMs: Long, val headingRad: Float, val confidence: Float, val validUntilMs: Long,
)
// LocalGroundFrame(toMs) 原点的位移，用 LocalGroundFrame(fromMs) 的坐标轴表达（§2）。
// 公制平移既不需要、也不意味着有 MovementDirectionEstimate。
data class TranslationEstimate(          // 自上次估计以来用户移动了多远
    val fromMs: Long, val toMs: Long,
    val deltaForwardM: Float, val deltaRightM: Float, val uncertaintyM: Float, val confidence: Float,
)

// M3b：分析图像 ↔ 深度张量的几何映射。属于模型配置契约的一部分。
data class DepthInputTransform(
    val sourceRegion: Rect,              // 送进模型的分析图像区域
    val modelWidth: Int, val modelHeight: Int,
    val policy: ResizePolicy,            // STRETCH | CROP_KEEP_ASPECT | PAD_KEEP_ASPECT
    val scaleX: Float, val scaleY: Float,// 裁到 sourceRegion 之后，分析像素 → 张量像素
    val padLeft: Int, val padTop: Int,   // 张量像素，仅 PAD 使用
) {
    fun toTensor(u: Float, v: Float): PointF   // 分析图像 → 张量
    fun toAnalysis(x: Float, y: Float): PointF // 张量 → 分析图像
    fun covers(u: Float, v: Float): Boolean    // 在 sourceRegion 之外或落在填充区时为 false
}

// sailens-guidance.safety——按能力划分（见 §5）
data class GuidanceQualification(
    val baseline: BaselineQualification,                 // 今天的 Guidance 所依赖的部分
    val capabilities: Map<GuidanceCapability, CapabilityStatus>,
)
sealed interface BaselineQualification {
    data object Qualified : BaselineQualification
    data class Degraded(val reasons: Set<DegradeReason>) : BaselineQualification
    data class Unqualified(val reason: StopReason) : BaselineQualification
}
enum class GuidanceCapability { ORIENTATION, GROUND_CONTACT_DISTANCE, DEPTH_GEOMETRY,
                                MOVEMENT_DIRECTION, TRANSLATION /* 以后：DROP_WARNING, … */ }
sealed interface CapabilityStatus {
    data object NotConfigured : CapabilityStatus
    data object Available : CapabilityStatus
    data class Unavailable(val reason: String) : CapabilityStatus
}
// sailens-shell：GuidanceQualification + 卡死检测 + 输出健康（映射见 §5）
sealed interface GuidanceSafetyState { /* Initializing, Guiding, Degraded(reasons),
                                          StopRequired(reason), Interrupted(reason) */ }
```

逐像素结果继续用 `BinaryMask` 表示（BitSet，没有逐像素对象）。

## 4. M0 — 基线与现场采集

**分工，避免重复劳动**：阶段 2 工作流负责冻结基线、trace 的"送达/撤回"字段和录制手册；下面的采
集由这边负责。先合送达字段（改动小），再在 P3 相机帧池 PR 之后做采集。

**trace 送达字段**（已在 PR #8、分支 `feat/trace-prompt-outcomes` 实现；以该 PR 为准）。
`FrameTrace.messageKeys` 仍是冷却后的候选。每条交出的提示新增一条 `prompt_outcome` 记录，包含
`eventId`（键）、`sourceSequenceNumber`（与帧关联）、`messageKey`、`category`、`priority`、
`deliveredAt` / `revokedAt` 二者恰有其一、`revokeReason`（`waiting_for_description` |
`output_refused`），以及当时的输出设置。时间戳是墙钟毫秒，与 `pipelineCompletedAt` 同一时钟。不记
录：已送达的语音后来被更高优先级打断。

**对时锚点。** trace 用墙钟毫秒；传感器采样（以及时间戳来源为 `REALTIME` 时的帧）用
elapsed-realtime 纳秒。采集写入器在会话开始时（以及恢复时）记一对 `(wallMs, elapsedRealtimeNanos)`
锚点，让两者能放在同一条时间线上；帧通过 `sequenceNumber` 与 trace 关联。

**两种采集语义。**

| | 现场证据采集 | 模型回归记录 |
|---|---|---|
| 用途 | 标注、#5 阈值校准、几何标定、仿真场景素材 | 流水线改动后精确回放决策路径 |
| 画面 | 5 Hz、长边 640 px、JPEG | 平时不存；只在标记事件前后的窗口里存完整分析分辨率帧 |
| 另外存 | `SENSOR_DELAY_GAME` 下的重力 / game rotation vector / 陀螺仪、`FrameCaptureInfo`、trace | 逐帧感知输出（sem 类别掩码或可通行掩码、检测结果、帧质量）+ `CameraGeometry` |
| 不能用于 | 重跑 sem/det 并期望输出完全一致（缩放 + JPEG 会改变模型输入） | 任何需要连续画面的用途 |

**采集写入器（仅 debug 版）。**
- 使用相机帧池 PR（#10）提供的限速订阅：`FrameSource.frames(minIntervalMs = 200)`，不需要的帧根本不会
  送到它这里。流上的帧是借出的（见 #10 修订后的 architecture §6.1）：收到的每一帧都通过
  `FrameSource.releaseFrame` 归还（幂等）。每个订阅者拿到各自的 `ImageFrame.copy()`，所以放在
  `ImageFrame` 上的 `FrameCaptureInfo` 会随之带过去；M3o 在 `ImageFrameConverter.convert`（#10 给它加了
  `PlaneAllocator` 参数）里填写它。
- 每个会话一个目录，放在应用内部 `files/captures/` 下，JSONL + 图像文件；手动导出；可在调试 UI
  里列出和删除；release 版不包含。采集数据里有人脸和地点（已确认可以接受），不会自动离开设备。
- 保留期限：会话录制 7 天后自动删除，已导出或标记保留的除外；总量上限 2 GB，超出时先删最旧的未标记
  会话。（现场证据按 5 Hz / 640 px 算，一小时约 0.7 GB。）
- `…guidance.trace.capture` 里的读取器在 JVM 上按时间顺序输出帧、传感器采样和感知记录，供仿真器
  （M2）和几何回放测试使用。

**采集操作**（仅 debug 版；对应现场录制手册 PR #9 里的占位）：

| 需求 | 设计 |
|---|---|
| 模式开关 | 调试设置："现场采集" 关 / 现场证据 / 现场证据 + 模型回归记录 |
| 开始 / 停止 | 模式打开时，采集跟随 Guidance 会话一起开始和停止；不设单独按钮，免得忘按 |
| 删除、标记保留 | 调试采集列表：每个会话显示大小、时长、是否保留；提供删除和保留操作 |
| 导出 | 通过系统分享，每个会话一个 zip；debug 版同时写明 `files/captures/` 路径，可用 `adb pull` |
| 查看某条提示前后的帧 | 在电脑上：读取器 + 一个小脚本，把某条 `prompt_outcome` 或标记前后 ±N 秒的帧拼成缩略图；不做应用内查看器 |
| 存储 | 现场证据估计约 0.7 GB/小时；M0 实测后写回手册 |
| **"标记漏报"** | 采集期间按音量减键（不用看屏幕即可操作，按下后短震确认），另有一个屏幕大按钮。写一条 `marker` 记录，含墙钟毫秒、elapsed-realtime 纳秒和最新帧的 `sequenceNumber`。只有采集打开时才拦截音量减键，其余时候不影响正常调音量 |

手册的对时约定（每段开头和结尾用手盖住镜头 3 秒）在 trace 里表现为一条 `event_camera_blocked` 的
prompt outcome，在采集里是一段黑帧；读取器可以据此自动切段。

**M0 必须在每台目标设备上测出的数据**：相机时间戳来源；帧与传感器的对齐误差（例如对着静止场景转
动手机，把画面运动和陀螺仪做相关）；内参来源。

**运行条件文档**（`docs/guidance-operating-envelope.md`）：手机放置方式和朝向、只支持步行、光线、
天气、室内/室外、楼梯、过马路、人群密度、耳机、硬件档次、熄屏。版本 A 列为不支持的有：跑步、楼梯/
落差指引、过马路指引、方向指引，以及 M3a 没有验证过的任何放置方式。

**测试**：在合成采集数据上做读取器往返测试；时间戳顺序；写入器归还它收到的每一帧。

## 5. M1 — 资格与安全状态（精确复现现状）；M1b — 停止压住已排队事件

**Guidance 侧（`GuidanceQualification`）** 按能力划分，每个流水线结果算一次：

- **基础资格**——今天的 Guidance（sem 连通性、det 障碍物、现有事件）所依赖的部分：帧龄（当前时间 −
  帧时间戳）与预算的比较；`FrameQuality`（`OBSTRUCTED` / `TOO_DARK`）；感知失败（已按
  `PipelinePerformanceBudget.maxConsecutiveFrameFailures` 统计的连续失败帧）；#5 的地面识别状态。
  `Unqualified` 的原因：`FRAME_STALE`、`CAMERA_UNUSABLE`、`PERCEPTION_FAILED`；有了规划器之后再加
  `NO_SAFE_PATH`。
- **能力状态**——每个可选能力（姿态、接地点距离、深度几何、移动方向、平移）一项：`NotConfigured` /
  `Available` / `Unavailable`。

规则：

1. 可选能力缺失、失效或过期，只让**该能力**变成 `Unavailable`。基于它的功能回退到今天的行为。基础资
   格不变：**配置了但坏掉，绝不比没配置更糟。**
2. 只有当一个**已启用**的面向用户的功能需要这个能力时，它的失效才有更大的影响，具体由各功能自己的评
   审决定：通常是该功能关闭并记入 trace（如果用户依赖它，再给一条低优先级提示）；如果这个能力是当前
   正在输出的控制信号（M7 之后）的必要输入，就停止控制输出（`StopRequired`）。
3. 目前没有任何面向用户的功能依赖可选能力（M3a 默认关闭，M3b 对用户不可见），所以在 M1 里所有能力失
   效都只记 trace。

**shell 侧（`GuidanceSafetyState`）** 把资格和 `GuidanceStallDetector`、输出健康合成起来。映射如下
（规则：输出通道的问题只让*送达*降级，不影响*对路的判断*）：

| 情况 | 安全状态 | 说明 |
|---|---|---|
| 有资格，输出全部正常 | `Guiding` | |
| 基础资格 `Degraded`（例如认不出地面） | `Degraded(perception)` | 现状：路径提示暂停，障碍物照常播报 |
| 某个可选能力 `Unavailable`，且没有已启用的功能依赖它 | `Guiding` | 只记 trace |
| TTS 不可用，震动可用 | `Degraded(output: speech)` | Guidance 照常判断；由震动词汇承担告警（即今天的 `SPEECH_UNAVAILABLE`） |
| 音频路由丢失，震动可用 | `Degraded(output: audio)` | |
| 基础资格 `Unqualified`（镜头不可用、帧过期、感知失败） | `StopRequired(reason)` | Guidance 不能给出导航输出 |
| 卡死窗口内完全没有结果（`GuidanceStallDetector`） | `StopRequired(PERCEPTION_STALLED)` | |
| 没有任何可靠输出通道（语音和震动都不可用） | `Interrupted(NO_OUTPUT_CHANNEL)` | 已经没法告诉用户任何事；UI 显示状态，会话持续尝试恢复 |
| 生命周期中断（即今天的 `INTERRUPTED`） | `Interrupted(reason)` | |

**M1 的行为**：不变。它精确复现今天的告警，以及 `EventConflictResolver` 里今天的
`SENSOR_QUALITY` 压制；**不**加撤回队列。证明方式：映射和状态转换的单测，以及与基线一致的真机告警
时间线。

**M1b（改变行为，在 M2 之后）**：进入 `StopRequired` / `Interrupted` 时撤回已排队的普通事件并挡住
新事件；离开需要连续 N 个合格结果（滞回）。用仿真器的停止/恢复场景和一次与基线对比的真机运行证明。

**测试**：状态上的穷举 `when`；映射表作为参数化测试；状态转换测试；（M1b）"StopRequired 期间普通
事件不能送达"、"过期结果不能恢复资格"。

## 6. M2 — 仿真器

- 场景是一段在假时钟上运行的脚本：合成的感知结果（掩码、检测、帧质量）、传感器时间线（重力、旋
  转）、故障（丢帧、过期帧、模型失败），以及期望输出（资格和安全状态、事件，以后还有控制）。
- 跑真实的 `AnalyzeSceneUseCase` → `DecideEventsUseCase` 路径加新组件，只在模型边界用 fake。确定
  性：固定随机种子，注入时钟（时钟已经可以注入）。
- 基准输出提交进仓库；基准有变化时必须在 PR 里说明原因。
- 第一批：直路无障碍、正前/左/右障碍、变窄、完全阻塞、分割闪烁、单帧误检、连续过期帧、镜头被挡、
  固定障碍物下手机上抬/下压（给 M3a）、部分被挡的行人（给 M3a 有效性）、停止后的恢复（给 M1b）、用
  户在走而障碍物消失（给 M4）。
- 以后：加一个采集适配器，把 M0 的模型回归记录送进同一套框架。

## 7. M3o — 姿态与相机几何

- `sailens-camera` 提供 `FrameCaptureInfo`：时间戳来源；内参优先取 `LENS_INTRINSIC_CALIBRATION`，
  没有时用 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 和 `SENSOR_INFO_PHYSICAL_SIZE` 估算（记录来源），再映射
  到分析图像像素。
- `MotionTracker`（只含旋转）：`SENSOR_DELAY_GAME` 下的 game rotation vector + 重力；静止标志沿用现
  有 `DeviceMotionDataSource` 的逻辑；文档写明 `headingRad` 是机身朝向，不是行走方向。
- 每帧的 `CameraGeometry`：用最近一次传感器采样算出相机坐标下的重力（记录采样时长差），加上内参、
  手机设置和质量。重力采样过旧、对齐误差未测或过大、缺内参时，质量下降。
- 拿不到姿态 → 能力 `ORIENTATION` 变为 `Unavailable`（§5）；所有使用方（M3a、M3b、M4）回退到今天的
  行为；基础资格不变。
- **测试**：在每种分析旋转下，用合成传感器数据检查旋转和坐标轴约定；检查内参经过裁剪和旋转后的映射。

## 8. M3a — 接地点距离

**输入**：`CameraGeometry`；分析图像坐标下的一个框；该帧的其他检测结果和 sem 掩码（用于有效性检查）。

**距离区间**
1. 接地像素 `p` = 框底边中点。
2. 射线 `r = K⁻¹ [u, v, 1]`，`q = g · r`（射线指向地平线以下的程度）。
3. 地面点 `X = (h / q) · r`；水平距离 `d = |X − (g · X) g|`。
4. 区间 `[下界, 上界]` 来自 `h ± Δh`（用初始高度时 ±15%；校准后更窄）和重力误差（±2°）。

**接地点有效性**——出现以下任一情况，接地点即无效；无效的接地点绝不替换今天的估计：
- `CLIPPED`：框碰到图像底边（真正的接地点在画面之外）。障碍物至少和图像底边一样近；这种情况只能升高
  风险。
- `OCCLUDED`：框底和另一个更近（在图像中更低）的检测框重叠，或者接地点正下方的像素在 sem 掩码里不
  是地面/可通行（腿被栏杆、汽车、长椅挡住）。
- `NEAR_HORIZON`：`q ≤ q_min`——距离计算是病态的。
- `IMPLAUSIBLE_BOX`：在算出的距离下，框的长宽比/尺寸与类别不符（例如行人框对这个距离来说太矮——这
  通常是检测器把框截短了）。

**风险保守的融合（第一阶段）**：
`newLevel = level(下界)`，阈值为 1.5 m / 4.0 m；接地点有效时实际使用的分级 =
`nearerOf(todayLevel, newLevel)`，无效时用 `todayLevel`。新估计器只能保持或升高风险，所以今天的代码
会播报的障碍物，绝不会被推到"远"而被 `EventGenerator.shouldAnnounceObstacle` 丢掉。让新估计完全取代
今天的启发式（允许它*降低*风险）是以后的另一个决定，要基于现场证据。

**手机高度**：`PhoneGeometrySettings` 来自 shell 持有的存储（运行参数预设只提供 1.3 m 初始值）。校
准方式是**放置方式预设**（胸前挂绳、手持齐胸、腰部），每种带默认高度；用户也可以选填身高，按预设比例
换算手机高度（来源记为 `CALIBRATED`；比例是提案值，要用采集数据核对）。不做引导式距离校准。启用 M3a
要求高度来源为 `PRESET` 或 `CALIBRATED`；只有初始值时绝不改变距离分级。

**接入**：`DepthRepository.estimateDistance` 增加该帧的 `CameraGeometry` 和有效性检查所需的上下文；
`DetectedObstacle` 带上区间、有效性和最终采用的来源供 trace 使用；对用户的事件仍然只说近/中/远（不向
用户报具体米数）。

**配置**：`GroundContactDistanceConfig(enabled = false, …)`；阈值（`q_min`、遮挡余量、各类别的合理
性范围）放在 profile 里。

**trace**：每个障碍物的 `lowerBoundM`、`upperBoundM`、`contactValidity`、`levelToday`、
`levelNew`、`levelUsed`；每帧的 `gravityAgeMs`、`pitchDegrees`、`geometryQuality`、`timestampSource`、
`intrinsicsSource`、`heightSource`。

**测试**：在合成相机上测纯数学（已知俯仰/高度 → 已知距离）；融合表（新估计可以升风险、不能降风险）；
各种有效性情况；仿真器俯仰场景（同一障碍物，俯仰 −30°…+10°，实际使用的分级永远不会比今天的更远）；
部分被挡的行人场景。

## 9. M3b — 基于深度的地面几何

**模型契约**（`docs/models.md` 增加一节）：输入 RGB，ImageNet 归一化，固定形状；输出相对视差（越
大越近），单通道。NCHW 或 NHWC 按张量形状自动识别，和 sem/det 一样。没有类别顺序，所以不存在
sem 那种"顺序错了也能悄悄通过"的风险；对应的风险是**输出反了**（给的是深度不是视差）——预检无法
静态发现，所以前几帧会检查：在与重力一致的地面拟合上，视差是否朝图像底部增大；否则把观测标为
无效。

**能力模型**：深度是可选能力（`DEPTH_GEOMETRY`，§5）。没有模型 → `NotConfigured`；有模型但失效、过
期或输出反了 → `Unavailable` 并附原因。两种情况下基础资格都不变，Guidance 照常运行——配置了但坏掉的深
度模型绝不比没有更糟。失效也绝不会变成"没有障碍物"：基于深度的功能回退到今天的行为，只有已启用且需要
深度的面向用户的功能才受影响（按 §5 规则 2）。

**深度输入变换（几何契约）。** 分析图像和模型张量之间的映射属于模型配置契约，不是实现细节，因为每个视
差采样都会被变成一条射线。
- 每个深度模型声明一个 `ResizePolicy`，与该导出版的验证方式一致：`STRETCH`（例如 `litert-community`
  导出版，不保持宽高比，直接缩放到 686×518）、`CROP_KEEP_ASPECT`（按张量宽高比中心裁剪后再缩放——PC
  实验用的就是这种）或 `PAD_KEEP_ASPECT`。它和模型一起配置（无法从 TFLite 元数据读出），并记入 trace。
- runner 针对每种分析尺寸/旋转构建一个 `DepthInputTransform`，与输出一起返回。允许 `scaleX ≠ scaleY`
  （拉伸）；只要是已知的就行。
- **所有几何计算都在分析图像像素里做。** 地面拟合的采样点是分析图像像素 `p`；`q = g · K⁻¹ p` 用的是
  分析图像的内参 `K`；`p` 对应的视差从张量的 `toTensor(p)` 处读取（双线性）。没有任何计算在张量像素
  里做，所以不需要 `K_depth`。（如果将来某个计算核要在张量空间里做，就必须用
  `K_depth = S · (K − 裁剪偏移)`，`S` 为变换的缩放，并证明与分析空间路径结果一致。）
- `GroundObservation` 的掩码在分析图像坐标系里。`covers(p)` 为 false 的像素（被裁掉或落在填充区）一律
  是**未知**——绝不插值或外推成有效的地面、高于或低于。
- 测试：每种策略和分析旋转下都满足 `toAnalysis(toTensor(p)) = p`；用每种策略渲染同一个合成平面，在分析
  空间里拟合出的地面一致。

**runner**（`sailens-vision.depth`）：LiteRT 会话，默认 GPU fp32（fp16 要先在真机上证明可用），在可
用时通过零拷贝 handle 路径读输出；把视差张量和它的 `DepthInputTransform` 一起返回。

**地面拟合**（`sailens-guidance.geometry`，先用 Kotlin）：
1. 在视差图下部按粗网格采样（约 6000 个点）。
2. 每个采样点算 `q = g · r`，用 RANSAC 拟合 `视差 = A·q + t`，内点阈值为相对误差 4%。
3. 用内点重新拟合；地面掩码 = 在阈值内且在地平线以下（`q > 0`）的像素。
4. 按带符号的相对残差（3 倍阈值）得到高于/低于地面的掩码。
5. 像素离地的公制高度（用于台阶）= `h − Z · q`，其中 `Z = A·h / (d − t)`。
6. 置信度来自内点比例、自由 3 自由度平面拟合与重力约束拟合是否一致，以及最近几帧深度的拟合是否
   稳定。

**调度**：通过 `PerceptionScheduler` 以 2–5 Hz 运行，缓存允许时共用该帧的预处理。过期观测（超过 2
个深度周期）不使用。

**GPU 争用**：深度是继 sem、det 之后的新 GPU 使用者；architecture §6.9 里的资源协调器在这里设计
（取代单独的 P3 GPU 仲裁任务）。优先级：sem/det 优先，深度可被抢占。

**trace**：`depthMs`、`depthBackend`、`groundInlierRatio`、`groundConfidence`、`aboveGroundRatio`、
`belowGroundRatio`、`planeAgreesWithGravity`。

**测试**：用合成视差（平面、台阶、墙、噪声）做 JVM 测试——地面掩码、台阶符号、台阶高度在容差内；
真机测试用存下的帧和 PC 参考输出对比；如果加了 native 计算核，还要有绑定覆盖。

## 10. M4 — 局部世界模型

**V0（逐帧）**
- 地面网格用其对应帧的 `LocalGroundFrame(t)` 表达（§2）：0.1 m 格子，前方 6 m × 宽 4 m（可配置），紧凑
  数组，预先分配。
- 每次更新只用当前观测重建：可走/空地来自 `GroundObservation`（没有深度时来自 sem 可通行区）；占据来
  自有效的 det 接地点和高于地面的区域；落差来自低于地面的区域；其余为未知。

**V1（时间融合，有限制）**
- **静止时**（`MotionState.isStationary`）：格子随时间融合并衰减；两帧前向轴之间的偏航变化 `Δψ`（绕
  上方向，由陀螺仪测得）按 `p_to = R(−Δψ) · p_from` 映射格子。
- **移动且平移未知时**：只有*风险*证据（有东西、落差）能从过去的帧带过来，按 `v_max · 时长` 膨胀
  （步行 v_max ≈ 1.5 m/s），短暂保持（约 0.5 s）后丢弃。空地/地面绝不跨帧保留：当前帧没有观测到是
  空地的格子就是未知。
- 运行时有了**公制平移**来源（M5b 的平移或 M11）后，网格才可以平移并融合空地；那是另一个改动，单独评
  审。只有移动方向来源不行。更新方式由契约固定：`Δ = (deltaForwardM, deltaRightM)` 在
  `LocalGroundFrame(from)` 里表达，偏航变化为 `Δψ`，格子按 `p_to = R(−Δψ) · (p_from − Δ)` 映射（先在上一
  帧里平移，再旋转到当前帧）；`uncertaintyM` 用来膨胀被带过来的格子。

**输出**：`LocalWorldModel(timestamp, ageMs, grid, fusionMode, confidence)`；`SceneSnapshot` 保持现有
字段，以后可以从中读取走廊摘要。

**测试**：闪烁/丢帧的仿真场景；用户在走的场景，证明没有格子因为过去的观测被判成空地；静止场景，证明
融合能减少闪烁。

## 11. M5a / M5b — 移动来源

两种能力，分别评判、分别实现（契约见 §3）：

| | 移动方向 | 公制平移 |
|---|---|---|
| 问题 | 用户往哪边走？ | 两帧之间用户移动了多远？ |
| 使用方 | 正式方向指引门槛（M7–M9） | 滚动空地 / 占据（M4 V1 之后、M6） |
| 契约 | `MovementDirectionEstimate`（朝向、置信度、有效期） | `TranslationEstimate`（Δ前、Δ右、不确定度、置信度） |

**M5a — 评估（离线，产出决策记录）。** 用 M0 采集数据评估候选方案：基于计步的航位推算（需要先定
`ACTIVITY_RECOGNITION` 权限）；从相邻帧估计的视觉运动（例如地面光流——M3 给出了地平面和尺度）。刚性固
定的验证暂不做（2026-09-25 决定）。决策记录给出*方向*和*平移*两个结论，各自为"已验证 / 未验证"，并附
与参照对比的误差统计。M5a 自己的评审要满足两个条件：(1) 结论所验证的帧率和分辨率必须与 M5b 运行时一致——
5 Hz / 640 px 的结果不能证明 15–30 Hz 或全分辨率的实现；需要更高采样的候选方案要单独做运动评估采集，不能
硬套 M0 数据；(2) *平移*结论需要时间对齐的位移参照，能检查每个相邻区间的 Δ前 / Δ右，而不只是整条步行路
线的总长度（总长度只能作粗略的合理性检查）。

**M5b — 运行时实现。** 只针对"已验证"的结论：在 `…guidance.motion` 里实现 `MovementDirectionSource`
和/或 `TranslationSource`，带新鲜度、置信度和显式失败（能力状态按 §5），并在真机上对照 M5a 的证据验
证。只有离线结论而没有 M5b，不满足任何门槛。

## 12. M6–M11（概要；各自评审时再细化）

- **M6** 在 M4 上做占据和走廊可通行宽度（除非运行时有 M5b 或 M11 的公制平移来源，否则只做逐帧 / 静止
  时）；先定下
  connectivity 透视分歧。native 候选：栅格化、距离变换——都要有 profiling 依据。
- **M7** Kotlin 规划器；`GuidanceControlSignal(timestamp, validUntil, mode HOLD|STEER|STOP,
  headingCorrection（归一化）, confidence, stopReason)`；只做调试输出。
- **M8** 针对振荡、来回翻转、过期控制、恢复的验证场景。
- **M9** 按 `guidance-validation-roadmap.zh-CN.md` C 阶段做 earcon（`AudioTrack`、运行时生成 PCM），
  作为实验；正式方向指引只能通过路线图里的门槛。
- **M10** native 计算核：只用 `RegisterNatives`，调用粒度要粗（每帧每个计算核一次），输入原始数组 /
  direct buffer，输出数值，C++ 里不放产品状态，失败要显式返回。没有证明等价的回退要让 Guidance 降
  级，不能悄悄改变语义。
- **M11** ARCore 评估，作为相机架构方案。

## 13. 通用规则

- **新鲜度**：每个观测、世界模型和控制信号都带时间戳和有效期；使用方要检查。过期的东西不能产生
  新的提示或方向。
- **先升后降**：新估计器相对今天的行为只能保持或升高风险；允许它降低风险是另一个需要证据的决定。
- **回退要显式并记入 trace**；会改变导航含义的回退必须证明等价，否则让资格降级。
- **性能**：热路径里不要逐像素对象、不要 `List<Point>`；复用缓冲区（遵守 P3 mask/帧池的所有权规
  则）；每个计算核每帧一次 JNI 调用。
- **对称**：有状态的新组件实现 `reset()`，并接到 `StopSceneAnalysisUseCase` 上。
- **文案**：任何新的事件 key 都走 `SceneEventMessageKeys` / `SceneEventStrings`，中英文都要有。

## 14. 验证层次

| 层 | 内容 | 在哪里跑 |
|---|---|---|
| A | 纯 Kotlin 单测（数学、状态机、新鲜度、融合表） | JVM，CI |
| B | native 计算核测试 + 绑定覆盖 | 真机（androidTest） |
| C | 带基准输出的仿真场景 | JVM，CI |
| D | 采集回放（M0 记录）前后对比 | JVM，本地 |
| E | 真机：耗时、温控、耗电、传感器、时间戳对齐、音频路由 | SM8450 + SM8850 |
| F | 任何输出词汇都要经过目标用户验证 | 按 guidance-validation-roadmap |

## 15. 许可

不复制 PG 代码；如果以后要复制，保留它的版权/许可头并更新 NOTICE。模型权重、声音素材和数据集都
不进本仓库；`scripts/experiments/geometry_probe/` 下的实验脚本是 Sailens 自己的代码。深度模型由用户
自带；官方发行版决定是否打包 Depth Anything V2 Small（Apache-2.0；它的训练数据包含带研究用途条款的数
据集——这是发行版层面的审查事项，不是平台层面的）。
