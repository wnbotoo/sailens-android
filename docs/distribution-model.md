**English** | [简体中文](distribution-model.zh-CN.md)

# Sailens repository and distribution model

> Status: **Android product identity implemented; GitHub repository rename pending.** Sailens
> Android uses the reference-host identity described here, and the first-party distribution uses
> the Sailens product identity in code. The distribution repository is renamed in place only after
> final validation.

## 1. Decision

Sailens has one Android platform and one first-party Android distribution.

**Sailens Android — `wnbotoo/sailens-android`**

- the canonical Android implementation of Sailens;
- Apache-2.0;
- model-neutral and zero-weights;
- nine reusable `sailens-*` libraries plus a thin reference host;
- the place where reusable capabilities, safety behaviour, runtime abstractions and shared UI live;
- buildable and installable for development and BYO-model validation, but **not the application that
  Sailens intends to publish to an app store**.

**Official Sailens Android distribution — `wnbotoo/sailens-app`**

- the first-party application maintained by Sailens for end users;
- the application that is intended to be published under the product name **Sailens**;
- a thin host over Sailens Android, consumed through an exact git-submodule pin and Gradle composite
  build;
- owns the official model bundle, product identity, capability expectations, release configuration,
  store-facing assets and distribution-specific notices;
- currently AGPL-3.0 because of the bundled model/licensing choices. That licence boundary is a
  property of the distribution and its bundled material, not of the Sailens Android platform.

Other first- or third-party distributions may consume Sailens Android in the same way. They do not
need to fork the platform.

The governing rule is:

> **Sailens Android implements capabilities. The official Sailens app selects, packages and releases
> them as the product.**

## 2. Target naming and Android identity

The accepted target is:

| Role | Repository | Product/repository name | Android namespace | applicationId | Store product |
|---|---|---|---|---|---|
| Platform + reference host | `sailens-android` | Sailens Android | `com.sailens` | `com.sailens.reference` | no |
| Official distribution | `sailens-app` | Sailens | `com.sailens` | `com.sailens` | yes |

The Kotlin/Android namespace remains `com.sailens` in both repositories. Namespace and
`applicationId` solve different problems; changing the product identity does **not** justify a
package move.

"YOLO" is no longer part of the long-term product identity. It remains where it is technically and
legally relevant: model provenance, model-specific compatibility, attribution and licence records.

## 3. Responsibility boundary

### Sailens Android owns reusable capability

Examples:

- `sailens-core`, `sailens-camera`, `sailens-runtime`, `sailens-vision`;
- Guidance and Describe implementations;
- output, accessibility and safety mechanisms;
- the reusable shell and capability/preflight model;
- model/runtime abstractions and generic tensor-layout support;
- shared tests, native code and architecture contracts.

Its `:app` is a **reference host and development harness**. It exists to assemble and validate the
platform, including BYO-model workflows. Zero available pipelines is legal for that host.

### The official Sailens distribution owns product choices

Examples:

- bundled model weights and their provenance;
- which capabilities are required for a release;
- official defaults and supported runtime/profile choices;
- product name, application ID, icons and store metadata;
- source/licence/about identity;
- release signing and release-channel configuration;
- privacy/distribution configuration that is specific to the shipping product.

The official app should stay thin. If code in the distribution becomes a generally reusable Sailens
capability, it belongs in Sailens Android after the appropriate licence review.

## 4. Source consumption now; no Maven publication yet

The nine Gradle library modules are **architecture, compile, dependency, test and native boundaries**.
They are not a promise that nine public Maven artifacts exist.

For the current phase, the official app consumes Sailens Android from source:

```text
sailens-app
└── sailens/  -> wnbotoo/sailens-android @ exact main commit
    └── includeBuild("sailens")
```

The exact Sailens Android commit is the platform version boundary for a product build. The official
application has its own product release version/tag.

This remains the preferred relationship through the first public Sailens releases because it:

- makes a release reproducible at an exact platform commit;
- exposes API drift immediately at compile time;
- keeps Android resources, native libraries, consumer rules and source debugging on the real project;
- avoids prematurely freezing an SDK/API surface and artifact topology.

### When Maven becomes justified

Maven publication is deferred until there is a real external SDK use case. Revisit it when most of
these are true:

1. at least one independently maintained consumer exists beyond the official Sailens app;
2. the intended public library APIs are stable enough to support compatibility commitments;
3. every module intended for publication has deliberate explicit API surface;
4. API/binary compatibility checks are in CI;
5. the project can state which modules are public artifacts and which are implementation details;
6. source-composite consumption is a material burden for external users.

At that point, design the public artifact surface deliberately. Do **not** assume every internal
Gradle module should be published one-for-one.

## 5. Licence boundary

Sailens Android remains Apache-2.0 and ships no model weights.

The official distribution currently remains AGPL-3.0 because of its bundled models and related
licensing decisions. Renaming the product from "YOLO Edition" to "Sailens" does not erase model,
dataset, attribution or corresponding-source obligations.

If the official model bundle changes in the future, the distribution licence can be re-evaluated
separately. That does not change the platform licence automatically.

## 6. Git history and release identity

### Sailens Android

- `main` is the canonical, append-only platform history.
- Do not fresh-root or squash the entire repository history.
- Feature branches may still be squash-merged when one semantic main commit is the clearest result.
- Once a commit is on `main`, downstream consumers may pin it; published main history is therefore
  a compatibility/reproducibility boundary.

### Official Sailens app

- keep the existing fresh-root repository history; do **not** delete and recreate it merely for the
  rename;
- rename the current distribution repository in place to `sailens-app` after final validation;
  preserve the existing fresh-root history and do not recreate the repository;
- each product release pins an exact Sailens Android `main` commit;
- release metadata should also record the bundled model versions/hashes and provenance.

A typical product release is therefore conceptually:

```text
Sailens v1.x.y
├── Sailens Android @ <main SHA>
├── semantic model @ <version/hash/provenance>
├── detection model @ <version/hash/provenance>
└── official product configuration
```

## 7. Implemented identity

### `sailens-android`

- repository: `sailens-android`;
- namespace: `com.sailens`;
- reference-host applicationId: `com.sailens.reference`;
- launcher identity: **Sailens Reference**.

### Official distribution

- repository: `sailens-app`;
- namespace: `com.sailens`;
- applicationId: `com.sailens`;
- product name: **Sailens**;
- "YOLO" appears only in model provenance/licence material, not product branding.

This identity does **not** change the nine-module architecture, package names, composite-build
mechanism or capability model.

## 8. Non-goals of the positioning change

This decision does not:

- publish Sailens libraries to Maven;
- turn Sailens Android into an app-store product;
- move reusable product logic into the distribution repository;
- rename Kotlin packages;
- change the module graph;
- change the current model licences by branding alone;
- rewrite Sailens Android history;
- delete/recreate the official distribution repository.
