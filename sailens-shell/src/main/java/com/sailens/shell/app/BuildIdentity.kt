package com.sailens.shell.app

/**
 * Which build this is, for the provenance of recorded data (field captures). Guidance's behaviour
 * depends on the code and on the model weights, which are not in git, so both are identified. Bound
 * by the host app, which knows its own commit and packages the weights; a distribution that pins
 * this repository records its own SHA, which identifies both.
 *
 * @property gitSha the full commit SHA, suffixed "-dirty" when the working tree differed from it
 *   (modified or untracked files); null when the build could not tell.
 * @property modelArtifacts packaged model file name → "sha256:<hex>", computed at build time.
 */
data class BuildIdentity(
    val gitSha: String?,
    val modelArtifacts: Map<String, String> = emptyMap(),
)
