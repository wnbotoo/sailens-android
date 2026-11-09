package com.sailens.shell.sim

import org.junit.Assert.fail
import java.io.File

/**
 * Compares a session's lines with its checked-in `<name>.timeline.txt`, or rewrites the file under `-PupdateGoldens`
 * (the build passes `sailens.updateGoldens` and `sailens.timelines.dir`). A rewritten file is written before any of
 * the test's own assertions run, so it is there to read even when one of them fails.
 */
internal object Timelines {
    private val update = System.getProperty("sailens.updateGoldens") == "true"

    fun check(name: String, lines: List<String>) {
        val fileName = "$name.timeline.txt"
        if (update) {
            val dir = File(checkNotNull(System.getProperty("sailens.timelines.dir")) { "timelines dir not set" })
            dir.mkdirs()
            File(dir, fileName).writeText(lines.joinToString("\n", postfix = "\n"))
            return
        }
        val resource = javaClass.getResource("/timelines/$fileName")
            ?: return fail("no timeline $fileName; generate it with -PupdateGoldens")
        val expected = resource.readText().replace("\r\n", "\n").trimEnd('\n').split('\n')
        val firstDiff = (0 until maxOf(expected.size, lines.size)).firstOrNull { expected.getOrNull(it) != lines.getOrNull(it) }
            ?: return
        fail(
            "$fileName differs from its timeline at line ${firstDiff + 1}\n" +
                "  timeline: ${expected.getOrNull(firstDiff) ?: "<end>"}\n" +
                "  actual:   ${lines.getOrNull(firstDiff) ?: "<end>"}\n" +
                "If the change is intended, regenerate with -PupdateGoldens and review the diff.",
        )
    }
}
