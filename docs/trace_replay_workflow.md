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
  - `TraceReplayParser`：解析 `trace_<sessionId>.jsonl`
  - `BuildTraceReplayReportUseCase`：生成 replay 聚合报告
  - 报告可输出：
    - `totalEvents`
    - `blockedFrameRate`
    - `dangerousFrameRate`
    - `avgProcessFrameMs` / `avgInferenceMs` / `avgTotalPipelineMs`
    - `p95TotalPipelineMs` / `maxTotalPipelineMs`
    - `errorCount`
    - `uniqueMessageKeys`

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
1. 观察真实会话下的平均时延 / p95 时延
2. 估算 `DROP_OLDEST` 下的帧丢失情况
3. 对比纯 `DDRNet` 与未来 `YOLO + DDRNet` 双模型模式
4. 给后续离线 replay 与评估脚本提供输入样本

## 下一步建议

1. 增加 trace 导出入口（调试页 / 分享日志）
2. 把离线 replay 解析 / 报告接到实际入口（调试页、开发者菜单或导出脚本）
3. 为 replay 输出增加批量对比能力：
   - 多 session 汇总
   - baseline vs experiment A/B 对比
   - 阈值告警（如 `p95TotalPipelineMs`、`droppedFrames`）
4. 再开始引入 `YOLO` 并做 A/B 对比


