package com.sailens.guidance.usecase.decision

import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptRevokeReasons
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.CutResult
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.decision.SpeechRanks
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.GuidanceQualifier
import com.sailens.guidance.trace.regression.RegressionRecorder

/**
 * 决策事件用例
 *
 * 返回按优先级排序的事件，**第一条是本帧要送达的那条**。冷却只记这一条：同帧的其余事件用户
 * 听不到，记进冷却就等于把它们静音一个冷却周期。这条记录是暂记：送达方送到了就用
 * [ConfirmDeliveredEventUseCase] 确认，没能送出去（例如更高优先级的播报还在念）就用
 * [RevokeUndeliveredEventUseCase] 撤销。
 *
 * @param clock 单调时钟（生产环境是 `SystemClock.elapsedRealtime()`）。冷却和过期都靠时间差，
 *   墙钟回拨会让差值变负、把提示压到时钟追回来为止。
 */
class DecideEventsUseCase(
    private val eventGenerator: EventGenerator,
    private val conflictResolver: EventConflictResolver,
    private val eventMerger: EventMerger,
    private val cooldownManager: CooldownManager,
    private val deviceSensorRepository: DeviceSensorRepository,
    private val clock: () -> Long,
    private val regressionRecorder: RegressionRecorder = RegressionRecorder.None,
) {
    /**
     * Which of the states Guidance must tell the user about hold in [snapshot], by
     * the generator's own test. Decides nothing and changes nothing.
     */
    fun visionFaults(snapshot: SceneSnapshot): VisionFaults = eventGenerator.visionFaults(snapshot)

    /**
     * Whether [snapshot] lets Guidance judge the way ahead: the baseline the generator decides with, from
     * the same config. Decides nothing and changes nothing itself; it is for the result, the trace and
     * the capture.
     */
    fun qualification(snapshot: SceneSnapshot): BaselineQualification = GuidanceQualifier.baseline(snapshot, eventGenerator.config)

    /**
     * The clock reading the latest decision made, null before the first. Kept rather than read again: a
     * second reading would be one more value on a recorded clock's tape.
     */
    var lastDecisionAtMs: Long? = null
        private set

    operator fun invoke(snapshot: SceneSnapshot): List<SceneEvent> {
        val now = clock()
        lastDecisionAtMs = now
        val stationary = deviceSensorRepository.isStationary.value
        if (regressionRecorder.isRecording) regressionRecorder.onStationary(stationary)

        // 1.  生成原始事件
        val rawEvents = eventGenerator.generate(snapshot, now)

        // 2. 冲突解决
        val resolvedEvents = conflictResolver.resolve(rawEvents)

        // 3. 事件合并
        val merged = eventMerger.merge(resolvedEvents)
        val detFirst = eventGenerator.detFirst
        val mergedEvents = if (detFirst.speechRank) merged.map { it.copy(rank = SpeechRanks.of(it, detFirst)) } else merged

        // 冷却的操作作为一个整体（见 CooldownManager.decide）：撤销只能落在两次决策之间。
        return cooldownManager.decide {
            // 把运动状态喂给冷却器：站着不动时同一个场景不该被反复播报。
            setStationary(stationary)
            observeHazards(snapshot.obstacles.mapTo(HashSet()) { it.id }, rawEvents, now)

            // 4. 冷却过滤
            val filteredEvents = filter(mergedEvents, now)

            // 5. 按喇叭刻度排序（稳定排序：同刻度保持生成顺序）。rank 全 0 时就是按优先级。
            val prioritized = if (detFirst.speechRank) {
                filteredEvents.sortedWith(
                    compareByDescending<SceneEvent> { SpeechScale.of(it) }
                        .thenBy { event -> if (event.category == EventCategory.OBSTACLE && !SpeechRanks.saysNearOfADetectedHazard(event)) 1 else 0 },
                )
            } else {
                filteredEvents.sortedByDescending { SpeechScale.of(it) }
            }

            // 6. 只为要送达的那条记录冷却
            prioritized.firstOrNull()?.let { recordEvent(it, now) }

            prioritized
        }
    }
}

/**
 * 送达方的回执：[DecideEventsUseCase] 选出的那条事件至少有一个通道接收了。它的冷却记录从此不能
 * 再撤回。不改变任何冷却判断，所以不进模型回归记录：回放只按录下的撤回复现（见 [CooldownManager.confirm]）。
 */
class ConfirmDeliveredEventUseCase(
    private val cooldownManager: CooldownManager,
) {
    operator fun invoke(event: SceneEvent) {
        cooldownManager.confirm(event.id)
    }
}

/**
 * 送达方的回执：[DecideEventsUseCase] 选出的那条事件最终没有送到用户。撤销它的冷却记录，
 * 让它在条件仍然成立时下一帧重新放行。
 */
class RevokeUndeliveredEventUseCase(
    private val cooldownManager: CooldownManager,
    private val regressionRecorder: RegressionRecorder = RegressionRecorder.None,
) {
    operator fun invoke(event: SceneEvent, reason: String? = null) {
        val decisionsBefore = cooldownManager.revoke(event.id)
        if (regressionRecorder.isRecording) regressionRecorder.onRevoke(event.id, decisionsBefore, reason)
    }
}

/**
 * 送达方的回执：[DecideEventsUseCase] 选出的那条事件，自带语音接收之后被打断了。冷却决定撤回并重给，还是确认
 * （没听完），两种都进模型回归记录。
 */
class ReportCutPromptUseCase(
    private val cooldownManager: CooldownManager,
    private val regressionRecorder: RegressionRecorder = RegressionRecorder.None,
) {
    operator fun invoke(event: SceneEvent) {
        val receipt = cooldownManager.reportCut(event.id)
        if (!regressionRecorder.isRecording) return
        when (receipt.result) {
            CutResult.REVOKED -> regressionRecorder.onRevoke(event.id, receipt.decisionsBefore, PromptRevokeReasons.CUT)
            CutResult.KEPT -> regressionRecorder.onCutKept(event.id, receipt.decisionsBefore)
        }
    }
}

/** 送达方的回执：判为被打断之后，引擎又说它其实念完了。不收回打断做过的事，只把这条写入算成听完。 */
class ReportHeardAfterCutUseCase(
    private val cooldownManager: CooldownManager,
    private val regressionRecorder: RegressionRecorder = RegressionRecorder.None,
) {
    operator fun invoke(event: SceneEvent) {
        cooldownManager.reportHeardAfterCut(event.id)
        if (regressionRecorder.isRecording) regressionRecorder.onHeardAfterCut(event.id, cooldownManager.decisionsSoFar())
    }
}

/**
 * 送达方的回执：某个通道**接收**了这条事件。它说出的、等着重给的阶段这时才算重给了。
 *
 * @return 要不要伴随震动，见 [CooldownManager.promptAccepted]。
 */
class AcceptedPromptUseCase(
    private val cooldownManager: CooldownManager,
) {
    operator fun invoke(event: SceneEvent): Boolean = cooldownManager.promptAccepted(event.id)
}
