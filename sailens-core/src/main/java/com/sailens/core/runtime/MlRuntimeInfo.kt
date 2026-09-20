package com.sailens.core.runtime

data class MlRuntimeInfo(
    val accelerator: String = UNKNOWN,
    val acceleratorSelection: String = UNKNOWN,
    val preprocessBackend: String = UNKNOWN,
    val postprocessBackend: String = UNKNOWN,
) {
    companion object {
        const val UNKNOWN = "unknown"

        fun unavailable(reason: String): MlRuntimeInfo = MlRuntimeInfo(
            accelerator = reason,
            acceleratorSelection = reason,
            preprocessBackend = reason,
            postprocessBackend = reason,
        )

        fun cached(previous: MlRuntimeInfo): MlRuntimeInfo = previous.copy(
            preprocessBackend = "cached",
            postprocessBackend = "cached",
        )

        fun skipped(reason: String): MlRuntimeInfo = MlRuntimeInfo(
            accelerator = reason,
            acceleratorSelection = reason,
            preprocessBackend = reason,
            postprocessBackend = reason,
        )
    }
}
