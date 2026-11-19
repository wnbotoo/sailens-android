package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.world.LocalWorldOwner
import com.sailens.shell.guidance.safety.GuidanceRunPhase
import com.sailens.shell.guidance.safety.GuidanceSafetySnapshot
import com.sailens.shell.guidance.safety.StallCondition

internal inline fun LocalWorldOwner?.observeResult(result: SceneResult, informMonitor: () -> Unit) {
    val admission = this?.beginResult(result.sequenceNumber, result.worldPublication)
    informMonitor()
    this?.finishResult(admission, result.worldPublication, result.qualification.baseline)
}

internal fun LocalWorldOwner.observeSafety(snapshot: GuidanceSafetySnapshot) {
    val axes = snapshot.axes
    observeSafety(
        LocalWorldOwner.PublicationSafety(
            running = axes.phase == GuidanceRunPhase.RUNNING,
            clearView = !axes.interrupted && axes.stall == StallCondition.NONE &&
                axes.judgement == BaselineQualification.Qualified,
            hold = axes.hold != null,
            stopping = axes.hold?.stopping == true,
        ),
        stopBegan = snapshot.stopBegan,
    )
}
