package com.sailens.guidance.delivery

import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults

/**
 * One notice [VisionFaultAlerts] offered or rhythm it asked for, and what the outputs did with it: the
 * protocol's half of a `guidance_alert` trace record. The shell adds the time and the haptic, and traces it.
 *
 * @param kind [GuidanceAlertKinds.FAULT_NOTICE], [GuidanceAlertKinds.FAULT_RECOVERY] or [GuidanceAlertKinds.FAULT_RHYTHM].
 * @param messageKey the sentence's key; null for a rhythm.
 * @param channel one of [GuidanceAlertChannels].
 * @param result one of [GuidanceAlertResults].
 */
data class VisionAlert(
    val kind: String,
    val messageKey: String?,
    val priority: EventPriority,
    val channel: String,
    val result: String,
)
