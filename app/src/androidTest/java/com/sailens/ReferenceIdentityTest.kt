package com.sailens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the identity of the Sailens Android reference host.
 *
 * The plain com.sailens applicationId belongs to the official Sailens distribution. Keeping this
 * host distinct lets both installs coexist on a device and prevents the platform/reference project
 * from accidentally claiming the store product identity.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceIdentityTest {

    @Test
    fun packageIsTheReferenceHost() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.sailens.reference", appContext.packageName)
    }

    @Test
    fun licenceShownInTheReferenceHostIsApache() {
        assertEquals("Apache-2.0", BuildConfig.APP_LICENSE)
    }

    @Test
    fun sourceLinkPointsAtSailensAndroid() {
        assertEquals("https://github.com/wnbotoo/sailens-android", BuildConfig.APP_SOURCE_URL)
    }
}
