package com.sailens.domain.model.analysis

/**
 * 输入帧的可用性判定。
 *
 * 非 [OK] 时，画面本身不可信，所有基于它的导航提示都必须让位给这条状态提示——
 * 见 [com.sailens.domain.processor.decision.EventConflictResolver]。
 */
enum class FrameQuality {
    /** 画面可用。 */
    OK,

    /** 镜头被遮挡（手指、口袋、镜头盖）：画面几乎没有纹理。 */
    OBSTRUCTED,

    /** 环境光不足：有纹理但整体过暗，检测结果不可靠。 */
    TOO_DARK,
}
