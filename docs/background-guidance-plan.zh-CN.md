[English](background-guidance-plan.md) | **简体中文**

# 息屏继续导航方案（相机前台服务）

本文记录把相机的生命周期归属从 Activity 迁到前台服务、从而支持息屏继续导航的方案。**尚未实现**。与本文一同提交的无障碍改动解决的是"这个失效能被用户察觉"，本方案解决的是"这个失效不再发生"。

## 1. 为什么需要它

相机绑在 Activity 生命周期上（`camera/Camera.kt` 里的 `ProcessCameraProvider.bindToLifecycle`，由 `CameraView` 的 `LifecycleOwner` 驱动）。后果：

- 屏幕熄灭 → Activity `onStop` → 相机解绑 → 整条感知链路停止。
- 而这一切对盲人用户完全不可见。"没有提示"和"前方没有危险"是同一种体验。这是整个应用里最危险的失效模式。

已经落地、且**不会**被本方案取代的两道防线：

| 已落地 | 覆盖场景 | 位置 |
| --- | --- | --- |
| 运行期间 `keepScreenOn` | 屏幕自动超时（最常见） | `LiveAnalysisScreen` |
| 中断告警（震动 + 语音） | 电源键、来电、系统回收 | `SceneAnalysisViewModel.onGuidanceInterrupted` |

它们买不到的是**息屏继续跑**。这正是本方案要加的，收益是实打实的：功耗显著下降，以及手机可以揣进胸袋或挂在颈绳上，而不必全程举着。对一次走 30 分钟的用户，这两点都很重要。

## 2. 为什么当时刻意押后

这不是一个小改动，风险也不与代码行数成正比。它改的是**整条感知链路的生命周期归属**。`StartSceneAnalysisUseCase` / `StopSceneAnalysisUseCase` 里每一个加速器会话、模型句柄和 tracker reset 路径，目前都默认了"Activity 作用域"。把它单独排一轮，才能让这份回归面不和播报/文案改动混在一起验证。

## 3. 改动范围

### 3.1 新增 `GuidanceService`

放在 `camera` 模块（CameraX 本来就归它管）的 `LifecycleService`：

- 成为传给 `Camera.bind` 的 `LifecycleOwner`。
- 没有 UI 附着时**只绑 `ImageAnalysis`**。`Preview` 随 Activity 的出现/消失而附着与解除——息屏时没有任何 surface 可渲染，仍然绑着纯属浪费电。
- 用户开启辅助时 `startForegroundService`，停止辅助时结束。服务的生命周期跟随**辅助**，而不是 Activity。

### 3.2 Manifest

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />

<service
    android:name=".GuidanceService"
    android:exported="false"
    android:foregroundServiceType="camera" />
```

`FOREGROUND_SERVICE_CAMERA` 从 API 34 起必需。`foregroundServiceType="camera"` 才是允许后台访问相机的那一项；不声明它的话，Android 11+ 上即使有服务，帧也会停。

### 3.3 通知

常驻通知不只是合规要求，它本身就是一个真实的无障碍入口——手机揣在兜里时，它是唯一够得着的控件：

- 内容：当前辅助状态（运行中 / 已中断）。
- 动作：**停止辅助**。必须能在不打开应用的情况下生效。
- 渠道重要性用 `LOW`，别让它自己发声——应用本来就在说话。
- 动作按钮要有完整 `contentDescription`，保证 TalkBack 在通知栏里读得对。

### 3.4 Preview 的附着与解除

`CameraView` 不再负责绑相机，改为向运行中的服务**申请预览 surface**，并在 dispose 时释放。`CameraViewModel.bindToCamera` 拆成 `attachPreview` / `detachPreview`。

## 4. 需要盯的风险

- **重复绑定**。Activity 那条路径没清干净的话，CameraX 会有两处 bind 相互竞争。确认只剩一个 `bindToLifecycle` 调用点。
- **模型资源释放**。`StopSceneAnalysisUseCase.release()` 现在由 `ViewModel.onCleared` 驱动。迁到服务后必须改由服务销毁驱动，否则加速器会话会跨会话泄漏。
- **厂商后台限制**。小米/华为/OPPO 这类激进策略仍可能杀掉服务。已落地的中断告警就是最后一道兜底，服务上线后**不要**把它删掉。
- **续航**。息屏跑视觉模型 30 分钟以上的功耗与发热还没实测过。默认开启之前先测；必要时准备降频/间歇运行的退路。

## 5. 验收标准

1. 息屏、手机放在口袋里，辅助持续播报 ≥ 10 分钟。
2. 从通知栏点"停止辅助"能结束服务并释放相机。
3. 转屏不会重启链路，也不会重复播报任何内容。
4. 从最近任务划掉应用后，服务结束，相机与加速器会话都被释放。
5. 息屏录制的 trace 会话，丢帧率相对亮屏无退化。
6. 服务被外部杀掉时，已落地的中断告警仍然触发。

## 6. 推荐拆分顺序

按"最小可评审步骤优先"拆：

1. 服务持有相机；preview 附着/解除；通知栏带停止按钮。**完全不动感知链路本身。**
2. 把资源释放从 `ViewModel.onCleared` 迁到服务销毁。
3. 实测息屏功耗与发热，再决定息屏运行是默认行为还是一个可选开关。
