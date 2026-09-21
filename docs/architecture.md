**English** | [简体中文](architecture.zh-CN.md)

# Sailens architecture

> Status: **agreed and implemented.** The nine-module platform structure and the source-consumed
> distribution relationship are both on main. §11 is retained as migration history rather than a
> list of outstanding work. The post-refactor repository/product positioning is defined separately
> in [distribution-model.md](distribution-model.md).

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

The code is organised around those two pipelines plus shared Sailens infrastructure. The official
first-party Android distribution is a thin application over this repository, pinned as a git
submodule and consumed through a Gradle composite build. It is maintained as `sailens-app`. Product naming and repository ownership are specified
in [distribution-model.md](distribution-model.md).

## 2. Goals and non-goals

### Goals

- Replace the old fork-and-merge relationship with an ordinary source dependency between Sailens
  Android and the official distribution.
- Organise code around stable product and runtime boundaries instead of horizontal Clean
  Architecture layers.
- Make Guidance and Describe independently configurable, each gated by its own static availability
  and its own runtime state (§5.2).
- Keep heavy concrete runtimes optional so an application does not inherit a VLM runtime merely
  because the shell knows that Describe exists.
- Keep the licence boundary aligned with the dependency graph.
- Preserve the performance properties listed in §6.5 and §6.9.
- Keep A and B buildable after every migration step.
- Make public library API explicit instead of exposing every Kotlin symbol by accident.

### Non-goals

- A generic pipeline graph, plugin discovery system, or configurable DAG.
- A generic AI-lens SDK shaped for hypothetical products.
- Publishing to Maven or introducing artifact versioning during this phase; publication remains
  deferred until an external SDK surface and independent consumers justify it.
- Voice / ASR implementation in this refactor.
- A final GPU arbitration policy before a real VLM runtime exists.
- Turning every logical boundary into a Gradle module. A Gradle module is justified only when an
  independent compile, dependency, test, native, or public-API boundary is useful.
- Behaviour changes, except the explicit pipeline-availability behaviour in step 10.

## 3. Where the code started

This is the pre-migration baseline the plan below replaces. It had six modules split primarily by
technical layer:

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
| sailens-camera | CameraX capture, continuous frame source, bounded current-frame snapshot, preview composable, camera-permission state/request primitives |
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

Camera permission **policy UI** is part of presentation, not capture. sailens-camera may expose the
permission state and the action that requests permission, but the rationale dialog, its copy and its
design-system dependency live in sailens-shell. This keeps sailens-camera from depending back on the
shell after the old ux module is retired.

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
        ├──────────────────► sailens-camera     (FrameSnapshotProvider, §6.1)
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

Kotlin explicit API mode is enabled where the public surface is already small and deliberate:
**sailens-core, sailens-camera, sailens-vlm, sailens-output and sailens-describe**. In those
modules the compiler refuses a declaration without a visibility modifier, so the API cannot grow
by accident.

It is **not** enabled on sailens-runtime, sailens-vision, sailens-guidance or sailens-shell. Turning
it on there today would mean writing `public` on roughly nine hundred declarations that are public
only because nobody has said otherwise — which enshrines an accidental surface rather than gating
it. The prerequisite is narrowing those modules first (most of sailens-shell's composables and
sailens-guidance's processors want `internal`), and that is a separate piece of work with its own
review. Until then, the gate in §12.5 applies to the five modules listed above and to nothing else.

Internal implementation symbols stay internal unless another module genuinely needs them.

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

### 5.1 Platform, official distribution and other consumers

**Sailens Android — `sailens-android`, Apache-2.0**

This repository is the canonical Android platform: all reusable Sailens modules plus a thin
reference host. It remains zero-weights and supports bring-your-own-model. Its host may legitimately
start with zero available pipelines and show a clear zero-pipeline state.

The reference host is not the planned app-store product. The accepted target identity is namespace
`com.sailens`, applicationId `com.sailens.reference`.

**Official Sailens Android distribution — `sailens-app`**

The first-party end-user product, **Sailens**, is a thin application over Sailens Android. It pins this repository
as a git submodule and consumes the `sailens-*` libraries through a composite build. It owns the
official model bundle, product identity, capability expectations, release configuration and
distribution-specific notices.

Its target product name is **Sailens**, with namespace `com.sailens` and applicationId
`com.sailens`. "YOLO" becomes model provenance rather than long-term product branding.

**Other distributions**

Future first- or third-party applications may provide Guidance, Describe, both, or neither and may
consume Sailens Android through the same source dependency. They do not need to fork the platform.

The full repository, release and Maven-publication policy is in
[distribution-model.md](distribution-model.md).

### 5.2 Configured, expected, available and runtime state are different concepts

The platform must not conflate four different questions:

1. **Configured** — the edition intends to expose a pipeline.
2. **Expected** — the edition promises that capability as part of its product.
3. **Availability / preflight** — a cheap, static check says the implementation, model source and
   statically verifiable contracts are present. It does **not** initialize the model or accelerator.
4. **Runtime state** — the real session lifecycle after the user starts work: not started,
   initializing, running or failed.

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
    data class Unavailable(val reason: StaticUnavailableReason) : PipelineAvailability
}

sealed interface PipelineRuntimeState {
    data object NotStarted : PipelineRuntimeState
    data object Initializing : PipelineRuntimeState
    data object Running : PipelineRuntimeState
    data class Failed(val reason: RuntimeFailure) : PipelineRuntimeState
}
~~~

A null pipeline spec means NotConfigured. All-null is valid.

**Available is deliberately not "runtime ready".** Preflight may check that an implementation is
wired, a configured model source resolves, cheap metadata/shape/count checks pass, and required
static bindings such as TaxonomyId ↔ NavigationSemantics agree. It must not create a compiled model,
initialize GPU/NPU delegates, allocate inference buffers or run a probe inference merely to decide
whether a control should exist. That work stays lazy at session start, as it does today.

"Cheap" is not "shallow". Reading the TFLite flatbuffer tables from a memory-mapped model costs the
pages the tables sit on and no native runtime, and it is what turns preflight into an actual check:
*the asset exists* is passed by a model of the wrong shape, which then fails at session start —
after the person has pressed start and begun walking. SemanticModelPreflight therefore verifies that
the output tensor parses and that its class count matches the declared Taxonomy.classCount, and
reports ModelOutputUnreadable or ModelClassCountMismatch rather than a generic absence. It stops
where the evidence stops: channel *order* is not machine-verifiable without labels and stays a
manual release gate (§6.2).

Application expectations are edition-level validation, not a framework restriction. For example,
the official Sailens distribution may declare Guidance required. If its packaged semantic model is missing, its declared
taxonomy is incompatible, or another **static configuration** contract fails, that is an edition
configuration failure even though Sailens itself supports a zero-pipeline state.

Static configuration failure has build-type-specific handling:

- **debug/development:** fail fast so packaging/wiring mistakes are discovered immediately;
- **release:** enter an explicit fatal configuration state instead of crashing/restarting. The state
  must be accessible and must actively signal the failure through an available non-visual channel
  at least once.

The signal is **haptic first, then speech** — not "speech, or haptics if speech is broken". At the
moment a fatal configuration state is reached, nothing has started a TTS engine, because the app
never got to the screen that does. Treating that as "speech is unavailable" means the user gets one
buzz and is never told what is wrong. So the haptic fires immediately, because it needs no engine,
and the engine is then started so the reason can be spoken once it is ready. If it never becomes
ready, the haptic has already happened. Signalling is once per process: a LaunchedEffect restarts on
configuration change and process-death restore, and a fatal state that buzzes on every rotation
teaches the user to ignore it.

A pipeline may still pass preflight and fail when the real session initializes on a particular
device (for example a GPU delegate or output-buffer allocation fails). That is a **runtime failure**,
not a contradiction of Available. Guidance keeps the existing user-visible, retryable
analysis-start-failed path; Describe follows the equivalent request/runtime error path.

An optional but statically unavailable pipeline is not presented as a dead control. The shell either
hides it or presents an explicit edition-level explanation outside the normal action path.

The four combinations therefore route to four different places, and a Describe-only edition does not
land on the guidance screen — that screen is a start/stop control for a navigation session it does
not have, and the one thing it *can* do would not be on it:

| Guidance | Describe | Start destination |
|---|---|---|
| available | available | guidance, with a Describe action |
| available | — | guidance, no Describe control |
| — | available | the Describe screen |
| — | — | the zero-pipeline screen |

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
    suspend fun awaitCurrentFrame(maxAgeMs: Long, timeoutMs: Long): ImageFrame?
    fun openSnapshotLease(): FrameLease
}
~~~

Both are backed by the same camera session, and **demand is explicit**. Turning a camera image into
an ImageFrame copies every plane, so it happens only when something has asked for one — but asking
is not the same as running Guidance. A stream subscription and a snapshot lease are each sufficient
on their own. That is what lets Describe answer while Guidance is stopped, without capture
converting frames nobody reads.

Describe rejects a stale snapshot. It must never silently describe a frame from several seconds ago.
This preserves the intent already documented in the VLM/ASR assistant plan.

A snapshot request is also bounded in time. It opens demand, waits for a frame that satisfies the
freshness bound, and gives up rather than waiting indefinitely: a person who pressed a button is
owed an answer, and "I could not see" is a better answer than silence.

Describe remains independent of CameraX: another source can implement FrameSnapshotProvider later.

### 6.2 Taxonomy and navigation semantics are separate; machine checks stop where evidence stops

ClassMapper currently mixes:

- dataset facts: class count, output order, labels;
- Guidance policy: passable, obstacle, road, traffic light, ground type, obstacle category.

It splits into:

- **Taxonomy** in sailens-vision;
- **NavigationSemantics** in sailens-guidance.

Both carry a stable TaxonomyId and class count. A Guidance binding validates the **declared**
taxonomy id and class count against NavigationSemantics during static preflight.

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

This does **not** prove that an opaque model's output channels really have the declared meaning.
If trustworthy model metadata contains labels, validate the exact label order too. If it does not,
channel semantics/order remain a manual release gate. A model with the right shape and class count
can otherwise run successfully while assigning the wrong meaning to classes.

The model-adjacent contract test therefore checks only what is machine-observable: shape, dtype,
layout, class count, declared taxonomy binding and any labels that actually exist in metadata. It
must never claim to verify semantic order when labels are absent. An edition may additionally pin a
human-verified taxonomy declaration to the exact model hash so replacing the weight invalidates that
manual verification.

There is no neutral NavigationSemantics fallback. Missing or machine-detectably incompatible
semantics makes Guidance statically unavailable. If the edition declares Guidance required, that is
a static configuration failure. A wrong channel order that cannot be observed from metadata remains
a release-process safety failure, not something startup validation can magically detect.

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

Preemption is more than flushing the speech queue. Flushing removes what is already queued, but the
VLM keeps decoding, so the description resumes behind the alert and the person hears half a sentence
about scenery arriving right after "step down ahead" with no way to tell which is current. Stopping
the right description takes three things, and each is owned by one shell-wide object rather than by
a screen:

- **One SceneDescriptionCoordinator for the whole shell.** It owns the description in flight: the
  job, the token that says whether it may still speak, and the speech it has queued. The guidance
  screen preempts through it, and the Describe screen starts and cancels through it, so the
  description Guidance stops is the one actually running — whichever screen it is on. (An earlier
  iteration gave each screen its own session; a warning then cancelled the guidance screen's idle
  session while the Describe screen's description kept talking.) Preempting cancels the generation,
  invalidates the token so a chunk already decoded cannot speak, and withdraws Describe's queued
  speech, including the tail of an answer that finished decoding but is still being read out.
  Cancellation reaches the decode through VlmRuntime.shouldStop, which frees the accelerator.
- **Speech ownership is a mechanism in sailens-output.** Utterances can carry an opaque SpeechOwner,
  and `withdraw(owner)` takes back that owner's speech and nothing else: the engine can only flush
  its whole queue, so the output layer flushes and hands everyone else's utterances back in order.
  Describe can therefore cancel only Describe — closing or cancelling a description cannot cut off a
  warning that happens to be playing. Guidance, which outranks everything, still speaks with a flush.
- **The shared engines outlive every screen.** The speech engine and the description model are
  released by a SharedEngineOwner when the last screen holding a lease lets go, never by a screen on
  its way out; closing Describe used to release the engine Guidance was still speaking through.

A description speaks through the channel that was current when it started. If the channel changes
mid-way — speech turned off, a screen reader turned on — the description is cancelled rather than
finished half in one voice and half in another. The next request uses the new channel.

Only one answer owns the output at a time. An answer can finish decoding while it is still being
read out, and the Describe action comes back as soon as decoding ends, so a new request first
withdraws whatever is left of the previous answer. Otherwise the rest of a description of where the
person was would play ahead of the description of where they are now, with nothing to tell the two
apart.

Screen-reader announcements have exactly one collector, at the shell root, because the Android View
belongs to presentation and the root is the only composable that exists for as long as the app
does. Every screen publishes to the shared ScreenReaderAnnouncer. A per-screen collector drops every
announcement raised while another screen is on top — which is how a navigation warning raised under
the Describe screen used to reach no one.

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

The official `sailens-app` distribution pins Sailens Android
as a git submodule and includes it as a Gradle composite build.

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

No Maven publishing or library artifact version numbers are introduced in the current phase. The
submodule commit is the platform version boundary for a product build; the official application has
its own product release version/tag.

The distribution intentionally consumes Sailens Android's version catalog so
AGP/Kotlin/tooling stay aligned during this phase. Maven publication is reconsidered only after a
stable public SDK surface and independent consumers exist; see
[distribution-model.md](distribution-model.md).

The composite build is part of the implemented architecture and is verified against the real Android
resources, assets and CMake setup.

## 8. Licensing boundary

- Sailens Android remains Apache-2.0 and ships no model weights.
- The official distribution currently remains AGPL-3.0 and owns its model weights and
  distribution-specific notices.
- The repository dependency graph, not shared source history, is the code boundary.
- Cityscapes dataset/model distribution terms remain a separate unresolved release question; this
  refactor does not decide them.

A's published main history remains append-only. Removing the fork relationship is **not** a reason to
rewrite sailens-android/main.

The official distribution already moved to fresh history when it stopped being a fork. Keep that
history and rename the repository in place; do not delete/recreate it merely for the product rename.

## 9. What goes away

From B:

- merge=ours fork machinery;
- upstream sync recipe and disabled push remote;
- the zero-code-difference invariant;
- repository conventions whose only purpose was preserving a fork merge base.

From A:

- data/src/main/assets as the BYO location (moved to app/src/main/assets in step 10/11);
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

The implementation keeps eleven small **steps** because that makes dependency movement, commits and
regression diagnosis manageable. A step is not an artificial human-review stop.

After each step, run the targeted build/tests needed to prove that the tree is still internally
consistent. If they pass, implementation continues directly to the next step in the same gate.
Human review happens only at the five **review gates** below. A failed intermediate check is fixed
inside the current gate rather than handed off as a half-migrated architecture.

| # | Implementation step | Review gate |
|---|---|---|
| 1 | Convert JNI to RegisterNatives and add binding + array-kernel contract coverage before package moves | **G1 — native safety foundation** |
| 2 | Extract sailens-shell; move root Compose/navigation/settings/design/Guidance UI **and camera permission rationale UI** into it; refactor camera to expose only permission state/request primitives so its :ux dependency is gone; keep Application/MainActivity in app | **G2 — app/composition foundation** |
| 3 | Convert B into a thin composite-build consumer; retire fork-only guardrails in B, not A's append-only history rule | G2 |
| 4 | Extract sailens-core and sailens-camera; introduce FrameSource + FrameSnapshotProvider; verify camera remains independent of shell/design UI | G2 |
| 5 | Extract sailens-runtime; split native runtime preprocessing | **G3 — vision/Guidance runtime** |
| 6 | Extract sailens-vision; split Taxonomy / NavigationSemantics with TaxonomyId validation; split vision native code | G3 |
| 7 | Move navigation logic into sailens-guidance; move Guidance presentation only into shell; split Guidance native code | G3 |
| 8 | Extract sailens-output and make arbitration primitives explicit while preserving current behaviour | **G4 — output + Describe** |
| 9 | Extract sailens-vlm and sailens-describe; move prompt/snapshot/scheduling policy into Describe | G4 |
| 10 | **Behaviour change:** implement configured/expected/static-availability/runtime-state model, fatal static-configuration handling and legal zero-pipeline state | **G5 — capability semantics + closeout** |
| 11 | Update README, AGENTS.md, models.md asset path, B documentation and architecture references | G5 |

### 11.1 Review gates

**G1 — native safety foundation**

Stop before any package/module movement. Required evidence:

- A builds and its existing JVM tests stay green;
- Layer A binding coverage and Layer B array-kernel instrumentation tests in §12.2 pass on an arm64
  device;
- all 13 current JNI declarations are registered through RegisterNatives;
- the built library has no exported Java_<mangled> JNI entry points; JNI_OnLoad is the sole JNI
  registration entry point;
- no inference behaviour is intentionally changed.

This gate isolates JNI migration risk from every later package move.

**G2 — app/composition foundation (steps 2–4)**

Complete shell, B composite consumption, core and camera as one migration phase. Stop only after:

- A builds/tests;
- B resolves A through the real composite build and builds/launches;
- A launches;
- a camera session works;
- camera has no dependency back to shell/design UI;
- Application/MainActivity remain host-owned;
- FrameSource and freshness-bounded FrameSnapshotProvider have their intended ownership.

Steps 2–4 may be separate commits, but they should not create three separate review handoffs.

**G3 — vision/Guidance runtime (steps 5–7)**

Treat runtime, vision and Guidance as one coupled extraction. Stop only when the final dependency
direction and all three native-library homes exist. Required evidence includes:

- full A/B builds and tests;
- libsailens_runtime.so, libsailens_vision.so and libsailens_guidance.so preserve the registration
  guarantees from G1;
- array-kernel contract tests still pass after the split;
- B's real float models exercise native_score and native_bbox_nms_float_handle;
- §12.3 performance red lines and same-frame preprocessing-cache reuse hold on the same target
  device;
- a real Guidance session completes without behavioural regression;
- sailens-guidance has no Compose/UI dependency.

This is the highest-risk structural gate and must not be merged into G4.

**G4 — output + Describe (steps 8–9)**

Complete the shared output mechanism and the headless Describe pipeline together, then verify:

- current Guidance speech/haptic behaviour is preserved;
- Guidance safety output can pre-empt Describe information according to shell policy while
  sailens-output itself remains policy-neutral;
- Describe uses FrameSnapshotProvider rather than collecting the continuous frame stream;
- stale snapshots, cancellation and single-flight behaviour have targeted tests;
- Guidance-only editions do not acquire a heavyweight concrete VLM runtime dependency;
- if physical sailens-vlm extraction is deferred, the temporary dependency arrangement below is
  still respected.

**G5 — capability semantics + closeout (steps 10–11)**

This is deliberately separate because step 10 changes product behaviour rather than merely moving
code. Verify the complete state model and then update documentation against the implemented result:

- all four pipeline combinations are valid;
- static availability/preflight does not eagerly initialize LiteRT/GPU/NPU sessions;
- required static-configuration failures follow the debug fail-fast / release accessible-fatal-state
  contract;
- device-specific session initialization failures remain runtime failures on the retryable path;
- unavailable pipelines do not become dead accessibility controls;
- final A/B build, tests, launch, native/performance checks and structural checks in §12 pass;
- README, AGENTS.md and model/repository documentation describe the code that actually landed.

### 11.2 Continuous verification inside a gate

Review gates reduce handoffs, **not verification**. Each implementation step still runs the smallest
useful checks immediately after its change (for example compile + targeted tests, a composite build,
or a native instrumentation test). The implementer continues automatically when those checks pass
and stops early only when a failure cannot be resolved inside the current gate.

A gate may contain multiple focused commits. There is no requirement to squash a gate into one
commit merely to make the review boundary match the Git history.

sailens-vlm is the one extraction that may be deferred if it would initially contain only trivial
interfaces and no concrete implementation. While deferred, the VLM contract lives as a package
inside sailens-describe and sailens-describe temporarily depends directly on sailens-runtime.
The existing LiteRtVlmEngine is the natural starting point for the future sailens-vlm-litert
implementation module.

This deferral ends **before** a heavyweight concrete VLM runtime/native dependency is integrated:
contract and concrete implementation must be physically separated first so Guidance-only editions
do not inherit VLM runtime cost. The logical boundary is fixed even while its Gradle boundary is
temporarily deferred.

## 12. Verification

### 12.1 Test preservation

- Baseline: 171 unit tests.
- Tests move with code.
- The count must not fall without an explicit documented reason.
- Count preservation is only a regression guardrail; it is not proof of behavioural equivalence.

### 12.2 Native verification has three layers

The current code has 13 JNI entry points: 9 array-based kernels and 4 LiteRT TensorBuffer-handle
paths. A cannot manufacture valid LiteRT handles without a real compiled model, so "invoke every JNI
entry in A" is not a feasible step-1 exit condition. Verification is split by what can actually be
proven:

Layers A and B are instrumentation tests on a physical arm64 device, not JVM unit tests: the project
builds arm64-v8a only, so an x86 emulator cannot load these libraries. They are therefore not part of
the 171-test baseline in §12.1 and do not run in a JVM-only CI.

**A. Binding coverage — required in A before package movement**

- load every target native library;
- each JNI_OnLoad/RegisterNatives registration checks and propagates failure;
- the registration tables cover all 13 Kotlin native declarations;
- a wrong class name, method name or JNI signature makes library loading fail;
- **no library exports a `Java_<mangled>` symbol.** The entry points keep internal linkage, so
  name-based binding is not merely unused but unavailable, and the registration table is the only
  thing that can bind a method. Without this, a stale export could keep a method working while its
  table row is wrong or missing, which is the failure the layer is here to catch. Each library
  split in steps 5–7 must preserve the property; it is checkable with
  `llvm-nm -D --defined-only <lib>.so | grep -E 'Java_|JNI_OnLoad'`, which should show `JNI_OnLoad` and no `Java_` entry points.

This is the complete guard against the migration risk that motivated RegisterNatives: a renamed or
missed binding cannot hide until a rare code path is executed.

**B. Kernel behaviour — required in A**

- invoke all 9 array-based native entries with deterministic synthetic inputs;
- include the float and int8 array kernels;
- exercise semantic, detection, YUV/quantization and connectivity behaviour as applicable;
- compare key outputs against the pre-refactor implementation where practical.

Where a kernel and its Kotlin fallback are **not** equivalent, the test pins the native values
rather than the comparison, because the native kernel is the shipping path. G1 found one such case:
the connectivity kernel does not apply the perspective width scale that
`KotlinConnectivityStatsExtractor` applies, and is not passed the two config values it would need.
On a corridor that recedes normally the two disagree on five of nine outputs, including
`floodReachRatio`, because the flood's early-stop test consumes that same retention. This is a
pre-existing product divergence, not a refactor artefact; it is recorded in
`NativeConnectivityKernelTest` and left for a separate behavioural decision.

**C. Handle integration — model-backed**

- in B, real packaged float models exercise the two float handle hot paths:
  native_score and native_bbox_nms_float_handle;
- preserve their performance red lines in §12.3;
- the two int8 handle paths are an explicit known coverage gap until an edition supplies suitable
  int8 model fixtures. Do not add third-party model weights to A merely to satisfy this refactor.

If B later claims/supports int8 handle execution as a product path, model-backed integration coverage
for those paths becomes a release gate for that edition.

A manual Guidance session alone is still insufficient: it covers the current float hot paths but
does not prove every binding or array kernel.

### 12.3 Performance red lines

After runtime/vision/Guidance native extraction, validate on the same target device:

- sem: postprocessBackend = native_score and outputReadTimeMs approximately 0;
- det: postprocessBackend = native_bbox_nms_float_handle;
- preprocessing cache still reports same-frame reuse;
- no extra full semantic-tensor read is introduced.

**Measured state (SM8850, local BYO weights).** Re-measured after the rework; unchanged from the
G3 exit measurement.

| Red line | State |
|---|---|
| sem postprocessBackend = native_score | **holds** |
| sem outputReadTimeMs ≈ 0 | **does not hold** — 15/22/64 ms (min/median/max) |
| det postprocessBackend = native_bbox_nms_float_handle | **does not hold** — reports `native_bbox_nms`, the array path |
| det outputReadTimeMs | 2/10/28 ms |
| preprocessing cache same-frame reuse | **does not hold** — both models report `native_yuv`, never `shared_native_yuv` |
| no extra full semantic-tensor read | holds |

All three failures are **pre-existing, not refactor regressions**. The zero-copy pair was verified
against a build of the pre-migration commit on the same device and is identical there; the traces
from that build also show `native_yuv` on both models, so the same-frame cache has never been
hitting either. The handle path has simply not been running: `libLiteRt.so` does export the
lock/unlock symbols and the TensorBuffer handle reflection is valid for LiteRT 2.1.5, so the cause
is further in, and the `std::call_once` around the dlsym is a suspect that is not confirmed. The
cache is a separate matter: sem and det start concurrently, so neither finds the other's entry, and
the reuse the line asks for would need the two preprocess steps ordered rather than raced.

Restoring either changes the detection postprocess and the frame budget, so both are product
decisions rather than part of this refactor. Treat these lines as targets to restore, not as
properties that were preserved.

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
- explicit API compilation prevents accidental library-surface growth **in the five modules where
  it is enabled** (§4.3); the other four are not gated and their surface is reviewed by hand.

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
9. **Semantics safety:** declared taxonomy id + class count are machine-checked during static preflight; label order is additionally checked only when trustworthy labels exist in metadata, otherwise channel order remains a manual release gate.
10. **A history:** sailens-android/main remains append-only.

The next design work should focus on implementation details inside these fixed boundaries rather than
creating more modules or a more generic pipeline framework.
