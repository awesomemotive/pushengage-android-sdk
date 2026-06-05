package com.pushengage.pushengage.RestClient

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestClientUrlTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var getBaseUrlMethod: Method

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(context)

        // Access the private static getBaseUrl method via reflection
        getBaseUrlMethod = RestClient::class.java.getDeclaredMethod(
            "getBaseUrl",
            PEPrefs::class.java,
            String::class.java
        )
        getBaseUrlMethod.isAccessible = true
    }

    // --- Production URL tests ---

    @Test
    fun getBaseUrl_production_base_returnsProdBaseUrl() {
        prefs.environment = PEConstants.PROD
        prefs.backendUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.BASE) as String

        assertEquals(PEConstants.PROD_BASE_URL, url)
    }

    @Test
    fun getBaseUrl_production_baseCdn_returnsProdCdnUrl() {
        prefs.environment = PEConstants.PROD
        prefs.backendCdnUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.BASE_CDN) as String

        assertEquals(PEConstants.PROD_BASE_CDN_URL, url)
    }

    @Test
    fun getBaseUrl_production_trigger_returnsProdTriggerUrl() {
        prefs.environment = PEConstants.PROD
        prefs.triggerUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.TRIGGER) as String

        assertEquals(PEConstants.PROD_TRIGGER_URL, url)
    }

    @Test
    fun getBaseUrl_production_analytics_returnsProdAnalyticsUrl() {
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.ANALYTICS) as String

        assertEquals(PEConstants.PROD_ANALYTICS_URL, url)
    }

    @Test
    fun getBaseUrl_production_log_returnsProdLogUrl() {
        prefs.environment = PEConstants.PROD
        prefs.loggerUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.LOG) as String

        assertEquals(PEConstants.PROD_LOG_URL, url)
    }

    // --- Staging URL tests ---

    @Test
    fun getBaseUrl_staging_base_returnsStgBaseUrl() {
        prefs.environment = PEConstants.STG
        prefs.backendUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.BASE) as String

        assertEquals(PEConstants.STG_BASE_URL, url)
    }

    @Test
    fun getBaseUrl_staging_baseCdn_returnsStgCdnUrl() {
        prefs.environment = PEConstants.STG
        prefs.backendCdnUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.BASE_CDN) as String

        assertEquals(PEConstants.STG_BASE_CDN_URL, url)
    }

    @Test
    fun getBaseUrl_staging_trigger_returnsStgTriggerUrl() {
        prefs.environment = PEConstants.STG
        prefs.triggerUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.TRIGGER) as String

        assertEquals(PEConstants.STG_TRIGGER_URL, url)
    }

    @Test
    fun getBaseUrl_staging_analytics_returnsStgAnalyticsUrl() {
        prefs.environment = PEConstants.STG
        prefs.analyticsUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.ANALYTICS) as String

        assertEquals(PEConstants.STG_ANALYTICS_URL, url)
    }

    @Test
    fun getBaseUrl_staging_log_returnsStgLogUrl() {
        prefs.environment = PEConstants.STG
        prefs.loggerUrl = ""

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.LOG) as String

        assertEquals(PEConstants.STG_LOG_URL, url)
    }

    // --- Custom URL override tests ---

    @Test
    fun getBaseUrl_customUrl_inPrefs_returnsCustomUrl() {
        prefs.environment = PEConstants.PROD
        val customUrl = "https://custom-backend.example.com/v2/"
        prefs.backendUrl = customUrl

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.BASE) as String

        assertEquals(customUrl, url)
    }

    @Test
    fun getBaseUrl_customCdnUrl_inPrefs_returnsCustomUrl() {
        prefs.environment = PEConstants.PROD
        val customUrl = "https://custom-cdn.example.com/v2/"
        prefs.backendCdnUrl = customUrl

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.BASE_CDN) as String

        assertEquals(customUrl, url)
    }

    @Test
    fun getBaseUrl_customTriggerUrl_inPrefs_returnsCustomUrl() {
        prefs.environment = PEConstants.PROD
        val customUrl = "https://custom-trigger.example.com/"
        prefs.triggerUrl = customUrl

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.TRIGGER) as String

        assertEquals(customUrl, url)
    }

    @Test
    fun getBaseUrl_customAnalyticsUrl_inPrefs_returnsCustomUrl() {
        prefs.environment = PEConstants.PROD
        val customUrl = "https://custom-analytics.example.com/"
        prefs.analyticsUrl = customUrl

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.ANALYTICS) as String

        assertEquals(customUrl, url)
    }

    @Test
    fun getBaseUrl_customLogUrl_inPrefs_returnsCustomUrl() {
        prefs.environment = PEConstants.PROD
        val customUrl = "https://custom-log.example.com/"
        prefs.loggerUrl = customUrl

        val url = getBaseUrlMethod.invoke(null, prefs,PEConstants.LOG) as String

        assertEquals(customUrl, url)
    }

    // --- Edge case ---

    @Test
    fun getBaseUrl_unknownUrlType_returnsEmpty() {
        val url = getBaseUrlMethod.invoke(null, prefs,"UNKNOWN_TYPE") as String

        assertEquals("", url)
    }

    @Test
    fun getAnalyticsClient_withNullCustomHeaderValue_doesNotCrash() {
        val server = MockWebServer()
        server.start()
        try {
            prefs.environment = PEConstants.PROD
            prefs.analyticsUrl = server.url("/").toString()
            prefs.siteKey = "site_key"

            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            @Suppress("UNCHECKED_CAST")
            val headers = hashMapOf("x-null-header" to null) as Map<String, String>

            val response = RestClient.getAnalyticsClient(context, headers)
                .notificationView(
                    "device_hash",
                    "tag",
                    PEConstants.ANDROID,
                    PEConstants.MOBILE,
                    "0.0.6",
                    "UTC"
                )
                .execute()

            assertEquals(200, response.code())

            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(request)
            assertNull(request!!.getHeader("x-null-header"))
        } finally {
            server.shutdown()
        }
    }
}
