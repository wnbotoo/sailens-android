package com.sailens.core.runtime

public data class MlRuntimeInfo(
    val accelerator: String = UNKNOWN,
    val acceleratorSelection: String = UNKNOWN,
    val preprocessBackend: String = UNKNOWN,
    val postprocessBackend: String = UNKNOWN,
) {
    public companion object {
        public const val UNKNOWN: String = "unknown"

        public fun unavailable(reason: String): MlRuntimeInfo = MlRuntimeInfo(
            accelerator = reason,
            acceleratorSelection = reason,
            preprocessBackend = reason,
            postprocessBackend = reason,
        )

        public fun cached(previous: MlRuntimeInfo): MlRuntimeInfo = previous.copy(
            preprocessBackend = "cached",
            postprocessBackend = "cached",
        )

        public fun skipped(reason: String): MlRuntimeInfo = MlRuntimeInfo(
            accelerator = reason,
            acceleratorSelection = reason,
            preprocessBackend = reason,
            postprocessBackend = reason,
        )
    }
}
