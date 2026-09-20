package com.sailens.shell.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechClauseBufferTest {

    @Test
    fun `holds tokens until a sentence boundary arrives`() {
        val buffer = SpeechClauseBuffer()

        assertNull(buffer.append("前方"))
        assertNull(buffer.append("有人"))
        assertEquals("前方有人。", buffer.append("。"))
    }

    @Test
    fun `does not split on a comma before the soft minimum`() {
        val buffer = SpeechClauseBuffer(softMinChars = 12)

        // "前方有人，" 只有 5 个字：切在这里会念出一个短到听不出意思的碎片。
        assertNull(buffer.append("前方有人，"))
        assertEquals("前方有人，右边可以走。", buffer.append("右边可以走。"))
    }

    @Test
    fun `splits on a comma once enough text has accumulated`() {
        val buffer = SpeechClauseBuffer(softMinChars = 8)

        assertEquals(
            "前方三米处有一根电线杆，",
            buffer.append("前方三米处有一根电线杆，"),
        )
    }

    @Test
    fun `force-splits when the model never punctuates`() {
        val buffer = SpeechClauseBuffer(softMinChars = 12, hardMaxChars = 7)

        // 一个标点都没有。死等标点等于永远不出声，所以到长度就切。
        assertNull(buffer.append("七个字的文"))
        assertEquals("七个字的文本段", buffer.append("本段"))
    }

    @Test
    fun `cuts at the last boundary when one delta carries several sentences`() {
        val buffer = SpeechClauseBuffer()

        // 一次吐出两整句时一起交出去当一条语句念，比拆两条更连贯。
        assertEquals(
            "前方有人。右边可以走。",
            buffer.append("前方有人。右边可以走。"),
        )
    }

    @Test
    fun `keeps the tail after the last boundary`() {
        val buffer = SpeechClauseBuffer()

        assertEquals("前方有人。", buffer.append("前方有人。右边"))
        assertEquals("右边", buffer.drain())
    }

    @Test
    fun `drain returns the unterminated tail`() {
        val buffer = SpeechClauseBuffer()

        assertNull(buffer.append("右边可以走"))
        // 模型没有用标点收尾。不交出这一段，整句的结论部分就被吞掉了。
        assertEquals("右边可以走", buffer.drain())
    }

    @Test
    fun `drain is empty once everything has been spoken`() {
        val buffer = SpeechClauseBuffer()

        assertEquals("前方有人。", buffer.append("前方有人。"))
        assertNull(buffer.drain())
    }

    @Test
    fun `blank and empty deltas produce nothing`() {
        val buffer = SpeechClauseBuffer()

        assertNull(buffer.append(""))
        assertNull(buffer.append(" "))
        assertNull(buffer.append("\n"))
        assertNull(buffer.drain())
    }
}
