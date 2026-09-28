package com.sailens.app

import android.content.Context
import com.sailens.shell.app.BuildIdentity
import org.json.JSONObject

/** Generated at build time by the `generateBuildProvenance` task (app/build.gradle.kts). */
private const val BUILD_PROVENANCE_ASSET = "build_provenance.json"

/**
 * Reads the build's provenance: commit and packaged model hashes. A missing or unreadable file
 * gives an unknown identity rather than an error -- provenance must never stop the app.
 */
internal fun readBuildIdentity(context: Context): BuildIdentity = runCatching {
    val json = JSONObject(context.assets.open(BUILD_PROVENANCE_ASSET).bufferedReader().use { it.readText() })
    val models = json.optJSONObject("modelArtifacts")
    BuildIdentity(
        gitSha = json.optString("gitSha").ifEmpty { null },
        modelArtifacts = models?.keys()?.asSequence()?.associateWith { models.getString(it) }.orEmpty(),
    )
}.getOrDefault(BuildIdentity(gitSha = null))
