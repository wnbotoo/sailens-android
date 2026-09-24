package com.sailens.guidance.model.analysis

/**
 * 语义模型是否认得用户脚下的地面。
 *
 * 连通性（前方不通 / 路况复杂 / 收窄）全部建立在"画面底部是可行走地面"这个前提上。模型的类别表里
 * 没有眼前这种地面时（Cityscapes 没有室内地板，近处的地板会被判成 building），前提不成立，
 * 连通性就会持续输出"前方不通"——而那只是模型不认识，不是真的走不通。
 *
 * 见 [com.sailens.guidance.processor.analysis.GroundRecognitionAnalyzer]。
 */
enum class GroundRecognition {
    /** 画面底部是模型认得的地面或障碍物，连通性可信。 */
    RECOGNIZED,

    /**
     * 本帧底部大面积是模型解释不了的类别，但还没持续到足以确认。连通性提示先不播（它们建立在
     * 这张画面上），状态提示也先不播（可能只是一两帧的抖动）。
     */
    UNCERTAIN,

    /** 已确认模型认不出地面：暂停连通性提示，并告诉用户。 */
    UNRECOGNIZED,
    ;

    /** 连通性类提示此时是否可信。 */
    val isPathJudgementReliable: Boolean
        get() = this == RECOGNIZED
}
