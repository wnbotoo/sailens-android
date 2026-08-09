**English** | [简体中文](background-guidance-plan.zh-CN.md)

# Background guidance plan (camera foreground service)

This records the plan for keeping guidance alive while the screen is off, by moving camera
ownership from the Activity into a foreground service. It is **not implemented yet**. The
accessibility work that shipped alongside this document covers the failure being *detected*; this
plan covers the failure being *prevented*.

## 1. Why this exists

The camera binds to the Activity lifecycle (`ProcessCameraProvider.bindToLifecycle` in
`camera/Camera.kt`, driven by `CameraView`'s `LifecycleOwner`). Consequences:

- Screen off → Activity `onStop` → camera unbinds → the whole perception pipeline stops.
- Nothing about that is observable to a blind user. "No announcements" and "nothing ahead is
  dangerous" are the same experience. This is the single most dangerous failure mode in the app.

Two mitigations already shipped and are **not** superseded by this plan:

| Shipped | Covers | Location |
| --- | --- | --- |
| `keepScreenOn` while running | Screen timeout, the common case | `LiveAnalysisScreen` |
| Interruption alert (vibration + speech) | Power button, incoming call, system reclaim | `SceneAnalysisViewModel.onGuidanceInterrupted` |

What those do **not** buy: running with the screen off. That is what this plan adds, and the wins
are real — significantly lower power draw, and the ability to put the phone in a shirt pocket or
lanyard instead of holding it up continuously. For a user walking for 30 minutes, both matter.

## 2. Why it was deliberately deferred

This is not a small change, and the risk is not proportional to the line count. It moves **who owns
the lifecycle of the entire perception pipeline**. Every accelerator session, model handle, and
tracker reset path in `StartSceneAnalysisUseCase` / `StopSceneAnalysisUseCase` currently assumes
Activity-scoped ownership. Sequencing it as its own change keeps that regression surface separate
from the announcement/copy work it would otherwise be bundled with.

## 3. Scope

### 3.1 New: `GuidanceService`

A `LifecycleService` in the `camera` module (it is the module that already owns CameraX):

- Becomes the `LifecycleOwner` passed to `Camera.bind`.
- Binds **only `ImageAnalysis`** when no UI is attached. `Preview` is attached and detached as the
  Activity comes and goes — there is no surface to render into with the screen off, and binding it
  anyway wastes power.
- Started with `startForegroundService` when the user starts guidance; stopped when they stop it.
  Service lifetime tracks *guidance*, not the Activity.

### 3.2 Manifest

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />

<service
    android:name=".GuidanceService"
    android:exported="false"
    android:foregroundServiceType="camera" />
```

`FOREGROUND_SERVICE_CAMERA` is required from API 34. `foregroundServiceType="camera"` is what
permits camera access while backgrounded; without it the frames stop on Android 11+ regardless of
the service.

### 3.3 Notification

An ongoing notification is mandatory, and it is also a genuine accessibility surface — for a phone
in a pocket it is the only reachable control:

- Content: current guidance state (running / interrupted).
- Action: **Stop guidance**. This must work without opening the app.
- Channel importance `LOW` so it does not make a sound of its own — the app already speaks.
- Full `contentDescription` on the action so TalkBack reads it correctly from the shade.

### 3.4 Preview attach / detach

`CameraView` stops being the thing that binds the camera. Instead it *requests a preview surface*
from the running service and releases it on dispose. `CameraViewModel.bindToCamera` becomes
`attachPreview` / `detachPreview`.

## 4. Risks to watch

- **Double binding.** If the Activity path is not fully removed, CameraX will have two bind calls
  racing. Verify only one `bindToLifecycle` call site remains.
- **Model resource release.** `StopSceneAnalysisUseCase.release()` is currently driven by
  `ViewModel.onCleared`. Under a service it must be driven by service destruction instead, or
  accelerator sessions leak across guidance sessions.
- **OEM background restrictions.** Aggressive vendors (Xiaomi, Huawei, Oppo) may still kill the
  service. The interruption alert already shipped is the backstop and must keep working — do not
  remove it once the service lands.
- **Battery.** Running vision models with the screen off for 30+ minutes is a thermal/power profile
  that has not been measured. Measure before enabling by default; consider a duty-cycle fallback.

## 5. Acceptance criteria

1. Guidance keeps announcing with the screen off, phone in a pocket, for ≥ 10 minutes.
2. Pressing "Stop guidance" from the notification stops the service and releases the camera.
3. Rotating the device does not restart the pipeline or re-announce anything.
4. Killing the app from Recents stops the service and releases the camera and accelerator sessions.
5. A trace session recorded with the screen off shows no dropped-frame regression versus screen-on.
6. If the service is killed externally, the shipped interruption alert still fires.

## 6. Sequencing

Recommended split, smallest reviewable step first:

1. Service owns the camera; preview attach/detach; notification with a stop action. **No changes to
   the perception pipeline itself.**
2. Move resource release from `ViewModel.onCleared` to service destruction.
3. Measure power/thermals with the screen off; decide whether screen-off running is the default or
   an opt-in setting.
