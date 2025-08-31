package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.common.UrgencyLevel
import com.friady.sailens.domain.model.perception.DetectedObstacle
import com.friady.sailens.domain.model.perception.RawObstacle
import java.util.UUID
import kotlin.collections.iterator

/**
 * 障碍物跟踪器
 */
class ObstacleTracker(
    private val config: PerceptionConfig,
) {
    private val tracks = mutableMapOf<UUID, TrackState>()

    data class TrackState(
        val id: UUID,
        var boundingBox: NormalizedRect,
        var category: ObstacleCategory,
        var zone: DirectionZone,
        var distance: DistanceLevel,
        var confidence: Float,
        var stableFrames: Int,
        var missedFrames: Int,
        var areaRatio: Float,
        var lastSeen: Long,
    )

    /**
     * 更新跟踪（接收新检测）
     */
    fun update(detections: List<RawObstacle>, timestamp: Long): List<DetectedObstacle> {
        // 1. 计算 IoU 匹配
        val matches = matchDetections(detections)

        // 2. 更新匹配的轨迹
        val matchedTrackIds = mutableSetOf<UUID>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        for ((trackId, detectionIndex) in matches) {
            val track = tracks[trackId] ?: continue
            val detection = detections[detectionIndex]

            track.boundingBox = detection.boundingBox
            track.category = detection.category
            track.zone = detection.zone
            track.distance = detection.distance
            track.confidence = detection.confidence
            track.areaRatio = detection.areaRatio
            track.stableFrames++
            track.missedFrames = 0
            track.lastSeen = timestamp

            matchedTrackIds.add(trackId)
            matchedDetectionIndices.add(detectionIndex)
        }

        // 3. 创建新轨迹
        for ((index, detection) in detections.withIndex()) {
            if (index !in matchedDetectionIndices) {
                val newTrack = TrackState(
                    id = UUID.randomUUID(),
                    boundingBox = detection.boundingBox,
                    category = detection.category,
                    zone = detection.zone,
                    distance = detection.distance,
                    confidence = detection.confidence,
                    stableFrames = 1,
                    missedFrames = 0,
                    areaRatio = detection.areaRatio,
                    lastSeen = timestamp
                )
                tracks[newTrack.id] = newTrack
            }
        }

        // 4. 处理丢失的轨迹
        incrementMissedFramesAndCleanup(matchedTrackIds)

        // 5. 返回稳定的轨迹
        return getStableObstacles(timestamp)
    }

    /**
     * 预测（无新检测时调用，如交替推理的偶数帧）
     */
    fun predict(timestamp: Long): List<DetectedObstacle> {
        // 所有轨迹都增加 missedFrames
        incrementMissedFramesAndCleanup(emptySet())

        // 返回仍然有效的轨迹，但降低置信度
        return tracks.values
            .filter { it.stableFrames >= config.trackerMinStableFrames }
            .map { track ->
                DetectedObstacle(
                    id = track.id,
                    boundingBox = track.boundingBox,
                    category = track.category,
                    zone = track.zone,
                    distance = track.distance,
                    urgency = calculateUrgency(track),
                    confidence = track.confidence * 0.9f,  // 预测时降低置信度
                    stableFrames = track.stableFrames,
                    areaRatio = track.areaRatio,
                    timestamp = timestamp
                )
            }
    }

    /**
     * 增加未匹配轨迹的 missedFrames，并清理过期轨迹
     */
    private fun incrementMissedFramesAndCleanup(matchedTrackIds: Set<UUID>) {
        val expiredIds = mutableListOf<UUID>()

        for ((id, track) in tracks) {
            if (id !in matchedTrackIds) {
                track.missedFrames++
                if (track.missedFrames >= config.trackerMaxMissedFrames) {
                    expiredIds.add(id)
                }
            }
        }

        expiredIds.forEach { tracks.remove(it) }
    }

    /**
     * 获取稳定的障碍物列表
     */
    private fun getStableObstacles(timestamp: Long): List<DetectedObstacle> {
        return tracks.values
            .filter { it.stableFrames >= config.trackerMinStableFrames }
            .map { track ->
                DetectedObstacle(
                    id = track.id,
                    boundingBox = track.boundingBox,
                    category = track.category,
                    zone = track.zone,
                    distance = track.distance,
                    urgency = calculateUrgency(track),
                    confidence = track.confidence,
                    stableFrames = track.stableFrames,
                    areaRatio = track.areaRatio,
                    timestamp = timestamp
                )
            }
    }

    private fun matchDetections(detections: List<RawObstacle>): List<Pair<UUID, Int>> {
        if (tracks.isEmpty() || detections.isEmpty()) {
            return emptyList()
        }

        val trackList = tracks.values.toList()
        val iouMatrix = Array(trackList.size) { FloatArray(detections.size) }

        for (i in trackList.indices) {
            for (j in detections.indices) {
                iouMatrix[i][j] = trackList[i].boundingBox.iou(detections[j].boundingBox)
            }
        }

        val matches = mutableListOf<Pair<UUID, Int>>()
        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Int>()

        // 贪婪匹配
        while (true) {
            var maxIou = config.trackerIoUThreshold
            var bestTrack = -1
            var bestDetection = -1

            for (i in trackList.indices) {
                if (i in matchedTracks) continue
                for (j in detections.indices) {
                    if (j in matchedDetections) continue
                    if (iouMatrix[i][j] > maxIou) {
                        maxIou = iouMatrix[i][j]
                        bestTrack = i
                        bestDetection = j
                    }
                }
            }

            if (bestTrack == -1) break

            matches.add(Pair(trackList[bestTrack].id, bestDetection))
            matchedTracks.add(bestTrack)
            matchedDetections.add(bestDetection)
        }

        return matches
    }

    private fun calculateUrgency(track: TrackState): UrgencyLevel {
        val distanceFactor = when (track.distance) {
            DistanceLevel.NEAR -> 3
            DistanceLevel.MEDIUM -> 2
            DistanceLevel.FAR -> 1
        }

        val zoneFactor = when (track.zone) {
            DirectionZone.CENTER -> 2
            DirectionZone.FRONT_LEFT, DirectionZone.FRONT_RIGHT -> 1.5f
            else -> 1f
        }

        val areaFactor = when {
            track.areaRatio > 0.1f -> 2
            track.areaRatio > 0.03f -> 1.5f
            else -> 1f
        }

        val score = distanceFactor * zoneFactor.toFloat() * areaFactor.toFloat()

        return when {
            score >= 6 -> UrgencyLevel.CRITICAL
            score >= 4 -> UrgencyLevel.HIGH
            score >= 2 -> UrgencyLevel.MEDIUM
            else -> UrgencyLevel.LOW
        }
    }

    fun reset() {
        tracks.clear()
    }
}
