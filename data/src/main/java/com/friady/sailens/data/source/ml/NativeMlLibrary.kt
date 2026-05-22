package com.friady.sailens.data.source.ml

internal object NativeMlLibrary {
    const val NAME = "sailens_ml"

    val isAvailable: Boolean = runCatching {
        System.loadLibrary(NAME)
    }.isSuccess
}
