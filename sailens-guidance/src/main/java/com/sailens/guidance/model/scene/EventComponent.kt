package com.sailens.guidance.model.scene

import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleSource
import java.util.UUID

/**
 * What one zone contributed to an obstacle event: which hazards the prompt can speak for. A merged event
 * has one per zone, in the order of its cooldown groups.
 *
 * Not part of the event's record. It is a function of the frame's snapshot, differing between a device and
 * a replay only by the mapping of track ids, and a decision only ever asks whether two ids are equal, so
 * decisions that read it still replay exactly. Nothing may depend on the order of ids; the order here is
 * the tracker's.
 *
 * @param priority the zone's own event priority, before any merge.
 * @param primaryTrackId the zone's primary obstacle, which chose the category word.
 * @param cooldownKeys the event's cooldown group for this zone.
 */
data class EventComponent(
    val zone: DirectionZone,
    val priority: EventPriority,
    val primaryTrackId: UUID,
    val cooldownKeys: Set<String>,
    val hazards: List<HazardRef>,
)

/**
 * One tracked hazard in a zone of an obstacle event.
 *
 * @param todayNear `distance == NEAR`.
 * @param speechNear NEAR as the prompt says it: what the NEAR prefix and its credit go by.
 * @param ownPriority the priority this track's event would take were it alone in its zone.
 */
data class HazardRef(
    val trackId: UUID,
    val category: ObstacleCategory,
    val todayNear: Boolean,
    val speechNear: Boolean,
    val ownPriority: EventPriority,
    val source: ObstacleSource,
)
