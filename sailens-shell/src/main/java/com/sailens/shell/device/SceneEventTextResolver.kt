package com.sailens.shell.device

import android.content.Context
import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.shell.R
import java.util.IllegalFormatException

/**
 * 把 [com.sailens.guidance.model.scene.SceneEvent] 渲染成一句要播报的话。
 *
 * domain 只产出 messageKey + 两个修饰（近距离、偏移建议），最终句子在这里拼。抽成单独一个类，
 * 是因为同一句话有两个消费方——自带 TTS 和屏幕阅读器播报——两边必须逐字一致，否则用户在
 * 开/关 TalkBack 时会听到两套措辞。
 *
 * 拼装顺序：先把方向后缀套在基础文案外，再套紧迫前缀。结果形如"注意，前方有人，靠左"。
 * 用 format string 而不是字符串拼接，是为了让标点和语序跟着语言走。
 */
class SceneEventTextResolver(private val context: Context) {

    fun resolve(event: SceneEventText): String {
        val base = resolveBaseMessage(event.messageKey, event.messageParams)
        val withDirection = event.directionHint?.let { bias ->
            context.getString(bias.templateResId(), base)
        } ?: base

        return if (event.isNear) {
            context.getString(R.string.event_modifier_near, withDirection)
        } else {
            withDirection
        }
    }

    @Suppress("DiscouragedApi")
    private fun resolveBaseMessage(
        messageKey: String,
        messageParams: Map<String, String>,
    ): String {
        val resId = context.resources.getIdentifier(messageKey, "string", context.packageName)
        // 兜底成 key 本身而不是空串：真出现缺失文案时，用户至少听得出"有个提示没翻译"，
        // 而不是静默丢掉一条可能关乎安全的提示。
        if (resId == 0) return messageKey
        if (messageParams.isEmpty()) return context.getString(resId)

        val args = messageParams.toSortedMap().values.toTypedArray()
        return try {
            context.getString(resId, *args)
        } catch (_: IllegalFormatException) {
            context.getString(resId)
        }
    }

    private fun DirectionBias.templateResId(): Int = when (this) {
        DirectionBias.LEFT -> R.string.event_modifier_bias_left
        DirectionBias.RIGHT -> R.string.event_modifier_bias_right
    }
}

/**
 * [SceneEventTextResolver] 需要的最小输入。
 *
 * 不直接吃 `SceneEvent` 是为了让 UI 侧也能复用同一套拼装逻辑渲染"最新提示"卡片，
 * 而不必在没有真实事件时伪造一个。
 */
data class SceneEventText(
    val messageKey: String,
    val messageParams: Map<String, String> = emptyMap(),
    val isNear: Boolean = false,
    val directionHint: DirectionBias? = null,
)

fun SceneEvent.toSceneEventText(): SceneEventText = SceneEventText(
    messageKey = messageKey,
    messageParams = messageParams,
    isNear = isNear,
    directionHint = directionHint,
)
