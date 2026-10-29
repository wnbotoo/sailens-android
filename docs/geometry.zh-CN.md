[English](geometry.md) | **简体中文**

# 相机几何、接地点距离与深度

Guidance 怎么知道相机朝哪、障碍物的接地点有多远，以及深度模型的输出怎么变成地面估计。本文描述
代码现在的样子；两者不一致时以代码为准，并修正本文。

代码在 `sailens-guidance`（`…guidance.geometry`、`…guidance.motion`、`…guidance.depth`）、
`sailens-vision`（`…vision.depth`）、`sailens-camera`（`CameraCharacteristicsSnapshot`），以及 shell 的
`CameraFactsAdapter` 和 `PhoneGeometryStore`。

## 1. 坐标系与时间

- **分析图像坐标系**：竖直方向正确的分析图像，语义掩码和检测框已经共用这个坐标空间（letterbox 由
  `SemanticContentRegion` 裁掉）。所有几何计算都在这个坐标系里做。内参从传感器经 CameraX 的
  sensor-to-buffer 变换和分析旋转映射进来。
- **像素坐标是连续的**：第 i 个像素占 [i, i+1)，所以宽 W 的图像中心是 W/2；旋转 90° 时缓冲区的 y 映
  射为竖直图像的 x = `H − y`。
- **相机坐标系**：x 向右，y 向下，z 向前，与分析图像坐标系对应。
- **重力**：相机坐标系下指向下方的单位向量 `g`，由设备坐标系的重力传感器，经后置摄像头的
  `SENSOR_ORIENTATION` 和该帧的旋转换算得到。
- **朝向和偏航符号**：朝向是**手机**指的方向，不是用户走的方向；手机向右转时朝向增大。安卓旋转角按
  各自轴的右手规则取号，使用前换算成这里的符号。
- **退化视角**：相机几乎垂直朝下或朝上时，光轴投影只剩噪声；朝向沿用上一个样本，不给转速。
- **时间**：Guidance 全程使用 `SystemClock.elapsedRealtime()`。`SensorEvent.timestamp` 就是这个基准。
  相机时间戳只有在 Camera2 报告 `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME` 时才是这个基准；否则改用该
  帧在来源侧的接收时间（`receivedElapsedRealtimeNanos`，Sailens 的回退方案，不是 Camera2 的时间戳来
  源），并把几何标为降级。所用的时间基准记入 trace。真机上帧时间和传感器时间对得多准，用对时突发测量
  （[field-capture.zh-CN.md](field-capture.zh-CN.md) §3）。

## 2. 相机事实与内参

相机事实按原样记录、逐帧解析；实时相机和录下的采集走同一段代码，所以旧采集仍然可用。

- **原始事实**：每次绑定，`sailens-camera` 的 `CameraCharacteristicsSnapshot`（内参标定、畸变、镜头位
  姿、有效阵列和校正前阵列、像素阵列、物理尺寸、焦距、传感器朝向、时间戳来源），每次绑定都重新读，
  绝不跨绑定缓存。每帧的 `ImageFrame` 带相机时间戳、来源侧接收时间、旋转和 `FrameSourceGeometry`
  （CameraX 的 sensor-to-buffer 变换和裁剪矩形）。shell 的 `CameraFactsAdapter` 把快照转成 Guidance 的
  `CameraFacts`；采集则通过 `CaptureCameraRecord.toCameraFacts()` 和 `FrameFacts.of(FrameRecord)` 得到同样
  的东西。
- **`CameraIntrinsicsResolver`**：有 `LENS_INTRINSIC_CALIBRATION` 就用它，否则由
  `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 和 `SENSOR_INFO_PHYSICAL_SIZE` 估计（记录来源）；经该帧的
  sensor-to-buffer 变换映射，再旋转到竖直方向。
  - 变换接受的是**传感器坐标**，即有效阵列矩形所在的坐标系，含原点偏移。标定值相对校正前阵列的左上
    角，所以先按该阵列的原点平移。估计值的中心是同一坐标系下有效阵列的中心。
  - **不应用裁剪矩形**：模型看到的是整个缓冲区。
  - 没有变换时，假定缓冲区是有效阵列的居中填充，并给结果打标记。
  - 不是轴对齐的缩放加平移（可以交换或翻转轴）的变换一律拒绝。
- 仿真器的相机解析为 360×640 类别图上的 449.625 / 180 / 320 px（fx = fy、cx、cy），来自录下的 SM8850
  主摄（4096×3072 有效阵列，f = 2877.6 px，1280×720 缓冲区，传感器朝向 90°）。

## 3. 重力、相机几何与运动

- **`GravityInCamera`**：设备 `TYPE_GRAVITY`（反作用力，朝上）→ 竖直相机坐标系下朝下的单位向量，仅后
  置摄像头。
- **`CameraGeometryResolver`**：每帧一个 `CameraGeometry`，带时间基准、内参、最近的重力样本及其时间差、
  手机设置（§4）。
  - **不可用**：没有帧时间、没有内参、没有传感器朝向或没有重力。
  - **降级**：用接收时间对齐、内参是估计的或映射是假定的、重力离帧超过 100 ms。
- **`MotionTracker`**（纯函数，输入 game rotation vector 样本）：相机朝向和偏航转速。离垂直约 10° 以内
  时沿用朝向（降级），这一段不给转速。保留最近 128 个样本，按帧的时间作答，在前后两个样本之间插值；
  任一侧 200 ms 内没有样本则状态不可用。**只有旋转**：平移未知（§8）。
- **`AndroidOrientationSource`**：`TYPE_GRAVITY` 和 `TYPE_GAME_ROTATION_VECTOR`，`SENSOR_DELAY_GAME`，在
  独立线程上。由 Guidance 会话自己启停（`StartSceneAnalysisUseCase` 的 `finally` 里），所以更高的传感
  器频率只在 Guidance 运行时有代价；带引用计数，晚结束的会话关不掉下一个会话的传感器。
- **接线**：`StartSceneAnalysisUseCase` 在处理帧*之前*解析几何，因为接地点距离（§5）要读它。运动状态
  在决策*之后*读取，只供记录，拖不慢提示。没有绑定 `CameraFactsSource` 的宿主照样运行 Guidance，几何
  不可用，这是合法的：所有距离都停在今天的等级。
- **trace 字段**：`geometryQuality`、`geometryIssues`、`geometryTimeBasis`、`gravityAgeMs`、
  `pitchDownDeg`、`rollDeg`、`intrinsicsSource`、`focalPx`、`headingDeg`、`yawRateDegPerS`、
  `orientationQuality`。旧 trace 解析为 `none`。

## 4. 机高

`PhoneGeometrySettings` 来自 shell 持有的存储（`PhoneGeometryStore`）；运行档位只提供种子值 1.3 m（胸
前高度）。Guidance 每帧读一次 `current()`。

| 放置方式 | 预设高度 | 身高比例 |
|---|---|---|
| 胸前挂绳 | 1.25 m | 0.73 |
| 胸前手持 | 1.20 m | 0.70 |
| 腰间 | 1.00 m | 0.58 |

高度和比例都是提议值，未标定。`PhoneGeometrySettings.of(placement, bodyHeightCm)`：什么都没有 → 种子
（`DEFAULT_SEED`）；有放置方式 → `PRESET`；放置方式加 100–220 cm 的身高 → `CALIBRATED`，取整到厘米；
只有身高 → 种子。

- **接地点开关只在会话边界生效。** `PerceptionProfileManager.selectGroundContact` 和档位一起在下一次
  `activateSelected` 生效，所以运行中的会话、以及它的采集记录下的配置，中途不会变。
- **release 保持关闭。** 只有 debug 版会从存储里设置开关。唯一的界面是 debug 页面 设置 → 诊断 →
  "接地点距离（实验）"：开关、放置方式、可选的身高，以及正在使用的高度和来源。release 里没人写这个存
  储，所以 trace 里记的是种子值。

## 5. 接地点距离

今天的距离等级来自检测框底边在图像中的位置（`DistanceLevel.fromNormalizedY`）。接地点估计每帧在它旁
边计算并记入 trace；只有 `PerceptionConfig.groundContact.enabled` 且机高来自预设或标定时，它才可能改
变等级，而且只能让等级**更近**，绝不更远。

**估计**（`GroundContactDistanceEstimator`）。接地像素是检测框底边中点。β 是该像素射线在地平线以下的
角度，由重力求得，`d = h / tan β`，`h` 为机高。区间是 `h(1 − Δh) / tan(β + δ)` … `h(1 + Δh) / tan(β − δ)`；
种子值或预设时 Δh = 15%，标定时 10%，δ = 2°。

**有效性**，按以下顺序检查；无效的接地点永远不会取代今天的等级：

- `NO_GEOMETRY`：没有几何，或几何不可用、重力过期。估计的内参和接收时间对齐可以接受。
- `NEAR_HORIZON`：β < 3°，距离病态。
- `CLIPPED`：框底离画面底边不到 1%，真正的接地点在画面外。
- `OCCLUDED`：另一个检测框在框的中间一半范围内盖住了接地行。它必须过提取器的置信度下限，且底边比本
  框低出画面高度的 2% 以上。
- `GROUND_UNSEEN`：接地点下方那一条里，语义类别图中属于地面（可通行或地面类别）的不到 30%。这一条高为
  画面的 2%，宽为框的中间一半。它和 `OCCLUDED` 分开，是因为室内很常见：反光地面会被读成 building。
- `IMPLAUSIBLE_BOX`：在所有允许的误差下（机高容差，以及区间所允许的同样 ±2° 重力误差），框的推算高度
  `H = h (1 − tan β_top / tan β)` 都落在该类别的范围之外。范围（提议值，未标定）：行人 0.7–2.4 m，自行车
  0.6–2.2 m，车辆 0.9–4.5 m；静态类别没有范围；框顶碰到画面顶边的不判。
  - 这个界是证明出来的，不是抽样的。与估计值相差不超过 δ 的重力，使重力与任一射线的夹角最多变化 δ
    （球面三角不等式），所以每个 β 都在其标称值的 δ 以内，并截到 ±90°。
  - `H` 对 `tan β_top` 和 `1 / tan β_bottom` 单调，对机高线性，所以这些区间的极值包住了所有允许的组合，
    包括误差锥的内部。底边射线可能垂直朝下时，`1 / tan` 取到极限值，恰好是 0。
  - 把两条射线的误差当作相互独立只会放宽包络，因此只会少拒。

**有效性抓不到的。** 底部被截短的框（腿被墙挡住）读出来更远、也更*高*，而不是更矮，所以在容差内常常
能通过。它的点估计比真实距离远；但它的下界——带着容差、也是融合真正使用的值——仍可能比真实距离近
（相机 1.2 m、下俯 25°、一个 0.9 m 的人实际在 1.6 m、底部 5 cm 被挡：读数 1.67 m，下界 1.32 m，MEDIUM →
NEAR）。真正有保证的只有融合：使用的等级绝不比今天的远。这项检查只对明显的情况起作用，比如止于膝盖的
行人框。

**融合**（`DistanceFusion`）：新等级为 `level(lowerBound)`，阈值 1.5 m / 4.0 m；接地点有效、开关打开且
机高不是种子值时，使用今天的等级和新等级中更近的那个，否则用今天的等级。`DistanceEstimate` 拒绝持有比
今天更远的使用等级。所以今天的代码会播报的障碍物，绝不会被推进 FAR 而丢掉。让估计*降低*风险是另一个
需要现场证据的决定。事件仍然只说 NEAR / MEDIUM / FAR，从不说米数。

**配置**：`PerceptionConfig.groundContact = GroundContactDistanceConfig(enabled = false, …)`。它是
`PerceptionConfig` 的一部分，所以模型回归采集会记录它。

**流水线**：`ProcessFrameUseCase(frame, geometry)`。每帧的距离上下文包含几何、该帧的检测框、它的类别图
和配置。`DefaultDepthRepository` 每次调用都算今天的等级和接地点。跟踪器预测的帧沿用上一次估计。

**trace**：每帧 `phoneHeightM`、`heightSource`、`groundContactEnabled`；`obstacleDistances[]` 每个被跟踪
的障碍物一项：`category`、`zone`、`contactValidity`、`lowerBoundM`、`pointM`、`upperBoundM`、
`levelToday`、`levelNew`、`levelUsed`。

**记录的几何与反事实回放。** 模型回归记录 schema 1.4 增加 `RegressionFrameRecord.geometry`，即该帧处
理时用的几何：它是记录下来的，不从传感器日志重新解析，因为实时流水线用的是它*手上*最近的样本，回放无
从得知。回放默认使用它；调用方可以为旧记录提供几何。反事实回放运行设备没跑过的配置。设备的撤回只说明
*它自己*哪些提示没送达，而改变后的提示可能其实送达了（升级可以打断挡住原提示的那段语音），所以反事
实运行要声明假设：`UNCHANGED_AS_RECORDED`（撤回只落到完全相同的事件上；在记录的配置上这能精确复现设
备，有测试检查）或 `ALL_DELIVERED`（不应用任何撤回）。

**评估**（`GroundContactEvaluation`；本地采集通过 `GroundContactCaptureEvaluationTest`，CI 里用合成采
集）：

- 三种运行：**精确**（记录的配置和几何，原样；必须复现设备）、**基线**（显式关闭）和**处理**（显式
  打开）。基线和处理保留记录的阈值，使用评估指定的机高（`-Pm3aPhoneHeight`），并在每种送达假设下各跑
  一次；效果是*同一假设下*处理对基线。只有关闭状态下录的采集，其 `UNCHANGED_AS_RECORDED` 基线必须等于
  设备。
- 几何用记录的；旧采集从传感器日志解析，这需要相机的 realtime 时钟；两者都没有的采集报告为不可评估。
- 只有 manifest 标为完整、没丢帧、读取器没有警告、读到的记录数与 manifest 一致的采集才算完整。其余的
  能评估多少评估多少，加标签，不计入通过。空评估判失败。
- 它统计用户会**听到**的提示：设备做出了完全相同的提示且没有撤回。其他提示送达情况未知，单独计数。只
  有说出来的内容变了才算提示改变：key、优先级、NEAR 前缀或方向后缀（`SceneEventStringsTest` 检查没有
  事件字符串带占位符）。

## 6. 深度输入变换与地面拟合

**模型契约。** 输入 RGB、ImageNet 归一化、固定形状；输出相对视差（越大越近），单通道；布局和
sem/det 一样由张量形状判定。没有类别顺序可弄错；对应的风险是**反向输出**（输出深度而不是视差），静
态预检看不出来，所以地面拟合每帧检查斜率的符号，拒绝反向的模型。对使用深度的一方的规则：配置了但坏掉
的深度模型绝不比没有更糟。失败、过期或反向输出的模型让 Guidance 照没有它时那样运行，失败绝不会变成
"没有障碍物"。

**深度输入变换**（`sailens-vision` `…vision.depth`）。分析图像和模型张量之间的映射是模型配置契约的一
部分，因为每个视差样本都要变成一条射线。

- 每个深度模型声明一个 `ResizePolicy`，与它导出时的验证方式一致：`STRETCH`（不保持宽高比直接缩放）、
  `CROP_KEEP_ASPECT`（居中裁到张量宽高比再缩放）或 `PAD_KEEP_ASPECT`。TFLite 元数据里读不到它，所以和模
  型一起配置。
- `DepthInputTransform.create(分析尺寸, 张量尺寸, policy)` 提供 `toTensor*` / `toAnalysis*` 和 `covers`。
  允许 `scaleX ≠ scaleY`（拉伸），只要已知即可。
- **所有几何都在分析图像像素里做。** 地面拟合的样本是分析图像像素 `p`；`q = g · K⁻¹ p` 用分析图像内参
  `K`；`p` 的视差在张量的 `toTensor(p)` 处读取。不在张量像素里做任何计算，因此不需要张量空间的内参。
- `covers(p)` 为假的像素（被裁掉或在填充里）是**未知**：绝不插值或外推成地面、地面以上或以下。
  `DisparityMap.sampleAt(u, v)` 双线性插值，模型没看到的点返回 null，并钳在中心落在内容区内的像素范围
  里，所以填充永远混不进来，即使填充是小数（960×1280 → 518×518 填 64.75 px）或半像素（640×1280 →
  518×518 填 129.5 px；中心恰好落在开区间右端的像素算填充）。

**地面拟合**（`…guidance.geometry` `GroundFitter`，Kotlin）：

1. 在图像 40% 高度以下按粗网格采样。远处地面上，小台阶或缓坡都还在内点阈值以内，采样它会把拟合拉弯
   （一条 15 cm 路沿外的远处路面曾把 `t` 从 0.20 拉到 0.10）。
2. 每个样本 `q = g · r`，对 `disparity = A·q + t` 做 RANSAC，阈值 4%，正负两种斜率都试。负斜率更好意味
   着反向输出，观测判为无效。
3. **所有判定都用深度比 `(d − t)/(A·q) = Z_ground/Z`**：内点判定、地面以上 / 以下标签和退行检查。相对深
   度模型输出 `a/Z + b`，a、b 每帧都可以不同；相对 `d` 本身的残差会让一个大的 b 藏住 15 cm 路沿或 1.5 m
   落差，而深度比不受任何 a、b 影响。
4. **地面是用户脚下的那个平面。** 样本按由近到远排序并分成 20 段；候选按它从最近一段起连续解释的段数
   打分，遇到解释不到一半的段就停；平手时选在这段之外匹配更少的候选。重拟合只用这一段的内点，这一段
   在深度上至少要跨 25%，否则结果为 `NO_SUPPORT`。如果按内点总数排名，正在走近的落差的底面一旦占了视
   野的大部分就会变成"地面"。选择和重拟合是刻意冗余的：任何一个单独都能处理走近的过程。
5. **支撑面切换保护。** 上一次有效拟合认为在地面以下的格子，有一半以上现在读成地面（且该网格里至少
   2% 在地面以下）时，结果为 `SUPPORT_SWITCH`，即未知：相对深度分不清"走下了台阶"和"在看边缘外面"。只
   有当某次拟合重新看到比其地面更低的一层（两层同时出现，近的那层在用户脚下），或新会话开始时才解除——
   不按拟合次数解除，因为站在边上的用户可以给出任意多个相同的帧。除此之外只有平移估计才能解除它。
6. **一条平线不是地面**：铺满视野的墙也能拟合出一条，跨度检查会拒绝它。
7. 按带符号的深度比（3 倍阈值）给出地面以上 / 以下的**标签**；像素离地的米制高度 `h − Z · q`，
   `Z = A·h / (d − t)`（`GroundObservation.heightAboveGroundM`）。
8. **与重力一致**：内点上的自由三参数平面，倾斜必须在重力预测的 10° 和 ±33% 以内。**置信度**是内点比
   例（0.2 → 0.6）、一致性（不一致时 ×0.5）和最近 3 次拟合地面覆盖稳定性三项的乘积。

**有效性**：`VALID`、`TOO_FEW_SAMPLES`、`DEGENERATE_VIEW`（相机垂直朝下）、`NO_GROUND`、`NO_SUPPORT`、
`SUPPORT_SWITCH` 或 `INVERSE_OUTPUT`。

目前还没有深度 runner、深度 `ModelType` 和调度；变换和拟合都是纯 JVM 代码，由仿真器的合成视差
（`renderDisparity`，`a/Z + b`，可经任意变换）来检验。

## 7. 仿真器

`sailens-guidance/src/test/java/com/sailens/guidance/sim/` 在 JVM 上确定性地运行合成世界里的脚本化行走。

- **`SyntheticScene`** 是物理真值：§2 的相机、一个位姿（相机高度、低于水平的俯角、走过的距离），以及
  由给定高度的地面块、竖直的盒子和远处背景组成的世界。光线投射渲染器把它变成模型会报告的东西：类别
  图和检测框（框只在某个高度以上才能被检测到，用来表示墙后的人）。`Scenario.facts(i)` 给出同一帧的采集
  会记录的原始事实（相机记录、带 sensor-to-buffer 变换的帧记录、重力样本），所以几何解析场景和解析录
  像完全一样。`SyntheticSceneTest` 检查两者描述的是同一台相机。
- **`GuidanceSimulator`** 跑完整的 `StartSceneAnalysisUseCase` 路径，接线方式和 `DomainBindingsModule` 给
  应用的一样，只有两个模型、时钟和静止标志是假的。JVM 迫使有两处替代，因为 native 库只有 arm64：连通
  性走 Kotlin 回退而不是 native 内核（两者在收窄的走廊上不同，见 [architecture.zh-CN.md](architecture.zh-CN.md)
  里关于连通性的说明），语义统计来自 Kotlin 提取器。golden 里的阻塞、宽度和洪泛数值都是回退路径的。
- **golden**：`sailens-guidance/src/test/resources/scenarios/<name>.golden.txt`，开头是场景描述，之后每帧一
  行：决定的事件（key、优先级、参数）、被跟踪的障碍物（类别 / 区域 / 距离）、地面识别、连通性数值和
  `geometry=<质量>/<俯角>`，保留 4 位小数。`ScenarioGoldenTest` 在第一处不同的行失败。`<name>.m3a.golden.txt`
  是同一场景打开接地点距离（胸前预设 1.3 m）、带每个障碍物距离证据的版本（`GroundContactScenarioTest`）；
  关闭时普通 golden 不变。有意的改动之后，重新生成并把 diff 和改动一起提交：
  `gradlew :sailens-guidance:testDebugUnitTest --tests "*ScenarioGoldenTest*" -PupdateGoldens`。
- **golden 固定行为，不代表认可。** 静止在 3 m 外的人是 MEDIUM 还是 FAR 只取决于俯角和机高；被读成
  building 的室内地面先给出 CRITICAL 的"前方不通"，再给"识别不到地面"；下到路面的 15 cm 路沿看不见；走向
  1.5 m 落差时阻塞升到 1.0 却没有提示；下半身被墙挡住的人整个走近过程都读作 FAR。打开接地点后，没有任何
  场景的决定事件改变，但等级会变（3 m 外、下俯约 29° 的人保持 MEDIUM，而今天的等级是 FAR；停着的车提
  前约 0.25 m 变成 NEAR）。

## 8. 几何的规则，以及为什么这样做

**所有几何使用方的规则**

- **先升后降**：新的估计器相对今天的行为只能保持或提高风险；让它降低风险是另一个需要证据的决定。
- **新鲜度**：每个观测都带时间戳和有效窗口，使用方要检查。过期的东西不能产生新的提示或方向。
- **回退要明确并记入 trace**；会改变导航含义的回退，必须证明等价，或者降级结果。
- **性能**：不要逐像素对象，热路径里不要 `List<Point>`；按帧池和掩码池的所有权规则复用缓冲区；每个内
  核每帧一次 JNI 调用。
- **对称**：有状态的组件实现 `reset()`，并接到 `StopSceneAnalysisUseCase`。

**为什么用接地点和重力。** 在 DIODE 和 Cityscapes 上的 PC 实验
（[experiments/geometry-probe-2026-09.zh-CN.md](experiments/geometry-probe-2026-09.zh-CN.md)）发现：

- 只靠相对视差分不清地面和墙（每个平面在视差里都是线性的）；在没有地面的帧上，它仍把下半幅图像的一半
  左右标成地面。加上重力后降到约 20%。
- 机高误差线性传递（机高 ±15% → 距离 ±15%），2° 的重力误差再加约 5 个百分点：机高是标定问题，不是常
  数。
- 从深度外推的障碍物距离不可靠，而且偏*远*，是危险的方向；距离必须来自接地点，而被遮挡或被截断的接地
  点也会同样失败，所以它需要自己的有效性检查。
- 用几何分割室内地面效果很弱（IoU 约 0.33）：它是语义模型的补充，不是替代。

**没有视觉惯性里程计的手机能知道什么。** 几秒内的重力和旋转是可靠的。平移和速度不可靠（加速度二次积
分会漂移，计步器需要 Sailens 不申请的权限），而且手持手机的设备偏航不等于行走方向。所以 `MotionTracker`
只有旋转，用户移动时不跨帧融合任何米制证据。

**许可。** 没有复制 Project Guideline 的代码；如果将来复制，保留其版权和许可头并更新 `NOTICE`。权重、
声音素材和数据集都不进本仓库；`scripts/experiments/geometry_probe/` 下的脚本是 Sailens 自己的代码。深度
模型自带；分发版如果附带深度模型，要审查它的许可和训练数据条款（Depth Anything V2 Small 是 Apache-2.0，
其训练数据包含仅限研究用途的数据集）。
