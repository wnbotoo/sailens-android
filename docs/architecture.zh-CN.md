[English](architecture.md) | **简体中文**

# Sailens 架构

> 状态：**已定案，实施中。**它替代按技术层拆分的模块结构，以及 A/B 两仓库之间基于 fork
> 的关系。G1 以及 G2 的第 2、4 步已落地，G3 进行中（第 5、6 步已落地）；第 3 步（B 转为
> composite build 消费方）和 G4、G5 尚未开始。§11 记录的是计划，不是进度。

## 1. 摘要

Sailens 是 **Smart AI Lens**：采集（今天是相机，未来可以是语音）→ pipeline → AI 结果
→ 输出。面向盲人和低视力用户的导航辅助是它的第一个应用，但不是可复用代码本身的身份。

目前有两条产品 pipeline：

- **Guidance** —— 连续运行，用于导航安全。语义分割必需，检测可选。
- **Describe** —— 按需运行。VLM 用于回答“眼前有什么”或针对当前画面回答问题。

平台接受全部四种产品组合：

| Guidance | Describe | 是否合法 |
|---|---|---|
| 无 | 无 | 是 |
| 有 | 无 | 是 |
| 无 | 有 | 是 |
| 有 | 有 | 是 |

零 pipeline 是合法的 shell 状态。某个具体发行版是否承诺必须提供其中一条，是另外一层
application-level contract。

代码将围绕这两条 pipeline 和它们共享的 Sailens 基础能力重组。sailens-yolo 不再作为
本仓库的 fork，而是通过 Gradle composite build 依赖本仓库的薄应用。

## 2. 目标与非目标

### 目标

- 用普通依赖替代 sailens-android 与 sailens-yolo 之间的 fork-and-merge。
- 从横向 Clean Architecture 技术层，转为围绕稳定产品边界和 runtime 边界组织代码。
- Guidance 与 Describe 可以独立配置，各自由自己的静态可用性和自己的 runtime state 决定是否
  呈现（§5.2）。
- 重型 concrete runtime 保持可选；不能因为 shell 知道 Describe 存在，就让不使用 VLM
  的应用也被迫带上 VLM runtime。
- 许可证边界与依赖图一致。
- 保留 §6.5 和 §6.9 中列出的现有性能性质。
- 每一步迁移结束后 A、B 都保持可构建。
- 显式控制 library public API，而不是让所有 Kotlin 符号默认暴露。

### 非目标

- 通用 pipeline graph、plugin discovery 或可配置 DAG。
- 为假想产品提前设计一个通用 AI Lens SDK。
- 发布到 Maven 或引入 artifact versioning。
- 在本次重构中实现 Voice / ASR。
- 在真正 VLM runtime 出现前确定最终 GPU 仲裁策略。
- 把每个逻辑边界都做成 Gradle module。只有独立 compile、dependency、test、native 或
  public-API boundary 有价值时才拆 module。
- 行为变更；唯一例外是第 10 步明确标出的 pipeline availability 行为。

## 3. 重构前的代码结构

这是下面的迁移计划要替换掉的起点。当时有六个主要 module，主要按技术层拆分：

~~~text
:domain         (无依赖)
:data           → :domain
:camera         → :domain, :ux
:presentation   → :camera, :domain, :ux
:ux             (无依赖)
:app            → 全部
~~~

这个结构对单一应用能工作，但产品已经演进出两条不同 pipeline，因此一些边界开始落错位置：

| 项目 | 问题 |
|---|---|
| ClassMapper | 把数据集事实和导航判断混在一起 |
| FrameTrace | 明显是 Guidance 专属，却放在宽泛 domain 层 |
| ObstacleDetection.category | Guidance 分类直接泄漏到 detector 输出 |
| SegmentationOutput.analysisStats | 通用 inference 输出里已经带了导航统计 |
| libsailens_ml.so | 通用预处理与导航 kernel 共用一个 native 库 |
| :camera → :domain | 复用 capture 会连整个导航 domain 一起拖进来 |
| :presentation | Guidance UI、共享输出设备、app shell 与未来 Describe UI 混在一起 |

另外还有一个 JNI 迁移风险：当前 native 方法通过静态 Java JNI symbol name 绑定。移动
Kotlin class 可能编译完全成功，却只在第一次调用 native 方法时崩溃。

迁移基线：**171 个 unit test**。测试必须跟代码一起迁移，总数不能无声下降。

## 4. 目标结构

### 4.1 Gradle modules

初始目标是 **9 个 library module + 1 个 reference app**：

| Module | 职责 |
|---|---|
| sailens-core | 最小共享 contract/value types：ImageFrame/YUV planes、geometry、BinaryMask、MlRuntimeInfo、LogService |
| sailens-camera | CameraX 采集、连续 frame source、带 freshness 上限的当前帧 snapshot、preview composable、相机权限状态/请求 primitive |
| sailens-runtime | LiteRT session、加速器选择、模型来源、metadata、YUV→tensor 预处理、共享预处理 cache、硬件画像、资源仲裁机制 |
| sailens-vision | segmentation/detection runner、数据集 taxonomy、默认后处理器 |
| sailens-vlm | VLM engine contract：frame + 完整 prompt → streamed text；不放产品 prompt policy |
| sailens-output | TTS、audio focus、读屏检测、haptic primitive、speech routing mechanism、clause buffering |
| sailens-guidance | 导航逻辑：NavigationSemantics、fused semantic kernel、连通性、安全分析、事件、cooldown、tracking、depth、sensor、trace/replay |
| sailens-describe | Describe 产品逻辑：prompt policy、snapshot freshness、请求调度、use case/controller |
| sailens-shell | 可复用 Sailens presentation/composition：root Compose、navigation、settings、design system、Guidance UI、未来 Describe UI、diagnostics/about、DI 聚合 |
| app | A 的薄 Android host：Application、MainActivity、manifest/window ownership、身份和 edition config |

目标中**没有独立 sailens-ux 或 sailens-guidance-ui Gradle module**。它们的逻辑边界继续作为
sailens-shell 内部 package 存在。

原因是它们没有足够价值支撑独立 compile/dependency/API boundary。UI、design system、
settings、navigation 本来就作为一个可复用 application presentation shell 一起变化。
Guidance 算法仍保持独立，因为它包含大量安全逻辑、测试，并且变化轴与 UI 不同。

相机权限的**策略 UI**属于 presentation，不属于 capture。sailens-camera 可以暴露权限状态以及
触发权限请求的动作，但 rationale dialog、文案和 design-system 依赖都放在 sailens-shell。
这样旧 ux module 删除之后，sailens-camera 也不会反向依赖 shell。

### 4.2 依赖图

~~~text
app / B / C ─────────────► sailens-shell
                              │
                              ├──► sailens-guidance
                              ├──► sailens-describe
                              ├──► sailens-camera
                              └──► sailens-output

sailens-guidance ─────────► sailens-vision ───► sailens-runtime ───► sailens-core
        │                         │                     │
        └─────────────────────────┴─────────────────────┘

sailens-describe ─────────► sailens-vlm ─────► sailens-runtime
        └────────────────────────────────────► sailens-core

sailens-camera ───────────────────────────────► sailens-core
sailens-output ───────────────────────────────► sailens-core
~~~

规则：

1. 依赖只向下。
2. sailens-guidance 和 sailens-describe 互不依赖。
3. sailens-guidance 保持无 UI。
4. sailens-shell 持有 presentation policy 与 app composition，但不持有具体 model runtime。
5. sailens-runtime 只提供资源仲裁**机制**，不放 Guidance/Describe 产品策略。
6. 任何共享基础模块都不能依赖 sailens-guidance、sailens-describe 或 sailens-shell。

未来真正的 LiteRT-LM concrete runtime 应优先单独做成例如 sailens-vlm-litert。完全不用
VLM 的 edition 不应承担它的 binary/native dependency cost。

### 4.3 包名与坐标

Gradle module 使用完整 sailens-* 前缀，因为这些是 Sailens 自己的 framework module，
不是独立的通用 Lens SDK。

Kotlin/Java package 不重复产品名：

| Module | Package root |
|---|---|
| sailens-core | com.sailens.core |
| sailens-camera | com.sailens.camera |
| sailens-runtime | com.sailens.runtime |
| sailens-vision | com.sailens.vision |
| sailens-vlm | com.sailens.vlm |
| sailens-output | com.sailens.output |
| sailens-guidance | com.sailens.guidance |
| sailens-describe | com.sailens.describe |
| sailens-shell | com.sailens.shell |

Composite build 坐标跟 module 名一致，例如 com.sailens:sailens-shell。

所有可复用 library module 开启 Kotlin explicit API mode。除非确实需要跨模块使用，内部
实现符号保持 internal。

### 4.4 sailens-shell 内部 package

UI 合并只是 Gradle boundary 合并，不代表逻辑边界消失：

~~~text
sailens-shell/
└── com/sailens/shell/
    ├── app/
    ├── navigation/
    ├── design/
    │   ├── theme/
    │   └── components/
    ├── guidance/
    │   ├── screen/
    │   ├── overlay/
    │   └── settings/
    ├── describe/
    ├── settings/
    ├── diagnostics/
    ├── about/
    └── di/
~~~

每个 host application 仍然自己拥有 Application、MainActivity 以及 manifest/window
lifecycle。shell 提供 SailensRoot()、navigation entries 和 Koin modules 等可复用组装能力。

## 5. Application editions 与 capability model

### 5.1 A、B、C

**A —— sailens-android，Apache-2.0**

包含全部可复用 Sailens modules 与薄 reference app。继续保持零权重，支持 BYO model。
BYO weights 从 data/src/main/assets 移到 app/src/main/assets。

A 可以合法地以零 available pipeline 启动，并显示清晰的 zero-pipeline state，指向
model/runtime 设置文档。

**B —— sailens-yolo，AGPL-3.0**

建在 sailens-shell 上的薄应用。它把 A 作为 git submodule pin 到具体 commit，再通过
composite build 消费。B 自己拥有 model weights、产品身份、capability expectation，以及
未来真正需要的 YOLO-specific decoder/runtime code。

B 不再要求与 A 代码完全一致。

**C —— 未来 edition**

可以提供 Guidance、Describe、两者都有或两者都没有。需要 VLM 时由 edition 显式提供
concrete runtime。

### 5.2 configured、expected、available 与 runtime state 是四个不同概念

平台不能把四个问题混在一起：

1. **Configured** —— edition 打算提供这条 pipeline。
2. **Expected** —— edition 把它作为产品能力承诺。
3. **Availability / preflight** —— 低成本静态检查确认 implementation、model source 与可机器
   验证的 contract 存在；**不会**初始化模型或加速器。
4. **Runtime state** —— 用户真正开始使用后，session 的运行状态：未开始、初始化中、运行中或失败。

代表性结构：

~~~kotlin
data class SailensAppSpec(
    val guidance: GuidanceSpec? = null,
    val describe: DescribeSpec? = null,
    val expectations: CapabilityExpectations = CapabilityExpectations(),
)

sealed interface PipelineAvailability {
    data object NotConfigured : PipelineAvailability
    data object Available : PipelineAvailability
    data class Unavailable(val reason: StaticUnavailableReason) : PipelineAvailability
}

sealed interface PipelineRuntimeState {
    data object NotStarted : PipelineRuntimeState
    data object Initializing : PipelineRuntimeState
    data object Running : PipelineRuntimeState
    data class Failed(val reason: RuntimeFailure) : PipelineRuntimeState
}
~~~

pipeline spec 为 null 表示 NotConfigured；两条都 null 完全合法。

**Available 不等于 runtime ready。**preflight 可以检查 implementation 是否已接线、配置的 model
source 是否能解析、低成本 metadata/shape/count 是否通过、以及 TaxonomyId ↔
NavigationSemantics 等静态 binding 是否一致。它不能为了决定一个入口是否存在，就提前创建
compiled model、初始化 GPU/NPU delegate、分配 inference buffer 或跑一次 probe inference。
真正的模型/加速器初始化继续保持 lazy，在 session 启动时发生，和今天一样。

expectation 是 edition-level validation，不是 framework 限制。例如 sailens-yolo 可以声明
Guidance required。如果它打包的 sem model 缺失、声明的 taxonomy 不兼容，或其他**静态配置**
contract 失败，这是 edition configuration failure，即使 Sailens framework 本身允许
zero-pipeline。

静态配置失败按 build type 区分处理：

- **debug/development：**fail fast，让 packaging/wiring 错误尽早暴露；
- **release：**进入明确的 fatal configuration state，不 crash、不进入 restart loop。这个状态必须
  对 accessibility 可达，并且至少主动通过当前可用的非视觉通道通知一次；speech 不可用时由
  haptic 兜底。

pipeline 也可能通过 preflight，却在某台设备上真正初始化 session 时失败，例如 GPU delegate
或 output buffer allocation 失败。这是 **runtime failure**，并不与 Available 矛盾。
Guidance 继续走今天已有、用户可见且可重试的 analysis-start-failed 路径；Describe 走对应的
request/runtime error 路径。

optional 但静态 unavailable 的 pipeline 不应作为死控件暴露。shell 可以隐藏它，或者在普通
action path 之外提供明确的 edition-level explanation。

## 6. 设计决策

### 6.1 Continuous frame 与 on-demand snapshot 是不同 contract

Guidance 消费连续 frame stream。Describe 是按需的，不应该为了拿“当前画面”而自己长期
collect frame stream。

因此 sailens-camera 暴露两个概念：

~~~kotlin
interface FrameSource {
    val frames: Flow<ImageFrame>
}

interface FrameSnapshotProvider {
    fun currentFrame(maxAgeMs: Long): ImageFrame?
}
~~~

二者可以由同一个 camera session 实现。

Describe 必须拒绝过旧 snapshot，绝不能静默描述几秒前的旧画面。这与已有 VLM/ASR
assistant plan 的安全意图保持一致。

Describe 仍然不依赖 CameraX；未来其他 source 只需要实现 FrameSnapshotProvider。

### 6.2 Taxonomy 与 NavigationSemantics 分离；机器校验只能做到证据允许的程度

当前 ClassMapper 混着：

- 数据集事实：class count、output order、labels；
- Guidance policy：passable、obstacle、road、traffic light、ground type、obstacle category。

拆成：

- **Taxonomy**，归 sailens-vision；
- **NavigationSemantics**，归 sailens-guidance。

两者都带稳定 TaxonomyId 和 class count。Guidance binding 在 static preflight 阶段验证
**声明的** taxonomy id 与 class count 是否和 NavigationSemantics 一致。

~~~kotlin
@JvmInline
value class TaxonomyId(val value: String)

interface Taxonomy {
    val id: TaxonomyId
    val classCount: Int
}

interface NavigationSemantics {
    val taxonomyId: TaxonomyId
    val classCount: Int
}
~~~

但这**不能证明**一个 opaque model 的实际输出通道就真的具有声明的语义。如果可信 model
metadata 带 labels，就进一步机器校验完整 label order；如果没有，channel semantics/order
仍然是人工 release gate。一个 shape 与 class count 都正确的模型，完全可能正常运行，却把
类别含义映射错。

因此，model 旁边的 contract test 只能检查机器真正看得到的东西：shape、dtype、layout、
class count、声明的 taxonomy binding，以及 metadata 里确实存在的 labels。labels 不存在时，
contract test 绝不能声称自己验证了 semantic order。edition 还可以把一次人工确认的 taxonomy
声明绑定到模型的精确 hash；一旦替换权重，就让这次人工验证自动失效。

不存在 neutral NavigationSemantics fallback。缺失或机器可检测到的不兼容会让 Guidance
静态 unavailable；如果 edition 声明 Guidance required，则这是 static configuration failure。
至于 metadata 无法观察到的错误 channel order，它仍然是 release-process safety failure，
不是 startup validation 能凭空检测出来的东西。

### 6.3 Detection output 保持通用

Detection runner 只输出 class id、可选 label、confidence 和 box。ObstacleCategory 属于
Guidance，通过 NavigationSemantics 在 inference 之后映射。

NMS、tensor layout decoding 和 letterbox geometry 保留在 sailens-vision。

### 6.4 Describe 持有 prompt 和请求策略

sailens-vlm 只接收完整 prompt 并返回 streamed text。它不知道用户是否是盲人，也不知道
Sailens 是导航产品，更不知道 frame stale policy。

sailens-describe 持有：

- system/user prompt assembly；
- current-frame freshness；
- single-flight/cancellation；
- quick describe 与 question 的产品语义；
- generation 太慢时结果是否仍有效。

真正的 LiteRT-LM concrete runtime 出现时放在独立 implementation module。

### 6.5 Segmentation 继续只做一次 native pass

现有 semantic 性能红线保持：

- postprocessBackend = native_score
- outputReadTimeMs ≈ 0

native path 通过 handle 读取 LiteRT output buffer，一次 pass 同时计算 argmax 与 Guidance
统计。本次重构不能为了让模块图更漂亮，又重新扫描一次完整 semantic tensor。

保持通用 seam：

~~~text
sailens-vision    SegmentationRunner<R>(..., SemanticPostprocessor<R>)
sailens-vision    ArgmaxPostprocessor
sailens-guidance  NavigationScorePostprocessor
~~~

NavigationScorePostprocessor 就是当前 fused Guidance postprocessor 的迁移版本。

### 6.6 Output arbitration：底层提供机制，上层决定产品策略

sailens-output 提供共享 output mechanism：

- TTS 与 audio focus；
- screen-reader channel；
- haptic primitive；
- priority/preemption primitive；
- clause buffering；
- announcement flow。

它不能知道 Guidance 或 Describe 是什么。

“Guidance safety alert 永远高于 Describe information”属于 sailens-shell / edition composition
的产品策略。Guidance 产生 typed event/urgency；Describe 产生 information result；shell
再把它们映射到 output priority。

Screen-reader announcement 在 sailens-shell 里只有一个 collector，因为真实 Android View
属于 presentation。

Guidance-specific haptic vocabulary 留在 shell 的 Guidance presentation package。
SPEECH_UNAVAILABLE 可以继续作为共享 output-level signal，因为它描述的是输出通道本身，
不是导航语义。

### 6.7 sailens-core 必须刻意保持小

sailens-core 不能变成新的“什么都往里扔”的 domain module。

只放真正被多个低层模块共享的稳定 type/contract。特别是：

- LogService interface 可以放 sailens-core。
- FileLogService **不能**放进去。它依赖 Android Context/files，是 app-platform
  implementation；初始放在 sailens-shell composition。
- trace/replay 在出现第二个真实 consumer 之前继续留在 sailens-guidance。
- sensor、depth、Stabilizer、FrameQualityAnalyzer 同理。

### 6.8 共享 preprocessing cache

sem 与 det 通过 InputPreprocessCache 复用同一帧的 YUV→tensor conversion。必须保持一个
runtime 级共享实例，不能每个 runner 各自一份。

归 sailens-runtime。

### 6.9 Runtime resource arbitration 只做机制，不做 pipeline policy

未来 VLM 与 realtime vision model 可能争抢 GPU/NPU。

sailens-runtime 可以提供中性的 resource coordinator，例如 acquire/release + priority +
preemptibility，但不能出现以 Guidance 或 Describe 命名的策略。

哪些任务是 safety-critical，以及 Guidance protection 暂停时要给用户什么反馈，都是上层
产品决策。如果为了 Describe 暂停 Guidance，这种 protection loss 必须通过非视觉通道告知。

### 6.10 JNI：RegisterNatives + 每模块 native library

先摆脱静态 Java JNI symbol，再移动 package。

目标 native library：

| Library | Module | 职责 |
|---|---|---|
| libsailens_runtime.so | sailens-runtime | YUV 预处理 |
| libsailens_vision.so | sailens-vision | 通用 semantic argmax 与 detection postprocess |
| libsailens_guidance.so | sailens-guidance | fused Guidance semantic score 与 connectivity stats |

每个 library 在自己的 JNI_OnLoad 注册 native method。通过 FindClass 找类的场景仍需要
keep rule。

不能让 sailens-runtime 里的共享 native library 去注册 Guidance class，否则 module dependency
会反向。

LiteRT zero-copy helper 继续作为 runtime-owned 的小型 native header/helper，供 vision 与
Guidance 的 CMake 使用，不引入反向 Kotlin/Gradle dependency。

### 6.11 配置

每个低层 module 只持有自己的 config type。硬件检测归 sailens-runtime，各模块默认值归各
模块，edition/tier composition 归 sailens-shell 与 host app。

sailens-shell 绝不直接读取别的应用的 BuildConfig。host 显式传入 AppInfo、product
capability spec、diagnostics flags 和 OSS metadata。

## 7. 开发方式：composite build，不上 Maven

sailens-yolo 把 sailens-android 作为 git submodule pin 到具体 commit，并通过 Gradle
composite build 引入。

~~~kotlin
includeBuild("sailens")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("sailens/gradle/libs.versions.toml")) }
    }
}
~~~

B 消费例如：

~~~kotlin
implementation("com.sailens:sailens-shell")
~~~

不引入 Maven publishing，也不增加版本号；submodule commit 本身就是版本边界。

当前阶段 B 有意直接读取 A 的 version catalog，让 AGP/Kotlin/tooling 保持一致。

在大量 module movement 依赖它之前，必须先用真实 Android resources、assets、CMake
验证 composite build。

## 8. 许可证与仓库边界

- A 继续是 Apache-2.0，且不提交 model weights。
- B 继续是 AGPL-3.0，自行持有 model weights 与 edition-specific notice。
- 仓库依赖图，而不是共享 source history，成为代码边界。
- Cityscapes dataset/model distribution terms 仍然是单独的 release 问题，本次重构不决定。

A 已发布的 main history 继续 append-only。取消 fork 关系**不是**重写
sailens-android/main 的理由。

B 在停止作为 fork 时可以选择 fresh history，这是 B 自己的 repository migration decision。

## 9. 会消失的东西

B 中：

- merge=ours fork machinery；
- upstream sync recipe 与 disabled push remote；
- “两边零代码差异”不变量；
- 只为维护 fork merge-base 存在的 repository convention。

A 中：

- data/src/main/assets 作为 BYO 路径；
- 新替代方案验证后，旧 mlModelBinding；
- 代码迁移完成后，旧 :domain、:data、:presentation、:ux modules。

目标中没有单独 sailens-ux 或 sailens-guidance-ui module。

## 10. 待办 release item 的归属

| Item | Home |
|---|---|
| 移除 LiteRT 依赖链带来的未使用 dataSync foreground service | 放在能安全移除它的最低 module；先验证 manifest propagation，再确定最终归属 |
| privacy policy | 各 app |
| safety disclaimer / first-run | sailens-shell，按 configured pipeline 选择 |
| release signing | 各 app |
| Cityscapes non-commercial/distribution 结论 | B / model distribution decision |

## 11. 迁移计划

实现仍然保留 11 个较小的 **step**，因为这样更利于控制依赖移动、组织 commit 和定位 regression。
但 step 不再等同于“必须停下来等人工 review”。

每个 step 做完后，立即跑能证明当前 tree 自洽的 targeted build/tests。只要通过，就直接在同一个
gate 内继续下一步。只有到下面 5 个 **review gate** 才停下来做人审。如果中间检查失败，就在
当前 gate 内修复，而不是把一个半迁移状态交出来。

| # | Implementation step | Review gate |
|---|---|---|
| 1 | JNI 改成 RegisterNatives，并在挪 package 前补 binding + array-kernel contract coverage | **G1 — native safety foundation** |
| 2 | 抽 sailens-shell；root Compose/navigation/settings/design/Guidance UI **以及 camera permission rationale UI** 进入 shell；camera 改为只暴露权限状态/请求 primitive，解除对 :ux 的依赖；Application/MainActivity 留在 app | **G2 — app/composition foundation** |
| 3 | B 变成薄 composite-build consumer；移除 B 的 fork-only guardrail，不删除 A 的 append-only 规则 | G2 |
| 4 | 抽 sailens-core 与 sailens-camera；引入 FrameSource + FrameSnapshotProvider；验证 camera 仍不依赖 shell/design UI | G2 |
| 5 | 抽 sailens-runtime；拆 native runtime preprocessing | **G3 — vision/Guidance runtime** |
| 6 | 抽 sailens-vision；Taxonomy / NavigationSemantics 拆分并加入 TaxonomyId 校验；拆 vision native | G3 |
| 7 | 导航逻辑迁到 sailens-guidance；Guidance presentation 只进 shell；拆 Guidance native | G3 |
| 8 | 抽 sailens-output；把 arbitration primitive 显式化，同时保持当前行为 | **G4 — output + Describe** |
| 9 | 抽 sailens-vlm 与 sailens-describe；prompt/snapshot/scheduling policy 迁入 Describe | G4 |
| 10 | **行为变更：**实现 configured/expected/static-availability/runtime-state model、fatal static-configuration handling 与合法 zero-pipeline state | **G5 — capability semantics + closeout** |
| 11 | 更新 README、AGENTS.md、models.md asset 路径、B 文档与所有 architecture reference | G5 |

### 11.1 Review gates

**G1 — native safety foundation**

任何 package/module movement 开始前必须停一次。验收证据：

- A 构建成功，现有 JVM tests 保持全绿；
- §12.2 的 Layer A binding coverage 与 Layer B array-kernel instrumentation tests 在 arm64 真机通过；
- 当前 13 个 JNI declaration 全部通过 RegisterNatives 注册；
- 构建出的 native library 不导出任何 Java_<mangled> JNI entry point；JNI_OnLoad 是唯一 JNI
  registration entry point；
- 不引入任何有意的 inference 行为变化。

这个 gate 的作用是把 JNI migration 风险和后续所有 package move 完全隔离开。

**G2 — app/composition foundation（steps 2–4）**

把 shell、B composite consumption、core、camera 当成一个完整 migration phase。完成以下检查后
才停：

- A build/tests 通过；
- B 通过真实 composite build 解析 A，并能 build/launch；
- A 能 launch；
- camera session 正常；
- camera 不反向依赖 shell/design UI；
- Application/MainActivity 仍归各 host app；
- FrameSource 与带 freshness 上限的 FrameSnapshotProvider ownership 正确。

steps 2–4 可以各自保留独立 commit，但不再制造三个独立 review handoff。

**G3 — vision/Guidance runtime（steps 5–7）**

runtime、vision、Guidance 作为一次耦合较强的 extraction 连续完成。只有最终 dependency
direction 和三个 native library 归属都形成后才停。验收至少包括：

- A/B 完整 build 与 tests；
- libsailens_runtime.so、libsailens_vision.so、libsailens_guidance.so 都保持 G1 的注册保证；
- native 拆分后 array-kernel contract tests 仍通过；
- B 的真实 float model 覆盖 native_score 与 native_bbox_nms_float_handle 两条 handle 热路径；
- 同一目标设备上保持 §12.3 性能红线与 same-frame preprocessing-cache reuse；
- 跑通真实 Guidance session，且没有行为 regression；
- sailens-guidance 不含 Compose/UI dependency。

这是整个结构重构风险最高的 gate，不能再和 G4 合并。

**G4 — output + Describe（steps 8–9）**

shared output mechanism 与 headless Describe pipeline 连续完成，然后验证：

- 当前 Guidance speech/haptic 行为保持；
- Guidance safety output 可以按 shell policy 抢占 Describe information，同时 sailens-output
  自身仍保持 policy-neutral；
- Describe 使用 FrameSnapshotProvider，而不是 collect continuous frame stream；
- stale snapshot、cancellation、single-flight 有 targeted tests；
- Guidance-only edition 不会被拖入 heavyweight concrete VLM runtime dependency；
- 如果物理 sailens-vlm extraction 被延迟，下文定义的临时依赖安排仍然成立。

**G5 — capability semantics + closeout（steps 10–11）**

这个 gate 单独存在，因为 Step 10 已经不是纯代码搬迁，而是产品行为变化。验证完整状态模型后，
再基于真正落地的代码做最终文档同步：

- 四种 pipeline 组合全部合法；
- static availability/preflight 不会 eager initialize LiteRT/GPU/NPU session；
- required static-configuration failure 符合 debug fail-fast / release accessible-fatal-state contract；
- device-specific session initialization failure 仍然属于 runtime failure，并走可重试路径；
- unavailable pipeline 不会变成无效的 accessibility control；
- 最终 A/B build、tests、launch、native/performance 与 §12 structural checks 全通过；
- README、AGENTS.md 和 model/repository 文档描述的是最终真正落地的代码。

### 11.2 Gate 内持续验证

review gate 变少，**不代表验证变少**。每个 implementation step 完成后仍立即运行最小但有效的
检查，例如 compile + targeted tests、composite build 或 native instrumentation test。通过后由
实现者自动继续；只有某个失败无法在当前 gate 内解决时才提前停下来。

一个 gate 可以包含多个聚焦的 commit。没有必要为了让 review boundary 和 Git history 一一对应，
强行把整个 gate squash 成一个 commit。

sailens-vlm 是唯一允许延迟物理 Gradle extraction 的边界：如果一开始真的只有几个 trivial
interface 且没有 concrete implementation，可以先保持 logical boundary。推迟期间，VLM contract
先作为 sailens-describe 内部的一个 package 存在，sailens-describe 暂时直接依赖
sailens-runtime。现有 LiteRtVlmEngine 是未来 sailens-vlm-litert implementation module 的自然起点。

一旦要真正接入 heavyweight concrete VLM runtime/native dependency，这个延迟就必须结束：
必须先把 contract 与 concrete implementation 物理拆开，避免 Guidance-only edition 被拖入
VLM runtime 成本。即使暂时不拆 Gradle module，logical boundary 也已经固定。

## 12. 验证

### 12.1 测试保持

- 基线：171 个 unit test。
- 测试随代码一起迁移。
- 数量不能下降，除非有明确记录的理由。
- 数量不下降只是 regression guardrail，不代表行为等价。

### 12.2 Native 验证分三层

当前代码一共有 13 个 JNI entry point：9 个 array-based kernel，加上 4 个 LiteRT
TensorBuffer-handle 路径。A 在没有真实 compiled model 的情况下无法制造合法 LiteRT handle，
所以“在 A 中把 13 个 JNI entry 全部至少调用一次”不能作为 Step 1 的可执行退出条件。验证按
实际能证明的内容拆成三层：

A、B 两层是 instrumentation test，跑在 arm64 真机上，不是 JVM unit test：项目只构建
arm64-v8a，x86 模拟器加载不了这些库。因此它们不属于 §12.1 的 171 个测试基线，也不会在只跑
JVM 的 CI 里执行。

**A. Binding coverage —— 挪 package 前在 A 中强制通过**

- load 每一个目标 native library；
- JNI_OnLoad/RegisterNatives 的每次注册都检查并向上传播失败；
- registration table 覆盖全部 13 个 Kotlin native declaration；
- class name、method name 或 JNI signature 任意错误都会让 library load 失败；
- **任何 library 都不导出 `Java_<mangled>` 符号。**entry point 保持 internal linkage，
  按名字绑定不只是“没用上”，而是根本不可用，registration table 成为唯一的绑定来源。
  否则一个残留的导出符号可以在 table 那一行写错或缺失的情况下让方法照常工作——而这正是本层
  要抓的失败。第 5–7 步拆库时必须保住这条性质；用
  `llvm-nm -D --defined-only <lib>.so | grep -E 'Java_|JNI_OnLoad'` 可以检查；结果应当显示 `JNI_OnLoad`，且没有任何 `Java_` entry point。

这已经完整覆盖 RegisterNatives 最初要解决的迁移风险：漏改或改错 binding 不会再隐藏到某条
冷路径第一次执行时才暴露。

**B. Kernel behavior —— A 中强制通过**

- 使用 deterministic synthetic input 真正调用全部 9 个 array-based native entry；
- 覆盖 float 与 int8 array kernel；
- 按实际职责覆盖 semantic、detection、YUV/quantization、connectivity；
- 能做的地方，对关键输出做 pre/post refactor equivalence 对比。

某个 kernel 和它的 Kotlin fallback **本来就不等价**时，测试钉住 native 的实际取值，而不是钉住
那个对比 —— 因为 native 才是出货路径。G1 发现了一例:connectivity kernel 不做
`KotlinConnectivityStatsExtractor` 的透视宽度缩放，而且根本没拿到所需的那两个 config 值。
对一条正常向远处收窄的走廊，两者在 9 个输出里有 5 个不一致，**包括 `floodReachRatio`** ——
因为洪泛的提前终止判据用的就是同一个 retention。这是既有的产品级分歧，不是重构造成的，
已记录在 `NativeConnectivityKernelTest`，留作单独的行为决策。

**C. Handle integration —— 需要真实模型**

- B 使用实际打包的 float 模型覆盖两条 float handle 热路径：
  native_score 与 native_bbox_nms_float_handle；
- 同时保住 §12.3 的两条性能红线；
- 两条 int8 handle 路径暂时明确记录为 known coverage gap，直到某个 edition 提供合适的
  int8 model fixture。不要仅为了满足本次重构，就向 A 引入第三方 model weights。

如果以后 B 或其他 edition 正式声明/支持 int8 handle execution，那么对应 model-backed
integration coverage 就升级为该 edition 的 release gate。

仅手工跑一次 Guidance session 仍然不够：它可以覆盖当前 float 热路径，但不能证明全部 binding
和 array kernel。

### 12.3 性能红线

runtime/vision/Guidance native 拆分后，在同一目标设备验证：

- sem: postprocessBackend = native_score 且 outputReadTimeMs ≈ 0；
- det: postprocessBackend = native_bbox_nms_float_handle；
- preprocessing cache 仍然命中 same-frame reuse；
- 没有新增完整 semantic tensor 的额外读取。

### 12.4 Session 对比

同一设备、同一路线比较重构前后 trace：

- blocked frames；
- event count/category；
- pipeline average 与 p95；
- backend reporting；
- 异常 startup/unavailability state。

trace compare 仍然只是 coarse integration check，不是 golden frame replay。

### 12.5 结构检查

迁移期间持续验证：

- sailens-guidance 没有 Compose/UI dependency；
- sailens-core 没有逐渐吸收 Android application service；
- 低层 shared module 不依赖 sailens-shell；
- Guidance-only edition 不会被拖入 concrete VLM runtime dependency；
- explicit API compile 能阻止 public surface 无意扩大。

## 13. 本轮 review 已确定的决策

以下项目不再是 open question：

1. **Module 前缀：**使用 sailens-*，不用 lens-*。
2. **Package root：**使用 com.sailens.*，不用 com.sailens.sailens.* 或 com.sailens.lens.*。
3. **UI modules：**原 ux 与 guidance-ui 概念合并进 sailens-shell。
4. **Guidance boundary：**sailens-guidance 与 UI 分离并保留独立 module。
5. **初始规模：**目标 9 library modules + app；sailens-vlm 允许在没有实际价值时延迟物理拆分。
6. **Pipeline 组合：**零 pipeline、仅 Guidance、仅 Describe、两者都有，全部合法。
7. **Host boundary：**Application/MainActivity 留在每个 app；shell 提供可复用 Compose/composition。
8. **Frame contract：**Guidance 消费 stream；Describe 消费带 freshness 上限的 snapshot。
9. **Semantics safety：**声明的 taxonomy id + class count 在 static preflight 中机器校验；只有可信 metadata 真正带 labels 时才额外校验 label order，否则 channel order 仍是人工 release gate。
10. **A 的历史：**sailens-android/main 保持 append-only。

下一步设计工作应该集中在这些固定边界内部的实现细节，而不是继续增加 module 或继续把
pipeline 抽象成更通用的 framework。
