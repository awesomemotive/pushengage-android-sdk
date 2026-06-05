package com.pushengage.pushengage.helper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the User-Agent sanitization invariants on the platform / wrapperVersion
 * prefs: characters outside [A-Za-z0-9._-] are stripped, length is capped, and an
 * empty / null / fully-stripped input falls back to the documented default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEPrefsPlatformTest {

    private lateinit var prefs: PEPrefs

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(ctx)
    }

    @Test
    fun platform_defaultsToAndroid_whenUnset() {
        assertEquals(PEPlatform.ANDROID, prefs.platform)
    }

    @Test
    fun platform_acceptsValidValue() {
        prefs.platform = PEPlatform.FLUTTER_ANDROID
        assertEquals(PEPlatform.FLUTTER_ANDROID, prefs.platform)
    }

    @Test
    fun platform_stripsInvalidChars() {
        prefs.platform = "Flutter Android!"
        assertEquals("FlutterAndroid", prefs.platform)
    }

    @Test
    fun platform_fallsBackToAndroid_whenFullyStripped() {
        prefs.platform = "!!! @@@"
        assertEquals(PEPlatform.ANDROID, prefs.platform)
    }

    @Test
    fun platform_fallsBackToAndroid_whenNull() {
        prefs.platform = null
        assertEquals(PEPlatform.ANDROID, prefs.platform)
    }

    @Test
    fun platform_capsLengthAt64() {
        val longInput = "A".repeat(100)
        prefs.platform = longInput
        assertEquals(64, prefs.platform.length)
    }

    @Test
    fun wrapperVersion_defaultsToEmpty_whenUnset() {
        assertEquals("", prefs.wrapperVersion)
    }

    @Test
    fun wrapperVersion_acceptsDottedVersion() {
        prefs.wrapperVersion = "2.3.0"
        assertEquals("2.3.0", prefs.wrapperVersion)
    }

    @Test
    fun wrapperVersion_stripsInvalidChars() {
        prefs.wrapperVersion = "2.3.0-beta+build 42"
        assertEquals("2.3.0-betabuild42", prefs.wrapperVersion)
    }

    @Test
    fun wrapperVersion_fallsBackToEmpty_whenNull() {
        prefs.wrapperVersion = null
        assertEquals("", prefs.wrapperVersion)
    }
}
