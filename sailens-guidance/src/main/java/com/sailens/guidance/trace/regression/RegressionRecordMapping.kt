package com.sailens.guidance.trace.regression

import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.perception.SegmentationAnalysisStats
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.trace.capture.BottomStatsRecord
import com.sailens.guidance.trace.capture.ConnectivityStatsRecord
import com.sailens.guidance.trace.capture.DetectionItem
import com.sailens.guidance.trace.capture.EventRecord
import com.sailens.guidance.trace.capture.SemanticStatsRecord
import com.sailens.vision.detection.Detection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/*
 * Conversions between the decision path's types and the model-regression records
 * (`RegressionSchema`). Each pair round-trips exactly: the recorder writes with the first, replay
 * reads with the second, and a test pins that one undoes the other.
 */

fun SceneEvent.toRecord(): EventRecord = EventRecord(
    id = id.toString(),
    timestamp = timestamp,
    category = category.name,
    priority = priority.name,
    messageKey = messageKey,
    messageParams = messageParams,
    expiresAt = expiresAt,
    dedupeKey = dedupeKey,
    cooldownKeys = cooldownKeys.sorted(),
    cooldownGroups = cooldownGroups.map { it.sorted() },
    confidence = confidence,
    severity = severity.name,
    relatedZones = relatedZones.map { it.name },
    distance = distance?.name,
    directionHint = directionHint?.name,
)

fun EventRecord.toSceneEvent(): SceneEvent = SceneEvent(
    id = UUID.fromString(id),
    timestamp = timestamp,
    category = EventCategory.valueOf(category),
    priority = EventPriority.valueOf(priority),
    messageKey = messageKey,
    messageParams = messageParams,
    expiresAt = expiresAt,
    dedupeKey = dedupeKey,
    cooldownKeys = cooldownKeys.toSet(),
    cooldownGroups = cooldownGroups.map { it.toSet() },
    confidence = confidence,
    severity = Severity.valueOf(severity),
    relatedZones = relatedZones.map(DirectionZone::valueOf),
    distance = distance?.let(DistanceLevel::valueOf),
    directionHint = directionHint?.let(DirectionBias::valueOf),
)

/**
 * The record of an event without its random id: what replay compares. Two decisions are the same
 * when these are equal.
 */
fun EventRecord.withoutId(): EventRecord = copy(id = "")

fun SegmentationAnalysisStats.toRecord(): SemanticStatsRecord = SemanticStatsRecord(
    passableMaskCrc32 = passableMask.regressionCrc32(),
    obstacleMaskCrc32 = obstacleMask.regressionCrc32(),
    roadRatio = roadRatio,
    hasTrafficLight = hasTrafficLight,
    bottomCenterGroundDistribution = bottomCenterGroundDistribution.mapKeys { it.key.name },
    bottomCenterRoadRatio = bottomCenterRoadRatio,
    bottomStats = bottomStats.toRecord(),
    passablePixelCount = passablePixelCount,
    navigationPassableRatio = navigationPassableRatio,
    obstaclePixelCount = obstaclePixelCount,
    classCounts = classCounts.toList(),
)

/**
 * The statistics back, with the two masks the caller rebuilt from the class map.
 *
 * @throws IllegalStateException when a rebuilt mask is not the one the device used (its CRC
 *   differs): replay would then not be exact, and must say so rather than continue.
 */
fun SemanticStatsRecord.toStats(passableMask: BinaryMask, obstacleMask: BinaryMask): SegmentationAnalysisStats {
    check(passableMask.regressionCrc32() == passableMaskCrc32) { "rebuilt passable mask differs from the recorded one" }
    check(obstacleMask.regressionCrc32() == obstacleMaskCrc32) { "rebuilt obstacle mask differs from the recorded one" }
    return SegmentationAnalysisStats(
        passableMask = passableMask,
        obstacleMask = obstacleMask,
        roadRatio = roadRatio,
        hasTrafficLight = hasTrafficLight,
        bottomCenterGroundDistribution = bottomCenterGroundDistribution.mapKeys { GroundType.valueOf(it.key) },
        bottomCenterRoadRatio = bottomCenterRoadRatio,
        bottomStats = bottomStats.toBottomStats(),
        passablePixelCount = passablePixelCount,
        navigationPassableRatio = navigationPassableRatio,
        obstaclePixelCount = obstaclePixelCount,
        classCounts = classCounts.toIntArray(),
    )
}

fun BottomStats.toRecord(): BottomStatsRecord =
    BottomStatsRecord(coverage, maxRunWidth, maxRunWidthRatio, maxRunRow, maxRunStart, maxRunEnd, maxRunCenter)

fun BottomStatsRecord.toBottomStats(): BottomStats =
    BottomStats(coverage, maxRunWidth, maxRunWidthRatio, maxRunRow, maxRunStart, maxRunEnd, maxRunCenter)

fun ConnectivityStats.toRecord(): ConnectivityStatsRecord = ConnectivityStatsRecord(
    validLayers = validLayers,
    totalLayers = totalLayers,
    widthRetentionAvg = widthRetentionAvg,
    widthRetentionP25 = widthRetentionP25,
    widthSlope = widthSlope,
    floodReachRatio = floodReachRatio,
    floodWidthRetentionP25 = floodWidthRetentionP25,
    floodVisitedRatio = floodVisitedRatio,
    suggestedBias = suggestedBias?.name,
)

fun ConnectivityStatsRecord.toStats(): ConnectivityStats = ConnectivityStats(
    validLayers = validLayers,
    totalLayers = totalLayers,
    widthRetentionAvg = widthRetentionAvg,
    widthRetentionP25 = widthRetentionP25,
    widthSlope = widthSlope,
    floodReachRatio = floodReachRatio,
    floodWidthRetentionP25 = floodWidthRetentionP25,
    floodVisitedRatio = floodVisitedRatio,
    suggestedBias = suggestedBias?.let(DirectionBias::valueOf),
)

fun Detection.toItem(): DetectionItem = DetectionItem(
    classId = classId,
    label = label,
    confidence = confidence,
    x = boundingBox.x,
    y = boundingBox.y,
    width = boundingBox.width,
    height = boundingBox.height,
)

fun DetectionItem.toDetection(): Detection = Detection(classId, label, confidence, NormalizedRect(x, y, width, height))

/**
 * CRC32 of a mask's size and bits (packed as `BinaryMask.copyPackedBits` packs them, each word
 * little-endian), so two masks with the same pixels have the same value on any JVM.
 *
 * Runs on Guidance's thread for every recorded frame, so the words go to CRC32 in one call: fed a
 * word at a time it made about 3,600 native calls per class-map-sized mask, 3–7 ms each on an
 * SM8850 -- the bulk of the recording's cost (M0b ABAB, 2026-10-13). The bytes are the same.
 *
 * @param scratch reused word buffer; pass one to avoid a per-call allocation in a per-frame path.
 */
fun BinaryMask.regressionCrc32(scratch: LongArray = LongArray(0)): Long {
    val words = copyPackedBitsInto(scratch)
    val wordCount = (width * height + Long.SIZE_BITS - 1) / Long.SIZE_BITS
    val bytes = crcBytes.get().let { if (it.capacity() >= wordCount * 8) it else ByteBuffer.allocate(wordCount * 8).order(ByteOrder.LITTLE_ENDIAN).also(crcBytes::set) }
    bytes.clear()
    bytes.asLongBuffer().put(words, 0, wordCount)
    val crc = CRC32()
    crc.update(intBytes(width))
    crc.update(intBytes(height))
    crc.update(bytes.array(), 0, wordCount * 8)
    return crc.value
}

/** Per thread: recording and replay may run on different threads. */
private val crcBytes = ThreadLocal.withInitial { ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN) }

private fun intBytes(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
