# Phase 5 Trace / Replay Workflow

## 目标

为 `Sailens` 建立一条最小可用的“可观测 -> 可回放 -> 可评估”链路，避免后续引入 `YOLO + DDRNet` 后只能凭主观体验判断效果。

## 当前已落地能力

- `StartSceneAnalysisUseCase` 会在每次分析会话开始时创建一个新的 `sessionId`
- 每帧都会记录 `frame trace`：
  - `sequenceNumber`
  - `frameTimestamp`
  - `frameWidth` / `frameHeight`
  - `droppedFramesSinceLast`
  - `processFrameMs`
  - `inferenceMs`
  - `analyzeSceneMs`
  - `decideEventsMs`
  - `totalPipelineMs`
  - `obstacleCount`
  - `eventCount`
  - `isBlocked` / `isNarrowing` / `isRoadDangerous`
  - `messageKeys`
- 会话结束时会输出 `session summary`：
  - `totalFrames`
  - `droppedFrames`
  - `totalEvents`
  - `blockedFrames`
  - `dangerousFrames`
  - `avgProcessFrameMs`
  - `avgInferenceMs`
  - `avgTotalPipelineMs`
  - `p95TotalPipelineMs`
  - `maxTotalPipelineMs`
- 域层已补齐最小离线 replay 能力：
  - `TraceReplayParser`：基于 `kotlinx.serialization.json` 结构化解析 `trace_<sessionId>.jsonl`
  - `BuildTraceReplayReportUseCase`：生成 replay 聚合报告
  - `ListTraceSessionsUseCase`：枚举已存储会话
  - `LoadTraceReplayReportUseCase` / `LoadLatestTraceReplayReportUseCase`：加载指定或最新报告
  - `EvaluateTraceReplayBudgetUseCase`：对 replay 报告给出预算 warning
  - 报告可输出：
    - `totalEvents`
    - `blockedFrameRate`
    - `dangerousFrameRate`
    - `avgProcessFrameMs` / `avgInferenceMs` / `avgTotalPipelineMs`
    - `p95TotalPipelineMs` / `maxTotalPipelineMs`
    - `errorCount`
    - `uniqueMessageKeys`
- `SceneAnalysisView` 已接入正式入口：
  - `Trace sessions` 页面：查看已存储 session 列表
  - `Trace replay report` 页面：查看指定 session 的详细指标
  - 可从页面中刷新 trace 会话列表
  - 可加载 latest trace report
  - 可按 session 载入 replay report
  - 可复制当前报告摘要到剪贴板
  - 可通过系统分享面板导出当前 session 的原始 `trace_<sessionId>.jsonl`
- Live debug 面板已接入 runtime budget：
  - 当前 `totalPipelineMs`
  - 当前 `process / analyze / decision` 耗时拆分
  - 最近 30 帧 `avg / p95 totalPipelineMs`
  - 最近 30 帧 dropped frame rate
  - 与 replay 共用的预算状态

## 文件位置

运行时 trace 输出目录：
- App 私有目录下的 `files/traces/`

文件格式：
- `trace_<sessionId>.jsonl`
- 每行一条 JSON 记录，按顺序包含：
  - `session_start`
  - 多条 `frame`
  - 可选 `error`
  - `session_summary`

## 当前用途

这批数据当前可以支持：
1. 在 App 内通过正式 session list / report 页面查看最近 trace 的关键性能指标
2. 观察真实会话下的平均时延 / p95 时延
3. 估算 `DROP_OLDEST` 下的帧丢失情况
4. 对比纯 `DDRNet` 与未来 `YOLO + DDRNet` 双模型模式
5. 给后续离线 replay 与评估脚本提供输入样本
6. 将原始 JSONL 分享到电脑、标注工具或批量评估脚本
7. 在实时运行时提前观察预算是否被击穿

## 下一步建议

1. 为 replay 输出增加批量对比能力：
   - 多 session 汇总
   - baseline vs experiment A/B 对比
   - 阈值告警（如 `p95TotalPipelineMs`、`droppedFrames`）
2. 采集真实场景 trace 基线
3. 再开始引入 `YOLO` 并做 A/B 对比
