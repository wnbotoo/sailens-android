package com.sailens.shell.safetynotice

import com.sailens.shell.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafetyNoticeStringsTest {

    @Test
    fun `the notice is five paragraphs, in reading order`() {
        assertEquals(
            listOf(
                R.string.safety_notice_assistive_only,
                R.string.safety_notice_no_prompt,
                R.string.safety_notice_walking,
                R.string.safety_notice_background,
                R.string.safety_notice_privacy,
            ),
            SAFETY_NOTICE_PARAGRAPHS,
        )
    }

    @Test
    fun `every string of the notice is there, in both languages, with nothing to fill in`() {
        for (values in LOCALES) {
            for (name in NOTICE_STRINGS) {
                val text = text(values, name)
                assertTrue("$values: no string named $name", text != null)
                assertTrue("$values/$name is empty", text!!.isNotBlank())
                assertTrue("$values/$name takes a parameter: $text", !Regex("""%(\d+\$)?[sdf]""").containsMatchIn(text))
            }
        }
    }

    @Test
    fun `the footer names the Settings section by its title`() {
        for (values in LOCALES) {
            val section = text(values, "settings_section_safety")!!
            val footer = text(values, "safety_notice_footer")!!
            assertTrue("$values: \"$footer\" does not name \"$section\"", footer.contains(section))
        }
    }

    private companion object {
        val LOCALES = listOf("values", "values-zh")

        val NOTICE_STRINGS = listOf(
            "safety_notice_title",
            "safety_notice_assistive_only",
            "safety_notice_no_prompt",
            "safety_notice_walking",
            "safety_notice_background",
            "safety_notice_privacy",
            "safety_notice_footer",
            "safety_notice_confirm",
            "settings_section_safety",
        )

        fun text(values: String, name: String): String? {
            val xml = File("src/main/res/$values/strings.xml").readText(Charsets.UTF_8)
            return Regex("""<string name="${Regex.escape(name)}"[^>]*>(.*?)</string>""").find(xml)?.groupValues?.get(1)
        }
    }
}
