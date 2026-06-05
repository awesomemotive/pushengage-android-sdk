package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEPlatform
import com.pushengage.pushengage.helper.PEPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [PushEngage.setWrapperVersion] and the platform detector hook in
 * [PushEngage.Builder.build]. Both are written for wrapper plugins (Flutter, RN)
 * to call from their Android-side init code, so the assertions verify the data
 * survives the round-trip into prefs where the RestClient interceptor reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageWrapperVersionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        PushEngageTestSupport.resetSingleton()
    }

    @After
    fun tearDown() {
        PushEngageTestSupport.resetSingleton()
    }

    @Test
    fun builder_build_setsPlatformToAndroid_whenNoWrapperOnClasspath() {
        PEPrefs(context).hash = "preexisting_hash" // skip auto-subscribe in ctor
        PushEngage.Builder().addContext(context).setAppId("site_xyz").build()

        assertEquals(PEPlatform.ANDROID, PEPrefs(context).platform)
    }

    @Test
    fun setWrapperVersion_isNoOp_whenInstanceNotInitialized() {
        // Called before build() — should not throw, should not write anything.
        PushEngage.setWrapperVersion("2.3.0")
        assertEquals("", PEPrefs(context).wrapperVersion)
    }

    @Test
    fun setWrapperVersion_persistsSanitizedValue_afterBuild() {
        PEPrefs(context).hash = "preexisting_hash"
        PushEngage.Builder().addContext(context).setAppId("site_xyz").build()

        PushEngage.setWrapperVersion("2.3.0")
        assertEquals("2.3.0", PEPrefs(context).wrapperVersion)
    }

    @Test
    fun setWrapperVersion_stripsInvalidChars() {
        PEPrefs(context).hash = "preexisting_hash"
        PushEngage.Builder().addContext(context).setAppId("site_xyz").build()

        PushEngage.setWrapperVersion("2.3.0 beta+1")
        assertEquals("2.3.0beta1", PEPrefs(context).wrapperVersion)
    }

    @Test
    fun setWrapperVersion_nullClearsToEmpty() {
        PEPrefs(context).hash = "preexisting_hash"
        PushEngage.Builder().addContext(context).setAppId("site_xyz").build()
        PushEngage.setWrapperVersion("2.3.0")

        PushEngage.setWrapperVersion(null)
        assertEquals("", PEPrefs(context).wrapperVersion)
    }
}
