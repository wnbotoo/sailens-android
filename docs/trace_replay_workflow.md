**English** | [简体中文](trace_replay_workflow.zh-CN.md)

# Trace / replay workflow

## Goal

Give Sailens a minimum viable "observe -> replay -> evaluate" loop, so that tuning the two models is
not judged by subjective impression alone.

## What exists today

For what each metric means, how it is computed, and how to read it when tuning, see
[`trace_metrics_guide.md`](trace_metrics_guide.md).

- `StartSceneAnalysisUseCase` creates a fresh `sessionId` at the start of every analysis session.
- Every frame records a `frame trace`:
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
  - `frameQuality` / `frameQualityClassified`, `qualification` / `qualificationReasons`,
    `capabilities`, `receiptToDecisionMs` (see
    [Qualification and alerts](trace_metrics_guide.md#qualification-and-alerts))
- Every alert given or offered while the session is open records a `guidance_alert`, numbered by
  `seq`: the vision faults' and, from the shell, a stall, a failure, an interruption and speech
  going unavailable.
- Every change of the safety state or a fact behind it records a `safety_state`, numbered by `seq`;
  the one kept from before the session opened is its first, right after `session_start` (see
  [Safety state](trace_metrics_guide.md#safety-state)).
- At session end a `session summary` is emitted:
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
  - `closedElapsedMs`, `guidanceAlertCount`, `safetyStateCount` (with
    `session_start.openedElapsedMs`: the window the trace observes, and how many alert and state
    records it should hold)
- The domain layer has minimal offline replay:
  - `TraceReplayParser` — structured parse of `trace_<sessionId>.jsonl` via
    `kotlinx.serialization.json`
  - `BuildTraceReplayReportUseCase` — builds the aggregate replay report
  - `ListTraceSessionsUseCase` — enumerates stored sessions
  - `LoadTraceReplayReportUseCase` / `LoadLatestTraceReplayReportUseCase` — load a specific or the
    latest report
  - `EvaluateTraceReplayBudgetUseCase` — emits budget warnings for a replay report
  - The report can output:
    - `totalEvents`
    - `blockedFrameRate`
    - `dangerousFrameRate`
    - `avgProcessFrameMs` / `avgInferenceMs` / `avgTotalPipelineMs`
    - `p95TotalPipelineMs` / `maxTotalPipelineMs`
    - `errorCount`
    - `uniqueMessageKeys`
    - `qualificationFrameCounts`
    - `guidanceAlertCounts`, with whether the alert records are complete within the window
    - `safetyTimeline`: how long each safety state held within the window, the time whose state is
      unknown, and whether the state records are complete
- `SceneAnalysisView` has real entry points:
  - **Trace sessions** page — browse stored sessions
  - **Trace replay report** page — detailed metrics for a chosen session
  - Refresh the session list from the page
  - Load the latest trace report
  - Load a replay report per session
  - Copy the current report summary to the clipboard
  - Export the session's raw `trace_<sessionId>.jsonl` through the system share sheet
- The live debug panel is wired to the runtime budget:
  - current `totalPipelineMs`
  - current `process / analyze / decision` split
  - `avg / p95 totalPipelineMs` over the last 30 frames
  - dropped-frame rate over the last 30 frames
  - the same budget state replay uses

## File locations

Runtime trace output directory:
- `files/traces/` under the app's private directory

File format:
- `trace_<sessionId>.jsonl`
- One JSON record per line, in order:
  - `session_start`
  - many `frame`
  - optional `overlay_render`
  - optional `prompt_outcome` (one per offered prompt)
  - optional `guidance_alert` (one per alert given or offered)
  - `safety_state` (the one kept from before the opening right after `session_start`, then one per
    change)
  - optional `error`
  - `session_summary`

## What this supports today

1. Viewing key performance metrics for recent traces in-app, via the session list / report pages
2. Observing average and p95 latency on real sessions
3. Estimating frame loss under `DROP_OLDEST`
4. Comparing model sizes, delegates, thresholds, and pipeline strategies
5. Feeding input samples to later offline replay and evaluation scripts
6. Sharing raw JSONL to a computer, an annotation tool, or a batch evaluation script
7. Spotting budget overruns live, while running

## Suggested next steps

1. Add batch comparison to the replay output:
   - multi-session aggregation
   - baseline vs experiment A/B comparison
   - threshold alerts (e.g. `p95TotalPipelineMs`, `droppedFrames`)
2. Capture a real-world trace baseline
3. Keep running A/B comparisons on the two-model configuration
