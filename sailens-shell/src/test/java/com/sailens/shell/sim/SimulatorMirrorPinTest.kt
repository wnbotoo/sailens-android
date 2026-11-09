package com.sailens.shell.sim

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The session simulator mirrors four pieces of the shell that need Android to run: the guidance screen's view model
 * ([ViewModelMirror]), the motor's calls ([SimHaptics]), the announcement mapping ([SimAnnouncements]) and the shared
 * engines' release in the DI module ([SessionSimulator]). This test fails as soon as one of them changes, so that the
 * mirror is checked against the change: the hashes pin the text the mirror was written against, line ends aside (a
 * Windows checkout may have CRLF). The build declares the four files as inputs of the test task, so an edit that
 * compiles to the same classes, a space included, still runs this test.
 *
 * Of the DI module only the owner's construction is pinned, from `SharedEngineOwner(` to its closing parenthesis
 * (brackets inside strings and comments do not count), since the rest of the module changes with every binding.
 *
 * A hash proves nothing about whether the mirror was right in the first place: that is `MirrorOrderTest`'s, and the
 * review's.
 */
class SimulatorMirrorPinTest {

    private class Pin(val name: String, val text: () -> String)

    @Test
    fun `the code the session simulator mirrors is what it was written against`() {
        val differing = PINS.filter { (pin, hash) -> sha256(pin.text()) != hash }.map { (pin, _) -> pin.name to sha256(pin.text()) }.toMap()
        assertEquals(
            "The session simulator mirrors this code. Check the mirror (ViewModelMirror, SimHaptics, SimAnnouncements, " +
                "the engine release in SessionSimulator) and MirrorOrderTest against the change, then update the hash here",
            emptyMap<String, String>(),
            differing,
        )
    }

    @Test
    fun `the module's pinned construction is found once and closes`() {
        val text = "val a = SharedEngineOwner(\n  release = { x(\")\") /* ( */ ; y('(') }, // )\n)\nval b = 1"
        assertEquals("SharedEngineOwner(\n  release = { x(\")\") /* ( */ ; y('(') }, // )\n)", call(text, "SharedEngineOwner("))
        for (broken in listOf("SharedEngineOwner(a) SharedEngineOwner(b)", "no owner here", "SharedEngineOwner(a, \"(\"")) {
            try {
                call(broken, "SharedEngineOwner(")
                throw AssertionError("expected no single closed call in: $broken")
            } catch (expected: IllegalStateException) {
                // as it should
            }
        }
    }

    private companion object {
        const val SOURCES = "src/main/java/com/sailens/shell"

        fun file(path: String): String = String(File(path).readBytes().filterNot { it == CARRIAGE_RETURN }.toByteArray(), Charsets.UTF_8)

        const val CARRIAGE_RETURN: Byte = 13

        val PINS = mapOf(
            Pin("SceneAnalysisViewModel.kt") { file("$SOURCES/guidance/screen/SceneAnalysisViewModel.kt") } to
                "f11d80146ab6720f83fa1c456c326f7580d298e65910e9b970b9d4e43167abc2",
            Pin("HapticManager.kt") { file("$SOURCES/device/HapticManager.kt") } to
                "3291c10da3ea86f1ae6cdcdf8f024df07ea3ce1a7f1cd3c492cbf5a21d1bf557",
            Pin("GuidanceAnnouncements.kt") { file("$SOURCES/device/GuidanceAnnouncements.kt") } to
                "2b232ef5b04e61509e033b08ac0368a10e651efbba9898b21210ca1065da5abf",
            Pin("ShellModule.kt, SharedEngineOwner(…)") { call(file("$SOURCES/di/ShellModule.kt"), "SharedEngineOwner(") } to
                "2464c9ca65bbdcb8834eaabdf5eb40cb0c52ba3648ff808d136d7078e32e7f7e",
        )

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /**
         * The call that starts with [start] (which must occur exactly once, and end with its opening parenthesis) up
         * to the parenthesis that closes it. Strings, characters and comments (block comments nest, as in Kotlin) are
         * skipped.
         */
        fun call(text: String, start: String): String {
            val at = text.indexOf(start)
            check(at >= 0) { "\"$start\" is not there" }
            check(text.indexOf(start, at + 1) < 0) { "\"$start\" is there more than once" }
            var i = at + start.length
            var depth = 1
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    text.startsWith("/*", i) -> i = skipBlockComment(text, i)
                    text.startsWith("\"\"\"", i) -> i = (text.indexOf("\"\"\"", i + 3).takeIf { it >= 0 } ?: error("an unclosed raw string")) + 3
                    text[i] == '"' -> i = skipQuoted(text, i, '"')
                    text[i] == '\'' -> i = skipQuoted(text, i, '\'')
                    text[i] == '(' -> { depth++; i++ }
                    text[i] == ')' -> {
                        depth--
                        i++
                        if (depth == 0) return text.substring(at, i)
                    }
                    else -> i++
                }
            }
            error("\"$start\" never closes")
        }

        private fun skipBlockComment(text: String, from: Int): Int {
            var depth = 0
            var i = from
            while (i < text.length) {
                when {
                    text.startsWith("/*", i) -> { depth++; i += 2 }
                    text.startsWith("*/", i) -> {
                        depth--
                        i += 2
                        if (depth == 0) return i
                    }
                    else -> i++
                }
            }
            error("an unclosed comment")
        }

        private fun skipQuoted(text: String, from: Int, quote: Char): Int {
            var i = from + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i += 2
                    quote -> return i + 1
                    '\n' -> error("an unclosed literal")
                    else -> i++
                }
            }
            error("an unclosed literal")
        }
    }
}
