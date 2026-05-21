# Sailens 改进实施计划（2026-05-20）

## 0. 目标

本轮目标不是继续堆功能，而是把现有主链路打磨成 **稳定、低延迟、可验证** 的生产级环境感知辅助系统。

核心原则：
1. **先修正确性，再做性能，再收口架构**
2. **优先保证当前帧决策质量，而不是追求处理所有历史帧**
3. **所有改动都要能被编译、测试或日志验证**

---

## 1. 实施路线图

## Phase 0：建立基线（当天完成）
- [x] 重写 `docs/functional_review_report.md`
- [x] 重写 `docs/implementation_plan.md`
- [x] 明确 P0 / P1 / P2 优先级与验收标准
- [x] 跑通一次最小构建/测试命令，作为后续回归基线

**验收标准**
- 文档与当前代码一致
- 可以据此直接开始实施，不需要再补充背景说明

---

## 2. Phase 1：P0 正确性修复（优先实施）

### 1.1 分割结果快照化
**目标文件**：`data/src/main/java/com/friady/sailens/data/source/ml/segmentation/LiteRTSegmenter.kt`

**改动**
- 保留复用写缓冲以降低分配
- 在构造 `SegmentationMask` 前对结果数组做 `clone()`，保证下游读到的是稳定快照

**原因**
- 当前是实时多阶段流水线；下游 UI 和分析逻辑不能读取正在被下一帧覆盖的数据

**验收**
- 代码层面不再把共享写缓冲直接暴露给 `SegmentationMask`
- 构建通过

### 1.2 修复事件冲突消解的短路问题
**目标文件**：`domain/src/main/java/com/friady/sailens/domain/processor/decision/EventConflictResolver.kt`

**改动**
- 用 `removeAll` 替代“只删除首个 suppressed 事件”的逻辑
- 新增至少一个单元测试覆盖 `BLOCKED + 多个 OBSTACLE` 场景

**验收**
- `:domain:test` 通过
- 测试能证明中心区域低优先级障碍事件全部被抑制

### 1.3 修复 preview / overlay 缩放不一致
**目标文件**：`presentation/src/main/java/com/friady/sailens/presentation/scene/SceneAnalysisView.kt`

**改动**
- `segMask` 叠加层继承同一个 `contentScale`

**验收**
- 编译通过
- 代码层面 preview 和 overlay 使用同一缩放策略

---

## 3. Phase 2：P1 高收益性能优化（本轮继续实施）

### 2.1 分离 Preview 与 Analysis 分辨率
**目标文件**：`camera/src/main/java/com/friady/sailens/camera/CameraViewModel.kt`

**改动**
- Preview 保持 `1920x1080`
- `ImageAnalysis` 降为 `640x360`（或必要时 `640x480`）
- 两者分别使用自己的 `ResolutionSelector`

**原因**
- 当前分析链路的瓶颈不在预览清晰度，而在 `ImageProxy -> Bitmap -> OpenCV -> LiteRT` 的 CPU/内存带宽开销

**验收**
- 构建通过
- 相比当前实现，分析流分辨率在代码层面已独立配置

### 2.2 优化 `BinaryMask.visualize()`
**目标文件**：`presentation/src/main/java/com/friady/sailens/presentation/ext/Image.kt`

**改动**
- 不再逐像素调用 `setPixel`
- 改为填充 `IntArray` 后调用 `bitmap.setPixels()`

**验收**
- 编译通过
- 热路径中不再出现每像素 JNI 写入模式

### 2.3 增补回归验证
**目标**
- 至少新增 1 个真实业务测试
- 至少跑一次 `:app:assembleDebug`
- 至少跑一次 `:domain:test`

---

## 4. Phase 3：稳定性与生命周期治理（下一阶段）

### 3.1 梳理 start / stop / release 语义
**涉及文件**
- `StartSceneAnalysisUseCase.kt`
- `StopSceneAnalysisUseCase.kt`
- `SceneAnalysisViewModel.kt`
- `MLPerceptionRepository.kt`

**目标**
- `stop()` 只负责停业务流与 reset 状态
- `release()` 负责真正释放模型与 delegate 资源
- ViewModel 销毁时路径清晰、可验证

**当前状态**
- [x] 已完成

### 3.2 恢复语音/触觉但加上可控开关
**涉及文件**
- `SceneAnalysisViewModel.kt`
- `SpeechManager.kt`
- `HapticManager.kt`
- `presentation/src/main/res/values/strings.xml`

**目标**
- 不再播报原始 `messageKey`
- 优先播报最高优先级事件
- 支持后续在设置页做 enable/disable

**当前状态**
- [x] 已完成

---

## 5. Phase 4：结构纯化与高阶性能优化（中期计划）

### 4.1 把 Android 图像类型移出 `:domain`
**目标**
- `domain/model/perception/ImageFrame.kt` 不再直接持有 `Bitmap`
- 域层改为平台无关输入模型

**当前状态**
- [x] 已完成

### 4.2 Primitive 化 BFS / FloodFill
**涉及文件**
- `ObstacleExtractor.kt`
- `ConnectivityChecker.kt`

**目标**
- packed coordinate (`Int`) 替代 `Pair<Int, Int>`
- 自定义 primitive queue/list，减少 GC 压力

**当前状态**
- [x] 已完成

### 4.3 将 DI 装配完全移出域层
**目标**
- `:domain` 仅保留纯业务对象与接口
- Koin module 收敛到外层模块

**当前状态**
- [x] 已完成

---

## 6. 本轮执行顺序（建议按此顺序直接实施）
1. 文档重写
2. `LiteRTSegmenter` 线程安全修复
3. `EventConflictResolver` 修复 + 单元测试
4. `SceneAnalysisView` overlay 对齐修复
5. `CameraViewModel` 分辨率解耦
6. `BinaryMask.visualize()` 性能优化
7. 构建与测试回归
8. 输出本轮已完成项与下一轮待办

---

## 7. 本轮完成后的交付物
- 更新后的 `docs/functional_review_report.md`
- 更新后的 `docs/implementation_plan.md`
- 一组已落地的 P0/P1 修复代码
- 至少一个新增单元测试
- 一次经过验证的构建/测试结果

---

## 8. 风险与注意事项
- 不要在本轮同时大改 `ImageFrame` 平台抽象，否则变更面过大，影响主链路稳定性
- 不要贸然启用 TTS/Haptic，先把 `messageKey -> string resource` 与优先级策略补齐
- `DecideEventsUseCase` 的处理顺序不要随意调整
- `BinaryMask`、`ConnectivityChecker`、`ObstacleExtractor` 都在热路径上，任何改动都要警惕额外分配

---

## 9. 当前建议状态
- **已完成**：Phase 1 + Phase 2 + Phase 3 + Phase 4
- **进行中**：Phase 5（已具备 trace 记录与域层 replay/report 基础）
- **下个迭代**：补齐 Phase 5 的导出/分享、批量对比、runtime 预算告警与基线数据集

---

## 9.1 下一阶段总路线（压缩为 3 个大 Phase）

### Phase 5：观测与性能地基（把方向 A+B+D 的基础合并）
**目标**
- 建立可回放 trace / session 数据集
- 建立离线评估与回归基线
- 补齐关键性能指标（帧耗时、推理耗时、丢帧率、事件命中率）
- 为后续引入 YOLO 做算力预算评估

**目标设备假设**
- 主要目标设备从 `Snapdragon 8 Gen 3` 起步，不再以 `S22` 作为性能上限
- 但调度设计仍然要保留降级能力，避免未来换机型或热降频后主链路失稳

**当前状态**
- [x] 已完成：`session trace / frame trace / dropped-frame` 观测能力
- [x] 已完成：域层离线 replay parser / report use case
- [x] 已完成：离线 replay 正式入口（session list page / report page / latest report / copy summary）
- [ ] 待完成：trace 导出 / 分享入口
- [ ] 待完成：真实场景回放数据基线
- [~] 进行中：性能预算提示（回放报告已显示 warning，运行时正式告警/面板未完成）

**包含方向**
- A：端侧性能专项（优先）
- B：感知稳定性专项（优先）
- D：自动驾驶式系统演进中的“日志/回放/评估基础设施”（优先）

**原因**
- 没有可回放与可评分基础设施，YOLO 融合只会变成“感觉更强”，而不是“可验证更强”
- 没有端侧性能预算，双模型会直接冲击实时性

### Phase 6：YOLO + DDRNet 双模型融合（核心模型升级）
**目标**
- 把 YOLO 检测能力接入现有 `COMBINED` 模式
- 引入异步调度 / 降频检测 / 跟踪补帧
- 做语义 + 检测 + 跟踪 + 决策融合
- 用回放评估对比“纯 DDRNet”与“双模型”收益

**建议架构**
- `DDRNet`：每帧低分辨率全局语义分割，负责可通行区域、道路/人行道、地面变化
- `YOLO`：低频或异步目标检测，负责人/车/静态障碍类别识别与精确框定位
- `Tracker`：在 YOLO 跳帧之间维持对象连续性
- ROI 精细分割：只对高风险或高不确定区域开启，不建议替代全局 DDRNet

### Phase 7：产品闭环与用户体验（把方向 C 与最终调优合并）
**目标**
- 把当前运行时开关升级为正式设置项
- 优化事件仲裁、播报节奏、去重与聚合策略
- 基于真实场景回放与外场试走进行调参
- 输出可交付的“默认配置 + 调试配置 + 实验配置”

**包含方向**
- C：产品体验专项
- A/B/D 的最终联调收尾

---

## 9.2 YOLO + DDRNet 方案判断

### 结论
**值得做，但必须作为“融合增强”，而不是把 DDRNet 改造成只做 YOLO ROI 分割的从属模型。**

### 推荐而不是推荐的点
**推荐：**
- 保留 `DDRNet` 的“全局场景语义”角色
- 让 `YOLO` 提供高价值目标类别与框级定位
- 用回放数据评估双模型是否真正提高障碍提示质量与道路风险识别

**不推荐：**
- 让 `DDRNet` 只在 YOLO ROI 上运行，完全放弃全局分割

**原因：**
- 你的 App 首先要解决的是“哪里能走、是不是进了路面、前方通路是否阻塞”，这本质上依赖全局语义结构
- YOLO 擅长“是什么”，DDRNet 擅长“哪里是可通行区域/地面结构”
- 如果只做 ROI 分割，会损失地面连续性、道路边界、宽度保持等全局上下文

### 更适合你的融合策略
1. `DDRNet` 每帧跑全局低分辨率语义分割
2. `YOLO` 每 2~3 帧跑一次，或独立异步线程跑检测
3. `ObstacleTracker` 在 YOLO 空档帧补预测
4. `DecideEventsUseCase` 融合：
   - 全局可通行语义
   - 框级障碍类别
   - 跟踪稳定性
   - 路面风险状态
5. 仅对“高风险/高不确定”区域做 ROI 级二次精化

---

## 9.3 新优先级排序（把 4 个方向和 YOLO 一起排）

### P1：必须先做
1. `D` 中的回放/评估基础设施
2. `A` 中的端侧性能预算与监测
3. `B` 中的回归测试与稳定性基线

### P2：随后做
4. YOLO + DDRNet 双模型融合

### P3：最后收口
5. `C` 中的产品体验与用户配置闭环

**一句话总结：先让系统“可测”，再让模型“变强”，最后让体验“变好”。**

---

## 10. 本轮已完成（本次提交）
- [x] `LiteRTSegmenter` 输出 mask 改为快照化，避免复用缓冲污染下游
- [x] `EventConflictResolver` 改为删除所有满足规则的 suppressed 事件
- [x] 新增 `EventConflictResolverTest`，覆盖 `BLOCKED + 多个障碍事件` 场景
- [x] `SceneAnalysisView` overlay 与 preview 统一 `contentScale`
- [x] `CameraViewModel` 分离 preview / analysis 分辨率（`1920x1080` vs `640x360`）
- [x] `BinaryMask.visualize()` 改为 `setPixels()` 批量写入
- [x] 移除未使用的 LiteRT Support 依赖与 ML Model Binding，解决构建冲突
- [x] `StartSceneAnalysisUseCase` 启动初始化改为幂等化
- [x] `StopSceneAnalysisUseCase` 分离 stop/reset 与 suspend release 语义
- [x] `SceneAnalysisViewModel` 在 stop/onCleared 中统一停止反馈并释放资源
- [x] `SpeechManager` 恢复字符串资源解析，不再播报原始 `messageKey`
- [x] 反馈链路按优先级事件恢复启用，且支持 UI 开关控制语音/震动
- [x] `ImageFrame` 改为平台无关 `IntArray` 像素缓冲，移除 `Bitmap` 对域层的侵入
- [x] `ObstacleExtractor` / `ConnectivityChecker` 改为 packed coordinate + primitive queue/list 热路径实现
- [x] Koin 的 domain 装配迁移到 `app/.../DomainBindingsModule.kt`
- [x] `StartSceneAnalysisUseCase` 已接入 `session trace / frame trace / session summary`
- [x] 新增 `TraceReplayParser` 与 `BuildTraceReplayReportUseCase`
- [x] `BuildTraceReplayReportUseCaseTest` 覆盖完整 trace 与缺失 summary 回退聚合
- [x] `TraceReplayService` + `FileTraceReplayService` 支持枚举 trace 会话与按 session 读取 JSONL
- [x] `SceneAnalysisViewModel` / `SceneAnalysisView` 已接入正式 replay 页面切换
- [x] 当前界面可进入 session list page、report page、加载 latest report、切换 session report、复制摘要
- [x] `EvaluateTraceReplayBudgetUseCase` 为 replay 报告补充预算 warning 判定
- [x] 验证通过：`:domain:test` 与 `:app:assembleDebug`
