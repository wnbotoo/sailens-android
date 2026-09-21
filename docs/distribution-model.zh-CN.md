[English](distribution-model.md) | **简体中文**

# Sailens 仓库与发行模型

> 状态：**目标定位已确认。**本文先记录产品与仓库决策，真正的 identity 改动放到后续独立变更中。
> 因此当前代码仍可能使用旧的 applicationId，下游仓库也仍可能叫 `sailens-yolo`；这不是本文档
> 未生效，而是有意把“决策”与“实施”拆开。

## 1. 决策

Sailens 有一个 Android 平台，以及一个官方维护的 Android 发行版。

**Sailens Android —— `wnbotoo/sailens-android`**

- Sailens 在 Android 上的 canonical implementation；
- Apache-2.0；
- model-neutral、零权重；
- 9 个可复用的 `sailens-*` library 加一个薄 reference host；
- 所有可复用 capability、安全行为、runtime abstraction、共享 UI 的归属地；
- 可以 build/install，用于开发和 BYO-model 验证，但**不是 Sailens 计划提交应用商店的最终产品**。

**Sailens 官方 Android 发行版 —— 当前 `wnbotoo/sailens-yolo`，目标
`wnbotoo/sailens-app`**

- Sailens 官方维护、面向最终用户的一方应用；
- 未来以产品名 **Sailens** 上架；
- 是 Sailens Android 之上的薄 host，通过精确 git-submodule pin + Gradle composite build
  消费平台；
- 自己拥有官方 model bundle、产品 identity、capability expectation、发布配置、商店资产和
  distribution-specific notice；
- 当前因打包模型及其许可选择而采用 AGPL-3.0。这个 licence boundary 属于发行版及其所带材料，
  不属于 Sailens Android 平台本身。

未来其他官方或第三方 distribution 也可以用同样方式消费 Sailens Android，不需要 fork 平台。

总原则：

> **Sailens Android 实现 capability；官方 Sailens App 选择、打包并把这些 capability 作为产品发布。**

## 2. 目标命名与 Android identity

已确认的目标是：

| 角色 | 仓库 | 产品/仓库名称 | Android namespace | applicationId | 商店产品 |
|---|---|---|---|---|---|
| 平台 + reference host | `sailens-android` | Sailens Android | `com.sailens` | `com.sailens.reference` | 否 |
| 官方发行版 | `sailens-app`（当前 `sailens-yolo`） | Sailens | `com.sailens` | `com.sailens` | 是 |

两个仓库的 Kotlin/Android namespace 都继续使用 `com.sailens`。namespace 与
`applicationId` 解决的是不同问题；产品 identity 调整**不需要**做 package move。

长期产品名称不再包含 “YOLO”。YOLO 只保留在确实需要它的地方：model provenance、
model-specific compatibility、attribution 与 licence 记录。

## 3. 职责边界

### Sailens Android 负责可复用 capability

包括但不限于：

- `sailens-core`、`sailens-camera`、`sailens-runtime`、`sailens-vision`；
- Guidance 与 Describe 的实现；
- output、accessibility 与 safety mechanism；
- 可复用 shell 与 capability/preflight model；
- model/runtime abstraction 和通用 tensor-layout 支持；
- 公共测试、native code、architecture contract。

它的 `:app` 是 **reference host / development harness**，用于组装和验证整个平台，包括
BYO-model 工作流。这个 host 合法地允许零 available pipeline。

### 官方 Sailens 发行版负责产品选择

包括：

- 随发行物打包的模型及其 provenance；
- 一个 release 承诺哪些 capability 必须可用；
- 官方默认值和受支持的 runtime/profile 选择；
- 产品名、applicationId、图标、商店 metadata；
- source/licence/about identity；
- signing 与 release-channel 配置；
- 只属于最终发行产品的 privacy/distribution 配置。

官方 App 应保持很薄。如果其中出现可被一般 Sailens consumer 复用的 capability，应在许可检查后
回到 Sailens Android，而不是在发行仓库里继续长大。

## 4. 当前继续 source consumption，不发布 Maven

9 个 Gradle library module 首先是 **architecture、compile、dependency、test、native boundary**，
并不等于项目已经承诺存在 9 个公开 Maven artifact。

当前阶段，官方 App 继续从源码消费 Sailens Android：

```text
sailens-app
└── sailens/  -> wnbotoo/sailens-android @ 精确 main commit
    └── includeBuild("sailens")
```

一个产品构建所 pin 的 Sailens Android commit，就是平台版本边界；官方 App 自己拥有独立的产品
release version/tag。

至少在 Sailens 最初的公开版本阶段，这种关系更合适，因为它：

- 用精确平台 commit 保证 release 可复现；
- API drift 会直接在编译期暴露；
- Android resources、native libraries、consumer rules 和源码调试都走真实 project；
- 不会过早冻结 SDK/API surface 与 artifact topology。

### 什么时候才值得发布 Maven

等真正出现外部 SDK 使用需求后再重新评估。大部分条件满足时再做：

1. 除官方 Sailens App 外，至少出现一个独立维护的真实 consumer；
2. 计划公开的 library API 已相对稳定，可以承担 compatibility commitment；
3. 所有计划发布的 module 都有经过设计的 explicit API surface；
4. CI 中存在 API/binary compatibility check；
5. 项目能明确说明哪些 module 是 public artifact，哪些只是 implementation detail；
6. source/composite consumption 已经对外部用户形成实质负担。

到那时重新设计公开 artifact surface。**不要**因为内部有 9 个 Gradle module，就默认 Maven 也要
一对一发布 9 个 artifact。

## 5. Licence boundary

Sailens Android 继续是 Apache-2.0，并且不附带模型权重。

官方发行版当前继续是 AGPL-3.0，因为它现在打包的模型与相关许可选择如此。把产品名称从
“YOLO Edition”改成“Sailens”不会消除模型、数据集、attribution 或 corresponding-source 义务。

未来如果官方 model bundle 变化，可以单独重新评估发行版 licence；这不会自动改变平台 licence。

## 6. Git history 与 release identity

### Sailens Android

- `main` 是 canonical、append-only 的平台历史；
- 不 fresh-root，也不把整个仓库历史 squash 成一条；
- feature branch 仍可以在“一个语义完整 main commit”最清晰时使用 squash merge；
- commit 一旦进入 `main`，下游就可能 pin 它，因此已发布 main history 是 compatibility /
  reproducibility boundary。

### 官方 Sailens App

- 保留当前已经 fresh-root 后的仓库历史；**不要**仅为了改名删除并重建仓库；
- 原地把 `sailens-yolo` rename 为 `sailens-app`；
- 每个产品 release 都 pin 一个精确的 Sailens Android `main` commit；
- release metadata 同时记录打包模型的版本/hash/provenance。

因此一个产品 release 可以抽象表示为：

```text
Sailens v1.x.y
├── Sailens Android @ <main SHA>
├── semantic model @ <version/hash/provenance>
├── detection model @ <version/hash/provenance>
└── official product configuration
```

## 7. 后续 identity migration

先合入本文档，之后用独立变更真正实施。

### `sailens-android`

- 仓库名继续是 `sailens-android`；
- namespace 继续是 `com.sailens`；
- reference host 的 `applicationId` 从 `com.sailens` 改为
  `com.sailens.reference`；
- 必要时同步 reference-host identity/about 文案。

### 官方发行版

- 仓库 `sailens-yolo` rename 为 `sailens-app`；
- namespace 继续是 `com.sailens`；
- `applicationId` 从 `com.sailens.yolo` 改为 `com.sailens`；
- 对最终用户的产品名改为 **Sailens**；
- 更新 source URL、identity test、release/store metadata；
- 把 “YOLO Edition” 从产品 branding 中移走，只保留在 model provenance/licence 文档。

这次 migration **不需要**改变 9-module architecture、package 名、composite build 机制或 capability
model。

## 8. 本次定位调整的非目标

这项决策不会：

- 现在就把 Sailens libraries 发布到 Maven；
- 把 Sailens Android 变成 app-store 产品；
- 把可复用产品逻辑搬进发行仓库；
- rename Kotlin package；
- 修改 module graph；
- 仅靠品牌改名改变现有 model licence；
- rewrite Sailens Android history；
- 删除并重建官方发行仓库。
