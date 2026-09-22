package com.sailens

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildContractTest {

    @Test
    fun `reference host build identity stays distinct from the official product`() {
        assertEquals("com.sailens.reference", BuildConfig.APPLICATION_ID)
        assertEquals("Apache-2.0", BuildConfig.APP_LICENSE)
        assertEquals("https://github.com/wnbotoo/sailens-android", BuildConfig.APP_SOURCE_URL)
    }
}
