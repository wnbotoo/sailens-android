package com.sailens.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The zero-copy path reads LiteRT's C++ TensorBuffer wrapper by layout (litert_zero_copy.h,
 * note 3) and calls LiteRtLockTensorBuffer by its 2.1.5 signature. Neither is a published
 * contract, so a LiteRT upgrade must not slip through unverified.
 *
 * If this fails because you upgraded LiteRT: re-check the wrapper layout and the C signatures
 * against the new release, re-run the §12.3 device gate (handle path active, output identical to
 * the array path, no crash), then update [VERIFIED_LITERT_VERSION].
 */
class LiteRtVersionPinTest {

    @Test
    fun `zero-copy was verified against the LiteRT version in use`() {
        val catalog = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gradle/libs.versions.toml") }
            .first { it.isFile }
        val version = Regex("""^litert\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(catalog.readText())
            ?.groupValues?.get(1)

        assertEquals(
            "LiteRT changed: re-verify the zero-copy ABI (see this test's KDoc) before bumping",
            VERIFIED_LITERT_VERSION,
            version,
        )
    }

    private companion object {
        const val VERIFIED_LITERT_VERSION = "2.1.5"
    }
}
