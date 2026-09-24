package com.sailens.guidance.model.analysis

import com.sailens.guidance.model.perception.DetectedObstacle

/**
 * 场景快照
 */
data class SceneSnapshot(
    val timestamp: Long,
    val obstacles: List<DetectedObstacle>,
    val bottomCoverage: Float,
    val connectivity: WalkPathConnectivity,
    val sceneElements: SceneElements,
    val roadSafety: RoadSafetyState,
    val groundTypeChange: GroundTypeChange?,
    /** det 跟踪框接地带从可行走区抠除的像素比例（用于观察 det/sem 交叉验证影响）。 */
    val occludedPassableRatio: Float = 0f,
    /**
     * 本帧输入是否可用。由 [com.sailens.guidance.processor.analysis.FrameQualityAnalyzer] 在
     * 感知链路之外单独判定后回填——它看的是原始画面，而不是模型输出。
     */
    val frameQuality: FrameQuality = FrameQuality.OK,
    /** 语义模型是否认得脚下的地面；不认得时连通性不可信。 */
    val groundRecognition: GroundRecognition = GroundRecognition.RECOGNIZED,
    /** 底部中央区域里模型解释不了的像素比例（未去抖，诊断用）。 */
    val unrecognizedGroundRatio: Float = 0f,
)

/**
 * 场景元素
 */
data class SceneElements(
    val hasIntersection: Boolean = false,
    val hasCrosswalk: Boolean = false,
    val hasTactilePaving: Boolean = false,
    val hasTrafficLight: Boolean = false,
)
