[English](field-capture.md) | **简体中文**

# 现场采集与模型回归记录

debug 版会为一次 Guidance 会话记录什么、用什么格式，以及电脑端工具和 JVM 回放怎么读回来。本文描述
代码现在的样子；两者不一致时以代码为准，并修正本文。

格式和 JVM 读取器在 `sailens-guidance`（`…trace.capture`、`…trace.regression`）；写入器在 shell 的 debug
源集（`…shell.capture`）；电脑端工具在 `scripts/capture/`（见那里的 README）。release 版不含任何采集代
码。

## 1. 采集是什么，以及隐私

一次采集是应用 `files/captures/` 下每个 Guidance 会话一个目录，以 trace 的 session id 命名。**现场证据**
采集包含抽样的帧、运动传感器和相机参数；**对时**采集是用来测量帧与传感器对齐的一小段突发；**模型回
归**采集另外记录精确回放决策路径所需的内容（§7）。

**隐私。** 采集里有人脸和地点。它们不进入自动备份（`backup_rules.xml`），也不进入云备份和设备迁移
（`data_extraction_rules.xml`）；导出的 ZIP 放在 `cacheDir`，不会被备份。除非用户主动导出，什么都不离
开设备。采集绝不进入本仓库或 CI。

## 2. 采集格式

`CaptureSchema`。每个会话一个目录：

| 文件 | 内容 |
|---|---|
| `manifest.json` | `CaptureManifest`：schema 大/小版本、session id、模式（`field_evidence` / `timing_sync` / `model_regression`）、开始时的墙钟和 elapsed-realtime 时间、应用版本、构建来源（`gitSha`：commit，工作区有差异时加 "-dirty"；`modelArtifacts`：每个打包模型的 SHA-256——权重不在 git 里）、设备、SDK、硬件档位、相机参数（若相机在会话开始后才绑定，则在第一帧时补上）、已注册的传感器（`sensorsAvailable`）、`complete`、`failureReason`、`pinned`、`exportedAtWallMs`、采集计数 |
| `frames.jsonl` + `frames/` | 每个存下的帧一条 `FrameRecord`：序号、相机时间戳、来源侧接收时间、原始尺寸和旋转、CameraX 的 sensor-to-buffer 变换（有效阵列 → 原始缓冲区）和裁剪框、编码（`jpeg` / `luma8`）、文件、存储尺寸 |
| `sensors.jsonl` | `SensorRecord`：传感器（`gravity`、`game_rotation_vector`、`gyroscope`）、事件时间戳、精度、数值 |
| `anchors.jsonl` | `ClockAnchorRecord`：会话开始、每 30–60 秒、会话结束时各记一对（墙钟毫秒，elapsed-realtime 纳秒） |
| `markers.jsonl` | `MarkerRecord`："标记漏报"、墙钟和 elapsed 时间（精确参照）、最新*已存储*帧的序号（其图片一定存在）、来源 |

- 创建目录时就写 `manifest.json`，`complete = false`；正常结束后以原子方式（临时文件 + 重命名）改写为
  `complete = true`。应用被杀、崩溃、拔 USB、磁盘满都会留下 `complete = false`，读取器会报告。
- 每行 JSONL 都有 `type`；被截断的最后一行跳过并给出警告。
- **版本规则。** `schemaMajor` 和 `schemaMinor` 必须存在；读取器先从原始 JSON 里读出它们，**再**解码正
  文；缺版本号或大版本未知都拒绝（报"不支持"，而不是"读不懂"），然后才解码。**小**版本只能新增：可选或
  带默认值的 manifest 字段、记录类型、已有记录的字段、开放字符串字段的取值（采集模式为此用字符串，旧读
  取器会带着警告读过新模式）。不能删除或改名必填字段，也不能改变字段的含义或类型；那属于大版本。记录
  里的封闭枚举不在小版本里扩展。已有的小版本新增了构建来源（1.2）、模型回归记录（1.3）、记录的几何
  （1.4）和语音生命周期（1.5）。
- 传感器频率按事件时间戳计算；`SENSOR_DELAY_GAME` 只是请求的档位。
- **计数**与 Guidance 的丢帧计数分开，这样缺口才能解释。对完整的采集，这些计数覆盖引擎收到的全部内容：
  `framesOffered` = `framesEncoded` + `framesDroppedByEncoder`；`sensorEvents`（已写入）+
  `sensorEventsDropped`（采集队列满而丢弃）= 收到的样本数。帧会在三个地方丢失：
  - **分析器之前**（相机本身，或 CameraX 只保留最新一帧）：这样的帧拿不到序号，哪里都不计数，只表现为相
    机时间戳间隔变长；
  - **采集自己的信箱**（分析器已转换并送出，但采集还没取走上一帧）：`framesMissedBySubscriber`，由收到的
    序号之间的空缺算出，只在 `timing_sync` 中计数，因为只有这时采集要每一帧（现场证据是抽样的，分析器也
    会给为其他订阅者转换的帧编号，所以它的空缺是设计如此）；
  - **采集的编码队列**：`framesDroppedByEncoder`。

  对突发采集，工具单独报告**已知**丢失（两个计数），绝不用估计值替代它，只*估计*分析器之前的丢失：已存
  储帧之间缺失、且没有序号空缺能解释的相机时间戳槽位；分析器帧周期取"间隔 ÷ 序号步长"，所以分析器之后
  的稳定丢帧藏不进帧率里。分析器之前的稳定丢帧仍然会藏住，所以要把该周期与相机帧率对照。总体丢失比例 =
  已知 + 估计，绝不低于已知丢失。**不完整的**采集在失败边界上可能有未计入的记录，作为证据的可信度也更
  低。

## 3. 计时与相机参数

- **帧的计时在来源侧取**：`ImageFrameAnalyzer.analyze()` 在任何需求判断、转换或排队之前记下
  `receivedElapsedRealtimeNanos`，相机自己的 `ImageFrame.timestamp` 含义不变。采集两个都记。trace 用墙钟
  毫秒；锚点把两者放到同一条时间线上，周期性的锚点还能发现会话中途墙钟被改过。
- **相机参数**：`sailens-camera` 的 `CameraCharacteristicsProvider.currentSnapshot()` 返回纯值的
  `CameraCharacteristicsSnapshot`；Camera2 互操作留在 `sailens-camera` 里。每次绑定都重新读快照，解绑时
  清除，绝不跨绑定缓存（有些逻辑相机会随设备状态改变 `SENSOR_ORIENTATION`）。分析尺寸取自第一帧，而不是
  请求的分辨率。每帧还记录 CameraX 的 sensor-to-buffer 矩阵和裁剪框：只凭有效阵列、缓冲区尺寸和旋转无法
  可靠地重建有效阵列到缓冲区的映射，而几何映射内参需要它（[geometry.zh-CN.md](geometry.zh-CN.md) §2）。
- **时间契约。** 原始相机时间戳就是标准的帧时间，**不做每设备修正**（没有标定表，不为任何手机写死数
  值），并认为画面内容落在它的**约 20 ms** 以内。依据来自 Guidance 运行时的对时突发：

  | | SM8850 — OnePlus CPH2747，SDK 36 | SM8450 — Samsung SM-S9010（S22），SDK 36 |
  |---|---|---|
  | 相机时间戳来源 | `REALTIME`（所有摄像头） | `REALTIME` |
  | 后置摄像头 | 逻辑多摄（超广角 2.31 mm、主摄 5.59 mm、长焦 12.19 mm）；记录的内参是主摄的 | 逻辑多摄；记录的内参是 5.4 mm 主摄的 |
  | 物理摄像头切换 | 未见（几厘米近距离、暗室） | 未见（5–10 cm 近距离、暗室） |
  | 分析流 | 1280×720（4:3 阵列的 16:9 裁切），30 fps | 1280×720，30 fps |
  | 报告的镜头畸变 | 全为 0 | 0.073、−0.107、0.047、0、0 |
  | 陀螺仪频率（`SENSOR_DELAY_GAME`） | 46.8 Hz | 49.7 Hz |
  | 帧 → 陀螺仪偏移，相机时钟（陀螺仪 ≈ 帧 + δ） | 中位数 +2.3 ms，−5.2…+8.6，sd 4.8（11 次突发） | 中位数 +12.9 ms，+8.2…+17.2，sd 3.5（8 次突发） |
  | 相机时间 → 应用收到 | 60.8 ms，±1 ms | 67.4 ms |

  两台设备都是 `REALTIME`，而接收时间多出 60–70 ms 的投递延迟，所以用相机时间戳。相机时钟本身是稳的（相机
  到接收 ±1 ms 以内），但画面内容相对曝光起始时间戳的位置，在同一台手机的不同突发之间相差 9–14 ms，可能
  与曝光时间有关，而曝光时间没有记录。行走转身速度 0.2–0.5 rad/s 下，20 ms 相当于 0.2–0.6°。要收紧这个预
  算，先要逐帧记录 `SENSOR_EXPOSURE_TIME` / `SENSOR_ROLLING_SHUTTER_SKEW`（同一个 capture-result 钩子也能
  给出 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`）。没见到物理摄像头切换，所以静态内参成立。

## 4. 会话接线与故障隔离

`GuidanceModule` 构建基础的 `TraceService`（写文件或空操作），如果绑定了 `TraceServiceDecorator` 就套上
它；debug shell 绑定一个加上采集的装饰器，release 不绑定。规则：

- 采集失败绝不让 Guidance 失败：磁盘满、传感器注册失败或编码器崩溃，只把采集标为失败 / 不完整，不从
  `TraceService` 的调用里抛出任何异常；
- 采集的开始和停止绝不阻塞 `TraceService` 的调用；
- 采集模式只在会话开始时读一次；
- 现场采集只存在于开启了 trace 的 debug 版（`TraceRuntimeConfig.enabled`）。

## 5. 帧写入、对时突发与对齐的时钟契约

**现场证据。** `FrameSource.frames(minIntervalMs = 200)` → 缩小复制成 NV21 → 立即 `releaseFrame` → 有界
队列（容量 1–2，丢最老的待处理项）→ 后台 JPEG 编码（长边 640 px，质量 80，像素不旋转，记录
`rotationDegrees`）。采样器取距上一帧至少 200 ms 后到达的第一帧；30 fps 时六帧是 199.96 ms，正好卡在边
上，所以按到达抖动每 6 或 7 帧存一帧：4.3–5 Hz。

**对时突发。** 模式 `timing_sync`：约 15 秒，按相机帧率，小尺寸亮度帧（`luma8`，Y 平面面积平均到长边
400 px——是平均而不是点采样，这样细纹理不会混叠成虚假运动），精确的逐帧时间戳，陀螺仪
`SENSOR_DELAY_GAME`。只用来测量帧与传感器的对齐，绝不作性能基线。在现场采集页面单独布设一次，与采集开
关无关，由下一次 Guidance 启动用掉（只在内存里，应用被杀就忘了）。它取来源送出的每一帧，最多排队 4
帧，15 秒后自己作为完整采集结束，而 Guidance 继续运行；采集的开始和结束是串行的，两个结束不会交错。文
件是 `frames/NNNNNN.y`。一次采集——无论突发还是现场证据、正常结束还是失败——在定稿之前都算活动：只有正
常结束写完 END 锚点和完整的 manifest，或失败后尝试写过不完整的 manifest，`isCapturing` 才变为假，所以
采集列表绝不会读到写了一半的 manifest。

**对齐**（`scripts/capture/timing_align.py`）：每对帧之间相位相关得到的图像运动，与同一区间陀螺仪 x/y 旋
转比较，取相关最好的滞后量，以突发四个部分之间的离散作为误差。在合成突发上与真值相差约 1.5 ms 以内；这
只是数值精度，不是设备的测量下限。

**时钟契约。** **只有相机时间戳来源为 `REALTIME` 时**才拿相机时间戳和陀螺仪比较，安卓规定它与
`SensorEvent` 共用时间基准。`UNKNOWN` 单调但原点未定，没报告来源也一样：这时相机结果不算测量
（`cameraComparable = false`），无论相关性看起来多好都绝不能判定相机时钟可比，以来源接收时间为权威测
量。一次突发**可用**，当且仅当：完整、采集没丢传感器样本、手机确实转了（跨光轴的陀螺仪转速中位数 ≥
0.15 rad/s：稳持手机行走实测 0.01–0.09，原地转身 0.20–0.24）、权威时钟上图像运动与陀螺仪的相关至少
0.5（与陀螺仪无关的真实运动作负对照约 0.3）、四个部分都能检查、偏移不在搜索边缘。各部分不一致、丢帧严
重只给警告。设备结果按设备**和构建**给出（manifest 的 `gitSha` 和 `modelArtifacts`；绝不跨构建合并突发，
没有 SHA 的采集不给结果）：至少三次可用突发的偏移中位数，以它们的范围和标准差作为几何必须容纳的不确定
度。

## 6. 控制（debug 版）

| 需求 | 设计 |
|---|---|
| 模式开关 | 设置 → 诊断 → 现场采集：开 / 关，另有"为下一次 Guidance 启动布设对时突发"（一次性）。模式在会话开始时读取，所以开关在下一次会话生效，采集录制中锁定 |
| 开始 / 停止 | 模式打开时跟随 Guidance 会话；没有单独的按钮 |
| 保留、删除 | 采集列表：开始时间、大小、状态（录制中 / 完整 / 不完整及原因）、已保留、已导出、帧和标记数。任一采集录制中时拒绝保留、删除和导出（工具不能干扰它正在收集的证据）；删除要确认；被拒绝或失败的操作会报告 |
| 导出 | 只限已结束的会话。ZIP 在写入线程之外生成到 `cacheDir/capture_exports/`（先检查剩余空间），期间该会话不受保留策略和删除影响。采集和导出绝不重叠：生成 ZIP 时拒绝启动 Guidance（"请先完成导出"，debug 的 `GuidanceStartGate`），万一仍有采集启动，立即以 `failureReason = export_in_progress` 不完整结束。无法标记为已导出的会话，其 ZIP 丢弃并报告。通过 FileProvider 的 `<cache-path>` 分享；`files/captures/` 本身绝不暴露。超过一天的导出在应用启动和打开列表时清除。批量导出用 `adb` + `run-as`（`scripts/capture/README.md`） |
| 查看提示前后的画面 | 在电脑上：`scripts/capture/contact_sheet.py` 渲染某条 `prompt_outcome` 前后 ±N 秒（以交出提示的那一帧为中心：trace 的 `sourceSequenceNumber` → `frameTimestamp` = 采集的 `sensorTimestampNanos`），或某个标记前后 |
| 存储 | 现场证据室内约每小时 0.5–0.75 GB、户外 1.15–1.27 GB（SM8850），SM8450 为 0.73，每次对时突发另加约 41 MB；`scripts/capture/capture_stats.py` 测量速率、帧率、缺口、丢弃、计数平衡和墙钟跳变 |
| 保留 | 在 debug 应用启动、采集开始前、打开采集列表时，以及采集期间每 30 秒执行。超过 7 天、未导出也未保留的会话在下一次执行时删除。总上限（默认 10 GB，可选 2 / 5 / 10）在采集期间执行：先删较早的未保留会话；若当前采集本身就要超限，则以 `failureReason = storage_limit_reached` 不完整结束。剩余空间低于约 2 GB 时采集停止或拒绝启动 |
| "标记漏报" | 采集进行时按音量减键：只有 `ACTION_DOWN` 且 `repeatCount == 0` 才算，也只有这时才消费按键；时间、会话和最新已存储帧在观察到按键时就固定（写入器忙只推迟写入，不推迟事件），只有标记写成功后才短震确认。另有一个大的屏幕按钮。前台会话中在 TalkBack 下可以免看操作（在 SM8850 和 SM8450 上验证过）；锁屏或后台不作承诺 |

## 7. 模型回归记录

`model_regression` 采集是现场证据加一份记录，使决策路径能在 JVM 上**精确**回放，从而逐帧比较行为改动
（`RegressionSchema`，schema 1.3 及以后，放在同一目录）。

**回放边界。** JVM 算不成一样的东西都在其边界处记录；其余部分——`ProcessFrameUseCase` →
`AnalyzeSceneUseCase` → 帧质量去抖 → `DecideEventsUseCase`——作为真实代码运行，只在边界处用假实现：

| 边界 | 每帧记录 | 回放时 |
|---|---|---|
| sem 模型 + native 融合 pass | 类别图（`classmaps.bin`，zlib，每个类别 id 一字节）；native 标量；可通行 / 障碍掩码的 CRC32；运行 / 缓存 / 失败 | 经 `NavigationSemantics` 从类别图重建掩码，并与 CRC 核对；标量按记录使用（native 与 Kotlin 的浮点路径不必一致） |
| det 模型 + native 解码/NMS | det 运行时的检测结果（类别、标签、置信度、框） | 由假的 `ObstacleProvider` 提供 |
| native 连通性 | `ConnectivityStats` + 输入掩码的 CRC32 | 回放输入 CRC 相同时用记录的统计（已知 Kotlin 回退与内核不一致）；否则说明上游变了：用 Kotlin 计算并把该帧标为近似 |
| 注入的时钟（感知、地面、决策） | 每个返回值，按组件、按调用顺序 | 按顺序还回去；精确模式断言每个列表都用完 |
| 帧质量 | 亮度均值、亮度标准差、清晰度（只记录）、逐帧分类 | `FrameQualityAnalyzer.analyzeMeasured` 从测量值继续 |
| `isStationary` | 决策步骤读到的值 | 假来源 |
| 相机几何（schema 1.4） | 该帧处理时用的 `CameraGeometry` | 按记录使用（[geometry.zh-CN.md](geometry.zh-CN.md) §5） |
| 撤回（UI 线程） | 事件 id + 它之前已做的决策次数，在冷却锁内读取 | 在那次决策之前应用；id 按位置映射到回放出的事件 |
| 配置 | 完整的 `PerceptionConfig` / `AnalysisConfig`、类别体系、深度来源 | 宽松解码 |
| 决策 | 每个决定的事件，全部字段 | 期望输出；比较时忽略随机 id |

只有在连续不断的帧上回放才是精确的：记录丢掉的第一帧（`RegressionStats.firstLostSeq`）就是终点，因为平
滑器和跟踪器无法接着恢复。超越函数在 ART 和 HotSpot 之间可能差一个 ulp。送达确认不记录：它们不改变任
何冷却判断，回放只按撤回复现。

**事件窗口。** 已送达提示前后（前 1.0 秒到后 0.5 秒）和标记之前（2 秒）的分析分辨率全帧，10 fps，
JPEG——用于在相同像素上离线重跑模型。帧在 Guidance 归还之前复制进环形缓冲（只复制平面，10 Hz 下 2.5
秒，SM8850 上约 46 MB），只有触发时才编码。被撤回的提示不触发：每分钟约 14 条，会让存储成倍增长。

**存储**（SM8850，户外）：现场证据约每小时 1.2 GB，类别图 0.6–0.8（p90 为 1.0），逐帧 JSONL 约 0.15，
事件窗口约 1.2：户外约每小时 3.2–3.6 GB，室内约一半。

**分段。** 一个场景就是一个 Guidance 会话；manifest 的 `group`（户外 / 室内）和 `sceneLabel` 给它命名。
没有视觉标记，也不从图像推断。应用内文字要求只在 SM8850 上录；代码并不强制。

**检查。** CI 里 `RegressionReplayRoundTripTest` 让一个合成会话分别在录制开和关的情况下跑完真实的
`StartSceneAnalysisUseCase`，要求决策完全相同，并精确回放其记录。录制的开销在 SM8850 上测过：同一场
景、手机放在支架上，录制关和开交替进行。

## 8. 回放一份采集

只在本地：

```
gradlew :sailens-guidance:testDebugUnitTest --tests "*CaptureReplayTest*" -PreplayCapture=<采集目录或装着多个采集的文件夹>
```

每份采集一份报告，写到 `sailens-guidance/build/replay/<sessionId>.txt`：回放了多少帧、第一处不一致（帧、
项目、期望值、实际值）、近似帧，以及在哪里因丢帧停止。同一个属性也供采集评估使用
（`GroundContactCaptureEvaluationTest`、`PromptPolicyBaselineTest`）。位于含 `SEALED` 文件的目录之下的采
集属于封存的验收集，除非给出 `-PunsealCapture` 否则跳过（电脑端工具用 `--unseal`）。

## 9. 语音生命周期记录

schema 1.5。debug 采集还记录交给语音引擎的每一句话的结果：`regression.jsonl` 里的
`RegressionUtteranceRecord` 行（submitted / refused / started / done / stopped / error / requeued /
requeue_failed / superseded / cancelled / unknown_terminal，以及 screen_reader_published），按观察到它的
线程计时，带尝试 id、逻辑 id（Guidance 提示的事件 id）和引擎代数。它们有自己的队列和丢弃计数
（`RegressionStats.utteranceEventsDropped`），所以绝不会让记录丢帧；回放忽略它们。
`RegressionManifest.utteranceLifecycle` 在所有旧记录里为假，那些记录因此没有生命周期证据——不是零失败。
读取它们的是 `PromptPolicyBaselineTest`（[experiments/detfirst-pr0-baseline-2026-10.zh-CN.md](experiments/detfirst-pr0-baseline-2026-10.zh-CN.md)）。

## 10. 标注提示

为用户实际收到的提示生成表格：

```bash
py scripts/export_prompt_labels.py <分段文件夹>/trace_*.jsonl -o <分段>.csv
```

每行是 trace 里一条已送达的 `prompt_outcome`。被撤回的提示从没被听到，不可能是误报，默认不列出
（`--include-revoked` 列出它们）。`delivered_via` 说明提示怎么到达用户（`speech`、`screen_reader`、
`haptics`、`status_card`）：只有 `haptics` 的提示是感到的，不是听到的，即使语音开着。`output_settings` 只
作背景。每行还带上该帧的地面识别状态、是否判为前方不通、被跟踪的障碍物和主要类别。

对照提示前后的画面（`contact_sheet.py <采集> --trace <trace>.jsonl --prompts --window 2`；标记用
`--markers --window 3`）。现场证据只存一部分帧，所以发出提示的那一帧常常没存：会高亮离它最近的已存储
帧，最多相差约 0.12 秒。填写 `label`：

| 标签 | 含义 |
|---|---|
| `tp` | 正确，而且对行走有意义 |
| `fp` | 前方没有那样的东西，或者有但不值得提示（比如路线外远处的人） |
| `wrong` | 确实有东西，但提示说错了（方向或类别） |
| `late` | 正确但太晚（播报时已经到跟前） |
| `unsure` | 从画面判断不了；在 `note` 里写原因 |

漏报记在另一张表里，每个漏报一行：分段、大致时间（分段内第几秒）、漏了什么、估计距离、备注。每个"标
记漏报"都要有一行（误按的写 `accidental`）。漏报指：路线上约 2 m 内的障碍物、确实不通的路，或台阶、路
沿，在走到之前没有任何提示。
