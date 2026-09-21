**English** | [简体中文](npu-litert-qnn.zh-CN.md)

# LiteRT Qualcomm NPU (HTP) — call chain, runtime delivery, and troubleshooting

This document covers the **Qualcomm NPU (Hexagon HTP)** path in Sailens: how it is invoked, how the
runtime libraries are delivered, how to install them locally, how to diagnose problems, and the case
studies collected getting there. For the model contract itself see [models.md](models.md).

---

## 0. Scope

This only records how the Qualcomm NPU runtime is wired, delivered, and diagnosed. Model assets and
the GPU/NPU split are still under evaluation; `SailensRuntimeProfile.kt` is the source of truth for
what actually runs where.
The runtime `.so` files are **not in git** (large, re-fetchable); rebuild them after cloning per
[§3](#3-local-build-and-install).

---

## 1. Call chain (how the NPU is invoked)

```
SailensRuntimeProfile (a model configured with acceleratorBackend = NPU)
  └─ LiteRtObstacleProvider.initialize() → initializeSession()   [obstacle/LiteRtObstacleProvider.kt]
       │  (sem goes through LiteRtSemanticSegmentationModel → LiteRTSegmenter, same structure)
       └─ LiteRtSessionFactory.create(sourceResolver, AcceleratorSelection)   [ml/session/]
            └─ AcceleratorSelector.select(...)  ← EXPLICIT/PREFER/FIRST + fallback + trace label
                 └─ (per-accelerator attempt, resolve its ModelSource, fall through on
                     createOutputBuffers failure)
                      └─ LiteRtCompiledModelFactory.create(...)  [ml/LiteRtCompiledModelFactory.kt]
                           ├─ createNpuEnvironment(context)
                           │    ├─ ensureNpuRuntimeDownloaded() → downloadLibrary() (no-op at
                           │    │   install-time)
                           │    ├─ check(isLibraryReady())
                           │    └─ requireQualcommDispatchLibrary()  ← fail fast if dispatch .so
                           │                                           is missing
                           ├─ buildOptions(NPU) → CompiledModel.Options(NPU) + QualcommOptions
                           └─ CompiledModel.create(...)
                                ├─ (JIT) libLiteRtCompilerPlugin_Qualcomm.so compiles the .tflite
                                │   into an HTP graph
                                └─ tflite: Replacing N/N node(s) with delegate (DispatchDelegate)
                                    → HTP
       → LiteRtSession { run(), close() }; the provider only binds tensor indices and does pre/post
```

Key points:

- **Two layers of abstraction**: `ml/session/` is the **accelerator-agnostic** runtime layer —
  `AcceleratorSelector` (selection + fallback + trace label), `LiteRtSession` (CompiledModel + IO
  buffers + `run()`/`close()`), and `LiteRtSessionFactory` (which glues them). sem and det are left
  with only pre/post and tensor binding. **A new CNN reuses this directly; a future VLM uses the
  same `AcceleratorSelector.select(...)` with `build` swapped for LlmInference, without rewriting
  fallback.**
- **Model files are resolved per actual accelerator attempt**: `ModelSourceResolver` /
  `ModelCatalog` map `(ModelType, Accelerator)` to a `ModelSource.Asset` or `ModelSource.File`;
  falling back to another accelerator re-selects the corresponding model file, and the provider
  reads tensor metadata from the source finally chosen.
- **`acceleratorBackend`** is specified per model in `SailensRuntimeProfile.kt` (sem/det/VLM each
  have their own). `acceleratorSelectionMode` defaults to `EXPLICIT` — a named backend that fails,
  fails; there is no silent fallback, which makes problems locatable.
- **`accelerator=NPU` as a label ≠ actually on the HTP.** It only means "NPU accelerator was
  requested and initialized". Proof of really being on HTP is the `DispatchDelegate` line in
  [§4](#4-diagnosis).
- This uses LiteRT's **`CompiledModel` + `Accelerator`** API, **not** the TFLite `Interpreter` —
  litert's bundled Interpreter dropped the delegate API, so QnnDelegate cannot be attached.

---

## 2. Runtime delivery model

Newer LiteRT **no longer packs the NPU runtime into the AAR** (`litert-2.1.5.aar` only carries
`libLiteRt.so` + the GL accelerator). The runtime comes from the official
`litert_npu_runtime_libraries(.zip / _jit.zip)`, wired in as a **dynamic feature module** and
delivered per device by **Play Feature Delivery**.

Two sets of `.so`, do not mix them up:

| Library | Purpose | Which package |
|---|---|---|
| `libLiteRtDispatch_Qualcomm.so` | **Executes** a compiled HTP graph | AOT and JIT both |
| `libLiteRtCompilerPlugin_Qualcomm.so` | **Compiles** a `.tflite` into an HTP graph | **JIT only** |
| `libQnnHtp.so` / `libQnnSystem.so` / `libQnnHtpV81Skel/Stub.so` | QNN HTP runtime (v81 = SM8850) | fetched from the QAIRT SDK by the fetch script |
| `libQnnHtpPrepare.so` (~85 MB) | On-device graph preparation (for JIT compilation) | JIT only |

**JIT vs AOT:**

- **JIT** (`_jit.zip`): carries the compiler plugin; the model compiles on-device on first run. The
  first frame is slow (see [case 5](#case-5-jit-compilation-is-slow-and-looks-like-a-hang)), and
  **2.1.5 has no compile cache, so it recompiles every launch**. Good for debug.
- **AOT** (`.zip`): dispatch-only; requires the model to be **offline** pre-compiled into a QNN
  context with O3, giving zero compilation at runtime (fastest, no first-frame stall). Good for
  release. (Planned.)

**Module delivery**: the vendor module's manifest is `dist:install-time` + condition
`device-group "Qualcomm_SM8850"` + `dist:fusing include="true"`. That is, Play installs the matching
generation's runtime at install time, by device group. `downloadLibrary()` is the on-demand path and
is essentially a no-op for install-time (`isLibraryReady()` returns true once installed).

---

## 3. Local build and install

### Wiring (auto-detected when the directory exists)

- `settings.gradle.kts`: only includes the runtime feature module if
  `litert_npu_runtime_libraries_jit/` exists at the project root and the module has a
  `build.gradle.kts`.
- `app/build.gradle.kts`:
  - adds `dynamicFeatures` only for runtime feature modules that exist locally
  - adds `implementation(project(":litert_npu_runtime_libraries_jit:runtime_strings"))` only when
    the `runtime_strings` module exists (it supplies the `dist:title` string; without it the bundle
    fails with `title_... not found`)
  - `implementation(libs.google.play.feature.delivery(-ktx))` (SplitInstall for downloadLibrary)
  - `packaging { jniLibs { useLegacyPackaging = true } }` (**required** — see
    [case 3](#case-3-extractnativelibsfalse--dispatch-library-cannot-be-loaded))

### Rebuilding the vendor folder (the `.so` are not in git)

1. Download the **`litert_npu_runtime_libraries_jit.zip`** matching your litert version (GitHub
   release) and extract it to the **project root**, producing `litert_npu_runtime_libraries_jit/`.
2. Run `./litert_npu_runtime_libraries_jit/fetch_qualcomm_library_jit.sh` to pull the QNN runtime
   (QAIRT SDK).
3. **Patch minSdk**: set `minSdk` in `qualcomm_runtime_v81/build.gradle.kts` and
   `runtime_strings/build.gradle.kts` to **= the base app's minSdk (34)** — the zip ships 31, and
   bundletool requires ≥ base (see [case 6](#case-6-bundletool-reports-minsdk--base)).

> For AOT, use `litert_npu_runtime_libraries.zip` (not _jit) + `fetch_qualcomm_library.sh`, and drop
> `_jit` from the gradle paths.

### Installing on a device (locally)

Feature modules **are not in the base APK** (the APK from `assembleDebug` has no runtime `.so`).
Locally, use a **universal APK** (ignores delivery conditions, fuses everything):

```powershell
adb uninstall com.sailens          # a clean reinstall is needed after changing packaging
./gradlew :app:bundleDebug
bundletool build-apks --mode=universal --bundle=app/build/outputs/bundle/debug/app-debug.aab --output=app-universal.apks
bundletool install-apks --apks=app-universal.apks
```

- Android Studio's Run button works too, but you must tick `qualcomm_runtime_v81` under
  **Run/Debug Configurations → "Dynamic features to deploy"** (otherwise only the base installs,
  with no runtime).
- Production: create a `Qualcomm_SM8850` device group in Play Console, upload the AAB to internal
  testing, and installing via Play brings the runtime in at install time.

---

## 4. Diagnosis

### QNN diagnostic marker (detailed profiling/dump)

```powershell
adb shell run-as com.sailens touch files/litert_npu_diagnostics.enabled
adb shell am force-stop com.sailens
# after running for a few seconds:
adb exec-out run-as com.sailens tar -C files -cf - litert_npu_diagnostics > litert_npu_diag.tar
adb shell run-as com.sailens rm files/litert_npu_diagnostics.enabled   # turn it off when
                                                                              # done; DEBUG+OPTRACE
                                                                              # is very slow
```

With this on, the next NPU `CompiledModel` creation uses `LogLevel.DEBUG` + `Profiling.OPTRACE` and
dumps IR JSON / DLC to `files/litert_npu_diagnostics/`.

### Reading logcat (fastest qualitative check)

| What you see | What it means |
|---|---|
| `Replacing N/N ... DispatchDelegate` | ✅ **whole graph on HTP** (QNN compiled it into one context node) |
| `Replacing N/M ... LITERT_CL` | ⚠️ on the **GPU** (OpenCL) |
| `Replacing N/M ... TfLiteXNNPackDelegate` | ⚠️ on the **CPU** (XNNPACK) |
| `No dispatch library found in .../lib/arm64` | ❌ dispatch library not extracted/installed (see case 2/3) |
| `Failed to apply compiler plugins: No compiler plugin found` | ❌ JIT compiler plugin missing (see case 4) |
| `1 compiler plugins were applied successfully` | ✅ JIT compilation succeeded |
| non-empty `dlc/` dump | ✅ the HTP graph was compiled |

### Debug decision tree (symptom → where to look, what to change)

1. **App reports `dispatch library ... not found` (the guard threw)** → the runtime is not installed:
   the base APK does not contain feature modules, so install the universal APK or tick "Dynamic
   features to deploy" in AS; and confirm `useLegacyPackaging = true` (case 3).
2. **`accelerator=NPU` but slow, det shows `LITERT_CL` (GPU) or `TfLiteXNNPackDelegate` (CPU)** →
   not really on HTP:
   - logcat has `No compiler plugin found` → use the **JIT package** (which has the compiler plugin)
     (case 4).
   - logcat has `No dispatch library` → same as #1.
3. **Init hangs for ages and looks dead (no SIGABRT/crash)** → JIT first-frame compilation is slow:
   turn diagnostics off, wait 20–30 s; or drop `optimizationLevel` to `PREPARE`; production uses AOT
   (case 5).
4. **`createOutputBuffers` crashes (`litert_compiled_model.h:1656/1620/495`)** → some int8 op fails
   CPU/GPU prepare. The retired seg model's INT8 `TransposeConv` is the classic case; the current
   realtime chain only has sem/det (case 1).
5. **Latency numbers do not add up (GPU looks much faster than NPU)** → the GPU async-enqueue trap;
   you must sum `infer + read` (case 7).
6. **You want to know which ops land on HTP vs CPU** → enable the diagnostic marker and read
   OPTRACE / `dlc/`; on the host, use the scripts below to inspect op structure and find
   SoC-unfriendly ops.

### Model structure scripts (host side)

- `scripts/inspect_tflite.py <model>` — op histogram + I/O quantization (finds ops the SoC will not
  take).
- `scripts/trace_tflite.py <model> OP1,OP2` — trace nodes by op type (inputs/outputs/quantization/
  constness).
- Dependencies: `python -m pip install tflite numpy`.

---

## 5. Case studies (things we got wrong)

### case 1: retired seg INT8 TransposeConv → createOutputBuffers crash

Early on (before NPU worked), a fully integer-quantized **instance segmentation** model crashed
`createOutputBuffers` on `CompiledModel`'s **CPU / GPU prepare** path
(`litert_compiled_model.h:1656/1620/495`), plus `Node N (TfLiteXNNPackDelegate) failed to prepare`
on CPU. **Root cause: an INT8 `TRANSPOSE_CONV` in the segmentation prototype head** (2× upsampling),
an op unique to that graph and absent from det/sem. The classic TFLite Interpreter falls back
gracefully; LiteRT CompiledModel's accelerator-prepare does not — it hard fails.
If the QNN compiler can take the whole graph, `TransposeConv` compiles into the HTP graph and the
CPU/GPU prepare crash never happens.
**Current status**: seg / refinement / provider / prototype mask decode are all retired; this
survives only as a historical LiteRT troubleshooting case. `ObstacleOcclusionAnalyzer` now
cross-validates det tracked-box ground-contact bands against the sem passable mask and never reads a
prototype tensor.

### case 2: missing dispatch library → silent CPU fallback (fake NPU)

`accelerator=NPU` but slow (~30 ms); logcat shows `No dispatch library found` +
`Replacing 393/401 ... TfLiteXNNPackDelegate`. A plain `adb install` package neither bundles the
runtime nor gets it from Play.
**Fix**: wire in the dynamic feature runtime module (see §2/§3). **And a guard was added**:
`requireQualcommDispatchLibrary()` checks for `libLiteRtDispatch_Qualcomm.so` when creating the NPU
env, and **fails init explicitly** rather than succeeding falsely.

### case 3: `extractNativeLibs=false` → dispatch library cannot be loaded

The runtime module installed, but LiteRT dlopens
`…/lib/arm64/libLiteRtDispatch_Qualcomm.so` by **filesystem path**, while AGP defaults to
`extractNativeLibs=false` (libraries stay compressed in the APK and `lib/arm64` is empty; logcat
shows load paths like `base.apk!/lib/...`) → load fails, guard throws.
**Fix**: add `packaging { jniLibs { useLegacyPackaging = true } }` to `app` (the official
`/next/npu` docs say the same).

### case 4: AOT vs JIT — missing compiler plugin → GPU fallback

With dispatch installed, the log said `Failed to apply compiler plugins: No compiler plugin found`,
det landed on `LITERT_CL` (GPU), and `dlc/` was empty. Root cause: using the **AOT package** (no
compiler plugin) while feeding it an un-precompiled `.tflite`.
**Fix**: switch to the **JIT package** (`_jit.zip`, with `libLiteRtCompilerPlugin_Qualcomm.so` +
`libQnnHtpPrepare.so`), which can compile the graph to HTP at runtime.

### case 5: JIT compilation is slow and looks like a hang

After the compiler plugin loads, QNN validates op by op (`Validating ... INT8`, fast), and then the
**app process goes silent for several seconds** (HTP graph finalize runs on the cDSP and does not
log per line) — this is **not a crash** (no SIGABRT). It is first-compile cost, amplified further by
`O3` + diagnostic DEBUG/OPTRACE.
**Fix**: for debug use `HTP_OPTIMIZE_FOR_PREPARE` (fastest compile), **do not enable the diagnostic
marker** while testing, and **wait 20–30 s** after launch for the first frame to finish compiling.
`PREPARE` optimizes inference less; production uses **AOT** (offline O3) to remove it entirely.
Three OptimizationLevels: `PREPARE` (fastest compile) < `INFERENCE` < `INFERENCE_O3` (fastest
inference).

### case 6: bundletool reports minSdk < base

`Modules cannot have a minSdkVersion lower than the base module`. The zip's modules ship
minSdk=31; the base is 34. **Fix**: set the vendor modules' minSdk ≥ base. (And `title_... not
found` → `app` must have `implementation(project(":...:runtime_strings"))`.)

### case 7 (measurement trap): the GPU's "1 ms" is fake

A GPU (OpenCL) `run()` **enqueues asynchronously** and returns in ~1 ms; the real computation is
settled when you **read the output back**. So "sem is 1 ms on the GPU" is not real latency — the
real cost hides in `outputReadTimeMs` (~14–30 ms). When comparing, sum `infer + read`; do not put
the GPU's async enqueue time next to a synchronous backend's number.

---

## 6. Verification checklist (confirm you are really on HTP)

After installing (do it with diagnostics off first, and give the first frame time to compile):

1. logcat has `Obstacle model initialized with NPU`
2. det's line is `Replacing N/N ... DispatchDelegate` (**not** LITERT_CL / TfLiteXNNPackDelegate)
3. no `No dispatch library` / no `No compiler plugin found`
4. `obstacle runtime tensors ... accelerator=NPU` (det actually ran)
5. (with diagnostics on) `files/litert_npu_diagnostics/dlc/` is non-empty
6. read real latency from the app's debug panel `obsMs` (logcat does not print per-frame ms)

---

## 7. Production release: AOT + AI Pack + Feature Delivery

This section is "what to do at release time". Read [§7.1 architecture](#71-architecture-two-independent-delivery-lines)
first, then follow [§7.5 checklist](#75-release-checklist).

> **Sailens today vs the production target (know the gap)**
> - **Today (debug works)**: models live in `app/src/main/assets/` (ride along with the APK, no
>   delivery); the runtime is the **JIT** dynamic feature (`litert_npu_runtime_libraries_jit`);
>   local install is `bundletool --mode=universal`; there is **no**
>   `device_targeting_configuration.xml` and **no** AI Pack.
> - **Production target**: models are **AOT precompiled → AI Pack** (Play delivers the right one per
>   device); the runtime is **dispatch-only** Feature Delivery
>   (`litert_npu_runtime_libraries`, not _jit) + **device targeting**; installation goes through Play
>   (internal/production). §7.2–7.4 below are how you move from today to the target.

### 7.1 Architecture: two independent delivery lines

| What is delivered | How it is packaged | How it is delivered |
|---|---|---|
| **Models** (AOT compile output / or .tflite) | AI Pack (`com.android.ai-pack`, `assetPacks`) | Play AI Pack (install-time / fast-follow / on-demand) |
| **Runtime libraries** (dispatch + QNN HTP) | dynamic feature (`com.android.dynamic-feature`, `dynamicFeatures`) | Play Feature Delivery (install-time, conditioned on device group) |

Both lines rely on **device targeting** (`device_targeting_configuration.xml` + device groups) so
Play sends each device the right artifact (right HTP version + right compile output).

### 7.2 AOT model compilation (offline, Python)

Purpose: precompile the `.tflite` with O3 into a per-SoC QNN context, so there is **zero compilation
at runtime** (removes the ~11 s first frame and every relaunch's recompile — see
[case 5](#case-5-jit-compilation-is-slow-and-looks-like-a-hang)).

Official tutorial (follow it):
**[LiteRT AOT Compilation Colab](https://github.com/google-ai-edge/litert-samples/blob/main/compiled_model_api/colab/LiteRT_AOT_Compilation_Tutorial.ipynb)**.
Core API (`ai_edge_litert.aot`):

```python
from ai_edge_litert.aot import aot_compile as aot_lib
from ai_edge_litert.aot.vendors.qualcomm import target as qnn_target
from ai_edge_litert.aot.ai_pack import export_lib as ai_pack_export

# 1) compile for the target SoC (multiple targets at once: 8 Elite Gen5 = SM8850, plus v79/v75...)
compiled_models = aot_lib.aot_compile(
    tflite_model_path,
    target=qnn_target.Target(soc_model="SM8850"),   # pass a target list for multiple SoCs
)

# 2) export as an AI Pack (for §7.3 packaging/delivery)
ai_pack_export.export(compiled_models, ai_pack_dir, ai_pack_name="my_model", litert_model_name="my_model.tflite")
```

Notes:

- **AOT supports partial delegation**: subgraphs the HTP cannot take fall to CPU/GPU automatically
  (same idea as `Options(NPU,GPU)`).
- One compile run can cover several Snapdragon generations (SM8850/8750/...), exported into the same
  AI Pack; Play sends each device its match.
- Output goes into the AI Pack; it also generates `device_targeting_configuration.xml` (needed in
  §7.4).

### 7.3 Delivering models with an AI Pack

The directory produced by `ai_edge_litert.aot.ai_pack.export` *is* an AI Pack module:

```
my_app/
  ai_packs/
    my_model/        # build.gradle.kts: plugins { id("com.android.ai-pack") }
    my_model_mtk/    #                    aiPack { packName="my_model"; dynamicDelivery { deliveryType="on-demand" } }
```

Wiring:

```kotlin
// settings.gradle.kts
include(":ai_packs:my_model")

// app/build.gradle.kts → android { }
assetPacks.add(":ai_packs:my_model")
```

Fetching the model at runtime (`AiPackModelProvider` + `ModelSelector`, choosing the right compile
output for the device's capabilities):

```kotlin
val qualcommNpuModelProvider = AiPackModelProvider(context, "my_model", "model/my_model.tflite") {
    buildSet {
        if (accelerator == Accelerator.NPU && NpuCompatibilityChecker.Qualcomm.isDeviceSupported())
            add(Accelerator.NPU)
    }
}
val model = ModelSelector(cpuGpuModelProvider, qualcommNpuModelProvider, /* ... */).selectModel(env)
val compiledModel = CompiledModel.create(model.getPath(), CompiledModel.Options(model.getCompatibleAccelerators()), env)
```

`deliveryType`: `install-time` (ships with install) / `fast-follow` (background fetch right after
install) / `on-demand` (fetch on use). For a blind-navigation app's realtime models, prefer
**install-time** (usable the moment the app opens).

> When wiring this into Sailens, replace `ModelSourceResolver`: the default implementation returns
> `ModelSource.Asset`, while an AI Pack implementation can return
> `ModelSource.File(AiPackModelProvider...getPath())`. `AcceleratorSelector`'s policy can converge
> with `ModelSelector` (both are "pick a backend/model per device") — exactly the seam
> [§1's abstraction](#1-call-chain-how-the-npu-is-invoked) was left for.

### 7.4 Feature Delivery runtime + device targeting

Runtime libraries (dispatch + QNN) use the **dispatch-only** package
(`litert_npu_runtime_libraries`, **not** _jit; AOT needs neither the compiler plugin nor that 85 MB
prepare library). Wiring is as in [§3](#3-local-build-and-install), but the **app build.gradle adds
device targeting**:

```kotlin
// app/build.gradle.kts → android { }
bundle {
    deviceTargetingConfig = file("device_targeting_configuration.xml")   // copied from the generated AI Pack
    deviceGroup {
        enableSplit = true        // split by device group; each device only gets its generation's runtime
        defaultGroup = "other"
    }
}
```

- `device_targeting_configuration.xml` defines the device groups (e.g. `Qualcomm_SM8850` → which
  SoCs); the vendor module manifest's `device-group` condition refers to it. **This is the real fix
  for the "bundletool: device-group not defined" warning** — the local universal APK sidesteps it,
  but production must have it.
- Device groups can also be created/managed in **Play Console** (by SoC, RAM, etc.), either instead
  of or alongside the XML.

### 7.5 Release checklist

1. [ ] AOT compile the models (§7.2) for every SoC you support, exported to an AI Pack.
2. [ ] Wire the AI Pack (§7.3): `ai_packs/` module + `assetPacks.add(...)` + settings include.
3. [ ] Switch the runtime to **dispatch-only** (`litert_npu_runtime_libraries`, not _jit);
       `dynamicFeatures` + `runtime_strings` dependency + each module's `minSdk = base`.
4. [ ] `packaging { jniLibs { useLegacyPackaging = true } }` (otherwise the dispatch library cannot
       be dlopened — see [case 3](#case-3-extractnativelibsfalse--dispatch-library-cannot-be-loaded)).
5. [ ] `bundle { deviceTargetingConfig=...; deviceGroup { enableSplit=true } }` + copy in
       `device_targeting_configuration.xml` (§7.4).
6. [ ] Code: provide an AI Pack `ModelSourceResolver` returning
       `ModelSource.File(AiPackModelProvider.getPath())`; use `model.getCompatibleAccelerators()`
       for Options.
7. [ ] `./gradlew :app:bundleRelease` → upload the AAB to **Play Console internal testing**.
8. [ ] On a target device (installed via Play), confirm `DispatchDelegate`, non-empty `dlc/`, and no
       `No dispatch/compiler` errors per [§6](#6-verification-checklist-confirm-you-are-really-on-htp).
9. [ ] Measure steady-state latency with diagnostics off (AOT should have no first-frame stall, and
       inference close to AI Hub numbers).
10. [ ] R8/release smoke test: JNI binds by name and LiteRT reflection only shows up at runtime (see
        AGENTS.md).

> The Play Console side (uploading the AAB, internal testing track, device group UI, staged rollout)
> is Play process — see the [PODAI docs](https://developer.android.com/google/play/on-device-ai) and
> [Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery).

---

## 8. TODO / next

- **Split by build type**: debug→JIT (`_jit` module + `PREPARE`), release→AOT (dispatch-only + AI
  Pack + O3), switching `dynamicFeatures` / `optimizationLevel` / asset source per buildType.
- **Move models from assets to an AI Pack** (§7.3), replacing `ModelSourceResolver` so the same
  session creation path loads a `ModelSource.File`.
- **VLM on NPU**: the NPU's real home (large models), for on-device scene description / VQA; reuses
  the `AcceleratorSelector` layer + runtime delivery infrastructure, coexisting with the realtime
  vision pipeline (additive, not a replacement).
- **Make the `accelerator=NPU` label trustworthy**: today it only reflects the *request* and does
  not prove HTP; feed whether `DispatchDelegate` landed back into the trace.
- **Inference pipeline optimization**: obstacle output on a zero-copy handle (matching sem),
  float→int8 quantization pushed down to native, and an `InferencePipeline` layer to funnel
  pre/run/read/post plus timing.

---

## 9. VLM engine (scene description)

On-demand "describe what is in front of me" is a **separate**, low-frequency path from the realtime
sem/det chain (seconds, not per frame). The VLM does not use `CompiledModel`/`run()` (it needs
tokenization, image encoding, and autoregressive decoding), so it has its own runtime — but it
**reuses the same accelerator selection policy** (`AcceleratorSelector`).

App-side trigger policy, ASR choice, shortcut entry points, and further code organization are in
[vlm-asr-assistant-plan.md](vlm-asr-assistant-plan.md).

### Architecture (skeleton landed, compiles)

```
domain: SceneDescriber (initialize / describe(frame, prompt?) / release)   ← app depends only on
                                                                             this, no litert
  └─ data/.../ml/vlm/LiteRtVlmEngine : SceneDescriber
       ├─ AcceleratorSelector.select(...) { accel -> runtimeFactory.create(...) }   ← reuses
       │                                                                              selection +
       │                                                                              fallback
       └─ VlmRuntime(seam): generate(prompt, image) → String   ← isolates the LLM library call; the
            │                                                     engine never depends on GenAI
            │                                                     directly
            └─ UnavailableVlmRuntimeFactory (default): isReady=false, initialize() throws → the app
                                                       hides the "describe" entry point
```

The default `UnavailableVlmRuntimeFactory` lets the engine be **gracefully unavailable when no model
is wired**, without affecting build or runtime. Wiring a VLM = providing a real `VlmRuntimeFactory`
+ a model bundle, injected into `LiteRtVlmEngine`.

### A MediaPipe LLM Inference adapter (works as written)

```kotlin
// app/build.gradle.kts: implementation("com.google.mediapipe:tasks-genai:0.10.27")
import com.google.mediapipe.tasks.genai.llminference.*
import com.google.mediapipe.framework.image.BitmapImageBuilder

class MediaPipeVlmRuntimeFactory : VlmRuntimeFactory {
    override fun isAvailable(context: Context) =
        runCatching { Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference"); true }.getOrDefault(false)

    override fun create(context: Context, config: VlmModelConfig, accelerator: Accelerator): VlmRuntime {
        // ⚠️ MediaPipe tasks-genai backends are currently CPU/GPU. Getting a VLM truly onto the NPU
        // means LiteRT-LM / Qualcomm Genie (still evolving). Here NPU is mapped to GPU for now; or
        // just throw and let AcceleratorSelector fall back to GPU/CPU.
        val backend = if (accelerator == Accelerator.CPU) LlmInference.Backend.CPU else LlmInference.Backend.GPU
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(config.modelPath)
            .setMaxTokens(config.maxTokens)
            .setMaxNumImages(1)
            .setPreferredBackend(backend)
            .build()
        return MediaPipeVlmRuntime(LlmInference.createFromOptions(context, options), config)
    }
}

private class MediaPipeVlmRuntime(
    private val llm: LlmInference,
    private val config: VlmModelConfig,
) : VlmRuntime {
    override fun generate(prompt: String, image: ImageFrame?): String {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(config.topK)
            .setTemperature(config.temperature)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(image != null).build())
            .build()
        LlmInferenceSession.createFromOptions(llm, sessionOptions).use { session ->
            session.addQueryChunk(prompt)
            if (image != null) session.addImage(BitmapImageBuilder(image.toBitmap()).build())  // needs YUV→Bitmap
            return session.generateResponse()  // use generateResponseAsync(listener) for streaming
        }
    }
    override fun close() = llm.close()
}
```

- `image.toBitmap()` needs a YUV_420_888 → `Bitmap` conversion you write (reuse the native
  preprocessing path or `YuvImage`).
- The model bundle (`.task`) is delivered via [§7.3 AI Pack](#73-delivering-models-with-an-ai-pack);
  set `config.modelPath` from `AiPackModelProvider.getPath()`.
- For a VLM genuinely on the NPU, use **LiteRT-LM** (`com.google.ai.edge.litert:litert-lm`,
  Qualcomm Genie backend) and write another `VlmRuntimeFactory` — the engine and domain do not
  change.
- **The VLM is additive, not a replacement** for realtime obstacle avoidance: realtime stays on
  sem/det, and the VLM only runs when the user asks.

---

## References

- LiteRT NPU docs: https://developers.google.com/edge/litert/next/npu
- Qualcomm NPU performance blog: https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/
- AOT compilation Colab: https://github.com/google-ai-edge/litert-samples/blob/main/compiled_model_api/colab/LiteRT_AOT_Compilation_Tutorial.ipynb
- Play On-device AI (PODAI): https://developer.android.com/google/play/on-device-ai
- Play Feature Delivery: https://developer.android.com/guide/playcore/feature-delivery
- MediaPipe LLM Inference (VLM, Android): https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
