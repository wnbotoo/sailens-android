package com.sailens.presentation.scene

sealed interface SceneAnalysisUiEffect {
    data class ShowToast(val message: String) : SceneAnalysisUiEffect

    /**
     * 请求屏幕阅读器播报一条导航提示。
     *
     * 只在检测到屏幕阅读器工作时发出——此时本应用自带的 TTS 是关着的，播报完全交给 TalkBack，
     * 以免同一句话被念两遍。屏幕阅读器没开时走 [com.sailens.presentation.device.SpeechManager]。
     */
    data class Announce(val text: String) : SceneAnalysisUiEffect
}
