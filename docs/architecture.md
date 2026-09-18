**English** | [简体中文](architecture.zh-CN.md)

# Sailens architecture: the lens framework

> Status: **proposal, under review.** Nothing in this document is implemented. It replaces the
> current layer-first module structure and the fork-based A/B repository relationship.

## 1. Summary

Sailens is repositioned as **Smart AI Lens**: capture (camera today, voice later) → pipelines → AI
results → output. Navigation assistance for blind and low-vision users is its first application, not
its identity.

Two pipelines exist, and an app may contain either one or both:

- **Guidance** — continuous. Semantic segmentation (+ optional detection) every frame, answering
  "will I walk into something".
- **Describe** — on demand. A vision-language model, answering "what is in front of me" when asked.

The code is reorganised around those two pipelines plus the infrastructure they share. The
`sailens-yolo` repository stops being a fork of this one and becomes a thin app that **depends on**
it through a Gradle composite build.

## 2. Goals and non-goals

**Goals**

- Replace fork-and-merge between the two repositories with an ordinary build dependency.
- Make each pipeline independently usable, with shared infrastructure that belongs to neither.
- Keep the licence boundary equal to the dependency graph.
- Keep every performance property that exists today (listed in §6.3 and §6.7).
- Leave the app working after every migration step.

**Non-goals** — explicitly out of scope for this refactor:

- A generic pipeline graph, plugin discovery, or any configurable DAG. Guidance and describe have
  fixed shapes; only their stage implementations vary, and interfaces + DI already handle that.
- Publishing to Maven, or versioned artifacts of any kind.
- Applications other than navigation assistance. No abstraction is shaped for a hypothetical one.
- Excluding a pipeline at build time. Pipelines are pluggable at **runtime** (§6.1).
- Voice / ASR input. The structure leaves room for it (§6.10); nothing is built.
- A GPU arbitration policy between the pipelines. There is no VLM runtime yet (§6.7).
- Behaviour changes. Every step is behaviour-preserving except step 10 (§11), which is marked.

## 3. Where the code is today

Six modules, split by clean-architecture **layer**:

```text
:domain         (no deps)
:data           → :domain
:camera         → :domain, :ux
:presentation   → :camera, :domain, :ux
:ux             (no deps)
:app            → everything
```

Measured against the two pipelines, the layers already *roughly* separate navigation logic from lens
infrastructure — but the boundary items sit on the wrong side:

| Module | Size | What it mostly is |
|---|---|---|
| `:domain` | ~7,000 lines | ~70 % navigation logic (connectivity, events, cooldown, road safety, trace) |
| `:data` | ~6,100 lines + 2,185 lines C++ | ~75 % generic lens runtime (LiteRT, accelerators, preprocessing, runners) |

Things that look generic but are shaped by navigation:

| Item | Why it is not generic |
|---|---|
| `ClassMapper` | Its methods are `isPassable`, `isRoad`, `isTrafficLight`, `toGroundType`, `toObstacleCategory` |
| `FrameTrace` | Fields are `isBlocked`, `navigationPassableRatio`, `blockageConfidence`, … |
| `ObstacleDetection` | Carries `category: ObstacleCategory`, a navigation classification, straight out of the runner |
| `SegmentationOutput.analysisStats` | The sem runner's native pass already computes passable mask, road ratio, ground-type distribution |
| `libsailens_ml.so` | One file mixes YUV preprocessing (generic) with connectivity statistics (navigation) |
| `:camera` | Depends on `:domain` for four frame types, so reusing capture drags in the whole navigation core |

And one mechanical hazard: all 13 native functions (5 Kotlin classes) are bound **by static name**
(`Java_com_sailens_data_source_ml_*`); `RegisterNatives` is used zero times. Moving any of those
classes to another package fails at **runtime**, not at build time.

Baseline for the migration: **171 unit tests** (`:domain` 129, `:presentation` 17, `:data` 14,
`:app` 9, `:camera` 1, `:ux` 1).

## 4. Target structure

### 4.1 Modules

| Module | Responsibility | Comes from |
|---|---|---|
| `lens-core` | Frame types (`ImageFrame`, YUV planes), geometry, `BinaryMask`, `MlRuntimeInfo`, `LogService` + `FileLogService` | `:domain` model/util, `:data` service |
| `lens-camera` | CameraX capture → `ImageFrame` stream, preview composable, permission flow | `:camera` |
| `lens-runtime` | LiteRT sessions, accelerator selection, model sources, metadata reader, YUV→tensor native preprocessing, the shared preprocessing cache, hardware profile detection | `:data` source/ml + session, `:app` `DeviceHardwareProfileProvider` |
| `lens-vision` | Segmentation runner (→ class map), detection runner (→ boxes with class ids), dataset taxonomies (label lists), default postprocessors | `:data` semantic + obstacle, mapper label tables |
| `lens-vlm` | VLM engine seam: frame + prompt → streamed text | `:data` source/ml/vlm |
| `lens-output` | TTS engine, audio focus, screen-reader detection, haptic primitive, speech router, clause buffering | `:presentation` device/* |
| `guidance` | Everything navigational: `NavigationSemantics`, fused sem kernel, connectivity, road safety, events, cooldown, tracking, depth, sensors, trace/replay | the rest of `:domain` and `:data` |
| `guidance-ui` | Live screen, guidance ViewModel, haptic vocabulary, event text, overlays, guidance settings sections, debug trace screens | `:presentation` |
| `describe` | Scene-description pipeline: prompt, use case, controller. **No UI** until a VLM runtime exists | `DescribeSceneUseCase`, the describe half of `SceneAnalysisViewModel` |
| `ux` | Design system, plus the about / open-source-licences screens (they are generic lists) | `:ux`, `:presentation` about/* |
| `shell` | The whole app as a library: activity, navigation, settings composition, DI aggregation, runtime profile | `:app` (except the application module itself), `:presentation` navigation |
| `app` | A's reference application: bring-your-own-model, zero weights. Only identity and config | `:app` |

### 4.2 Dependencies

```text
app / B / C ──► shell
shell       ──► guidance-ui, describe, lens-camera, lens-output, ux
guidance-ui ──► guidance, lens-output, lens-camera, ux
guidance    ──► lens-vision, lens-runtime, lens-core
describe    ──► lens-vlm, lens-output, lens-core
lens-vision ──► lens-runtime, lens-core
lens-vlm    ──► lens-runtime, lens-core
lens-camera ──► lens-core, ux
lens-output ──► lens-core
lens-runtime──► lens-core
lens-core, ux  (no project deps)
```

Rules:

1. Depend downward only.
2. **No edge between the pipelines.** `guidance` never sees `describe`, and vice versa. Everything
   they must coordinate on (speech priority, GPU) lives below both of them.
3. No `lens-*` module depends on `guidance`, `describe`, or `shell`.
4. `describe` does not depend on `lens-camera`: it takes a `Flow<ImageFrame>` from whoever wires it,
   so any frame source works.

### 4.3 Packages

`com.sailens.lens.{core,camera,runtime,vision,vlm,output}`, `com.sailens.guidance`,
`com.sailens.guidance.ui`, `com.sailens.describe`, `com.sailens.ux`, `com.sailens.shell`. Modules are
flat top-level directories (`lens-core/`, …), not nested Gradle paths.

Library modules enable Kotlin `explicitApi()`. Today everything is public by default; once B depends
on these modules, every public symbol is something B can break on.

## 5. What A, B and C become

**A (`sailens-android`, Apache-2.0)** — all library modules plus `app`, the bring-your-own-model
reference app. Still zero weights. BYO weights move from `data/src/main/assets/` to
`app/src/main/assets/` (still git-ignored); assets are APK-wide, so the runners find them unchanged.

**B (`sailens-yolo`, AGPL-3.0)** — a single application module on top of `shell`. It has no
YOLO-specific code, because nothing about YOLO needs any: the runners are driven by model metadata and
the output layouts are generic. Its only Kotlin is its `Application`, which hands `shell` the things
`shell` cannot read for itself — the identity from B's own `BuildConfig`, and which
`NavigationSemantics` to bind.

```text
sailens-yolo/
├── sailens/                 git submodule → sailens-android, pinned commit
├── settings.gradle.kts      includeBuild("sailens")
├── app/
│   ├── build.gradle.kts     applicationId com.sailens.yolo, APP_LICENSE, APP_SOURCE_URL → shell
│   └── src/
│       ├── main/kotlin/     YoloApplication: AppInfo + OSS list + semantics selection → shell
│       ├── main/assets/     sem.tflite, det.tflite (Git LFS)
│       ├── main/res/        app_name
│       └── test/            TfliteModelMetadataReaderTest (contract guard)
├── LICENSE  NOTICE  README*  YOLO_EDITION_NOTICE*  docs/yolo-models*
```

B *may* own YOLO-specific code when it needs some — a new decode head, say. The difference from
today is that it can, because it is an ordinary dependent project, not a fork that must stay
code-identical.

**C (hypothetical)** — same shape: weights, a `VlmRuntimeFactory` wiring, and a `NavigationSemantics`
binding if its detector uses a different taxonomy. A LiteRT-LM runtime implementation itself belongs
in A's `lens-vlm` (Apache, per the Gemma 4 E2B decision); C supplies the model.

## 6. Design decisions

### 6.1 Pipelines are pluggable at runtime

Every app built on `shell` compiles both pipelines in. Each reports whether it can run:

| Pipeline | Available when |
|---|---|
| guidance | a sem model resolves **and** a `NavigationSemantics` is bound (det optional, as today) |
| describe | a `VlmRuntimeFactory` reports a runtime and model |

An unavailable pipeline is **not offered** — hidden, not disabled. A control that can never respond
is one more dead focus stop for a screen-reader user, and "I pressed it and nothing happened" is
indistinguishable from "nothing is ahead".

This covers every combination with one shell: A with no weights (neither available — the app says so
and points at `docs/models.md`), B (guidance only), C (both), a VLM-only app (describe only).

It also dissolves the previously deferred "make sem optional" refactor. Guidance still **requires**
sem. What becomes optional is the guidance pipeline itself.

Describe stays headless: per the earlier decision, no UI entry point exists until a VLM runtime does.

### 6.2 `ClassMapper` splits into `Taxonomy` and `NavigationSemantics`

`ClassMapper` mixes a dataset fact with a navigation judgement:

- **`Taxonomy`** (`lens-vision`) — class count and label names in model output order. A fact about
  the dataset. `CityscapesTaxonomy`, `CocoTaxonomy`.
- **`NavigationSemantics`** (`guidance`) — per-class tables: passable, obstacle, road, traffic light,
  ground type, obstacle category. A decision about walking. `CityscapesNavigationSemantics`,
  `CocoNavigationSemantics`.

Tables, not per-call methods, because tables are what the native kernel consumes: today's
`SemanticClassLookup` is exactly `classCount` + `passable` + `obstacle` + `road` + `trafficLight` +
`groundType` arrays.

The Cityscapes and COCO semantics stay in A. Cityscapes is a dataset taxonomy, not a YOLO one; any
Cityscapes-trained sem model needs it, including a BYO model in A (`docs/models.md` already fixes the
BYO contract at Cityscapes trainId + COCO 80). B selects them rather than owning them — one line in
its `Application`.

**Semantics are always bound explicitly, and there is no neutral fallback. Missing semantics is a
startup error.** A neutral mapping is not safe, it is maximally unsafe. With every class non-passable
the mask is empty, and `ConnectivityChecker` computes:

```text
verticalReachRatio = 0, floodReachRatio = 0, widthRetentionP25 = 0
score = 0.35 + 0.35 + 0.30 = 1.0    →  blockageConfidence 1.0  →  SEVERE  →  CRITICAL
```

That clears `MIN_HARD_BLOCKED_CONFIDENCE` (0.75) and all three hard-blocked reach gates. The app would
announce a top-priority "path blocked" on the first frame and stay latched there.

### 6.3 Segmentation keeps its single native pass

The documented red line for sem is `postprocessBackend = native_score` with `outputReadTimeMs ≈ 0`.
It holds because one native pass reads the LiteRT output buffer **by handle** (zero copy) and computes
argmax *and* the navigation statistics together. Splitting "generic runner" from "navigation
statistics" naively means two passes over a 640×640×19 tensor.

So the runner takes an injected postprocessor:

```text
lens-vision   SegmentationRunner<R>(…, postprocessor: SemanticPostprocessor<R>)
lens-vision   ArgmaxPostprocessor : SemanticPostprocessor<SegmentationMask>          (default)
guidance      NavigationScorePostprocessor : SemanticPostprocessor<SegmentationAnalysisStats>
```

`NavigationScorePostprocessor` is today's `NativeSemanticScorePostprocessor`, moved. The seam already
exists — that postprocessor is injected through DI now. One pass, same as today.

### 6.4 Detections carry class ids; categories belong to guidance

`ObstacleDetection` loses `category`. The detection runner emits class id, label, confidence and box;
`guidance` maps class id → `ObstacleCategory` through `NavigationSemantics`. NMS, letterbox geometry
and layout decoding stay in `lens-vision`. The det red line
(`postprocessBackend = native_bbox_nms_float_handle`) is unaffected: the category lookup is a table
read per surviving box.

### 6.5 One speech router, shared by both pipelines

When both pipelines exist they speak through one channel, and the rules for sharing it live in
`lens-output`, below both:

1. **Channel exclusivity** — app TTS *or* the screen reader, decided once, as today.
2. **Priority** — a guidance alert pre-empts anything describe is saying. Describe never pre-empts
   guidance. Today this is implicit (`speak` flushes, `speakSystemNotice` queues); it becomes an
   explicit `ALERT` / `INFORMATION` priority on the router.
3. **Screen-reader announcements** — the router exposes them as a flow; `shell` collects it in one
   place and performs the announcement (it needs a `View`). Today `LiveAnalysisScreen` does this for
   guidance only. One collector also means the deprecated `announceForAccessibility` has one call
   site to replace.

The haptic vocabulary splits by concern. The one symbol about the output channel itself —
`SPEECH_UNAVAILABLE` — goes to `lens-output`, because a describe-only app needs "speech is dead" too.
Everything else is about guidance and stays in `guidance-ui`: the direction symbols, `BLOCKED`,
`NOTICE`, `SENSOR_FAILURE` (the camera cannot see — detected by guidance's frame-quality analysis) and
`INTERRUPTED` (continuous protection was lost — describe has none to lose). The Phase B blind test
still validates the union as one set: splitting ownership does not split the confusion matrix, and
`SPEECH_UNAVAILABLE`'s documented contrast with `SENSOR_FAILURE` still has to hold.

### 6.6 The prompt belongs to describe, not the engine

`VlmModelConfig` carries `"你是盲人出行助手…"`. That is application text. `lens-vlm` takes a complete
prompt; `describe` owns the system prompt and assembles it. `SceneDescriber` becomes a generic
`VisionLanguageModel` (frame + prompt → `Flow` of chunks); the streaming contract, `trySendBlocking`,
`timeToFirstTokenMs` and `SpeechClauseBuffer` are unchanged.

### 6.7 `lens-runtime` owns what the pipelines contend for

- **The preprocessing cache.** sem and det reuse one YUV→tensor conversion per frame through
  `InputPreprocessCache`. It stays a single shared instance owned by `lens-runtime`, never one per
  runner.
- **GPU arbitration (future).** VLM and YOLO contend for the GPU when both pipelines exist.
  `lens-runtime` is where the policy will live. It is not built now — there is no VLM runtime — but
  the safety constraint is recorded here: if guidance pauses while describe runs, the pause is a loss
  of protection and must be signalled through a non-visual channel.

### 6.8 JNI: `RegisterNatives`, three native libraries

Each library registers its own natives in `JNI_OnLoad`:

| Library | Module | Functions |
|---|---|---|
| `libsailens_runtime.so` | `lens-runtime` | YUV preprocessing (3) |
| `libsailens_vision.so` | `lens-vision` | obstacle postprocessing (4), semantic argmax (1) |
| `libsailens_guidance.so` | `guidance` | fused navigation score kernel (4), connectivity statistics (1) |

Why `RegisterNatives` is step 1: a missed rename then fails at library load, on first launch. With
static names it fails on the **first call** — and some of these functions are on paths almost never
exercised (the int8 postprocessors; the shipped models are float16). A missed rename there would ship.
Keep rules are still needed: `FindClass` looks up class names.

Why per-module libraries rather than one: with `RegisterNatives`, `JNI_OnLoad` must find every class
it registers. One shared library in `lens-runtime` would have to know guidance's class names — an
inverted dependency.

The zero-copy handle path resolves LiteRT's buffer lock/unlock with `dlsym` at runtime (today's
`LiteRtApi` helper); nothing links against LiteRT at build time. That helper becomes a small header in
`lens-runtime`, included by path from the other two modules' CMake. Same repository, so no prefab.

### 6.9 Configuration

Each module keeps depending only on its own config type — already the pattern
(`ProfileBindingsModule` fans `SailensRuntimeProfile` out into per-layer configs). The profile splits
accordingly: hardware detection → `lens-runtime`; per-module defaults → each module; tier selection
and composition → `shell`. Apps pass identity (`AppInfo`, OSS list) into `shell`; `shell` never reads
an app's `BuildConfig`.

### 6.10 What stays in guidance until a second consumer exists

Trace/replay, depth, device sensors, `Stabilizer`, `FrameQualityAnalyzer`: only guidance uses them.
Some are generic in form, but moving them down now would be guessing at a second consumer's needs.
They move when one appears.

Voice input will be a new source module (`lens-audio`) beside `lens-camera`, consumed by `describe`
for spoken questions. Nothing in this structure has to change to add it.

## 7. Development setup: composite build, no Maven

B pins A as a git submodule and includes it as a Gradle composite build:

```kotlin
// sailens-yolo/settings.gradle.kts
includeBuild("sailens")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("sailens/gradle/libs.versions.toml")) }
    }
}
```

- **No publishing, no version numbers.** B's `implementation("com.sailens:shell")` resolves to A's
  project through dependency substitution; A's modules only need `group = "com.sailens"` for the
  coordinates to match.
- **One version catalog.** B reads A's; composite builds need consistent AGP and Kotlin versions.
- **Day to day:** develop A in its own checkout; in B, `git submodule update --remote` and commit the
  new pointer. For tight iteration, edit inside B's submodule directly — it is a full clone of A.
- **AGPL corresponding source for free:** a B release tag plus the submodule commit it pins is the
  exact source of that build.

Composite builds with Android libraries, resources, assets and CMake are standard, but step 3 (§11)
validates this on the real project before any large move depends on it.

## 8. Licensing after the change

- A: Apache-2.0, all modules, zero weights — unchanged.
- B: AGPL-3.0 combined work = A's modules (Apache, pinned) + YOLO26 weights. Apache → AGPL
  combination is permitted; A is unaffected.
- The licence boundary is now the dependency graph. No git history is shared, so nothing has to guard
  against AGPL history entering A.
- **Unchanged and still open:** whether Cityscapes' non-commercial terms permit public distribution of
  B's sem weights. This refactor does not touch that question.

## 9. What goes away

In B: `.gitattributes` `merge=ours`, the `merge.ours.driver` setup, the `upstream` remote and its
`DISABLED` push URL, the sync recipe, the AGENTS.md prefix convention, and the "zero code changes"
invariant. B gets fresh history (force-push is acceptable for both repositories).

In A: the `data/src/main/assets` BYO convention (→ `app/src/main/assets`), `mlModelBinding`.

## 10. Where pending release items land

| Item | Home |
|---|---|
| Remove the unused `dataSync` foreground service (from `litert → ai-delivery → work-runtime`) | `lens-runtime` manifest, so every app inherits it — verify a library-level `tools:node="remove"` propagates |
| Privacy policy | per app |
| Safety disclaimer / first run | `shell`, text per pipeline |
| Release signing | per app |
| Cityscapes non-commercial decision | unchanged, B |

## 11. Migration plan

Every step ends with A building, B building against it, and tests green. Steps that touch native code
also need an on-device launch and a guidance session.

| # | Step | Device check |
|---|---|---|
| 1 | JNI → `RegisterNatives`, no package moves yet | yes |
| 2 | Extract `shell` from `:app`; `app` becomes identity + config only | — |
| 3 | **B becomes a thin app on a composite build** (fresh history). Validates §7 early; from here B is a consumer, never a fork. Retire the "`main` is append-only" guardrail in both `AGENTS.md` files in the same step: it existed to protect the fork's merge-base, which this step removes | — |
| 4 | Extract `lens-core`; `lens-camera` stops depending on the navigation core | — |
| 5 | Extract `lens-runtime`; native split part 1 | yes |
| 6 | Extract `lens-vision`; `Taxonomy` / `NavigationSemantics` split; postprocessor seam; native split part 2 | yes |
| 7 | Rename the remainder to `guidance` / `guidance-ui`; native split part 3 | yes |
| 8 | Extract `lens-output`; speech router with explicit priority | yes |
| 9 | Extract `lens-vlm` and `describe`; prompt moves to `describe` | — |
| 10 | **Behaviour change:** pipeline availability, hidden unavailable pipelines, zero-model state in A | yes |
| 11 | Docs: README architecture, `AGENTS.md`, `models.md` asset path, B's README and `yolo-models.md` | — |

Step 3 comes this early on purpose. It removes the fork immediately, and it proves the composite build
before twelve modules depend on it. B touches only `shell`'s entry point (identity + selections), so
the internal moves in steps 4–9 do not reach it; step 10 may change what that entry point takes.

## 12. Verification

- **Tests are counted, not just run.** Baseline 171. Tests move with their code; the total after each
  step must not drop without an explained reason.
- **Performance red lines** (from `models.md`), checked on device after steps 5–7:
  `sem: postprocessBackend = native_score, outputReadTimeMs ≈ 0` and
  `det: postprocessBackend = native_bbox_nms_float_handle`.
- **Before/after session traces** on the same device and route: blocked frames, event count, average
  and p95 pipeline time. The trace tooling compares recorded sessions; it does not replay frames, so
  this is a coarse check, not a golden-output test.
- Native code has no JVM test coverage. Every native step is a device step.

## 13. Open questions

1. **Names.** `lens-*`, `guidance`, `describe`, `shell` — and the `com.sailens.lens.*` package root.
2. **Fewer modules?** Twelve replaces six. The two cheap merges, if wanted: `guidance` + `guidance-ui`
   (loses a pure-logic boundary, nothing else), and `lens-vlm` + `describe` (the engine/prompt split
   becomes package-level).
