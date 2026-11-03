[English](detfirst-parameters-2026-10.md) | **简体中文**

# 参数冻结：det 优先的上线组合，开封存验收组之前

这份文档记录封存验收组（S）打开之前要冻结的参数和代码范围。门槛在 [detfirst-thresholds-2026-10.zh-CN.md](detfirst-thresholds-2026-10.zh-CN.md)（第 8 版，§4.7），这里不重复。S 只打开一次（R7、Q14）；这份文档的提交号写进本地分配表 `detfirst-d18\assignment.csv` 的"参数冻结"那一行之前，S 不得打开。

**只用了校准/基线组（C）。** S 没有被任何显示结果的工具打开过。C 用来选过参数，所以 C 上的数字是样本内的。

## 1. 上线组合

`DetFirstConfig.LAUNCH`：

| 字段 | 值 |
|---|---|
| `proximityEscalation` | 开 |
| `speechRank` | 开 |
| `retryInterrupted` | 开 |
| `keepBlockedWithObstacle` | 开 |
| `nearMeters` | 2.0 m |
| `pathComplexOff` | 开 |
| `metricGate.vehicleAheadM` / `vehicleSideM` | 5.0 m / 2.5 m |
| `metricGate.personAheadM` / `personSideM` | 4.0 m / 3.0 m |
| `metricGate.staticM` / `roadsideStaticM` | 2.5 m / 2.0 m |
| `metricGate.metricOnlyRepeatMs` | 6 s（按冷却倍率放大后不超过 8 s） |
| `metricGate.minStableFrames` | 20 |

`minStableFrames` 数的是跟踪匹配次数，不是时钟。C（SM8850，录制时的帧率）是 69546 帧、55.7 分钟，20.8 帧每秒，所以至少约 0.96 s；更慢的设备等得更久。

所有开关默认关，只有 `-PdetFirst=launch` 的 debug 包才打开。关着的时候和开关出现之前的策略逐字节相同（`ScenarioGoldenTest`）。

## 2. 常数

| 常数 | 值 | 位置 |
|---|---|---|
| 升级窗口 | 8 s | `CooldownManager.ESCALATION_WINDOW_MS` |
| 结果窗口 | 8 s | `UtteranceSettlement.DEFAULT_WINDOW_MS` |
| 每个阶段的重给 | 1 次 | `CooldownManager` |
| 同帧 NEAR | 只在同帧优先，不打断同档 | `SpeechRanks.saysNearOfADetectedHazard` |

喇叭上的档位（优先级 × 8 + rank，`SpeechScale`）：

| 句子 | rank |
|---|---|
| 镜头遮挡、过暗 | 7 |
| 故障节奏，除认不出地面之外 | 7 |
| 辅助中断 | 6 |
| 障碍物、前方不通 | 4 |
| 仅公制障碍物（一律 MEDIUM 档） | 3 |
| 其余 | 0 |
| 模式说明（MEDIUM 档，不看 `speechRank`） | 7 |

模式说明：拒绝后 500 ms 再给；第 k 次被打断后等 min(2^k, 8) s；没有次数上限；读屏接收一次就算告知。

## 3. 定义

- **静止**（C7）：一帧的决策在静止时 ⇔ 录下的 `stationary` 为真。静止时每分钟的提示 = 静止帧上决策、之后完整说完的条数 ÷ 静止的分钟数。
- **C4 的"从没进入 2.0 m"**：这条轨迹一生中没有一个新鲜点估计 ≤ 2.0 m（两臂共用的 1.15 m 样本）。
- **C8 的抽样**：不超过 60 条全标；超过就取种子 `20261103` 洗牌后的前 60 条。列表是 `detfirst-c8-list.csv`。
- **P_live、严重损失、`heard_ahead`、未建模**：见门槛文档 §4.7。

## 4. 时长

已录时长：C 上量到的。C 自己用其余各段的合并表（留一法），S 用冻结的 C 表（`SpeakerDurations.FROZEN_C`）。录制里没有的新文案（ZH-CN，语速 1.30，真机 D1.7 工具测量）：

| key | ms |
|---|---|
| `CAMERA_RESTORED` | 1450 |
| `LIGHT_RESTORED` | 1437 |
| `GROUND_RECOGNIZED` | 2649 |
| `MODE_OBSTACLES_AND_BLOCKED` | 2695 |

## 5. 闭环的时间语义

误差为零的运行决定验收，它只有一个分支，每句按上面的时长。第 e 条的 4 条固定误差轨迹：每句一个固定误差，由句子 id 和轨迹号确定，在 ±ε 以内，两臂共用；ε = 150 ms，录制里没有的文案翻倍。

只报告的联合推演：同一帧的调用看到同一个占用状态和过期时间，同一毫秒只比较一次；`heldAfterDone` 的两支；`frameSkewMs = 50`；上限 100 万支、5 亿分支帧。

X3、X5 和越过 δ 的帧不恢复：之后的阶段算未知。C 上它们都是 0。

## 6. C 上的结果

14 段，55.7 分钟，|P| = 609。逐项数字在门槛文档 §4.7；用 `-PreplayLabels` 跑，三个机高的判定**全部通过**。C 的路径走一遍 S 用的冻结时长表（1.15 m），也全部通过，变化最大的是 B1：34/57，留一法是 35/57。

每个开关单独跑和去掉单个开关的诊断结果只作诊断，不是门槛。C6 的三类和原口径计数见门槛文档 §4.4 末尾。

## 7. 真机（CPH2747，SM8850；最终构建）

用户本人跑，两个包，不含录像和地点。

| 项 | 结果 |
|---|---|
| 接近行人、墙前有人、语音关时的震动、模式说明 5 次（D3） | 通过 |
| 被打断后重给 | 用户报整组通过，没有确认实际凑出这个场景，还要补一次确认 |
| TalkBack 开：不崩溃、不刷屏 | 通过 |
| 开关全关的普通包：接近行人、墙前有人 | 和今天的行为一致 |
| PR-D5 B1、B2 | 通过 |
| PR-D5 A1–A3 | 都有提示，但夹杂"前方不通"或障碍物提示，快速捂放时恢复提示不准。用户判断整体问题不大；原因只读了代码，没看日志，是推测。 |
| PR-D5 C（可选） | 没跑 |

## 8. 打开 S 的命令

分配表两行都填上之后才能跑。前两条各跑一次，第三条三个机高各一次；把 `<sealed>` 换成 `sailens-captures\detfirst-d18\sealed`：

```
gradlew :sailens-guidance:testDebugUnitTest --tests "*PromptPolicyBaselineTest*" -PreplayCapture=<sealed> -PunsealCapture -Pm3aPhoneHeight=1.15
gradlew :sailens-guidance:testDebugUnitTest --tests "*DetFirstRehearsalTest*" -PreplayCapture=<sealed> -PunsealCapture --rerun
gradlew :sailens-guidance:testDebugUnitTest --tests "*DetFirstPairedRehearsalTest*" -PreplayCapture=<sealed> -PunsealCapture [-Pm3aPhoneHeight=1.05|1.25] -PreplaySingleSwitches=launch --rerun
```

- 第一条是开环的模型校准（第 3 节里 A12 以外的各项），第二条是 A12（录制机高 1.2 m），都用冻结的 C 时长表。
- 三个入口在算任何指标之前，都先核对读到的正好是完整的 S：13 段都在，没有重复，没有别的录制，每段都完整、回放没有问题。不满足就不算，什么都不出。
- 第三条写出 `detfirst-paired.txt`、`detfirst-label-list.csv`、`detfirst-label-frames.csv`、`detfirst-c8-list.csv`。用户看片段标注之后，用同一个提交带 `-PreplayLabels=<导出的文件>` 再跑一次，a1、a2 才有判定。这次重跑属于同一次验收，不算再打开 S。
- 标注覆盖整张清单，每条只能是“需要 / 不需要 / 误检 / 看不清”四种之一；有空值或别的值，a1、a2 不通过。
- 分母不足 20 的比例按 R5 记不可判定，不通过。
- 三个机高的报告、清单、标注文件和 C8 的结果分别留档。

**结果怎么用：**

- 实质验收或常设检查有一项失败，就不翻开关。不能改代码或门槛后，再用这批 S 取得通过。
- B8 第 2 项（被打断后重给）不是打开 S 的前置条件，但仍是翻开关前的硬门槛。如果它之后失败，需要改冻结的代码，这批 S 就不能为改后的版本背书。

## 9. 冻结范围

原来的 6 个文件（门槛文档第 3.4 节）仍和 `5418a35` 逐字节相同（`FrozenEvaluationFilesTest`）。

**另外冻结的是写进分配表的那个提交本身**：仓库在这个提交上的全部内容，包括生产代码、评估代码、验收入口、构建脚本和 Gradle 接线，不另列文件清单。打开 S 时就用这个提交构建，工作区不能有改动。改任何一处都要重新做 C 预演；S 打开之后就不能改。

## 10. 已知局限

门槛文档 §4.7 的代价和残余风险都适用。已知的评估问题：`lossesIn` 里的 `sameInputs` 豁免沿用旧的截止时刻，换配置时可能漏掉后来才发生的分歧；上线组合的模式说明让两臂始终不同，所以走不到它。
