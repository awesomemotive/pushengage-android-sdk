package com.pushengage.pushengage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Issue #38 guard: PushEngage.callAndroidSync must not fire /sites/{site_key}/sync/android
 * when no valid App ID is configured. Previously a missing/empty site key resulted in a
 * request to /sites//sync/android (logged server-side as the DOMAIN-placeholder slot)
 * which the backend 400'd, and the SDK silently retried.
 *
 * MockWebServer is wired up as the backend-CDN base URL; the assertion is that no request
 * reaches the dispatcher during the guard window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageAndroidSyncAppIdGuardTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var prefs: PEPrefs
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        server = MockWebServer()
        server.start()

        prefs = PEPrefs(context).apply {
            // Point the CDN at MockWebServer so a leaked request would be observable.
            siteStatus = PEConstants.ACTIVE
            backendCdnUrl = server.url("/").toString()
        }

        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)
    }

    @After
    fun tearDown() {
        server.shutdown()
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
    }

    private fun invokeCallAndroidSync() {
        val method = PushEngage::class.java.getDeclaredMethod("callAndroidSync")
        method.isAccessible = true
        method.invoke(null)
    }

    @Test
    fun callAndroidSync_whenSiteKeyEmpty_sendsNoRequest() {
        prefs.siteKey = ""

        invokeCallAndroidSync()

        assertNull(
            "Guard must short-circuit before any request is dispatched",
            server.takeRequest(500, TimeUnit.MILLISECONDS)
        )
    }

    @Test
    fun callAndroidSync_whenSiteKeyBlank_sendsNoRequest() {
        prefs.siteKey = "   "

        invokeCallAndroidSync()

        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun callAndroidSync_whenSiteKeyEmpty_doesNotIncrementRetryCount() {
        // The pre-fix code path incremented siteSyncRetryCount on every 400 response and
        // rescheduled itself up to RETRY_COUNT times — multiplying the bad request flood.
        // The new guard returns before the request is dispatched, so the counter must stay
        // at its starting value.
        val before = PushEngageTestSupport.getStaticField("siteSyncRetryCount") as Int
        prefs.siteKey = ""

        invokeCallAndroidSync()

        val after = PushEngageTestSupport.getStaticField("siteSyncRetryCount") as Int
        org.junit.Assert.assertEquals(
            "Retry counter must not advance when sync is short-circuited",
            before, after
        )
    }
}
