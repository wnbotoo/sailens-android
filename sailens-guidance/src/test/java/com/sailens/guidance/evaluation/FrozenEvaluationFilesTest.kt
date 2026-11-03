package com.sailens.guidance.evaluation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The six files frozen with the thresholds (threshold note §3.4) are the bytes of the freezing commit,
 * line ends aside: a Windows checkout may have CRLF. Changing one is possible only by R6: a new commit,
 * its reason and C evidence, review, the user's consent, and this table.
 */
class FrozenEvaluationFilesTest {

    @Test
    fun `the frozen evaluation files are what the thresholds were frozen on`() {
        val differing = FROZEN.filter { (name, hash) -> sha256(name) != hash }.keys
        assertEquals(emptySet<String>(), differing)
    }

    private fun sha256(name: String): String {
        val text = File(DIRECTORY, name).readBytes().filterNot { it == CARRIAGE_RETURN }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DIRECTORY = "src/test/java/com/sailens/guidance/evaluation"
        const val CARRIAGE_RETURN: Byte = 13
        val FROZEN = mapOf(
            "SpeakerModel.kt" to "ff8d1fc89d653c6a6b6c4e86a4fe414c327a6b466aaf19be6814591824ef8f9e",
            "PromptPolicyCaptureEvaluation.kt" to "8d251b43a846c95190ef0fb6f4a17c7b83c81d4cb18205866f3aa4ee6c547a8b",
            "SpeakerDurations.kt" to "becc66ec8eb715d224620fda5f1e73c8572f4b725efadd567d203c5214c13500",
            "D18Sets.kt" to "51a52406709ee8b25cbdf963489db40f5a9cbd1c6262118400e546ca0567c506",
            "SpeakerRobustness.kt" to "fa1cea60d690026f2b443035c6fe0ab08c6c613c3d1ca02325d1d02c6d6fa418",
            "TtsDurations.kt" to "b45f84a53775cf813d2c69aa9ff847d022cbb6b7d22d9d21becf4d82186f518d",
        )
    }
}
