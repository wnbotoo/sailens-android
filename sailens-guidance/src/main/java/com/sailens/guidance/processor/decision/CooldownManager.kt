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
    /**
     * 最近一个滑窗内记录过的事件（时间戳 + 写入者），用于判定"事件风暴"。撤回只删写入者自己那条，
     * 见 [revoke]。
     */
    private val recentEmissions = ArrayDeque<Emission>()
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
        // 在室内可能一待就是很久：进入时说一次，之后一分钟提醒一次"这项判断仍然停着"就够了。
        EventCategory.GROUND_UNRECOGNIZED to 60000,
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

    /** 每个冷却 key 上最后一次放行的事件：时间戳 + 当时（继承后）的优先级 + 写下它的事件。 */
    private data class CooldownRecord(
        val timestampMs: Long,
        val priority: EventPriority,
        val eventId: UUID,
    )

    /**
     * 一次 [recordEvent]：写入者、时间、优先级，以及它写的每个 key 和那个 key 的基础冷却（继承窗口按写入的
     * 事件取，和写入时的规则一致）。
     */
    private class Write(
        val eventId: UUID,
        val timestampMs: Long,
        val priority: EventPriority,
        val baseCooldowns: Map<String, Long>,
    ) {
        /** 送达方说它送到了（[confirm]）。之后再也撤不回来。 */
        var confirmed = false
    }

    private class Emission(val timestampMs: Long, val eventId: UUID)

    // 撤回不再是"恢复写入前的快照"：快照只在撤回的恰好是最后一次写入时才对。A、B 先后写同一个 key、
    // B 继承了 A 的优先级，先撤 A 再撤 B，快照会把 A 的记录恢复回来——两条都没送达，key 上却留着 A
    // （det 优先方案 D3a-1）。所以记录拆成两段：
    //
    // - [confirmedRecords]：已确认的那段前缀按记录规则完整折叠的结果。继承链可以任意长，所以必须是
    //   完整折叠，不能按窗口裁掉旧写入再重放。
    // - [openWrites]：还没并进去的写入，按写入顺序；可能有已确认、但排在待定写入之后的。
    //
    // 当前记录 = 把 openWrites 按顺序折叠到 confirmedRecords 上。撤回删掉自己那次写入再重新折叠，于是
    // 结果和"那次写入从没发生过"一样。

    private val confirmedRecords = mutableMapOf<String, CooldownRecord>()
    private val openWrites = ArrayDeque<Write>()

    /** 当前记录：[openWrites] 按顺序折叠到 [confirmedRecords] 上。缓存，撤回时重算。 */
    private val lastEventRecords = mutableMapOf<String, CooldownRecord>()

    /** 已完成的 [decide] 次数；撤销记下它，回放据此把撤销放回两次决策之间的原位。 */
    private var decisions = 0L

    /**
     * 一次决策里对冷却的全部操作（设静止、过滤、记录）作为一个整体执行：送达方的撤销来自另一个
     * 线程，只能落在两次决策之间，不会插进一次决策的过滤和记录之间。这样撤销的位置是确定的，
     * M0b 回放才能精确复现。
     */
    @Synchronized
    fun <T> decide(block: CooldownManager.() -> T): T {
        val result = block()
        decisions++
        return result
    }

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
     * 暂记一条要送达用户的事件。只能为送达方要送的那一条调用：没播出去的事件（同帧的次要事件、被更高
     * 优先级语音挡下的事件）一旦被记录，就会在自己的冷却期里被静音，而用户从没听到过它。送达方随后
     * 用 [confirm] 或 [revoke] 给出结果。
     */
    @Synchronized
    fun recordEvent(event: SceneEvent, now: Long) {
        val write = Write(
            eventId = event.id,
            timestampMs = now,
            priority = event.priority,
            baseCooldowns = event.allCooldownKeys().associateWith { key -> event.baseCooldownFor(key) },
        )
        lastEventRecords.fold(write)
        openWrites.addLast(write)
        recentEmissions.addLast(Emission(now, event.id))
        trimEmissionWindow(now)
        // 送达方的回执总是紧跟在决策之后，保留少量即可。更旧的写入不再能撤回，和从前一样。
        while (openWrites.size > MAX_OPEN_WRITES) {
            openWrites.first().confirmed = true
            advanceConfirmedPrefix()
        }
    }

    /**
     * [eventId] 的记录已送达用户（至少一个通道接收了它）：从此不能再撤回。不改变任何记录——当前记录本来
     * 就算着它——只决定之后的撤回能不能生效。未知或已撤回的 id 是空操作。
     */
    @Synchronized
    fun confirm(eventId: UUID) {
        openWrites.firstOrNull { it.eventId == eventId }?.confirmed = true
        advanceConfirmedPrefix()
    }

    /**
     * 撤销 [recordEvent] 对 [eventId] 的记录：这条事件最终没有送达用户（例如更高优先级的播报
     * 还在念）。撤销后它下一帧还能被重新生成、重新放行，而不是在冷却期里被静音。
     *
     * 撤销后的记录和"这次写入从没发生过"一样，无论之后有没有别的事件写过同一个 key：后来的写入从它
     * 继承来的优先级也一起拿掉。已确认、太旧或从未记录的 id 是空操作。
     *
     * @return 撤销生效时已完成的决策次数（见 [decide]）。
     */
    @Synchronized
    fun revoke(eventId: UUID): Long {
        val decisionsBefore = decisions
        revokeRecord(eventId)
        return decisionsBefore
    }

    private fun revokeRecord(eventId: UUID) {
        val index = openWrites.indexOfFirst { it.eventId == eventId }
        if (index < 0 || openWrites[index].confirmed) return
        openWrites.removeAt(index)
        recentEmissions.indexOfLast { it.eventId == eventId }.takeIf { it >= 0 }?.let(recentEmissions::removeAt)
        lastEventRecords.clear()
        lastEventRecords.putAll(confirmedRecords)
        openWrites.forEach { lastEventRecords.fold(it) }
        advanceConfirmedPrefix()
    }

    /**
     * 把开头连续已确认的写入并进 [confirmedRecords]。只推进连续的前缀：后面的写入先确认时，不能跨过前面
     * 仍待定的那条——那条撤回后，后面的要重新折叠。当前记录不变：折叠是按顺序的。
     */
    private fun advanceConfirmedPrefix() {
        while (openWrites.firstOrNull()?.confirmed == true) {
            confirmedRecords.fold(openWrites.removeFirst())
        }
    }

    /** 记录规则：把 [write] 写到它的每个 key 上。 */
    private fun MutableMap<String, CooldownRecord>.fold(write: Write) {
        write.baseCooldowns.forEach { (key, baseCooldown) ->
            val existing = this[key]
            val withinBaseWindow = existing != null &&
                write.timestampMs - existing.timestampMs < baseCooldown
            this[key] = CooldownRecord(
                timestampMs = write.timestampMs,
                // 记住这个 key 上出现过的最高优先级，直到基础冷却自然到期。否则
                // CRITICAL → HIGH → CRITICAL 的抖动会让每个 CRITICAL 都被算成"升级"。
                priority = existing
                    ?.takeIf {
                        withinBaseWindow && it.priority.value > write.priority.value
                    }
                    ?.priority
                    ?: write.priority,
                eventId = write.eventId,
            )
        }
    }

    /**
     * The whole state, for the equivalence tests (det-first plan D3a-1): each key's current record as
     * (time, priority, writer), and the storm window's entries in order. Changes nothing.
     */
    @Synchronized
    internal fun state(): State = State(
        records = lastEventRecords.mapValues { (_, r) -> Triple(r.timestampMs, r.priority, r.eventId) },
        emissions = recentEmissions.map { it.timestampMs },
    )

    internal data class State(
        val records: Map<String, Triple<Long, EventPriority, UUID>>,
        val emissions: List<Long>,
    )

    /**
     * A read-only copy of the records, for measurement (det-first plan D1.6): taken just before a
     * decision with that decision's [now] and motion state, it says which keys hold an event back and
     * at what recorded priority. Changes nothing -- not even the emission window, which is counted,
     * not trimmed.
     */
    @Synchronized
    internal fun snapshot(now: Long, stationary: Boolean): Snapshot {
        var scale = 1.0f
        if (stationary) scale *= stationaryCooldownScale
        if (recentEmissions.count { now - it.timestampMs <= stormWindowMs } >= stormEventThreshold) scale *= stormCooldownScale
        return Snapshot(now, scale.coerceAtMost(maxCooldownScale), lastEventRecords.mapValues { (_, r) -> r.timestampMs to r.priority })
    }

    /** One key holding an event back, and the priority recorded on it. */
    data class Hold(val key: String, val recordedPriority: EventPriority)

    inner class Snapshot internal constructor(
        private val now: Long,
        private val scale: Float,
        private val records: Map<String, Pair<Long, EventPriority>>,
    ) {
        /**
         * Null when [event] passes, as [filter] would decide at this snapshot; else, for each of its
         * cooldown groups (a merged event has one per component), the keys holding that group.
         */
        fun holds(event: SceneEvent): List<GroupHold>? {
            val eventScale = if (event.isExemptFromScaling()) 1.0f else scale
            val groups = event.cooldownGroupsOrDefault().map { group ->
                GroupHold(
                    keys = group,
                    holds = group.mapNotNull { key ->
                        val (timestampMs, priority) = records[key] ?: return@mapNotNull null
                        val held = now - timestampMs < event.scaledCooldownFor(key, eventScale) &&
                            event.priority.value <= priority.value
                        Hold(key, priority).takeIf { held }
                    },
                )
            }
            return if (groups.any { it.holds.isEmpty() }) null else groups
        }
    }

    /** One cooldown group of an event and the keys in it that hold the event back. */
    data class GroupHold(val keys: Set<String>, val holds: List<Hold>)

    @Synchronized
    fun reset() {
        lastEventRecords.clear()
        confirmedRecords.clear()
        openWrites.clear()
        recentEmissions.clear()
        isStationary = false
        decisions = 0
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
        while (recentEmissions.isNotEmpty() && now - recentEmissions.first().timestampMs > stormWindowMs) {
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
        const val MAX_OPEN_WRITES = 16
    }
}
