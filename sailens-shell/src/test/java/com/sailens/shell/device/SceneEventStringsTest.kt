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
}
