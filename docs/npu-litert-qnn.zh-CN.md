[English](npu-litert-qnn.md) | **简体中文**

# LiteRT Qualcomm NPU (HTP) — 调用链路、运行时交付与排查手册

本文档专门讲清楚 Sailens 里 **Qualcomm NPU(Hexagon HTP)** 这条路:怎么被调用、运行时库怎么交付、本地怎么装、怎么诊断,以及一路踩过的坑(case study)。模型本身的契约/选型见 [models.zh-CN.md](models.zh-CN.md)。

---

## 0. 范围

本文只记录 Qualcomm NPU runtime 的接线、交付和诊断方法。当前模型资产和 GPU/NPU 组合仍在评估中，实际运行配置以 `SailensRuntimeProfile.kt` 为准。
运行时 `.so` **不进 git**(体量大、可重新拉取),clone 后需按 [§3](#3-本地构建与安装) 重建。

---

## 1. 调用链路(how the NPU is invoked)

```
SailensRuntimeProfile (某个模型配置为 acceleratorBackend = NPU)
  └─ LiteRtObstacleProvider.initialize() → initializeSession()   [obstacle/LiteRtObstacleProvider.kt]
       │  (sem 走 LiteRtSemanticSegmentationModel → LiteRTSegmenter,结构同)
       └─ LiteRtSessionFactory.create(sourceResolver, AcceleratorSelection)   [ml/session/]
            └─ AcceleratorSelector.select(...)  ← EXPLICIT/PREFER/FIRST + fallback + trace 标签
                 └─ (逐 accelerator 尝试,解析对应 ModelSource,createOutputBuffers 失败即回退下一个)
                      └─ LiteRtCompiledModelFactory.create(...)  [ml/LiteRtCompiledModelFactory.kt]
                           ├─ createNpuEnvironment(context)
                           │    ├─ ensureNpuRuntimeDownloaded() → downloadLibrary() (install-time 时空操作)
                           │    ├─ check(isLibraryReady())
                           │    └─ requireQualcommDispatchLibrary()  ← 缺 dispatch .so 即 fail-fast
                           ├─ buildOptions(NPU) → CompiledModel.Options(NPU) + QualcommOptions
                           └─ CompiledModel.create(...)
                                ├─ (JIT) libLiteRtCompilerPlugin_Qualcomm.so 把 .tflite 编译成 HTP 图
                                └─ tflite: Replacing N/N node(s) with delegate (DispatchDelegate) → HTP
       → LiteRtSession { run(), close() }；provider 只负责绑 tensor index + pre/post
```

关键点:
- **两层抽象**:`ml/session/` 是**加速器无关**的运行时层——`AcceleratorSelector`(选择+fallback+trace 标签)、`LiteRtSession`(CompiledModel+IO buffers+`run()`/`close()`)、`LiteRtSessionFactory`(把两者拼起来)。sem/det 各自只剩 pre/post + tensor 绑定。**加新 CNN 直接复用;未来 VLM 用同一个 `AcceleratorSelector.select(...)`、`build` 换成 LlmInference 即可,不重写 fallback。**
- **模型文件按实际 accelerator attempt 解析**:`ModelSourceResolver` / `ModelCatalog` 把 `(ModelType, Accelerator)` 映射到 `ModelSource.Asset` 或 `ModelSource.File`;fallback 到另一个 accelerator 时会重新选对应模型文件,provider 再从最终选中的 source 读取 tensor metadata。
- **`acceleratorBackend`** 在 `SailensRuntimeProfile.kt` 里逐模型指定(sem/det/VLM 各自一项)。`acceleratorSelectionMode` 默认 `EXPLICIT`——指定 backend 失败就失败,不静默回退,方便定位。
- **`accelerator=NPU` 标签 ≠ 真在 HTP 上**。它只代表“请求并初始化了 NPU accelerator”。真上 HTP 的证据看 [§4](#4-诊断diagnosis) 的 `DispatchDelegate`。
- 走的是 LiteRT **`CompiledModel` + `Accelerator`** API,**不是** TFLite `Interpreter`——litert 自带的 Interpreter 砍掉了 delegate API,QnnDelegate 挂不上。

---

## 2. 运行时交付模型(delivery)

新版 LiteRT **不再把 NPU 运行时打进 AAR**(`litert-2.1.5.aar` 只有 `libLiteRt.so` + GL accelerator)。运行时由官方 `litert_npu_runtime_libraries(.zip / _jit.zip)` 提供,作为 **dynamic feature 模块**接入,由 **Play Feature Delivery** 按设备下发。

两套 `.so`,别混:
| 库 | 作用 | 在哪个包 |
|---|---|---|
| `libLiteRtDispatch_Qualcomm.so` | **执行**已编译的 HTP 图 | AOT + JIT 都有 |
| `libLiteRtCompilerPlugin_Qualcomm.so` | 把 `.tflite` **编译**成 HTP 图 | **只有 JIT** |
| `libQnnHtp.so` / `libQnnSystem.so` / `libQnnHtpV81Skel/Stub.so` | QNN HTP 运行时(v81=SM8850) | fetch 脚本从 QAIRT SDK 拉 |
| `libQnnHtpPrepare.so` (~85MB) | on-device 图准备(JIT 编译用) | 只有 JIT |

**JIT vs AOT:**
- **JIT**(`_jit.zip`):带 compiler plugin,模型在设备上首次运行时编译。首帧慢(见 [case 5](#case-5-jit-编译很慢看起来像卡死)),且 **2.1.5 无 compile cache → 每次启动重编**。适合 debug。
- **AOT**(`.zip`):dispatch-only,需**离线**把模型用 O3 预编译成 QNN context,运行期零编译(最快、无首帧卡顿)。适合 release。(规划中)

**模块交付方式**:vendor 模块 manifest 是 `dist:install-time` + 条件 `device-group "Qualcomm_SM8850"` + `dist:fusing include="true"`。即 Play 安装时按设备组自动装对应那代的运行时。`downloadLibrary()` 是 on-demand 路径,对 install-time 基本是空操作(`isLibraryReady()` 装好即 true)。

---

## 3. 本地构建与安装

### 接线(目录存在时自动接入)
- `settings.gradle.kts`:如果工程根存在 `litert_npu_runtime_libraries_jit/` 且模块下有 `build.gradle.kts`，才 include 对应 runtime feature module。
- `app/build.gradle.kts`:
  - 仅对本地存在的 runtime feature module 添加 `dynamicFeatures`
  - 仅在 `runtime_strings` 模块存在时添加 `implementation(project(":litert_npu_runtime_libraries_jit:runtime_strings"))`(提供 `dist:title` 字符串,否则 bundle 报 `title_... not found`)
  - `implementation(libs.google.play.feature.delivery(-ktx))`(downloadLibrary 的 SplitInstall)
  - `packaging { jniLibs { useLegacyPackaging = true } }`(**必须**,见 [case 3](#case-3-extractnativelibsfalse--dispatch-库加载不到))

### 重建 vendor 文件夹(`.so` 不在 git 里)
1. 下载与 litert 版本匹配的 **`litert_npu_runtime_libraries_jit.zip`**(GitHub release),解压到**工程根**,得到 `litert_npu_runtime_libraries_jit/`。
2. 跑 `./litert_npu_runtime_libraries_jit/fetch_qualcomm_library_jit.sh` 拉 QNN 运行时(QAIRT SDK)。
3. **打 minSdk 补丁**:把 `qualcomm_runtime_v81/build.gradle.kts` 和 `runtime_strings/build.gradle.kts` 的 `minSdk` 改成 **= 基座 app 的 minSdk(34)**——zip 自带 31,bundletool 要求 ≥ 基座(见 [case 6](#case-6-bundletool-报-minsdk--基座))。

> AOT 时改用 `litert_npu_runtime_libraries.zip`(非 _jit)+ `fetch_qualcomm_library.sh`,并把 gradle 路径里的 `_jit` 去掉。

### 装到设备(本地)
特性模块**不进基座 APK**(`assembleDebug` 出的 APK 没有运行时 `.so`)。本地用 **universal APK**(无视下发条件、fuse 全部):
```powershell
adb uninstall com.sailens          # 换打包方式后需干净重装
./gradlew :app:bundleDebug
bundletool build-apks --mode=universal --bundle=app/build/outputs/bundle/debug/app-debug.aab --output=app-universal.apks
bundletool install-apks --apks=app-universal.apks
```
- Android Studio 点 Run 也行,但要在 **Run/Debug Configurations → "Dynamic features to deploy"** 勾上 `qualcomm_runtime_v81`(否则只装基座、没运行时)。
- 生产:Play Console 建 `Qualcomm_SM8850` device group,AAB 传 internal testing,经 Play 安装即 install-time 自动装。

---

## 4. 诊断(diagnosis)

### QNN 诊断 marker(详细 profiling/dump)
```powershell
adb shell run-as com.sailens touch files/litert_npu_diagnostics.enabled
adb shell am force-stop com.sailens
# 跑几秒后:
adb exec-out run-as com.sailens tar -C files -cf - litert_npu_diagnostics > litert_npu_diag.tar
adb shell run-as com.sailens rm files/litert_npu_diagnostics.enabled   # 排查完关掉,DEBUG+OPTRACE 很拖时间
```
开启后下次 NPU `CompiledModel` 创建会用 `LogLevel.DEBUG` + `Profiling.OPTRACE`,并把 IR JSON / DLC dump 到 `files/litert_npu_diagnostics/`。

### logcat 判读(最快定性)
| 你看到 | 含义 |
|---|---|
| `Replacing N/N ... DispatchDelegate` | ✅ **整图在 HTP**(QNN 编成一个 context 节点) |
| `Replacing N/M ... LITERT_CL` | ⚠️ 在 **GPU**(OpenCL) |
| `Replacing N/M ... TfLiteXNNPackDelegate` | ⚠️ 在 **CPU**(XNNPACK) |
| `No dispatch library found in .../lib/arm64` | ❌ dispatch 库没解压/没装(见 case 2/3) |
| `Failed to apply compiler plugins: No compiler plugin found` | ❌ 缺 JIT 编译器插件(见 case 4) |
| `1 compiler plugins were applied successfully` | ✅ JIT 编译成功 |
| `dlc/` dump 非空 | ✅ HTP 图编出来了 |

### 调试决策树(症状 → 看哪、改哪)
1. **app 报 `dispatch library ... not found`(护栏抛出)** → 运行时没装到位:基座 APK 不含特性模块,用 universal APK 或 AS 勾 “Dynamic features to deploy” 安装;并确认 `useLegacyPackaging = true`(case 3)。
2. **`accelerator=NPU` 却很慢,det 出现 `LITERT_CL`(GPU)或 `TfLiteXNNPackDelegate`(CPU)** → 没真上 HTP:
   - logcat 有 `No compiler plugin found` → 用 **JIT 包**(带 compiler plugin)(case 4)。
   - logcat 有 `No dispatch library` → 同第 1 条。
3. **init 卡很久像死了(无 SIGABRT/crash)** → JIT 首帧编译慢:别开诊断、等 20–30s;或把 `optimizationLevel` 降到 `PREPARE`;生产走 AOT(case 5)。
4. **`createOutputBuffers` 崩(`litert_compiled_model.h:1656/1620/495`)** → 某 int8 op 在 CPU/GPU prepare 失败。历史上已退役 seg 的 INT8 `TransposeConv` 是典型案例；当前实时链路只保留 sem/det(case 1)。
5. **延迟数字对不上(GPU 看着比 NPU 快很多)** → GPU 异步入队陷阱,要算 `infer + read` 总和(case 7)。
6. **想知道每个 op 落在 HTP 还是 CPU** → 开诊断 marker 看 OPTRACE / `dlc/`;主机侧用下面的脚本看 op 结构、找 SoC 不友好的算子。

### 模型结构脚本(主机侧)
- `scripts/inspect_tflite.py <model>` —— op 直方图 + IO 量化(找 SoC 不支持的 op)。
- `scripts/trace_tflite.py <model> OP1,OP2` —— 按 op 类型追节点(输入/输出/量化/是否常量)。
- 依赖:`python -m pip install tflite numpy`。

---

## 5. Case studies(踩过的坑)

### case 1: 历史 seg INT8 TransposeConv → createOutputBuffers 崩
早期(NPU 未通时)一个全整数量化的**实例分割**模型在 `CompiledModel` 的 **CPU / GPU prepare** 路径上 `createOutputBuffers` 崩(`litert_compiled_model.h:1656/1620/495`),CPU 上还有 `Node N (TfLiteXNNPackDelegate) failed to prepare`。**根因:分割 prototype 头里的 INT8 `TRANSPOSE_CONV`**(2× 上采样),是该图独有、det/sem 没有的算子;经典 TFLite Interpreter 能优雅回退,LiteRT CompiledModel 的 accelerator-prepare 不回退、硬失败。
如果 QNN 编译器能完整接收该图，`TransposeConv` 可被编进 HTP 图，避免 CPU/GPU prepare 路径上的崩溃。
**当前状态**:seg/refinement/provider/prototype mask 解码已退役；这段仅保留为历史 LiteRT 排障案例。当前 `ObstacleOcclusionAnalyzer` 使用 det 跟踪框接地带与 sem passable mask 做交叉验证，不再读取 prototype tensor。

### case 2: dispatch 库缺失 → 静默回退 CPU(假 NPU)
`accelerator=NPU` 却慢(~30ms),logcat 见 `No dispatch library found` + `Replacing 393/401 ... TfLiteXNNPackDelegate`。纯 `adb install` 的包既没自带、也没 Play 下发运行时。
**解**:接入 dynamic feature 运行时模块(见 §2/§3)。**并加了护栏** `requireQualcommDispatchLibrary()`:NPU env 创建时检查 `libLiteRtDispatch_Qualcomm.so`,缺了就**显式 init 失败**而不是假成功。

### case 3: `extractNativeLibs=false` → dispatch 库加载不到
运行时模块装上了,但 LiteRT 按**文件系统路径** dlopen `…/lib/arm64/libLiteRtDispatch_Qualcomm.so`,而 AGP 默认 `extractNativeLibs=false`(库压在 APK 内、`lib/arm64` 为空;logcat 见 `base.apk!/lib/...` 这种加载路径)→ 加载失败、护栏报错。
**解**:`app` 加 `packaging { jniLibs { useLegacyPackaging = true } }`(官方 `/next/npu` 文档也是这么写的)。

### case 4: AOT vs JIT —— 缺编译器插件 → 回退 GPU
dispatch 装好后日志 `Failed to apply compiler plugins: No compiler plugin found`,det 落 `LITERT_CL`(GPU)、`dlc/` 空。根因:用了 **AOT 包**(无 compiler plugin)却喂未预编译的 `.tflite`。
**解**:换 **JIT 包**(`_jit.zip`,带 `libLiteRtCompilerPlugin_Qualcomm.so` + `libQnnHtpPrepare.so`),运行时即可把图编到 HTP。

### case 5: JIT 编译很慢,看起来像卡死
compiler plugin 加载后,QNN 逐 op `Validating ... INT8`(快),然后 **app 进程静默数秒**(HTP graph finalize 在 cDSP 上跑、不逐行打日志)——**不是 crash**(无 SIGABRT),是首次编译耗时,被 `O3` + 诊断 DEBUG/OPTRACE 进一步放大。
**解**:debug 用 `HTP_OPTIMIZE_FOR_PREPARE`(编译最快档),测试时**别开诊断 marker**、启动后**等 20-30s** 让首帧编完。`PREPARE` 推理优化少;生产走 **AOT**(离线 O3)彻底消除。OptimizationLevel 三档:`PREPARE`(编译最快)< `INFERENCE` < `INFERENCE_O3`(推理最快)。

### case 6: bundletool 报 minSdk < 基座
`Modules cannot have a minSdkVersion lower than the base module`。zip 自带模块 minSdk=31,基座是 34。**解**:把 vendor 模块 minSdk 改成 ≥ 基座。(以及 `title_... not found` → `app` 必须 `implementation(project(":...:runtime_strings"))`。)

### case 7(测量陷阱): GPU “1ms” 是假的
GPU(OpenCL)`run()` 是**异步入队**,~1ms 就返回,真实计算等**读回输出**时才结算。所以“sem 在 GPU 上 1ms”不是真延迟——真实成本藏在 `outputReadTimeMs`(~14-30ms)。对比时要算 `infer + read` 总和，不要把 GPU 的异步入队时间拿来和同步后端数字直接比。

---

## 6. 验证清单(确认真上 HTP)

装好后(建议先不开诊断、给首帧编译留时间):
1. logcat 有 `Obstacle model initialized with NPU`
2. det 那行是 `Replacing N/N ... DispatchDelegate`(**不是** LITERT_CL / TfLiteXNNPackDelegate)
3. 无 `No dispatch library` / 无 `No compiler plugin found`
4. `obstacle runtime tensors ... accelerator=NPU`(detect 真跑了)
5. (开诊断时)`files/litert_npu_diagnostics/dlc/` 非空
6. 真实延迟看 app debug 面板 `obsMs`(logcat 不打每帧 ms)

---

## 7. 生产发布:AOT + AI Pack + Feature Delivery

本节是“发布时要做什么”。新人请先读懂 [§7.1 架构](#71-架构两条独立的下发线),再按 [§7.5 清单](#75-发布清单)执行。

> **Sailens 现状 vs 生产目标(先认清差距)**
> - **现状(debug 能用)**:模型放 `app/src/main/assets/`(随 APK,不走下发);运行时走 **JIT** dynamic feature(`litert_npu_runtime_libraries_jit`);本地用 `bundletool --mode=universal` 装;**没有** `device_targeting_configuration.xml`,**没有** AI Pack。
> - **生产目标**:模型 **AOT 预编译 → AI Pack**(Play 按设备下发对的那份);运行时走 **dispatch-only** Feature Delivery(`litert_npu_runtime_libraries`,非 _jit)+ **device targeting**;经 Play(内测/正式)安装。下面 7.2–7.4 就是把现状搬到目标。

### 7.1 架构:两条独立的下发线
| 下发什么 | 怎么打包 | 怎么下发 |
|---|---|---|
| **模型**(AOT 编译产物 / 或 .tflite) | AI Pack(`com.android.ai-pack`,`assetPacks`) | Play AI Pack(install-time / fast-follow / on-demand) |
| **运行时库**(dispatch + QNN HTP) | dynamic feature(`com.android.dynamic-feature`,`dynamicFeatures`) | Play Feature Delivery(install-time,按 device group 条件) |

两条线都靠 **device targeting**(`device_targeting_configuration.xml` + 设备组)让 Play 给每台设备发对的那一份(对的 HTP 版本 + 对的编译产物)。

### 7.2 AOT 模型编译(离线,Python)
目的:把 `.tflite` 提前用 O3 编译成各 SoC 的 QNN context,**运行期零编译**(消除 ~11s 首帧 + 每次重编,见 [case 5](#case-5-jit-编译很慢看起来像卡死))。

官方教程(照着跑):**[LiteRT AOT Compilation Colab](https://github.com/google-ai-edge/litert-samples/blob/main/compiled_model_api/colab/LiteRT_AOT_Compilation_Tutorial.ipynb)**。核心 API(`ai_edge_litert.aot`):

```python
from ai_edge_litert.aot import aot_compile as aot_lib
from ai_edge_litert.aot.vendors.qualcomm import target as qnn_target
from ai_edge_litert.aot.ai_pack import export_lib as ai_pack_export

# 1) 针对目标 SoC 编译(可一次多 target:8 Elite Gen5=SM8850,以及 v79/v75... 各代)
compiled_models = aot_lib.aot_compile(
    tflite_model_path,
    target=qnn_target.Target(soc_model="SM8850"),   # 多 SoC 时传 target 列表
)

# 2) 导出成 AI Pack(供 §7.3 打包下发)
ai_pack_export.export(compiled_models, ai_pack_dir, ai_pack_name="my_model", litert_model_name="my_model.tflite")
```

要点:
- **AOT 支持 partial delegation**:HTP 接不住的子图自动落 CPU/GPU(跟 `Options(NPU,GPU)` 一脉相承)。
- 一次编译可覆盖多代骁龙(SM8850/8750/...),导出到同一个 AI Pack,Play 按设备发对应那份。
- 产物进 AI Pack;同时会生成 `device_targeting_configuration.xml`(§7.4 要用)。

### 7.3 用 AI Pack 下发模型
`ai_edge_litert.aot.ai_pack.export` 产出的目录就是个 AI Pack 模块:

```
my_app/
  ai_packs/
    my_model/        # build.gradle.kts: plugins { id("com.android.ai-pack") }
    my_model_mtk/    #                    aiPack { packName="my_model"; dynamicDelivery { deliveryType="on-demand" } }
```

接线:
```kotlin
// settings.gradle.kts
include(":ai_packs:my_model")

// app/build.gradle.kts → android { }
assetPacks.add(":ai_packs:my_model")
```

运行期取模型(`AiPackModelProvider` + `ModelSelector`,按设备能力选对的编译产物):
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
`deliveryType`:`install-time`(随装)/ `fast-follow`(装完即后台拉)/ `on-demand`(用时再拉)。盲人 app 的实时模型建议 **install-time**(开屏即可用)。

> 把这套接进 Sailens 时,替换 `ModelSourceResolver`:默认实现返回 `ModelSource.Asset`,AI Pack 实现可返回 `ModelSource.File(AiPackModelProvider...getPath())`;`AcceleratorSelector` 的选择策略可以和 `ModelSelector` 合流(都是“按设备选 backend/模型”),正是为 [§1 的抽象](#1-调用链路how-the-npu-is-invoked)留的口子。

### 7.4 Feature Delivery 运行时 + device targeting
运行时库(dispatch + QNN)用 **dispatch-only** 包(`litert_npu_runtime_libraries`,**非** _jit;AOT 不需要 compiler plugin / 那 85MB 的 prepare 库)。接线同 [§3](#3-本地构建与安装),但 **app build.gradle 多加 device targeting**:

```kotlin
// app/build.gradle.kts → android { }
bundle {
    deviceTargetingConfig = file("device_targeting_configuration.xml")   // 从生成的 AI Pack 拷过来
    deviceGroup {
        enableSplit = true        // 按设备组拆分,只给每台设备发它那代的运行时
        defaultGroup = "other"
    }
}
```
- `device_targeting_configuration.xml` 定义设备组(如 `Qualcomm_SM8850` → 哪些 SoC),vendor 模块 manifest 的 `device-group` 条件就指它。**这就是 [case(警告)bundletool 报 device-group not defined](#3-本地构建与安装) 的正解**——本地 universal 绕开了它,生产必须有。
- 设备组也可以在 **Play Console** 里建/管理(SoC、RAM 等维度),与 XML 二选一/配合。

### 7.5 发布清单
1. [ ] AOT 编译模型(§7.2),覆盖要支持的所有 SoC,导出到 AI Pack。
2. [ ] AI Pack 接线(§7.3):`ai_packs/` 模块 + `assetPacks.add(...)` + settings include。
3. [ ] 运行时换 **dispatch-only**(`litert_npu_runtime_libraries`,非 _jit);`dynamicFeatures` + `runtime_strings` 依赖 + 各模块 `minSdk = 基座`。
4. [ ] `packaging { jniLibs { useLegacyPackaging = true } }`(否则 dispatch 库 dlopen 不到,见 [case 3](#case-3-extractnativelibsfalse--dispatch-库加载不到))。
5. [ ] `bundle { deviceTargetingConfig=...; deviceGroup { enableSplit=true } }` + 拷入 `device_targeting_configuration.xml`(§7.4)。
6. [ ] 代码:提供 AI Pack 版 `ModelSourceResolver`,返回 `ModelSource.File(AiPackModelProvider.getPath())`;Options 用 `model.getCompatibleAccelerators()`。
7. [ ] `./gradlew :app:bundleRelease` → 上传 AAB 到 **Play Console internal testing**。
8. [ ] 在目标设备(经 Play 安装)按 [§6 验证清单](#6-验证清单确认真上-htp)确认 `DispatchDelegate`、`dlc/` 非空、无 `No dispatch/compiler` 报错。
9. [ ] 关诊断测稳态延迟(AOT 应无首帧卡顿、推理接近 AI Hub 数)。
10. [ ] R8/release 冒烟:JNI 按名绑定 + LiteRT 反射运行期才暴露(见 AGENTS.md)。

> Play Console 侧(上传 AAB、internal testing 轨道、设备组 UI、灰度)属 Play 流程,详见 [PODAI 文档](https://developer.android.com/google/play/on-device-ai) 与 [Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery)。

---

## 8. 待办 / 后续

- **build-type 拆分**:debug→JIT(`_jit` 模块 + `PREPARE`)、release→AOT(dispatch-only + AI Pack + O3),用 buildType 切 `dynamicFeatures`/`optimizationLevel`/asset 来源。
- **模型从 assets 迁到 AI Pack**(§7.3),替换 `ModelSourceResolver` 让同一条 session 创建路径加载 `ModelSource.File`。
- **VLM on NPU**:NPU 的真正主场(大模型),规划端侧场景描述/VQA;复用 `AcceleratorSelector` 选择层 + 运行时交付基建,与实时视觉管线并存(叠加,不替代)。
- **`accelerator=NPU` 标签可信化**:目前只反映“请求”,不证明真上 HTP;可把 `DispatchDelegate` 落地与否回灌到 trace。
- **推理管线优化**(详见与本文同期的分析):obstacle 输出走零拷贝 handle(对齐 sem)、float→int8 量化下native、抽一层 InferencePipeline 收口 pre/run/read/post + 计时。

---

## 9. VLM 引擎(场景描述)

按需的「描述我面前是什么」是一条**独立**于实时 sem/det 的低频路径(秒级,不是每帧)。VLM 不走 `CompiledModel`/`run()`(它要分词、编码图像、自回归解码),所以有自己的运行时——但**复用同一套加速器选择策略**(`AcceleratorSelector`)。

应用侧触发策略、ASR 选择、快捷键入口和后续代码组织见 [vlm-asr-assistant-plan.zh-CN.md](vlm-asr-assistant-plan.zh-CN.md)。

### 架构(已落地骨架,编译通过)
```
domain: SceneDescriber (initialize / describe(frame, prompt?) / release)   ← app 只依赖这个,无 litert
  └─ data/.../ml/vlm/LiteRtVlmEngine : SceneDescriber
       ├─ AcceleratorSelector.select(...) { accel -> runtimeFactory.create(...) }   ← 复用选择+fallback
       └─ VlmRuntime(seam): generate(prompt, image) → String   ← 把 LLM 库调用隔离,引擎不直接依赖 GenAI
            └─ UnavailableVlmRuntimeFactory(默认):isReady=false,initialize() 抛错 → app 隐藏“描述”入口
```
默认 `UnavailableVlmRuntimeFactory` 让引擎在**没接模型时优雅不可用**,不影响构建/运行。接 VLM = 提供一个真的 `VlmRuntimeFactory` + 模型 bundle,注入 `LiteRtVlmEngine`。

### 接 MediaPipe LLM Inference 适配器(写好就能用)
```kotlin
// app/build.gradle.kts: implementation("com.google.mediapipe:tasks-genai:0.10.27")
import com.google.mediapipe.tasks.genai.llminference.*
import com.google.mediapipe.framework.image.BitmapImageBuilder

class MediaPipeVlmRuntimeFactory : VlmRuntimeFactory {
    override fun isAvailable(context: Context) =
        runCatching { Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference"); true }.getOrDefault(false)

    override fun create(context: Context, config: VlmModelConfig, accelerator: Accelerator): VlmRuntime {
        // ⚠️ MediaPipe tasks-genai 的 backend 目前是 CPU/GPU。VLM 真上 NPU 要走 LiteRT-LM / Qualcomm
        // Genie(还在演进)。这里把 NPU 暂映射成 GPU;或直接 throw,让 AcceleratorSelector 回退 GPU/CPU。
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
            if (image != null) session.addImage(BitmapImageBuilder(image.toBitmap()).build())  // 需 YUV→Bitmap
            return session.generateResponse()  // 流式用 generateResponseAsync(listener)
        }
    }
    override fun close() = llm.close()
}
```
- `image.toBitmap()` 需自己写 YUV_420_888 → `Bitmap`(可复用 native 预处理那套或 `YuvImage`)。
- 模型 bundle(`.task`)走 [§7.3 AI Pack](#73-用-ai-pack-下发模型) 下发;`config.modelPath` 用 `AiPackModelProvider.getPath()`。
- 真上 NPU 的 VLM 用 **LiteRT-LM**(`com.google.ai.edge.litert:litert-lm`,Qualcomm Genie 后端)再写一个 `VlmRuntimeFactory` 即可,引擎/域不动。
- **VLM 是叠加,不替代**实时避障:实时仍走 sem/det,VLM 只在用户主动问时跑。

---

## 参考
- LiteRT NPU 文档:https://developers.google.com/edge/litert/next/npu
- 高通 NPU 性能博客:https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/
- AOT 编译 Colab:https://github.com/google-ai-edge/litert-samples/blob/main/compiled_model_api/colab/LiteRT_AOT_Compilation_Tutorial.ipynb
- Play On-device AI(PODAI):https://developer.android.com/google/play/on-device-ai
- Play Feature Delivery:https://developer.android.com/guide/playcore/feature-delivery
- MediaPipe LLM Inference(VLM,Android):https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
