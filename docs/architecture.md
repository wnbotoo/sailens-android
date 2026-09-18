**English** | [简体中文](architecture.zh-CN.md)

# Sailens architecture

> Status: **proposal, reviewed and revised.** Nothing in this document is implemented yet. It
> replaces the current layer-first module structure and the fork-based A/B repository relationship.

## 1. Summary

Sailens is **Smart AI Lens**: capture (camera today, voice later) → pipelines → AI results → output.
Navigation assistance for blind and low-vision users is its first application, not the identity of
the reusable code.

Two product pipelines exist:

- **Guidance** — continuous perception for navigation safety. Semantic segmentation is required;
  detection is optional.
- **Describe** — on demand. A vision-language model answers what is in front of the user or answers
  a question about the current view.

The platform accepts all four product combinations:

| Guidance | Describe | Valid |
|---|---|---|
| no | no | yes |
| yes | no | yes |
| no | yes | yes |
| yes | yes | yes |

Zero pipelines is a legitimate shell state. Whether a particular application edition is expected to
provide one of them is a separate application-level contract.

The code is reorganised around those two pipelines plus shared Sailens infrastructure. The
sailens-yolo repository stops being a fork and becomes a thin application that depends on this
repository through a Gradle composite build.

## 2. Goals and non-goals

### Goals

- Replace fork-and-merge between sailens-android and sailens-yolo with an ordinary dependency.
- Organise code around stable product and runtime boundaries instead of horizontal Clean
  Architecture layers.
- Make Guidance and Describe independently configurable and independently available at runtime.
- Keep heavy concrete runtimes optional so an application does not inherit a VLM runtime merely
  because the shell knows that Describe exists.
- Keep the licence boundary aligned with the dependency graph.
- Preserve the performance properties listed in §6.5 and §6.9.
- Keep A and B buildable after every migration step.
- Make public library API explicit instead of exposing every Kotlin symbol by accident.

### Non-goals

- A generic pipeline graph, plugin discovery system, or configurable DAG.
- A generic AI-lens SDK shaped for hypothetical products.
- Publishing to Maven or introducing artifact versioning.
- Voice / ASR implementation in this refactor.
- A final GPU arbitration policy before a real VLM runtime exists.
- Turning every logical boundary into a Gradle module. A Gradle module is justified only when an
  independent compile, dependency, test, native, or public-API boundary is useful.
- Behaviour changes, except the explicit pipeline-availability behaviour in step 10.

## 3. Where the code is today

The repository has six modules split primarily by technical layer:

~~~text
:domain         (no deps)
:data           → :domain
:camera         → :domain, :ux
:presentation   → :camera, :domain, :ux
:ux             (no deps)
:app            → everything
~~~

This works for a single application, but the product has already evolved into two different
pipelines. Several boundaries therefore sit on the wrong side:

| Item | Problem |
|---|---|
| ClassMapper | mixes dataset facts with navigation judgements |
| FrameTrace | navigation-specific but placed in a broad domain layer |
| ObstacleDetection.category | a Guidance classification leaks out of the detector |
| SegmentationOutput.analysisStats | generic inference output already carries navigation statistics |
| libsailens_ml.so | generic preprocessing and navigation kernels share one native library |
| :camera → :domain | reusing capture pulls in the whole navigation domain |
| :presentation | Guidance UI, shared output devices, navigation shell and future Describe UI are mixed |

There is also a JNI migration hazard: the current native functions are bound by static Java symbol
names. Moving a Kotlin class can therefore compile successfully and fail only when a native method is
first called.

Migration baseline: **171 unit tests**. Tests move with their code and the total must not silently
decrease.

## 4. Target structure

### 4.1 Gradle modules

The initial target is **9 library modules + 1 reference app**:

| Module | Responsibility |
|---|---|
| sailens-core | smallest shared contracts and value types: ImageFrame/YUV planes, geometry, BinaryMask, MlRuntimeInfo, LogService |
| sailens-camera | CameraX capture, continuous frame source, bounded current-frame snapshot, preview composable, permission flow |
| sailens-runtime | LiteRT sessions, accelerator selection, model sources, metadata, YUV→tensor preprocessing, shared preprocessing cache, hardware profile, resource-arbitration mechanism |
| sailens-vision | segmentation and detection runners, dataset taxonomies, default postprocessors |
| sailens-vlm | VLM engine contract: frame + complete prompt → streamed text; no product prompt policy |
| sailens-output | TTS, audio focus, screen-reader detection, haptic primitive, speech-routing mechanism, clause buffering |
| sailens-guidance | navigation logic: NavigationSemantics, fused semantic kernel, connectivity, safety analysis, events, cooldown, tracking, depth, sensors, trace/replay |
| sailens-describe | Describe product logic: prompt policy, snapshot freshness, request scheduling, use cases/controller |
| sailens-shell | reusable Sailens presentation and composition: root Compose, navigation, settings, design system, Guidance UI, future Describe UI, diagnostics/about, DI aggregation |
| app | A's thin Android host: Application, MainActivity, manifest/window ownership, identity and edition config |

There is **no separate sailens-ux or sailens-guidance-ui Gradle module**. Their boundaries remain as
packages inside sailens-shell.

Reason: those modules did not earn independent compile/dependency/API boundaries. UI, design system,
settings and navigation change together as one reusable application presentation shell. The
Guidance algorithm boundary remains separate because it has substantial safety logic, tests and a
different change axis.

### 4.2 Dependency graph

~~~text
app / B / C ─────────────► sailens-shell
                              │
                              ├──► sailens-guidance
                              ├──► sailens-describe
                              ├──► sailens-camera
                              └──► sailens-output

sailens-guidance ─────────► sailens-vision ───► sailens-runtime ───► sailens-core
        │                         │                     │
        └─────────────────────────┴─────────────────────┘

sailens-describe ─────────► sailens-vlm ─────► sailens-runtime
        └────────────────────────────────────► sailens-core

sailens-camera ───────────────────────────────► sailens-core
sailens-output ───────────────────────────────► sailens-core
~~~

Rules:

1. Dependencies point downward.
2. sailens-guidance and sailens-describe never depend on one another.
3. sailens-guidance remains UI-free.
4. sailens-shell owns presentation policy and application composition, but not concrete model
   runtimes.
5. sailens-runtime provides resource-arbitration **mechanism**, not Guidance/Describe product policy.
6. No shared infrastructure module may depend on sailens-guidance, sailens-describe or sailens-shell.

A future concrete LiteRT-LM implementation should normally be a separate module such as
sailens-vlm-litert. An edition that does not use it should not pay its binary/native dependency cost.

### 4.3 Packages and coordinates

Gradle module names use the full Sailens prefix because these are Sailens framework modules, not a
generic Lens SDK.

Packages do not repeat the product name:

| Module | Package root |
|---|---|
| sailens-core | com.sailens.core |
| sailens-camera | com.sailens.camera |
| sailens-runtime | com.sailens.runtime |
| sailens-vision | com.sailens.vision |
| sailens-vlm | com.sailens.vlm |
| sailens-output | com.sailens.output |
| sailens-guidance | com.sailens.guidance |
| sailens-describe | com.sailens.describe |
| sailens-shell | com.sailens.shell |

Composite-build coordinates follow the module names, for example
com.sailens:sailens-shell.

Reusable library modules enable Kotlin explicit API mode. Internal implementation symbols stay
internal unless another module genuinely needs them.

### 4.4 sailens-shell package structure

The UI consolidation is a Gradle consolidation, not a loss of logical boundaries:

~~~text
sailens-shell/
└── com/sailens/shell/
    ├── app/
    ├── navigation/
    ├── design/
    │   ├── theme/
    │   └── components/
    ├── guidance/
    │   ├── screen/
    │   ├── overlay/
    │   └── settings/
    ├── describe/
    ├── settings/
    ├── diagnostics/
    ├── about/
    └── di/
~~~

The host application still owns Application, MainActivity and its manifest/window lifecycle. The
shell exposes reusable composition such as SailensRoot(), navigation entries and Koin modules.

## 5. Application editions and capability model

### 5.1 A, B and C

**A — sailens-android, Apache-2.0**

All reusable Sailens modules plus the thin reference app. It remains zero-weights and supports
bring-your-own-model. BYO weights move from data/src/main/assets to app/src/main/assets.

A may legitimately start with zero available pipelines and show a clear zero-pipeline state that
points to model/runtime setup documentation.

**B — sailens-yolo, AGPL-3.0**

A thin application over sailens-shell. It pins A as a git submodule and consumes it through a
composite build. B owns its model weights, identity, product expectations and any genuinely
YOLO-specific decoder/runtime code that becomes necessary.

B is no longer required to stay code-identical to A.

**C — future edition**

May provide Guidance, Describe, both, or neither. A concrete VLM runtime is supplied explicitly by
the edition if needed.

### 5.2 Configured, available and expected are different concepts

The platform must not conflate these:

1. **Configured** — the edition intends to expose a pipeline.
2. **Available** — the required runtime/model/contracts are healthy now.
3. **Expected** — the edition promises that capability as part of its product.

A representative shape is:

~~~kotlin
data class SailensAppSpec(
    val guidance: GuidanceSpec? = null,
    val describe: DescribeSpec? = null,
    val expectations: CapabilityExpectations = CapabilityExpectations(),
)

sealed interface PipelineAvailability {
    data object NotConfigured : PipelineAvailability
    data object Available : PipelineAvailability
    data class Unavailable(val reason: Reason) : PipelineAvailability
}
~~~

A null pipeline spec means NotConfigured. All-null is valid.

Application expectations are edition-level validation, not a framework restriction. For example,
sailens-yolo may declare Guidance required. If its packaged semantic model is missing or invalid,
that is a B startup/configuration failure even though Sailens itself supports a zero-pipeline state.

An optional but unavailable pipeline is not presented as a dead control. The shell either hides it
or presents an explicit edition-level explanation outside the normal action path.

## 6. Design decisions

### 6.1 Continuous frames and on-demand snapshots are separate contracts

Guidance consumes a continuous stream. Describe is on demand and must not own or collect a frame
stream merely to obtain the current view.

sailens-camera therefore exposes two concepts:

~~~kotlin
interface FrameSource {
    val frames: Flow<ImageFrame>
}

interface FrameSnapshotProvider {
    fun currentFrame(maxAgeMs: Long): ImageFrame?
}
~~~

Both can be backed by the same camera session.

Describe rejects a stale snapshot. It must never silently describe a frame from several seconds ago.
This preserves the intent already documented in the VLM/ASR assistant plan.

Describe remains independent of CameraX: another source can implement FrameSnapshotProvider later.

### 6.2 Taxonomy and navigation semantics are separate and must prove compatibility

ClassMapper currently mixes:

- dataset facts: class count, output order, labels;
- Guidance policy: passable, obstacle, road, traffic light, ground type, obstacle category.

It splits into:

- **Taxonomy** in sailens-vision;
- **NavigationSemantics** in sailens-guidance.

Both carry a stable TaxonomyId and class count. A Guidance model binding validates them before
analysis starts.

~~~kotlin
@JvmInline
value class TaxonomyId(val value: String)

interface Taxonomy {
    val id: TaxonomyId
    val classCount: Int
}

interface NavigationSemantics {
    val taxonomyId: TaxonomyId
    val classCount: Int
}
~~~

If model metadata contains labels, validate the exact label order as well. If it does not, the
edition must explicitly declare the taxonomy and keep a contract test beside the model.

There is no neutral NavigationSemantics fallback. Missing or incompatible semantics makes Guidance
unavailable. If the edition declares Guidance required, that becomes an edition startup failure.

### 6.3 Detection output stays generic

Detection runners emit class id, optional label, confidence and box. ObstacleCategory belongs to
Guidance and is resolved through NavigationSemantics after inference.

NMS, tensor-layout decoding and letterbox geometry stay in sailens-vision.

### 6.4 Describe owns prompts and request policy

sailens-vlm receives a complete prompt and returns streamed text. It does not know that the user is
blind, that Sailens is a navigation product, or how stale a frame may be.

sailens-describe owns:

- system/user prompt assembly;
- current-frame freshness;
- single-flight/cancellation;
- quick-describe versus question semantics;
- result validity when generation finishes late.

A concrete runtime such as LiteRT-LM belongs in a separate implementation module when it exists.

### 6.5 Segmentation keeps one native pass

The current semantic performance red line remains:

- postprocessBackend = native_score
- outputReadTimeMs approximately 0

The native path reads the LiteRT output buffer by handle and computes argmax plus Guidance statistics
in one pass. The refactor must not introduce a second scan over the full semantic tensor merely to
make the module diagram cleaner.

The seam stays generic:

~~~text
sailens-vision    SegmentationRunner<R>(..., SemanticPostprocessor<R>)
sailens-vision    ArgmaxPostprocessor
sailens-guidance  NavigationScorePostprocessor
~~~

NavigationScorePostprocessor is the moved form of the current fused Guidance postprocessor.

### 6.6 Output arbitration: mechanism below, product policy above

sailens-output provides shared output mechanisms:

- TTS and audio focus;
- screen-reader channel handling;
- haptic primitive;
- priority/preemption primitives;
- clause buffering;
- announcement flow.

It must not know what Guidance or Describe are.

The product policy that a Guidance safety alert outranks Describe information belongs in
sailens-shell / edition composition. Guidance emits typed events/urgency; Describe emits information
results; the shell maps them onto output priorities.

Screen-reader announcements have one collector in sailens-shell because the actual Android View
belongs to presentation.

Guidance-specific haptic vocabulary stays in the shell's Guidance presentation package.
SPEECH_UNAVAILABLE may remain a shared output-level signal because it describes the output channel,
not navigation semantics.

### 6.7 sailens-core stays deliberately small

sailens-core is not a new miscellaneous domain module.

It contains only stable types/contracts needed by multiple lower modules. In particular:

- LogService interface may live in sailens-core.
- FileLogService does **not**. It depends on Android Context/files and is an application-platform
  implementation; initially it belongs in sailens-shell composition.
- Trace/replay remains in sailens-guidance until a second real consumer exists.
- Sensors, depth, Stabilizer and FrameQualityAnalyzer remain in sailens-guidance for the same reason.

### 6.8 Shared preprocessing cache

Semantic and detection inference reuse one same-frame YUV→tensor conversion through
InputPreprocessCache. The cache remains a single shared runtime instance, not one cache per runner.

This belongs in sailens-runtime.

### 6.9 Runtime resource arbitration is mechanism, not pipeline policy

A future VLM and realtime vision models may contend for GPU/NPU resources.

sailens-runtime may provide a neutral resource coordinator such as acquire/release with priority and
preemptibility. It must not contain rules named after Guidance or Describe.

Which work is safety-critical and what user feedback is required when Guidance protection pauses are
higher-level product decisions. If Guidance is paused for Describe, that loss of protection must be
communicated through a non-visual channel.

### 6.10 JNI: RegisterNatives and per-module native libraries

Move away from static Java JNI symbol names before package movement.

Target native libraries:

| Library | Module | Responsibility |
|---|---|---|
| libsailens_runtime.so | sailens-runtime | YUV preprocessing |
| libsailens_vision.so | sailens-vision | generic semantic argmax and detection postprocessing |
| libsailens_guidance.so | sailens-guidance | fused Guidance semantic scoring and connectivity statistics |

Each library registers its own methods in JNI_OnLoad. Keep rules are still required for classes
resolved through FindClass.

A shared native library in sailens-runtime must not register Guidance classes because that would
invert the module dependency.

The LiteRT zero-copy helper remains a small runtime-owned native header/helper used by the vision and
Guidance CMake builds without introducing an inverse Kotlin/Gradle dependency.

### 6.11 Configuration

Each lower module owns its own config type. Hardware detection belongs in sailens-runtime. Per-module
defaults belong with their modules. Edition/tier composition belongs in sailens-shell and the host
application.

sailens-shell never reads another application's BuildConfig directly. The host passes AppInfo,
product capability specs, diagnostics flags and OSS metadata explicitly.

## 7. Development setup: composite build, no Maven

sailens-yolo pins sailens-android as a git submodule and includes it as a Gradle composite build.

~~~kotlin
includeBuild("sailens")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("sailens/gradle/libs.versions.toml")) }
    }
}
~~~

B consumes coordinates such as:

~~~kotlin
implementation("com.sailens:sailens-shell")
~~~

No Maven publishing or version numbers are introduced. The submodule commit is the version boundary.

B intentionally consumes A's version catalog so AGP/Kotlin/tooling stay aligned during this phase.

The composite build must be proven on the real Android resources, assets and CMake setup before most
module movement depends on it.

## 8. Licensing boundary

- A remains Apache-2.0 and ships no model weights.
- B remains AGPL-3.0 and owns its model weights and edition-specific notices.
- The repository dependency graph, not shared source history, becomes the code boundary.
- Cityscapes dataset/model distribution terms remain a separate unresolved release question; this
  refactor does not decide them.

A's published main history remains append-only. Removing the fork relationship is **not** a reason to
rewrite sailens-android/main.

B may choose fresh history when it stops being a fork, because that is a B repository migration
decision.

## 9. What goes away

From B:

- merge=ours fork machinery;
- upstream sync recipe and disabled push remote;
- the zero-code-difference invariant;
- repository conventions whose only purpose was preserving a fork merge base.

From A:

- data/src/main/assets as the BYO location;
- the old mlModelBinding arrangement after its replacement is proven;
- Gradle modules :domain, :data, :presentation and :ux after their code has migrated.

There is no standalone sailens-ux or sailens-guidance-ui module in the target.

## 10. Pending release items

| Item | Home |
|---|---|
| remove unused dataSync foreground service inherited through LiteRT dependencies | the lowest module whose manifest can safely own the removal; verify manifest propagation before fixing the final home |
| privacy policy | per application |
| safety disclaimer / first-run content | sailens-shell, selected by configured pipelines |
| release signing | per application |
| Cityscapes non-commercial/distribution decision | B / model-distribution decision |

## 11. Migration plan

Every step ends with A building, B building against the current A commit, and tests green.

| # | Step | Device/native check |
|---|---|---|
| 1 | Convert JNI to RegisterNatives **and add native contract coverage** before package moves | yes |
| 2 | Extract sailens-shell; move root Compose/navigation/settings/design/Guidance UI into it; keep Application/MainActivity in app | app launch |
| 3 | Convert B into a thin composite-build consumer; retire fork-only guardrails in B, not A's append-only history rule | B build/launch |
| 4 | Extract sailens-core and sailens-camera; introduce FrameSource + FrameSnapshotProvider | camera session |
| 5 | Extract sailens-runtime; split native runtime preprocessing | yes |
| 6 | Extract sailens-vision; split Taxonomy / NavigationSemantics with TaxonomyId validation; split vision native code | yes |
| 7 | Move navigation logic into sailens-guidance; move Guidance presentation only into shell; split Guidance native code | yes |
| 8 | Extract sailens-output and make arbitration primitives explicit while preserving current behaviour | yes |
| 9 | Extract sailens-vlm and sailens-describe; move prompt/snapshot/scheduling policy into Describe | targeted tests |
| 10 | **Behaviour change:** implement configured/available/expected capability model and legal zero-pipeline state | yes |
| 11 | Update README, AGENTS.md, models.md asset path, B documentation and architecture references | — |

sailens-vlm is the one extraction that may be deferred if it would initially contain only trivial
interfaces and no concrete implementation. The logical boundary is fixed; the Gradle boundary can be
created when it has real dependency/API value.

## 12. Verification

### 12.1 Test preservation

- Baseline: 171 unit tests.
- Tests move with code.
- The count must not fall without an explicit documented reason.
- Count preservation is only a regression guardrail; it is not proof of behavioural equivalence.

### 12.2 Native contract tests

Before native package movement, add instrumentation/native contract coverage that:

- loads every target native library;
- proves every RegisterNatives binding;
- invokes every native entry point at least once, including rarely used int8 paths;
- exercises semantic and detection postprocessors with fixed synthetic tensors;
- compares key outputs against the pre-refactor implementation where practical.

A manual Guidance session alone is insufficient because it does not cover cold native paths.

### 12.3 Performance red lines

After runtime/vision/Guidance native extraction, validate on the same target device:

- sem: postprocessBackend = native_score and outputReadTimeMs approximately 0;
- det: postprocessBackend = native_bbox_nms_float_handle;
- preprocessing cache still reports same-frame reuse;
- no extra full semantic-tensor read is introduced.

### 12.4 Session comparison

Compare before/after traces on the same device and route:

- blocked frames;
- event counts/categories;
- average and p95 pipeline time;
- backend reporting;
- unexpected startup/unavailability states.

Trace comparison remains a coarse integration check, not a golden frame replay.

### 12.5 Structural checks

During migration, verify:

- sailens-guidance has no Compose/UI dependency;
- sailens-core does not accumulate Android application services;
- no shared lower module depends on sailens-shell;
- concrete VLM runtime dependencies are not pulled into Guidance-only editions;
- explicit API compilation prevents accidental library-surface growth.

## 13. Decisions resolved by this review

The following are no longer open questions:

1. **Module prefix:** use sailens-*, not lens-*.
2. **Package root:** use com.sailens.*, not com.sailens.sailens.* or com.sailens.lens.*.
3. **UI modules:** merge the old ux and guidance-ui concepts into sailens-shell.
4. **Guidance boundary:** keep sailens-guidance separate from UI.
5. **Initial size:** target 9 library modules + app, with sailens-vlm allowed to defer its physical
   Gradle extraction until useful.
6. **Pipeline combinations:** zero, Guidance-only, Describe-only and both are all valid.
7. **Host boundary:** Application/MainActivity stay in each app; shell provides reusable Compose and
   composition.
8. **Frame contract:** Guidance consumes a stream; Describe consumes a freshness-bounded snapshot.
9. **Semantics safety:** taxonomy identity/order compatibility is an explicit startup contract.
10. **A history:** sailens-android/main remains append-only.

The next design work should focus on implementation details inside these fixed boundaries rather than
creating more modules or a more generic pipeline framework.
