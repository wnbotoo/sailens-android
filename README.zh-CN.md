[English](README.md) | **简体中文**

# Sailens Android

**Sailens** —— *Smart AI LENS*。

面向盲人与低视力用户的 Android 导航辅助。摄像头驱动的感知管线把你面前的场景变成语音和触觉反馈：
哪里能走、路沿在哪、什么东西挡着。

**许可证：Apache-2.0。** 本仓库**不附带任何模型权重**——见[自备模型](#自备模型)。

## 状态

发布前。管线、UI、运行时均已实现，端侧调优仍在进行。目标设备为骁龙 8 Gen 1 或更高级别的硬件。

## 仓库定位

本仓库是 **Sailens Android**：Apache-2.0、model-neutral 的 Android 平台与 reference host，
负责实现可复用的 Sailens capability。它可以 build/install，用于开发和 BYO-model 验证，但
**不是**计划提交应用商店的最终产品。

官方维护的一方 Android 发行版位于独立仓库 `wnbotoo/sailens-app`，以产品名
**Sailens** 面向最终用户发布。本 reference host 使用
`applicationId = "com.sailens.reference"`；官方产品拥有
`applicationId = "com.sailens"`。仓库/产品边界、release 模型以及暂不发布 Maven 的决策见
[`docs/distribution-model.zh-CN.md`](docs/distribution-model.zh-CN.md)。

## 自备模型

本仓库**不带模型权重**。App 在运行时解析两个图：

```text
app/src/main/assets/sem.tflite      # 语义可行走区域分割
app/src/main/assets/det.tflite      # 障碍物检测
```

两个路径都已被 gitignore，所以本地工作区可以放权重而不会进任何提交。

丢进任何满足契约的 TFLite 图，管线就会用它——shape、layout、dtype、量化参数**都在加载时从模型
metadata 自动读取**，所以**换模型通常不需要改代码**。

没有权重时不会报错：preflight 发现没有模型，而这个版本没有承诺任何能力，所以应用会停在
zero-pipeline 屏，并指向这里。官方发行版声明导航为必需，因此会把缺失或不匹配的模型
当作配置失败——进入 fatal 屏，先震动，再把原因说出来。模型通过了 preflight、却在某台设备上起不来，
属于运行时失败，显示可重试的「开始分析失败」。

> **接模型前先读 [`docs/models.zh-CN.md`](docs/models.zh-CN.md)。** 契约不只是 shape：
> **类别通道顺序只按_数量_校验，从不校验语义。** shape 正确但类别顺序不同的模型会毫无报错地运行，
> 然后静默地把世界讲错给一个看不见的人听。**这是安全属性，不是格式洁癖。**

契约摘要：

| | 输入 | 输出 | 类别顺序 |
|---|---|---|---|
| `sem` | `[1, H, W, 3]` | `[1, h, w, 19]` 稠密分数（App 自己 argmax） | Cityscapes trainId |
| `det` | `[1, H, W, 3]` | `[1, 4+classCount, N]` 或 `[1, N, 6]`，单张量 | COCO 80 |

模型权重带有各自的许可证和数据集条款，与本仓库的 Apache-2.0 代码许可**相互独立**。
你带什么模型进来，那部分就由你自己负责核查。

## 架构

九个可复用的 `sailens-*` library 加一个参考 app，按两条产品 pipeline 而不是技术分层组织 ——
见 [docs/architecture.zh-CN.md](docs/architecture.zh-CN.md)。

```text
:sailens-core     共享契约：ImageFrame、geometry、BinaryMask、MlRuntimeInfo、LogService
:sailens-camera   CameraX 采集、FrameSource / FrameSnapshotProvider、预览、相机权限状态
:sailens-runtime  LiteRT session、加速器选择、模型来源、YUV 预处理
:sailens-vision   分割 / 检测 runner、数据集 taxonomy、默认后处理
:sailens-vlm      VLM 引擎契约：帧 + 完整 prompt -> 流式文本
:sailens-output   TTS、音频焦点、读屏检测、触觉原语
:sailens-guidance 导航逻辑：语义、连通性、安全分析、事件、深度、trace
:sailens-describe Describe 产品逻辑：prompt、快照新鲜度、请求调度
:sailens-shell    可复用表现层：SailensRoot()、导航、设计系统、Guidance 与 Describe UI
:app              Koin 装配、Application/MainActivity、运行时 profile、edition spec
```

依赖只向下；Guidance 和 Describe 互不依赖。帧的流向：
`CameraX → ImageFrameAnalyzer → SharedFlow<ImageFrame> → ProcessFrameUseCase → AnalyzeSceneUseCase
→ DecideEventsUseCase → 语音/触觉`。

两个正交的旋钮：

- **Runtime profile**（`SailensRuntimeProfile`）：每个模型跑在哪个加速器上。目前只有一个
  profile `standard`，所有模型都以 GPU 为目标。
- **感知挡位**（`PerceptionProfile`，用户可选）：`BASIC` 只跑 `sem`，`DEFAULT` 跑 `sem + det`。

## 构建

```bash
./gradlew build          # 编译 + 单元测试
./gradlew :app:assembleDebug
```

需要 JDK 17 和 Android SDK（compileSdk 37，minSdk 31）。只构建 arm64-v8a。

高通 NPU 的运行时 `.so` 不进版本控制；需要那条路径时按
[`docs/npu-litert-qnn.zh-CN.md`](docs/npu-litert-qnn.zh-CN.md) 重建。

## 文档

下列每份文档都有英文版（去掉 `.zh-CN` 后缀），可从其页首切换。

| | | |
|---|---|---|
| [`docs/architecture.zh-CN.md`](docs/architecture.zh-CN.md) | [English](docs/architecture.md) | Sailens 模块结构：模块边界、seam、迁移计划与验收 |
| [`docs/distribution-model.zh-CN.md`](docs/distribution-model.zh-CN.md) | [English](docs/distribution-model.md) | 平台与官方发行版：职责、identity、源码消费方式、release/version 策略 |
| [`docs/models.zh-CN.md`](docs/models.zh-CN.md) | [English](docs/models.md) | 模型契约、backend 配置、性能红线 |
| [`docs/perception-profiles.zh-CN.md`](docs/perception-profiles.zh-CN.md) | [English](docs/perception-profiles.md) | 感知挡位、调度、tracker TTL |
| [`docs/npu-litert-qnn.zh-CN.md`](docs/npu-litert-qnn.zh-CN.md) | [English](docs/npu-litert-qnn.md) | 高通 NPU 接线、交付、诊断 |
| [`docs/trace_metrics_guide.zh-CN.md`](docs/trace_metrics_guide.zh-CN.md) | [English](docs/trace_metrics_guide.md) | Trace / replay 指标定义 |
| [`docs/trace_replay_workflow.zh-CN.md`](docs/trace_replay_workflow.zh-CN.md) | [English](docs/trace_replay_workflow.md) | 可观测 → 可回放 → 可评估 |
| [`docs/vlm-asr-assistant-plan.zh-CN.md`](docs/vlm-asr-assistant-plan.zh-CN.md) | [English](docs/vlm-asr-assistant-plan.md) | 规划中的 VLM / ASR 助手路径 |
| [`docs/background-guidance-plan.zh-CN.md`](docs/background-guidance-plan.zh-CN.md) | [English](docs/background-guidance-plan.md) | 规划中的相机前台服务（息屏继续导航） |
| [`docs/guidance-validation-roadmap.zh-CN.md`](docs/guidance-validation-roadmap.zh-CN.md) | [English](docs/guidance-validation-roadmap.md) | 延期的真机提示验证与空间化 earcon 门槛 |
| [`docs/local-navigation-roadmap.zh-CN.md`](docs/local-navigation-roadmap.zh-CN.md) | [English](docs/local-navigation-roadmap.md) | 提案：吸收 Project Guideline 的局部导航里程碑 |
| [`docs/local-navigation-implementation.zh-CN.md`](docs/local-navigation-implementation.zh-CN.md) | [English](docs/local-navigation-implementation.md) | 提案：各里程碑的契约、代码位置与测试 |

`AGENTS.md` 是给编码 agent 的仓库指南（仅英文——它是给工具读的，不是给人读的）。

`LICENSE` 和 `NOTICE` 不做翻译：只有英文原文具有法律效力，非官方译本反而会制造「以哪份为准」的歧义。

## 贡献

欢迎 issue 和 pull request。贡献按 Apache-2.0 授权接受。

由于本仓库不带权重，端到端跑起来需要你先自备 `sem.tflite` 和 `det.tflite`。
单元测试（`./gradlew build`）**不需要任何模型**即可运行。

## 许可证

Apache License 2.0 —— 见 [LICENSE](LICENSE)。
