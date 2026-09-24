package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import java.util.UUID

/**
 * 冷却管理器。
 *
 * 基础冷却按事件类别配置，另外叠加两个自适应倍率，解决"节奏"层面的打扰——阈值调得再准，
 * 固定冷却在下面两个场景里都会让人受不了：
 *
 * - **用户站着不动**（等红灯、站着聊天）：同一个场景被按固定周期反复播报。
 * - **人流密集**（地铁站、商场）：障碍物事件 2 秒冷却 = 每 2 秒一句，10 分钟 300 句。
 *   用户不会去调设置，他会直接卸载。
 *
 * ## 冷却绝不能压掉"变得更危险了"
 *
 * 静止**不等于**场景没有更新：用户站着不动的时候，车和人照样在动。所以"拉长冷却"只对
 * **同等或更低优先级的重复事件**成立。优先级升高（行人 HIGH → 车辆 CRITICAL 进入正前方）
 * 必须立刻穿透旧冷却，否则用户恰好在风险上升的那一刻被静音。
 *
 * 三重保护：
 * 1. [EventPriority] 升高即绕过冷却；放行后会记录最高优先级，避免类别抖动反复制造升级。
 * 2. 缩放后的冷却有绝对上限 [maxScaledCooldownMs]，界定最坏情况下的"失聪"时长。
 * 3. 传感器质量事件完全不参与缩放——站着不动并不代表镜头没被挡住。
 */
class CooldownManager(
    private val stationaryCooldownScale: Float = 2.0f,
    private val stormWindowMs: Long = 20_000L,
    private val stormEventThreshold: Int = 8,
    private val stormCooldownScale: Float = 2.0f,
    private val maxCooldownScale: Float = 3.0f,
    /**
     * 缩放后的冷却上限。倍率相乘很容易叠出十几秒，而十几秒不播报在人行道上太久了；
     * 这里给最坏情况一个明确的天花板。基础冷却本身超过它的（红绿灯、道路警告）不受影响。
     */
    private val maxScaledCooldownMs: Long = 8_000L,
) {
    /** 最近一个滑窗内实际播出去的事件时间戳，用于判定"事件风暴"。 */
    private val recentEmissions = ArrayDeque<Long>()
    private var isStationary = false
    val cooldowns: Map<EventCategory, Long> = mapOf(
        EventCategory.BLOCKED to 5000,
        EventCategory.NARROWING to 4000,
        EventCategory.OBSTACLE to 2000,
        EventCategory.INTERSECTION to 5000,
        EventCategory.ROAD_WARNING to 12000,
        EventCategory.GROUND_CHANGE to 8000,
        EventCategory.PATH_COMPLEX to 6000,
        EventCategory.TRAFFIC_LIGHT to 12000,
        EventCategory.SENSOR_QUALITY to 15000,
    )

    private val keyCooldowns: Map<String, Long> = mapOf(
        "road_warning_vehicle" to 5000,
        "obstacle_PRIMARY_CENTER" to 4000,
        "obstacle_PRIMARY_FRONT" to 5000,
        "obstacle_PRIMARY_SIDE" to 6000,
        // 镜头被挡时用户处于"以为受保护、实际什么都没检测"的状态，必须持续提醒到他处理掉，
        // 所以比其他状态提示更频繁。过暗则沿用类别默认的 15s：用户挪手机也解决不了，催也没用。
        "sensor_quality_obstructed" to 8000,
    )

    /** 每个冷却 key 上最后一次放行的事件：时间戳 + 当时的优先级 + 写下它的事件。 */
    private data class CooldownRecord(
        val timestampMs: Long,
        val priority: EventPriority,
        val eventId: UUID,
    )

    private val lastEventRecords = mutableMapOf<String, CooldownRecord>()

    /** 撤销一次记录所需的全部信息：被覆盖前的旧记录（null = 原本没有）和那次放行的时间戳。 */
    private data class RecordUndo(
        val previous: Map<String, CooldownRecord?>,
        val emittedAtMs: Long,
    )

    /** 最近几次记录的撤销信息。送达方的回执总是紧跟在决策之后，保留少量即可。 */
    private val undoLog = LinkedHashMap<UUID, RecordUndo>()

    /**
     * 更新用户是否静止。由上游的运动检测驱动，见
     * [com.sailens.guidance.repository.DeviceSensorRepository.isStationary]。
     */
    @Synchronized
    fun setStationary(stationary: Boolean) {
        isStationary = stationary
    }

    @Synchronized
    fun filter(events: List<SceneEvent>, now: Long): List<SceneEvent> {
        val scale = currentCooldownScale(now)
        return events.filter { event -> event.passesCooldown(now, scale) }
    }

    /**
     * 任意一组冷却 key 全部通过即放行。合并事件每个组成事件各占一组（见 [SceneEvent.cooldownGroups]），
     * 所以只要合并提示里有一个方位是新的或升级了，整条提示就能播出。
     */
    private fun SceneEvent.passesCooldown(now: Long, scale: Float): Boolean {
        val eventScale = if (isExemptFromScaling()) 1.0f else scale
        return cooldownGroupsOrDefault().any { group -> passesGroup(group, now, eventScale) }
    }

    private fun SceneEvent.passesGroup(group: Set<String>, now: Long, eventScale: Float): Boolean {
        return group.all { key ->
            val record = lastEventRecords[key] ?: return@all true
            val elapsed = now - record.timestampMs
            if (elapsed >= scaledCooldownFor(key, eventScale)) return@all true

            // 冷却未到，但风险等级上升了：必须放行。这条豁免是"静止/风暴拉长冷却"的安全边界——
            // 用户站着不动时，一辆车照样能开进他正前方，而那条 CRITICAL 事件和刚才那个行人
            // 共用同一个方位 cooldown key。
            //
            priority.value > record.priority.value
        }
    }

    /**
     * 记录一条**真正送达用户**的事件。只能由送达方调用：没播出去的事件（同帧的次要事件、被更高
     * 优先级语音挡下的事件）一旦被记录，就会在自己的冷却期里被静音，而用户从没听到过它。
     */
    @Synchronized
    fun recordEvent(event: SceneEvent, now: Long) {
        val previous = mutableMapOf<String, CooldownRecord?>()
        event.allCooldownKeys().forEach { key ->
            val existing = lastEventRecords[key]
            previous[key] = existing
            val withinBaseWindow = existing != null &&
                now - existing.timestampMs < event.baseCooldownFor(key)
            lastEventRecords[key] = CooldownRecord(
                timestampMs = now,
                // 记住这个 key 上出现过的最高优先级，直到基础冷却自然到期。否则
                // CRITICAL → HIGH → CRITICAL 的抖动会让每个 CRITICAL 都被算成"升级"。
                priority = existing
                    ?.takeIf {
                        withinBaseWindow && it.priority.value > event.priority.value
                    }
                    ?.priority
                    ?: event.priority,
                eventId = event.id,
            )
        }
        recentEmissions.addLast(now)
        trimEmissionWindow(now)
        undoLog[event.id] = RecordUndo(previous = previous, emittedAtMs = now)
        while (undoLog.size > MAX_UNDO_ENTRIES) {
            undoLog.remove(undoLog.keys.first())
        }
    }

    /**
     * 撤销 [recordEvent] 对 [eventId] 的记录：这条事件最终没有送达用户（例如更高优先级的播报
     * 还在念）。撤销后它下一帧还能被重新生成、重新放行，而不是在冷却期里被静音。
     *
     * 只恢复仍然由这条事件写着的 key：撤销之前如果已有别的事件改写了同一个 key，那条更新的记录
     * 保持不动。未知 id（太旧或从未记录）是空操作。
     */
    @Synchronized
    fun revoke(eventId: UUID) {
        val undo = undoLog.remove(eventId) ?: return
        undo.previous.forEach { (key, previousRecord) ->
            if (lastEventRecords[key]?.eventId != eventId) return@forEach
            if (previousRecord == null) {
                lastEventRecords.remove(key)
            } else {
                lastEventRecords[key] = previousRecord
            }
        }
        recentEmissions.lastIndexOf(undo.emittedAtMs).takeIf { it >= 0 }?.let(recentEmissions::removeAt)
    }

    @Synchronized
    fun reset() {
        lastEventRecords.clear()
        recentEmissions.clear()
        undoLog.clear()
        isStationary = false
    }

    /**
     * 当前生效的冷却倍率。
     *
     * 传感器质量事件不受影响——见 [isExemptFromScaling]。用户静止不动并不代表镜头没被挡住，
     * 而事件风暴本身也不该压掉"我看不见了"这条。
     */
    @Synchronized
    internal fun currentCooldownScale(now: Long): Float {
        trimEmissionWindow(now)
        var scale = 1.0f
        if (isStationary) scale *= stationaryCooldownScale
        if (recentEmissions.size >= stormEventThreshold) scale *= stormCooldownScale
        return scale.coerceAtMost(maxCooldownScale)
    }

    private fun trimEmissionWindow(now: Long) {
        while (recentEmissions.isNotEmpty() && now - recentEmissions.first() > stormWindowMs) {
            recentEmissions.removeFirst()
        }
    }

    private fun SceneEvent.cooldownKeys(): Set<String> = cooldownKeys.ifEmpty {
        setOf(dedupeKey.ifBlank { category.name })
    }

    private fun SceneEvent.cooldownGroupsOrDefault(): List<Set<String>> =
        cooldownGroups.filter { it.isNotEmpty() }.ifEmpty { listOf(cooldownKeys()) }

    private fun SceneEvent.allCooldownKeys(): Set<String> =
        cooldownKeys() + cooldownGroupsOrDefault().flatten()

    private fun SceneEvent.baseCooldownFor(key: String): Long =
        keyCooldowns[key] ?: cooldowns[category] ?: DEFAULT_COOLDOWN_MS

    private fun SceneEvent.scaledCooldownFor(key: String, scale: Float): Long =
        scaleCooldown(baseCooldownFor(key), scale)

    private fun scaleCooldown(base: Long, scale: Float): Long {
        // 上限用 max(base, ceiling)：基础冷却本来就长的（红绿灯 12s）不该被这里缩短，
        // 而缩放也不该把短冷却推到 ceiling 之上。
        val ceiling = maxOf(base, maxScaledCooldownMs)
        return (base * scale).toLong().coerceAtMost(ceiling)
    }

    private fun SceneEvent.isExemptFromScaling(): Boolean =
        category == EventCategory.SENSOR_QUALITY

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 3000L
        const val MAX_UNDO_ENTRIES = 16
    }
}
