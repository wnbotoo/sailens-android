package com.sailens.runtime

import android.content.Context
import java.io.InputStream

/**
 * Where a model's bytes come from.
 *
 * Decouples the LiteRT loading layer from packaging. Today every model is an [Asset] bundled in the
 * APK; a model delivered via Play Asset Delivery (AI Pack) or a self-hosted download instead resolves
 * to a [File] under the app's storage. The accelerator/NPU compile path is identical for both — only
 * the byte source differs (asset mmap vs. file mmap), so the loading layer threads a [ModelSource]
 * rather than a bare asset path.
 *
 * The seam that decides Asset vs. File (and triggers an asset-pack/download fetch when needed) is
 * [ModelSourceResolver]: a config carrying a [File] source is loaded straight from disk.
 */
sealed interface ModelSource {

    /** Short identifier for logs and error messages. */
    val label: String

    /**
     * Model packaged in the APK's `assets/`, read through the app's
     * [android.content.res.AssetManager].
     */
    data class Asset(val assetPath: String) : ModelSource {
        override val label: String get() = assetPath
    }

    /** Model materialized as a file on disk: an asset-pack location, or a downloaded + cached file. */
    data class File(val file: java.io.File) : ModelSource {
        override val label: String get() = file.name
    }

    /** Opens the model bytes (e.g. for metadata reading). The caller owns the stream and must close it. */
    fun openStream(context: Context): InputStream = when (this) {
        is Asset -> context.assets.open(assetPath)
        is File -> file.inputStream()
    }

    /**
     * Verifies the model is present and readable, throwing [IllegalStateException] otherwise. For an
     * [Asset] this means it was packaged into the APK; for a [File] it means it has already been
     * delivered/downloaded. Whatever fetches an asset pack or downloaded file is responsible for
     * doing so *before* this check runs.
     */
    fun ensureAvailable(context: Context) {
        when (this) {
            is Asset -> runCatching { context.assets.open(assetPath).use {} }
                .onFailure { error ->
                    throw IllegalStateException(
                        "Model asset '$assetPath' is not packaged in app assets.",
                        error,
                    )
                }

            is File -> check(file.isFile && file.canRead()) {
                "Model file '${file.absolutePath}' is not available " +
                    "(asset pack / download not delivered yet?)."
            }
        }
    }
}
