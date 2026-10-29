package com.sailens.shell.device

import com.sailens.guidance.model.scene.SceneEventMessageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEventStringsTest {

    @Test
    fun `every key Guidance can emit has a string resource, and nothing else is mapped`() {
        assertEquals(
            "Keys Guidance emits without a string (they would be read aloud raw), or strings " +
                "mapped to keys Guidance never emits",
            SceneEventMessageKeys.all.sorted(),
            SceneEventStrings.byKey.keys.sorted(),
        )
    }

    @Test
    fun `every mapped resource is a distinct real id`() {
        val ids = SceneEventStrings.byKey.values
        assertTrue(ids.all { it != 0 })
        assertEquals(ids.size, ids.toSet().size)
    }

    /**
     * No event message has a format placeholder, so `SceneEvent.messageParams` is never spoken: what a
     * user hears is the message, the NEAR prefix and the direction suffix. The ground-contact capture evaluation
     * relies on this to tell a reworded prompt from changed metadata; a placeholder added here must
     * be reflected there.
     */
    @Test
    fun `no event message takes a parameter`() {
        for (values in listOf("values", "values-zh")) {
            val xml = java.io.File("src/main/res/$values/strings.xml").readText()
            for (key in SceneEventMessageKeys.all) {
                val text = Regex("""<string name="${Regex.escape(key)}"[^>]*>(.*?)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$values: no string named $key", text != null)
                assertTrue("$values/$key takes a parameter: $text", !Regex("""%(\d+\$)?[sd]""").containsMatchIn(text!!))
            }
        }
    }
}
