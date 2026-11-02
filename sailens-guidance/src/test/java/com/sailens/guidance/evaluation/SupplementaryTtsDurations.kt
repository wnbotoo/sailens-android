package com.sailens.guidance.evaluation

import com.sailens.guidance.model.scene.SceneEventMessageKeys

/**
 * Durations of the sentences newer than the frozen [TtsDurations] table and absent from every recording
 * (`docs/experiments/detfirst-tts-durations-2026-10.md`, "Supplement"): zh-CN, `com.google.android.tts` at
 * speech rate 1.30 on the recording phone, each the median of three plays. The frozen table's 71 sentences
 * measured again in the same run differ from it by −28 to +19 ms. A wording taken from here is a fallback
 * one, with ε = 300 ms.
 */
internal object SupplementaryTtsDurations {
    val ZH_CN_RATE_130: Map<String, Long> = mapOf(
        SceneEventMessageKeys.CAMERA_RESTORED to 1450L,
        SceneEventMessageKeys.LIGHT_RESTORED to 1437L,
        SceneEventMessageKeys.GROUND_RECOGNIZED to 2649L,
        SceneEventMessageKeys.MODE_OBSTACLES_AND_BLOCKED to 2695L,
    )
}
