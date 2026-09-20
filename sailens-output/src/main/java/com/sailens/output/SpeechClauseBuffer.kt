package com.sailens.output

/**
 * 把 VLM 的 token 流切成"可以立刻念出来"的小句。
 *
 * 为什么需要它：VLM 逐 token 解码，一句完整的话要数秒才出齐。按 token 念会把每个字念成一条
 * 独立语句（字与字之间插进引擎的启停间隙，完全听不懂）；等整句出齐再念又白等数秒，而用户
 * 看不到进度条——他只知道自己按了按钮然后什么都没发生。所以按**子句**攒够就念。
 *
 * 切分策略，从强到弱：
 * 1. 句末标点（。！？.!?换行）—— 一定切。这是最自然的语音停顿点。
 * 2. 句中标点（，、；,;:）—— 只有攒够 [softMinChars] 才切。每个逗号都切会把一句话
 *    切成一串短促的碎片，听起来比整句晚念更糟。
 * 3. 攒到 [hardMaxChars] 还没遇到任何标点 —— 强制切。模型可能整段不打标点，
 *    那种情况下死等标点等于永远不出声。
 *
 * 非线程安全：调用方（ViewModel 的单个协程）串行使用。
 */
class SpeechClauseBuffer(
    private val softMinChars: Int = DEFAULT_SOFT_MIN_CHARS,
    private val hardMaxChars: Int = DEFAULT_HARD_MAX_CHARS,
) {
    private val pending = StringBuilder()

    /**
     * 吃进一片增量文本，返回此刻已经可以念的子句；还没攒够则返回 null。
     *
     * 一片增量里可能同时含多个句末标点（模型一次吐一整句的情况），此时切到**最后**一个
     * 标点为止，一次交出去当一条语句念——比拆成多条更连贯。
     */
    fun append(delta: String): String? {
        if (delta.isEmpty()) return null
        pending.append(delta)

        val cut = cutIndex() ?: return null
        val clause = pending.substring(0, cut)
        pending.delete(0, cut)
        return clause.takeIf { it.isNotBlank() }
    }

    /**
     * 交出剩下的尾巴。生成结束时必须调用，否则最后一个没有标点收尾的子句会被吞掉——
     * 而那往往正是句子的结论部分。
     */
    fun drain(): String? {
        if (pending.isBlank()) {
            pending.clear()
            return null
        }
        val rest = pending.toString()
        pending.clear()
        return rest
    }

    /** 返回应当切断的位置（不含则 null）。 */
    private fun cutIndex(): Int? {
        lastIndexOfAny(STRONG_BOUNDARIES)?.let { return it + 1 }

        if (pending.length >= softMinChars) {
            lastIndexOfAny(WEAK_BOUNDARIES)?.let { return it + 1 }
        }

        // 一个标点都没有，且已经攒得过长：就地切断。宁可断在词中间，也不要一直不出声。
        if (pending.length >= hardMaxChars) return pending.length

        return null
    }

    private fun lastIndexOfAny(chars: Set<Char>): Int? {
        for (i in pending.indices.reversed()) {
            if (pending[i] in chars) return i
        }
        return null
    }

    private companion object {
        /** 句末：一定切。 */
        val STRONG_BOUNDARIES = setOf('。', '！', '？', '\n', '.', '!', '?')

        /** 句中：攒够才切。 */
        val WEAK_BOUNDARIES = setOf('，', '、', '；', '：', ',', ';', ':')

        /** 短于这个长度就不在句中标点处切，避免碎成一串。 */
        const val DEFAULT_SOFT_MIN_CHARS = 12

        /** 完全没有标点时的强制切断长度。 */
        const val DEFAULT_HARD_MAX_CHARS = 48
    }
}
