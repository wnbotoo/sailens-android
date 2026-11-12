package com.sailens.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.shell.app.SailensAppSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The spec `App.kt` injects is the one this edition binds (`editionModule`), resolved in the running app with
 * its real Android context, and it promises what this host promises: nothing, since it ships no weights.
 */
@RunWith(AndroidJUnit4::class)
class EditionBindingTest {

    @Test
    fun theRunningAppResolvesThisEditionsSpec() {
        val koin = GlobalContext.get()
        val spec = koin.get<SailensAppSpec>()

        assertSame("one spec for the process", spec, koin.get<SailensAppSpec>())
        assertFalse(spec.expectations.guidanceRequired)
        assertFalse(spec.expectations.describeRequired)
    }
}
