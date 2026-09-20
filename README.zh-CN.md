[English](README.md) | **简体中文**

# Sailens

**Sailens** —— *Smart AI LENS*。

面向盲人与低视力用户的 Android 导航辅助。摄像头驱动的感知管线把你面前的场景变成语音和触觉反馈：
哪里能走、路沿在哪、什么东西挡着。

**许可证：Apache-2.0。** 本仓库**不附带任何模型权重**——见[自备模型](#自备模型)。

## 状态

发布前。管线、UI、运行时均已实现，端侧调优仍在进行。目标设备为骁龙 8 Gen 1 或更高级别的硬件。

## 自备模型

本仓库**不带模型权重**。App 在运行时解析两个图：

```text
data/src/main/assets/sem.tflite     # 语义可行走区域分割
data/src/main/assets/det.tflite     # 障碍物检测
```

两个路径都已被 gitignore，所以本地工作区可以放权重而不会进任何提交。

丢进任何满足契约的 TFLite 图，管线就会用它——shape、layout、dtype、量化参数**都在加载时从模型
metadata 自动读取**，所以**换模型通常不需要改代码**。

没有权重时，模型加载在 init 阶段失败，并作为「开始分析失败」呈现给用户。

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

四个 Gradle 模块 + 两个支撑模块的 clean architecture，用 Koin 装配：

```text
:domain        感知 / 分析 / 决策用例 —— 不含任何 Android API
:data          LiteRT 推理、深度、日志、trace
:presentation  UI 状态、overlay 渲染、TTS、触觉
:app           Koin 装配、根 Compose、运行时 profile
:camera        CameraX 采集与帧流
:ux            设计系统
```

外层模块向内依赖 `:domain` 接口。帧的流向：
`CameraX → ImageFrameAnalyzer → SharedFlow<ImageFrame> → ProcessFrameUseCase → AnalyzeSceneUseCase
→ DecideEventsUseCase → 语音/触觉`。

两个正交的旋钮：

- **运行时档位**（`SailensRuntimeProfile`）：每个模型跑在哪个加速器上。视觉模型跑 GPU；
  NPU 预留给未来的 VLM 路径。
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
| [`docs/architecture.zh-CN.md`](docs/architecture.zh-CN.md) | [English](docs/architecture.md) | 已完成本轮评审的 Sailens 模块化重构方案（尚未实现） |
| [`docs/models.zh-CN.md`](docs/models.zh-CN.md) | [English](docs/models.md) | 模型契约、backend 配置、性能红线 |
| [`docs/perception-profiles.zh-CN.md`](docs/perception-profiles.zh-CN.md) | [English](docs/perception-profiles.md) | 感知挡位、调度、tracker TTL |
| [`docs/npu-litert-qnn.zh-CN.md`](docs/npu-litert-qnn.zh-CN.md) | [English](docs/npu-litert-qnn.md) | 高通 NPU 接线、交付、诊断 |
| [`docs/trace_metrics_guide.zh-CN.md`](docs/trace_metrics_guide.zh-CN.md) | [English](docs/trace_metrics_guide.md) | Trace / replay 指标定义 |
| [`docs/trace_replay_workflow.zh-CN.md`](docs/trace_replay_workflow.zh-CN.md) | [English](docs/trace_replay_workflow.md) | 可观测 → 可回放 → 可评估 |
| [`docs/vlm-asr-assistant-plan.zh-CN.md`](docs/vlm-asr-assistant-plan.zh-CN.md) | [English](docs/vlm-asr-assistant-plan.md) | 规划中的 VLM / ASR 助手路径 |
| [`docs/background-guidance-plan.zh-CN.md`](docs/background-guidance-plan.zh-CN.md) | [English](docs/background-guidance-plan.md) | 规划中的相机前台服务（息屏继续导航） |
| [`docs/guidance-validation-roadmap.zh-CN.md`](docs/guidance-validation-roadmap.zh-CN.md) | [English](docs/guidance-validation-roadmap.md) | 延期的真机提示验证与空间化 earcon 门槛 |

`AGENTS.md` 是给编码 agent 的仓库指南（仅英文——它是给工具读的，不是给人读的）。

`LICENSE` 和 `NOTICE` 不做翻译：只有英文原文具有法律效力，非官方译本反而会制造「以哪份为准」的歧义。

## 贡献

欢迎 issue 和 pull request。贡献按 Apache-2.0 授权接受。

由于本仓库不带权重，端到端跑起来需要你先自备 `sem.tflite` 和 `det.tflite`。
单元测试（`./gradlew build`）**不需要任何模型**即可运行。

## 许可证

Apache License 2.0 —— 见 [LICENSE](LICENSE)。
