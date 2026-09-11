package com.sailens.data.source.ml.vlm

import com.sailens.data.source.ml.ModelAcceleratorBackend
import com.sailens.data.source.ml.ModelAcceleratorSelectionMode

/**
 * VLM (vision-language model) configuration for [LiteRtVlmEngine].
 *
 * @param modelPath path to the model bundle (e.g. a `.task` / `.litertlm`). For NPU/Play this is the
 *   path returned by an AI Pack model provider; for local dev it can be an assets / files path.
 * @param acceleratorBackend preferred backend; VLM benefits most from the NPU (large model, power).
 * @param acceleratorSelectionMode PREFER_BACKEND so a device without NPU degrades to GPU/CPU.
 * @param maxTokens decode budget per call (keep small — descriptions are 1-2 sentences).
 */
data class VlmModelConfig(
    val modelPath: String,
    val acceleratorBackend: ModelAcceleratorBackend = ModelAcceleratorBackend.NPU,
    val acceleratorSelectionMode: ModelAcceleratorSelectionMode = ModelAcceleratorSelectionMode.PREFER_BACKEND,
    val maxTokens: Int = 256,
    val temperature: Float = 0.4f,
    val topK: Int = 40,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        /**
         * Neutral asset name for the VLM bundle, matching the bring-your-own-model convention the
         * vision models use (`sem.tflite` / `det.tflite`): the catalog names a *role*, never a
         * specific upstream model. Nothing reads this yet — with no [VlmRuntimeFactory] wired the
         * path is never opened — but it fixes the name a future runtime resolves.
         */
        const val DEFAULT_MODEL_ASSET = "vlm.litertlm"

        const val DEFAULT_SYSTEM_PROMPT =
            "你是盲人出行助手。用一两句简短中文，描述正前方最重要的东西：障碍物、行人、可走的路。" +
                "具体、冷静，不要寒暄，不要罗列无关细节。"
    }
}
