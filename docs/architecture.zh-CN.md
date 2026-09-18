[English](architecture.md) | **简体中文**

# Sailens 架构：lens 框架

> 状态：**方案，评审中。**本文所述内容均未实现。它将取代现有的按层划分的模块结构，以及基于 fork 的 A/B 双仓关系。

## 1. 概要

Sailens 重新定位为 **Smart AI Lens**：采集（今天是摄像头，以后加语音）→ pipeline → AI 结果 → 输出。面向盲人和低视力用户的出行辅助是它的第一个应用，而不是它本身。

现有两条 pipeline，一个应用可以只包含其中一条，也可以两条都有：

- **引导（guidance）**——连续的。每帧跑语义分割（+ 可选的检测），回答"我会不会撞上什么"。
- **描述（describe）**——按需的。视觉语言模型，在用户发问时回答"我面前是什么"。

代码围绕这两条 pipeline 以及它们共享的基础设施重新组织。`sailens-yolo` 仓库不再是本仓库的 fork，而是一个通过 Gradle composite build **依赖**本仓库的薄应用。

## 2. 目标与非目标

**目标**

- 用普通的构建依赖，取代两个仓库之间的 fork + merge。
- 每条 pipeline 都能独立使用；共享基础设施不属于任何一条。
- 许可证边界 = 依赖图。
- 保住今天已有的全部性能特性（见 §6.3、§6.7）。
- 每一步迁移之后，应用都能正常运行。

**非目标**——明确不在本次重构范围内：

- 通用的 pipeline 图、插件发现，或任何可配置的 DAG。引导和描述的形状是固定的，变化的只是各阶段的实现，而接口 + DI 已经能处理这一点。
- 发布到 Maven，或任何形式的带版本号的产物。
- 出行辅助以外的应用。不为假想中的应用设计任何抽象。
- 在构建期排除某条 pipeline。pipeline 在**运行时**可插拔（§6.1）。
- 语音 / ASR 输入。结构上为它留了位置（§6.10），但什么都不做。
- 两条 pipeline 之间的 GPU 仲裁策略。现在还没有 VLM runtime（§6.7）。
- 行为变更。除了明确标出的第 10 步（§11），每一步都保持行为不变。

## 3. 代码现状

六个模块，按 clean architecture 的**层**划分：

```text
:domain         (无依赖)
:data           → :domain
:camera         → :domain, :ux
:presentation   → :camera, :domain, :ux
:ux             (无依赖)
:app            → 全部
```

拿两条 pipeline 来衡量，现有分层已经*大致*把导航逻辑和 lens 基础设施分开了——但边界上的东西放错了边：

| 模块 | 规模 | 主要内容 |
|---|---|---|
| `:domain` | 约 7,000 行 | 约七成是导航逻辑（连通性、事件、冷却、道路安全、trace） |
| `:data` | 约 6,100 行 + 2,185 行 C++ | 约四分之三是通用的 lens 运行时（LiteRT、加速器、预处理、runner） |

看起来通用、实际上被导航塑形的东西：

| 项 | 为什么不是通用的 |
|---|---|
| `ClassMapper` | 方法是 `isPassable`、`isRoad`、`isTrafficLight`、`toGroundType`、`toObstacleCategory` |
| `FrameTrace` | 字段是 `isBlocked`、`navigationPassableRatio`、`blockageConfidence`…… |
| `ObstacleDetection` | runner 直接输出 `category: ObstacleCategory`，这是导航上的分类 |
| `SegmentationOutput.analysisStats` | sem runner 的 native pass 里已经算好了可通行 mask、道路占比、地面类型分布 |
| `libsailens_ml.so` | 一个文件里混着 YUV 预处理（通用）和连通性统计（导航） |
| `:camera` | 为了四个帧类型依赖 `:domain`，于是复用相机采集就要把整个导航核心拖进来 |

还有一个机械性的隐患：全部 13 个 native 函数（分属 5 个 Kotlin 类）都是**静态名字绑定**（`Java_com_sailens_data_source_ml_*`），`RegisterNatives` 一次都没用。挪动其中任何一个类的包名，失败都发生在**运行时**，而不是编译期。

迁移基线：**171 个单元测试**（`:domain` 129、`:presentation` 17、`:data` 14、`:app` 9、`:camera` 1、`:ux` 1）。

## 4. 目标结构

### 4.1 模块

| 模块 | 职责 | 来源 |
|---|---|---|
| `lens-core` | 帧类型（`ImageFrame`、YUV 平面）、几何、`BinaryMask`、`MlRuntimeInfo`、`LogService` + `FileLogService` | `:domain` model/util，`:data` service |
| `lens-camera` | CameraX 采集 → `ImageFrame` 流、预览 composable、权限流程 | `:camera` |
| `lens-runtime` | LiteRT 会话、加速器选择、模型来源、metadata 读取、YUV→tensor native 预处理、共享预处理缓存、硬件画像检测 | `:data` source/ml + session，`:app` 的 `DeviceHardwareProfileProvider` |
| `lens-vision` | 分割 runner（→ 类别图）、检测 runner（→ 带类别 id 的框）、数据集分类法（标签表）、默认后处理器 | `:data` semantic + obstacle，mapper 里的标签表 |
| `lens-vlm` | VLM 引擎 seam：帧 + prompt → 流式文本 | `:data` source/ml/vlm |
| `lens-output` | TTS 引擎、音频焦点、读屏检测、震动原语、语音路由、子句缓冲 | `:presentation` device/* |
| `guidance` | 一切导航相关：`NavigationSemantics`、融合的 sem kernel、连通性、道路安全、事件、冷却、跟踪、深度、传感器、trace/replay | `:domain` 和 `:data` 的其余部分 |
| `guidance-ui` | 实时界面、引导 ViewModel、触觉词汇、事件文案、叠加层、引导相关的设置分区、debug trace 界面 | `:presentation` |
| `describe` | 场景描述 pipeline：prompt、use case、controller。在有 VLM runtime 之前**没有 UI** | `DescribeSceneUseCase`、`SceneAnalysisViewModel` 里描述那一半 |
| `ux` | 设计系统，外加关于 / 开源许可界面（它们是通用列表） | `:ux`，`:presentation` about/* |
| `shell` | 以 library 形式存在的整个应用：Activity、导航、设置界面组装、DI 聚合、运行时画像 | `:app`（application 模块本身除外），`:presentation` navigation |
| `app` | A 的参考应用：自带模型（BYO），零权重。只有身份和配置 | `:app` |

### 4.2 依赖

```text
app / B / C ──► shell
shell       ──► guidance-ui, describe, lens-camera, lens-output, ux
guidance-ui ──► guidance, lens-output, lens-camera, ux
guidance    ──► lens-vision, lens-runtime, lens-core
describe    ──► lens-vlm, lens-output, lens-core
lens-vision ──► lens-runtime, lens-core
lens-vlm    ──► lens-runtime, lens-core
lens-camera ──► lens-core, ux
lens-output ──► lens-core
lens-runtime──► lens-core
lens-core, ux  (无 project 依赖)
```

规则：

1. 只向下依赖。
2. **两条 pipeline 之间没有依赖边。**`guidance` 看不见 `describe`，反之亦然。它们需要协调的一切（语音优先级、GPU）都放在两者之下。
3. 任何 `lens-*` 模块都不依赖 `guidance`、`describe` 或 `shell`。
4. `describe` 不依赖 `lens-camera`：它从接线方拿一个 `Flow<ImageFrame>`，任何帧源都能用。

### 4.3 包名

`com.sailens.lens.{core,camera,runtime,vision,vlm,output}`、`com.sailens.guidance`、`com.sailens.guidance.ui`、`com.sailens.describe`、`com.sailens.ux`、`com.sailens.shell`。模块是扁平的顶层目录（`lens-core/`……），不用嵌套的 Gradle 路径。

library 模块开启 Kotlin 的 `explicitApi()`。今天所有东西默认都是 public；一旦 B 依赖这些模块，每个 public 符号都是 B 可能因之出错的地方。

## 5. A、B、C 分别变成什么

**A（`sailens-android`，Apache-2.0）**——全部 library 模块，加上 `app` 这个 BYO 参考应用。仍然零权重。BYO 权重从 `data/src/main/assets/` 挪到 `app/src/main/assets/`（仍被 git 忽略）；asset 是整个 APK 共享的，所以 runner 找模型的方式不变。

**B（`sailens-yolo`，AGPL-3.0）**——一个建在 `shell` 之上的 application 模块。它没有任何 YOLO 专属代码，因为 YOLO 本来就不需要：runner 由模型 metadata 驱动，输出布局是通用的。它唯一的 Kotlin 是自己的 `Application`，负责把 `shell` 自己读不到的东西交给它——来自 B 自己 `BuildConfig` 的身份信息，以及绑定哪个 `NavigationSemantics`。

```text
sailens-yolo/
├── sailens/                 git submodule → sailens-android，钉在具体 commit
├── settings.gradle.kts      includeBuild("sailens")
├── app/
│   ├── build.gradle.kts     applicationId com.sailens.yolo、APP_LICENSE、APP_SOURCE_URL → shell
│   └── src/
│       ├── main/kotlin/     YoloApplication：AppInfo + OSS 列表 + 语义选择 → shell
│       ├── main/assets/     sem.tflite、det.tflite（Git LFS）
│       ├── main/res/        app_name
│       └── test/            TfliteModelMetadataReaderTest（契约守卫）
├── LICENSE  NOTICE  README*  YOLO_EDITION_NOTICE*  docs/yolo-models*
```

B 需要时*可以*拥有 YOLO 专属代码——比如一个新的解码头。和今天的区别在于它能这么做了，因为它是一个普通的依赖方项目，而不是一个必须保持代码完全一致的 fork。

**C（假想的）**——同样的形状：权重、一个 `VlmRuntimeFactory` 的接线，以及当它的检测器用了不同分类法时绑定的 `NavigationSemantics`。LiteRT-LM runtime 的实现本身属于 A 的 `lens-vlm`（Apache，依据 Gemma 4 E2B 的选型结论）；C 只提供模型。

## 6. 设计决策

### 6.1 pipeline 在运行时可插拔

每个建在 `shell` 上的应用都把两条 pipeline 编译进去。每条 pipeline 各自报告能不能跑：

| pipeline | 可用条件 |
|---|---|
| guidance | 能解析到 sem 模型，**并且**绑定了 `NavigationSemantics`（det 可选，和今天一样） |
| describe | `VlmRuntimeFactory` 报告有 runtime 和模型 |

不可用的 pipeline **不提供入口**——隐藏，而不是置灰。一个永远不会响应的控件，对读屏用户只是又一个无效的焦点停留；而"按了没反应"和"前方什么都没有"在他那里无法区分。

一个 shell 覆盖所有组合：A 没有权重（两条都不可用——应用明说这一点，并指向 `docs/models.md`）、B（只有引导）、C（两条都有）、纯 VLM 应用（只有描述）。

这也让此前 defer 的"sem 可选"重构不再需要。引导仍然**要求** sem；变成可选的是引导这条 pipeline 本身。

描述保持无界面：按之前的决定，在 VLM runtime 出现之前不提供任何 UI 入口。

### 6.2 `ClassMapper` 拆成 `Taxonomy` 和 `NavigationSemantics`

`ClassMapper` 混着一个数据集事实和一个导航判断：

- **`Taxonomy`**（`lens-vision`）——类别数和按模型输出顺序排列的标签名。关于数据集的事实。`CityscapesTaxonomy`、`CocoTaxonomy`。
- **`NavigationSemantics`**（`guidance`）——逐类别的查找表：可通行、障碍物、道路、交通灯、地面类型、障碍物类别。关于行走的判断。`CityscapesNavigationSemantics`、`CocoNavigationSemantics`。

用查找表而不是逐次调用的方法，是因为 native kernel 要的就是表：今天的 `SemanticClassLookup` 正好就是 `classCount` + `passable` + `obstacle` + `road` + `trafficLight` + `groundType` 几个数组。

Cityscapes 和 COCO 的语义留在 A。Cityscapes 是数据集分类法，不是 YOLO 的；任何用 Cityscapes 训练的 sem 模型都需要它，包括 A 里的 BYO 模型（`docs/models.md` 已经把 BYO 契约定为 Cityscapes trainId + COCO 80）。B 是选用它们，而不是持有它们——在它的 `Application` 里一行。

**语义永远显式绑定，不存在中性兜底。缺少语义是启动错误。**中性映射不是"安全"，而是"最不安全"。所有类别都不可通行时 mask 为空，`ConnectivityChecker` 算出：

```text
verticalReachRatio = 0, floodReachRatio = 0, widthRetentionP25 = 0
score = 0.35 + 0.35 + 0.30 = 1.0    →  blockageConfidence 1.0  →  SEVERE  →  CRITICAL
```

它越过 `MIN_HARD_BLOCKED_CONFIDENCE`（0.75）和全部三道 hard-blocked 门槛。应用会在第一帧就播出一条最高优先级的"前方不通"，然后一直锁在那里。

### 6.3 分割保持单次 native pass

sem 有文档化的性能红线：`postprocessBackend = native_score` 且 `outputReadTimeMs ≈ 0`。它成立的原因是：一次 native pass **通过 handle** 读取 LiteRT 的输出 buffer（零拷贝），同时算出 argmax *和*导航统计。把"通用 runner"和"导航统计"天真地拆开，就意味着对一个 640×640×19 的 tensor 扫两遍。

所以 runner 接收一个注入的后处理器：

```text
lens-vision   SegmentationRunner<R>(…, postprocessor: SemanticPostprocessor<R>)
lens-vision   ArgmaxPostprocessor : SemanticPostprocessor<SegmentationMask>          (默认)
guidance      NavigationScorePostprocessor : SemanticPostprocessor<SegmentationAnalysisStats>
```

`NavigationScorePostprocessor` 就是今天的 `NativeSemanticScorePostprocessor` 挪了位置。这个 seam 已经存在——那个后处理器今天就是通过 DI 注入的。一次 pass，和今天一样。

### 6.4 检测结果携带类别 id；类别归引导

`ObstacleDetection` 去掉 `category`。检测 runner 输出类别 id、标签、置信度和框；`guidance` 通过 `NavigationSemantics` 把类别 id 映射成 `ObstacleCategory`。NMS、letterbox 几何和布局解码留在 `lens-vision`。det 的红线（`postprocessBackend = native_bbox_nms_float_handle`）不受影响：类别映射只是对每个保留下来的框查一次表。

### 6.5 一个语音路由，两条 pipeline 共用

两条 pipeline 同时存在时，它们通过同一个通道说话，共享规则放在两者之下的 `lens-output`：

1. **通道互斥**——自带 TTS *或*读屏，只选一次，和今天一样。
2. **优先级**——引导告警会抢占描述正在说的任何内容。描述永远不抢占引导。今天这是隐式的（`speak` 冲掉队列，`speakSystemNotice` 排队）；之后变成路由上显式的 `ALERT` / `INFORMATION` 优先级。
3. **读屏播报**——路由把它们作为一个 flow 暴露出来；`shell` 在唯一一处收集并执行播报（这需要一个 `View`）。今天是 `LiveAnalysisScreen` 只为引导做这件事。只有一个收集点，也意味着已被废弃的 `announceForAccessibility` 将来只有一处要替换。

触觉词汇按关注点拆分。唯一一个关于输出通道本身的符号——`SPEECH_UNAVAILABLE`——归 `lens-output`，因为纯描述应用同样需要"语音已失效"。其余都是关于引导的，留在 `guidance-ui`：方位符号、`BLOCKED`、`NOTICE`、`SENSOR_FAILURE`（相机看不见——由引导的画面质量分析检出）和 `INTERRUPTED`（连续保护丢失了——描述没有连续保护可丢）。Phase B 盲测仍然把它们的并集当成一套来验证：拆分归属不等于拆分混淆矩阵，`SPEECH_UNAVAILABLE` 与 `SENSOR_FAILURE` 之间文档化的区分仍然必须成立。

### 6.6 prompt 属于描述，不属于引擎

`VlmModelConfig` 里带着 `"你是盲人出行助手…"`。这是应用层文本。`lens-vlm` 接收完整的 prompt；`describe` 持有系统提示词并负责拼装。`SceneDescriber` 变成通用的 `VisionLanguageModel`（帧 + prompt → 分片的 `Flow`）；流式契约、`trySendBlocking`、`timeToFirstTokenMs` 和 `SpeechClauseBuffer` 都不变。

### 6.7 `lens-runtime` 持有两条 pipeline 会争抢的东西

- **预处理缓存。**sem 和 det 通过 `InputPreprocessCache` 共用同一帧的 YUV→tensor 转换。它保持为 `lens-runtime` 持有的单一共享实例，绝不每个 runner 各一份。
- **GPU 仲裁（未来）。**两条 pipeline 同时存在时，VLM 和 YOLO 会抢 GPU。`lens-runtime` 就是这个策略将来所在的位置。现在不做——还没有 VLM runtime——但安全约束先记在这里：如果描述运行期间引导暂停，这个暂停就是保护的丢失，必须通过非视觉通道告知。

### 6.8 JNI：`RegisterNatives`，三个 native 库

每个库在 `JNI_OnLoad` 里注册自己的 native 方法：

| 库 | 模块 | 函数 |
|---|---|---|
| `libsailens_runtime.so` | `lens-runtime` | YUV 预处理（3） |
| `libsailens_vision.so` | `lens-vision` | 障碍物后处理（4）、语义 argmax（1） |
| `libsailens_guidance.so` | `guidance` | 融合的导航打分 kernel（4）、连通性统计（1） |

为什么 `RegisterNatives` 是第 1 步：改成它之后，漏改的名字会在加载库时失败，也就是第一次启动就暴露。静态名字则是在**第一次调用**时才失败——而其中有些函数所在的路径几乎从不执行（int8 后处理器；实际打包的模型是 float16）。那里漏改一个名字，就会随版本发出去。keep 规则仍然需要：`FindClass` 要按类名查找。

为什么按模块拆成多个库而不是一个：用了 `RegisterNatives` 之后，`JNI_OnLoad` 必须找得到它注册的每一个类。放在 `lens-runtime` 里的单一共享库就得知道引导的类名——依赖方向反了。

零拷贝的 handle 路径在运行时用 `dlsym` 解析 LiteRT 的 buffer lock/unlock（今天的 `LiteRtApi` helper）；构建期没有任何东西链接 LiteRT。这个 helper 变成 `lens-runtime` 里的一个小头文件，由另外两个模块的 CMake 按路径 include。同一个仓库，所以不需要 prefab。

### 6.9 配置

每个模块仍然只依赖自己的配置类型——这已经是现有的模式（`ProfileBindingsModule` 把 `SailensRuntimeProfile` 分发成各层的配置）。画像随之拆分：硬件检测 → `lens-runtime`；各模块的默认值 → 各自模块；档位选择与组装 → `shell`。应用把身份信息（`AppInfo`、OSS 列表）交给 `shell`；`shell` 从不读取某个应用的 `BuildConfig`。

### 6.10 在出现第二个使用方之前留在引导里的东西

trace/replay、深度、设备传感器、`Stabilizer`、`FrameQualityAnalyzer`：只有引导在用。其中有些在形式上是通用的，但现在把它们往下挪，等于在猜测一个还不存在的使用方需要什么。等它出现再挪。

语音输入将是 `lens-camera` 旁边的一个新的源模块（`lens-audio`），由 `describe` 消费，用于语音提问。加它不需要改动这套结构里的任何东西。

## 7. 开发配置：composite build，不上 Maven

B 以 git submodule 的形式钉住 A，并把它作为 Gradle composite build 引入：

```kotlin
// sailens-yolo/settings.gradle.kts
includeBuild("sailens")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("sailens/gradle/libs.versions.toml")) }
    }
}
```

- **不发布，没有版本号。**B 的 `implementation("com.sailens:shell")` 通过依赖替换解析到 A 的 project；A 的模块只需要设 `group = "com.sailens"`，坐标就能对上。
- **只有一份 version catalog。**B 读 A 的那份；composite build 需要一致的 AGP 和 Kotlin 版本。
- **日常：**在 A 自己的 checkout 里开发；在 B 里 `git submodule update --remote`，提交新的指针。需要紧密迭代时，直接在 B 的 submodule 目录里改——它就是一个完整的 A clone。
- **AGPL 对应源码自然满足：**B 的一个 release tag，加上它钉住的 submodule commit，就是那个构建的确切源码。

Android library、资源、asset 和 CMake 在 composite build 里都是标准用法，但第 3 步（§11）会在任何大规模挪动依赖它之前，先在真实项目上验证它。

## 8. 变更后的许可证

- A：Apache-2.0，全部模块，零权重——不变。
- B：AGPL-3.0 组合作品 = A 的模块（Apache，钉住版本）+ YOLO26 权重。Apache → AGPL 的组合是允许的；A 不受影响。
- 许可证边界现在就是依赖图。两边不共享 git 历史，所以不再需要任何东西去防止 AGPL 历史进入 A。
- **不变且仍然悬而未决：**Cityscapes 的非商用条款是否允许公开分发 B 的 sem 权重。本次重构不涉及这个问题。

## 9. 会被删掉的东西

B 里：`.gitattributes` 的 `merge=ours`、`merge.ours.driver` 设置、`upstream` remote 及其 `DISABLED` 的 push URL、同步流程、AGENTS.md 前缀约定，以及"零代码改动"这条不变量。B 重开历史（两个仓库都可以强推）。

A 里：`data/src/main/assets` 的 BYO 约定（→ `app/src/main/assets`）、`mlModelBinding`。

## 10. 待办的发布事项落在哪里

| 事项 | 归属 |
|---|---|
| 摘掉用不到的 `dataSync` 前台服务（来自 `litert → ai-delivery → work-runtime`） | `lens-runtime` 的 manifest，这样每个应用都继承——需验证 library 级的 `tools:node="remove"` 能传递 |
| 隐私政策 | 各应用 |
| 安全免责 / 首次启动 | `shell`，文案按 pipeline 区分 |
| release 签名 | 各应用 |
| Cityscapes 非商用的结论 | 不变，B |

## 11. 迁移计划

每一步结束时：A 能构建，B 依赖它也能构建，测试全绿。涉及 native 代码的步骤还需要真机启动并跑一次引导会话。

| # | 步骤 | 真机检查 |
|---|---|---|
| 1 | JNI → `RegisterNatives`，暂不挪包 | 是 |
| 2 | 从 `:app` 抽出 `shell`；`app` 只剩身份 + 配置 | — |
| 3 | **B 变成建在 composite build 上的薄应用**（重开历史）。尽早验证 §7；从此 B 是使用方，不再是 fork。同一步里撤掉两份 `AGENTS.md` 中"`main` 是 append-only"的护栏：它存在的唯一理由是保护 fork 的 merge-base，而这一步把它去掉了 | — |
| 4 | 抽出 `lens-core`；`lens-camera` 不再依赖导航核心 | — |
| 5 | 抽出 `lens-runtime`；native 拆分第 1 部分 | 是 |
| 6 | 抽出 `lens-vision`；拆分 `Taxonomy` / `NavigationSemantics`；后处理器 seam；native 拆分第 2 部分 | 是 |
| 7 | 其余部分改名为 `guidance` / `guidance-ui`；native 拆分第 3 部分 | 是 |
| 8 | 抽出 `lens-output`；带显式优先级的语音路由 | 是 |
| 9 | 抽出 `lens-vlm` 和 `describe`；prompt 挪到 `describe` | — |
| 10 | **行为变更：**pipeline 可用性、隐藏不可用的 pipeline、A 的零模型状态 | 是 |
| 11 | 文档：README 架构、`AGENTS.md`、`models.md` 的 asset 路径、B 的 README 和 `yolo-models.md` | — |

第 3 步刻意排得这么靠前。它立刻去掉 fork，并在十二个模块依赖它之前先把 composite build 验证掉。B 只接触 `shell` 的入口（身份 + 选择），所以第 4–9 步的内部挪动影响不到它；第 10 步可能会改变这个入口接收的内容。

## 12. 验证

- **测试要数数量，不只是跑通。**基线 171。测试跟着代码一起挪；每一步之后总数不得下降，除非有明确的理由。
- **性能红线**（来自 `models.md`），在第 5–7 步之后上真机检查：`sem: postprocessBackend = native_score, outputReadTimeMs ≈ 0` 和 `det: postprocessBackend = native_bbox_nms_float_handle`。
- **同一台设备、同一条路线的前后会话 trace 对比**：blocked 帧数、事件数、pipeline 平均耗时和 p95。trace 工具比较的是录下来的会话，不会重放帧，所以这是粗粒度检查，不是逐帧的黄金输出测试。
- native 代码没有 JVM 测试覆盖。每个涉及 native 的步骤都是真机步骤。

## 13. 待定问题

1. **命名。**`lens-*`、`guidance`、`describe`、`shell`——以及 `com.sailens.lens.*` 这个包根。
2. **模块少一点？**十二个取代六个。如果需要，两个代价最低的合并：`guidance` + `guidance-ui`（失去一条纯逻辑边界，别无其他代价），以及 `lens-vlm` + `describe`（引擎 / prompt 的拆分降级为包级别）。
