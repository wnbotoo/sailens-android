package com.sailens.domain.processor.decision

import com.sailens.domain.model.common.DirectionZone
import com.sailens.domain.model.common.EventCategory
import com.sailens.domain.model.common.EventPriority
import com.sailens.domain.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownManagerTest {
    private val cooldownManager = CooldownManager()

    @Test
    fun `cooldown suppresses repeated event with same dedupe key`() {
        val first = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )
        val repeated = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )

        cooldownManager.recordEvent(first, now = 10_000)

        val filtered = cooldownManager.filter(listOf(repeated), now = 11_000)

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `cooldown allows same category event when dedupe key changes`() {
        val left = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )
        val center = obstacleEvent(
            messageKey = "event_obstacle_center",
            dedupeKey = "obstacle_CENTER",
            zone = DirectionZone.CENTER,
        )

        cooldownManager.recordEvent(left, now = 10_000)

        val filtered = cooldownManager.filter(listOf(center), now = 11_000)

        assertEquals(listOf(center), filtered)
    }

    @Test
    fun `cooldown suppresses obstacle when any shared cooldown key is active`() {
        val mergedLeftCenter = obstacleEvent(
            messageKey = "event_obstacle_left_center",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            cooldownKeys = setOf("obstacle_LEFT", "obstacle_CENTER", "obstacle_PRIMARY_CENTER"),
        )
        val center = obstacleEvent(
            messageKey = "event_obstacle_center",
            dedupeKey = "obstacle_CENTER",
            zone = DirectionZone.CENTER,
            cooldownKeys = setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"),
        )

        cooldownManager.recordEvent(mergedLeftCenter, now = 10_000)

        val filtered = cooldownManager.filter(listOf(center), now = 11_000)

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `vehicle road warning uses shorter cooldown`() {
        val first = roadWarningEvent(
            messageKey = "event_road_warning_vehicle",
            dedupeKey = "road_warning_vehicle",
        )
        val repeated = roadWarningEvent(
            messageKey = "event_road_warning_vehicle",
            dedupeKey = "road_warning_vehicle",
        )

        cooldownManager.recordEvent(first, now = 10_000)

        assertTrue(cooldownManager.filter(listOf(repeated), now = 14_999).isEmpty())
        assertEquals(listOf(repeated), cooldownManager.filter(listOf(repeated), now = 15_000))
    }

    @Test
    fun `non vehicle road warning keeps category cooldown`() {
        val first = roadWarningEvent(
            messageKey = "event_road_warning",
            dedupeKey = "road_warning_area",
        )
        val repeated = roadWarningEvent(
            messageKey = "event_road_warning",
            dedupeKey = "road_warning_area",
        )

        cooldownManager.recordEvent(first, now = 10_000)

        assertTrue(cooldownManager.filter(listOf(repeated), now = 15_000).isEmpty())
        assertEquals(listOf(repeated), cooldownManager.filter(listOf(repeated), now = 22_000))
    }

    @Test
    fun `standing still stretches the cooldown for equal priority repeats`() {
        val first = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )
        val repeated = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )

        cooldownManager.setStationary(true)
        cooldownManager.recordEvent(first, now = 10_000)

        // 基础冷却 2s，静止倍率 2x。注意这里只覆盖**同等优先级的重复**：静止不代表场景没更新
        // （车和人照样在动），所以拉长冷却仅对"同一件事再说一遍"成立，见下面的升级豁免测试。
        assertTrue(cooldownManager.filter(listOf(repeated), now = 13_000).isEmpty())
        assertEquals(listOf(repeated), cooldownManager.filter(listOf(repeated), now = 14_000))
    }

    @Test
    fun `priority escalation bypasses a stretched cooldown`() {
        // 这是自适应冷却的安全边界。用户站着不动，前方先报了个行人；两秒后一辆车开进正前方。
        // 两条事件共用 obstacle_PRIMARY_CENTER 这个 key，如果升级不豁免，用户就恰好在风险
        // 上升的那一刻被静音。
        val person = obstacleEvent(
            messageKey = "event_obstacle_center_person",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            cooldownKeys = setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"),
            priority = EventPriority.HIGH,
        )
        val vehicle = obstacleEvent(
            messageKey = "event_obstacle_center_vehicle",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            cooldownKeys = setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"),
            priority = EventPriority.CRITICAL,
        )

        cooldownManager.setStationary(true)
        cooldownManager.recordEvent(person, now = 10_000)

        // 中心区基础冷却 4s，静止后 8s；但 CRITICAL 必须在两秒后就放行。
        assertEquals(listOf(vehicle), cooldownManager.filter(listOf(vehicle), now = 12_000))
    }

    @Test
    fun `priority escalation is immediate even within one frame interval`() {
        val person = obstacleEvent(
            messageKey = "event_obstacle_center_person",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            priority = EventPriority.HIGH,
        )
        val vehicle = obstacleEvent(
            messageKey = "event_obstacle_center_vehicle",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            priority = EventPriority.CRITICAL,
        )

        emitThroughProductionPath(person, now = 10_000)

        // 安全升级不能再人为增加 800ms 静音窗口。放行后的最高优先级记录负责阻止后续抖动。
        assertEquals(
            listOf(vehicle),
            emitThroughProductionPath(vehicle, now = 10_200),
        )
    }

    @Test
    fun `de-escalation does not bypass the cooldown`() {
        val vehicle = obstacleEvent(
            messageKey = "event_obstacle_center_vehicle",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            priority = EventPriority.CRITICAL,
        )
        val person = obstacleEvent(
            messageKey = "event_obstacle_center_person",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            priority = EventPriority.HIGH,
        )

        cooldownManager.recordEvent(vehicle, now = 10_000)

        // 风险下降不是新信息，正常走冷却。
        assertTrue(cooldownManager.filter(listOf(person), now = 11_500).isEmpty())
    }

    @Test
    fun `repeated escalation cannot ratchet past the cooldown`() {
        val person = obstacleEvent(
            messageKey = "event_obstacle_center_person",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            priority = EventPriority.HIGH,
        )
        val vehicle = obstacleEvent(
            messageKey = "event_obstacle_center_vehicle",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            priority = EventPriority.CRITICAL,
        )

        emitThroughProductionPath(person, now = 10_000)
        // 第一次升级放行并按真实 DecideEventsUseCase 顺序记录。
        assertEquals(listOf(vehicle), emitThroughProductionPath(vehicle, now = 11_000))
        // 降级被过滤，所以生产链路不会调用 recordEvent；最高优先级记录仍是 CRITICAL。
        assertTrue(emitThroughProductionPath(person, now = 11_100).isEmpty())
        assertTrue(emitThroughProductionPath(vehicle, now = 12_500).isEmpty())
    }

    @Test
    fun `scaled cooldown is capped in absolute terms`() {
        // 倍率相乘很容易叠出十几秒，而十几秒不播报在人行道上太久了。
        cooldownManager.setStationary(true)
        repeat(8) { index ->
            cooldownManager.recordEvent(
                obstacleEvent(
                    messageKey = "event_obstacle_center",
                    dedupeKey = "obstacle_PRIMARY_SIDE_$index",
                    zone = DirectionZone.CENTER,
                ),
                now = 10_000L + index * 200L,
            )
        }
        val sideEvent = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_PRIMARY_SIDE",
            zone = DirectionZone.LEFT,
            cooldownKeys = setOf("obstacle_PRIMARY_SIDE"),
        )
        cooldownManager.recordEvent(sideEvent, now = 10_000)

        // 侧向基础冷却 6s，倍率 3x 会算出 18s，但上限把它压回 8s。
        assertEquals(listOf(sideEvent), cooldownManager.filter(listOf(sideEvent), now = 18_000))
    }

    @Test
    fun `walking keeps the base cooldown`() {
        val first = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )
        val repeated = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )

        cooldownManager.setStationary(false)
        cooldownManager.recordEvent(first, now = 10_000)

        assertEquals(listOf(repeated), cooldownManager.filter(listOf(repeated), now = 12_000))
    }

    @Test
    fun `event storm raises the cooldown scale`() {
        // 人流密集场景：短时间内连续播报。不降频的话 10 分钟 300 句，用户会直接卸载。
        repeat(8) { index ->
            cooldownManager.recordEvent(
                obstacleEvent(
                    messageKey = "event_obstacle_center",
                    dedupeKey = "obstacle_CENTER_$index",
                    zone = DirectionZone.CENTER,
                ),
                now = 10_000L + index * 500L,
            )
        }

        assertEquals(2.0f, cooldownManager.currentCooldownScale(now = 14_000), 0.001f)
    }

    @Test
    fun `event storm decays once the window passes`() {
        repeat(8) { index ->
            cooldownManager.recordEvent(
                obstacleEvent(
                    messageKey = "event_obstacle_center",
                    dedupeKey = "obstacle_CENTER_$index",
                    zone = DirectionZone.CENTER,
                ),
                now = 10_000L + index * 500L,
            )
        }

        assertEquals(1.0f, cooldownManager.currentCooldownScale(now = 60_000), 0.001f)
    }

    @Test
    fun `combined scaling is capped`() {
        cooldownManager.setStationary(true)
        repeat(8) { index ->
            cooldownManager.recordEvent(
                obstacleEvent(
                    messageKey = "event_obstacle_center",
                    dedupeKey = "obstacle_CENTER_$index",
                    zone = DirectionZone.CENTER,
                ),
                now = 10_000L + index * 500L,
            )
        }

        // 2.0 * 2.0 = 4.0，钳到 3.0：倍率叠加起来太长会把真正的危险也压掉。
        assertEquals(3.0f, cooldownManager.currentCooldownScale(now = 14_000), 0.001f)
    }

    @Test
    fun `sensor quality ignores cooldown scaling`() {
        cooldownManager.setStationary(true)
        val first = sensorQualityEvent()
        val repeated = sensorQualityEvent()

        cooldownManager.recordEvent(first, now = 10_000)

        // 站着不动并不代表镜头没被挡住；这条提示不能被"少打扰"的逻辑压掉。
        // obstructed 的 key 冷却是 8s，不受静止倍率影响。
        assertTrue(cooldownManager.filter(listOf(repeated), now = 17_000).isEmpty())
        assertEquals(listOf(repeated), cooldownManager.filter(listOf(repeated), now = 18_000))
    }

    private fun sensorQualityEvent(): SceneEvent = SceneEvent(
        timestamp = 10_000,
        category = EventCategory.SENSOR_QUALITY,
        priority = EventPriority.CRITICAL,
        messageKey = "event_camera_blocked",
        expiresAt = 16_000,
        dedupeKey = "sensor_quality_obstructed",
    )

    private fun obstacleEvent(
        messageKey: String,
        dedupeKey: String,
        zone: DirectionZone,
        cooldownKeys: Set<String> = emptySet(),
        priority: EventPriority = EventPriority.MEDIUM,
    ): SceneEvent = SceneEvent(
        timestamp = 10_000,
        category = EventCategory.OBSTACLE,
        priority = priority,
        messageKey = messageKey,
        expiresAt = 13_000,
        dedupeKey = dedupeKey,
        cooldownKeys = cooldownKeys,
        relatedZones = listOf(zone),
    )

    private fun roadWarningEvent(
        messageKey: String,
        dedupeKey: String,
    ): SceneEvent = SceneEvent(
        timestamp = 10_000,
        category = EventCategory.ROAD_WARNING,
        priority = EventPriority.HIGH,
        messageKey = messageKey,
        expiresAt = 15_000,
        dedupeKey = dedupeKey,
    )

    /**
     * 与 DecideEventsUseCase 相同的 filter -> record 顺序：只有真正放行的事件才进入冷却历史。
     */
    private fun emitThroughProductionPath(event: SceneEvent, now: Long): List<SceneEvent> {
        val emitted = cooldownManager.filter(listOf(event), now)
        emitted.forEach { cooldownManager.recordEvent(it, now) }
        return emitted
    }
}
