[English](detfirst-d18-recording-plan-2026-10.md) | **简体中文**

# 录制计划：det 优先 D1.8（校准/基线组与封存验收组）

det 优先方案 D1.8 要求带语音生命周期重新录一批数据，**在录制之前**按完整会话分成两组：

- **校准/基线组（C）**：用来校准 `SpeakerModel`（D1.4），并给出新口径的基线（完整说完、按完成时刻算的提前时间）。
- **封存验收组（S）**：留给 D2–D4 的验收和 M5 不倒退检查。在所有参数和门槛冻结之前**不看**。

本计划沿用[现场录制手册](../field-recording-manual.zh-CN.md)的安全、隐私和持机规则，这里只写 D1.8 不同的地方。
录制数据含人脸和地点，只留在手机和你自己的电脑上，不进仓库、不上传。

## 1. 原则

1. **分组在录之前定好，按完整会话分。** 每个计划好的录制槽位在第一次出门前就写好属于 C 还是 S，之后不改。
   不能看了录得好不好再决定放哪组。
2. **两组覆盖同样的场景层。** 每个场景层内，C 和 S 槽位一样多、交替排列。这样不看 S，用 C 的数量就能估计 S 有多少样本。
3. **样本量按独立的危险阶段算，不按会话数算。** 先录试点验证采集链路，再按 C 组统计出的缺口补录。
   补录时 C 和 S 同时各加一样多的槽位。
4. **封存组只做完整性检查。** 可以跑完整性检查（`capture_stats.py`）和精确回放（`CaptureReplayTest`）；任何会显示结果的
   工具都不跑，直到门槛冻结写进 `docs/experiments/`。会显示结果的工具见到 `SEALED` 标记一律拒绝（第 5 节）：det 优先基线
   （`PromptPolicyBaselineTest`）、M3a 评估（`GroundContactCaptureEvaluationTest`）、contact sheet（`contact_sheet.py`）和
   提示标注表（`export_prompt_labels.py`）。标记既按给出的路径向上找，也按真实位置向上找，所以链接或 junction 不能绕过。
   以后新增的指标入口必须用同一个检查（`SealedCaptures.forMetrics`、`sailens_capture.refuse_sealed`）。
   门槛冻结并写进 `docs/experiments/` 之后才打开。

## 2. 录制前检查（每天出门前）

在手册第 3 节的基础上：

1. **版本**：PR #30 合入 main 后的那个提交，加上与 2026-10 录制相同的权重
   （`det.tflite` sha256 `d9cef07c…`、`sem.tflite` sha256 `d29dee02…`）。合入后打一个带注释的 tag
   `baseline/detfirst-pr0`，写上这两个哈希，每天用 `capture_stats.py --baseline-tag baseline/detfirst-pr0` 检查。
   混进别的版本，这一段就不能用。
2. **设置**（整批数据保持一致）：
   - 语音开，**语速 1.30**（D1.7 时长表就是在这个语速下测的）；震动开；**TalkBack 关**。读屏会话无法建模，不算进任何一组。
   - 感知挡位：**标准**（sem + det）。
   - 接地点距离：**开关关**（基线就是今天的策略）；放置方式选"胸前手持"，身高 170。
   - 现场采集：打开"Guidance 运行时采集"和"同时录制模型回归记录"；分组选户外或室内；**场景名称填槽位号**（例如 `C-O2-03`）。
3. 电量 > 60%，可用存储 > 10 GB（模型回归记录户外约每小时 3.5 GB）。连续录 20 分钟左右休息一下，防止发热降频。

## 3. 场景层和槽位

每个槽位就是一次"开启辅助 → 停止辅助"，5–8 分钟，只录一种场景。槽位号格式：`<组>-<场景层>-<序号>`。

| 场景层 | 内容 | 主要考察 |
|---|---|---|
| O1 停放车辆 | 沿路边停着的车走；正对一辆停着的车走近，到约 1 米处停下再绕开 | 前方车辆的首次提示、NEAR、CRITICAL |
| O2 迎面行人 | 人流中等的人行道或商业街，有人迎面或横穿 | 前方/侧方行人，同级不升级（D1.6） |
| O3 人行道静态障碍 | 杆、树、停放的自行车、长椅：先正对走近再绕开，也从旁边经过 | 静态障碍，NEAR |
| O4 路口等待 | 走近路口，站着等至少 30 秒，再通过 | 静止时的冷却、重复提示 |
| I1 商场走廊 | 中庭和走廊，有行人和店前障碍 | 前方不通与障碍物抢喇叭（尽力而为） |
| I2 商场出入口 | 从室外开始录，走进去再走出来 | 地面认不出、提示切换（尽力而为） |

**第一轮槽位**（每层 4 个：C、S 各 2 个，交替排列）：

| 场景层 | 槽位（按录制顺序） |
|---|---|
| O1 | 由抛硬币决定先 C 还是先 S，之后交替：例如 `C-O1-01`、`S-O1-01`、`C-O1-02`、`S-O1-02` |
| O2 / O3 / O4 / I1 / I2 | 同上，每层单独抛一次硬币 |

抛硬币的结果在第一次出门前写进分配文件头部（第 4 节），之后不改。同一层的 C 和 S 尽量在同一天、同类地点录，避免两组落在不同的天气或时段。

## 4. 先录什么

1. **试点（两段，都算 C 组）**：户外 O1 一段（`C-P-01`），室内 I1 一段（`C-P-02`）。录完当天检查：
   - manifest 里 `schemaMinor = 5`、`regression.utteranceLifecycle = true`；
   - `regression.stats` 里 `framesLost = 0`、`utteranceEventsDropped = 0`、`utteranceEventsUnreconciled = 0`；
   - 在 C 组文件夹上跑基线测试，生命周期一栏显示 "recorded"、有"spoken to the end"的数字。

   任何一项不对，先停下来修采集链路，不往下录。
2. **第一轮槽位**：按第 3 节的表录，户外层优先（O1、O2 最关键），室内两层尽力而为。
3. **按缺口补录**：每次录完，只在 **C 组**上跑基线，看各类危险阶段的数量。第一轮的目标（初始值，试点后可调）：每组至少
   - 前方车辆首次提示 20 个、前方行人首次提示 20 个；
   - NEAR 阶段（全部类别）20 个、CRITICAL 阶段 20 个；
   - 同级冷却压住的 NEAR 条目 10 个。

   哪一项不够，就给对应的场景层**同时**加一个 C 槽位和一个 S 槽位，交替顺序接着排。参考：2026-10 的 7 段旧录制约 55 分钟，
   前方车辆和前方行人各 19 个，所以每组大约需要一小时以车、人为主的户外录制。户外、室内各录一段**不预设为足够**。

## 5. 分配文件和文件夹

放在仓库**外面**，和其他录制放在一起：

```
C:\Users\wnbot\AndroidStudioProjects\sailens-captures\detfirst-d18\
  assignment.csv
  calibration\<sessionId>\      C 组的会话（从手机导出后放这里）
  sealed\SEALED                 空文件：封存标记，所有会显示结果的工具见到就拒绝
  sealed\<sessionId>\           S 组的会话
```

`assignment.csv` 的模板（第一次出门前填好前几行和全部计划槽位，录完一个填一个 `session_id`）：

```
# det-first D1.8 分配表。第一次录制前写好；槽位所属的组一经写下就不再改。
# 规则：每个场景层内 C、S 交替，先后由抛硬币决定，结果：O1=?, O2=?, O3=?, O4=?, I1=?, I2=?（日期：2026-10-__）
# 门槛冻结：（提交号）于（日期）。这一行填上之前，S 组不得用于任何指标。
slot,stratum,set,group,planned_date,session_id,recorded_date,status,notes
C-P-01,pilot,C,outdoor,,,,planned,试点：链路检查
C-P-02,pilot,C,indoor,,,,planned,试点：链路检查
C-O1-01,O1,C,outdoor,,,,planned,
S-O1-01,O1,S,outdoor,,,,planned,
```

- `session_id` 就是手机上采集会话的 id（导出 ZIP 和文件夹名都是它）；`recorded_date` 写录制日期。
- `status`：`planned` / `recorded` / `discarded`（中断、TalkBack 误开、版本不对等）。**作废的槽位不改组、不顶替**，
  补录时新增一个槽位号。
- 录完的会话按 `set` 放进 `calibration` 或 `sealed`。手机上"保留"它，直到确认电脑收到。

## 6. 每天录完

1. 导出（手册第 8 节），放进对应文件夹，填好分配表。
2. 两组都检查完整性：`capture_stats.py --baseline-tag baseline/detfirst-pr0`；精确回放可以对两组都跑
   （`CaptureReplayTest` 只说回放是否一致，不给指标）。
3. 只在 C 组上跑基线：

   ```
   gradlew :sailens-guidance:testDebugUnitTest --tests "*PromptPolicyBaselineTest*" -PreplayCapture=<...\detfirst-d18\calibration> -Pm3aPhoneHeight=1.15
   ```

   对着 `sealed` 跑会被拒绝，M3a 评估、contact sheet 和标注表也一样。门槛冻结之后才加 `-PunsealCapture`（Gradle）或
   `--unseal`（Python 工具）打开。
4. 按第 4 节第 3 条决定要不要补录。

## 7. 什么时候打开封存组

只有在以下都写进 `docs/experiments/` 并提交之后：`SpeakerModel` 的校准门槛（D1.4-2）、D2–D4 的成功门槛（Q6），以及要用的参数。
提交号写进分配表的"门槛冻结"那一行，然后才删掉 `SEALED` 或加 `-PunsealCapture` 跑 S 组。
S 组上的结果不能再拿回去调参数；调了，这组就作废，要重新录一批封存组。
