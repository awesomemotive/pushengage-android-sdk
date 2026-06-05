package com.pushengage.pushengage.RestClient

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Exercises the request-header interceptor in [RestClient.getRetrofitClient]. These tests cover
 * the null-safety hardening introduced for `X-Pe-*` headers, the `User-Agent` format, and the
 * custom-headers map path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestClientHeaderTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PEPrefs(context)

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- Positive case ----

    @Test
    fun headers_includeAllStandardFields_whenSiteKeyPresent() {
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        prefs.siteKey = "test_site_key"

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = RestClient.getAnalyticsClient(context, null)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        assertEquals(200, response.code())
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("application/json", request!!.getHeader("content-type"))
        assertEquals("Android", request.getHeader("X-Pe-Client"))
        assertEquals("test_site_key", request.getHeader("X-Pe-App-Id"))
        // X-Pe-Client-Version comes from Build.VERSION.RELEASE which Robolectric populates.
        assertNotNull(request.getHeader("X-Pe-Client-Version"))
        // User-Agent format: Android/<osVer>/<apiLevel>/<pkg>/<appVer>/SDK/<sdkVer>/<flavor>[/<wrapperVer>]
        val ua = request.getHeader("User-Agent")
        assertNotNull(ua)
        assertTrue("UA should start with Android/ prefix: $ua", ua!!.startsWith("Android/"))
        assertTrue("UA should embed SDK segment: $ua", ua.contains("/SDK/${PEConstants.SDK_VERSION}/"))
        // No wrapper registered → flavor is the trailing segment and equals "Android".
        assertTrue("UA should end with /Android (no wrapper): $ua", ua.endsWith("/Android"))
        // 9 segments without wrapper: Android/os/api/manufacturer/pkg/app/SDK/ver/flavor
        val segments = ua.split("/")
        assertEquals("UA should have 9 slash-delimited segments without wrapper: $ua", 9, segments.size)
        // Manufacturer slot (index 3) must be non-empty — Robolectric populates Build.MANUFACTURER.
        assertTrue("Manufacturer segment must be non-empty: $ua", segments[3].isNotEmpty())
    }

    @Test
    fun headers_sdkVersion_isSdkConstant_notHostAppVersion() {
        // Regression: X-Pe-Sdk-Version previously sent the host app's
        // PackageInfo.versionName. It must report the SDK's own version.
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        prefs.siteKey = "test_site_key"

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        RestClient.getAnalyticsClient(context, null)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals(PEConstants.SDK_VERSION, request!!.getHeader("X-Pe-Sdk-Version"))
        val ua = request.getHeader("User-Agent")
        assertNotNull(ua)
        assertTrue("UA should embed SDK version: $ua", ua!!.contains("/SDK/${PEConstants.SDK_VERSION}/"))
    }

    @Test
    fun headers_userAgent_includesFlavorAndWrapperVersion_whenWrapperRegistered() {
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        prefs.siteKey = "site_key"
        prefs.platform = "FlutterAndroid"
        prefs.wrapperVersion = "2.3.0"

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        RestClient.getAnalyticsClient(context, null)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        val ua = request!!.getHeader("User-Agent")
        assertNotNull(ua)
        // Wrapper present → 10 segments, trailing /<flavor>/<wrapperVer>.
        assertTrue("UA should contain flavor segment: $ua", ua!!.contains("/FlutterAndroid/"))
        assertTrue("UA should end with wrapper version: $ua", ua.endsWith("/2.3.0"))
        assertEquals("UA should have 10 segments with wrapper: $ua", 10, ua.split("/").size)
    }

    @Test
    fun headers_userAgent_omitsWrapperVersionSlot_whenOnlyPlatformSet() {
        // Platform set to a non-default flavor, but no wrapper version. The UA still
        // emits the flavor; the trailing /<wrapperVer> slot is absent so segment
        // count signals "wrapped but version-unknown".
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        prefs.siteKey = "site_key"
        prefs.platform = "ReactNativeAndroid"

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        RestClient.getAnalyticsClient(context, null)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        val ua = request!!.getHeader("User-Agent")
        assertNotNull(ua)
        assertTrue("UA should end with /ReactNativeAndroid: $ua", ua!!.endsWith("/ReactNativeAndroid"))
        assertEquals("UA should have 9 segments without wrapper: $ua", 9, ua.split("/").size)
    }

    // ---- Null-safety: empty siteKey ----

    @Test
    fun headers_sendEmptyAppIdAndUsesAndroidFlavor_whenSiteKeyIsEmpty() {
        // X-Pe-App-Id is part of the SDK's contract with the backend — always present
        // on every outbound request. When siteKey has never been configured we send
        // an empty header rather than omitting it, so backend routing/observability
        // sees a consistent header set.
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        // siteKey deliberately left unset (defaults to "").

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = RestClient.getAnalyticsClient(context, null)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        assertEquals(200, response.code())
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        // X-Pe-App-Id should be present as an empty string, not absent.
        assertEquals(
            "X-Pe-App-Id must be sent as empty string when siteKey is unset",
            "",
            request!!.getHeader("X-Pe-App-Id")
        )
        // User-Agent should still be set; flavor falls back to Android when platform is unset.
        val ua = request.getHeader("User-Agent")
        assertNotNull(ua)
        assertTrue("UA should start with Android/ prefix: $ua", ua!!.startsWith("Android/"))
        assertTrue("UA should end with /Android (flavor default): $ua", ua.endsWith("/Android"))
    }

    // ---- Null-safety: custom headers map ----

    @Test
    fun customHeader_withNonNullValue_isSent() {
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        prefs.siteKey = "site_key"

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val headers = hashMapOf("X-Custom-Header" to "custom-value")
        val response = RestClient.getAnalyticsClient(context, headers)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        assertEquals(200, response.code())
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("custom-value", request!!.getHeader("X-Custom-Header"))
    }

    @Test
    fun customHeader_withEmptyStringValue_isStillSent() {
        // The helper's contract is `value != null`, so empty strings ARE forwarded.
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        prefs.siteKey = "site_key"

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val headers = hashMapOf("X-Empty-Header" to "")
        val response = RestClient.getAnalyticsClient(context, headers)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        assertEquals(200, response.code())
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("", request!!.getHeader("X-Empty-Header"))
    }

    @Test
    fun customHeaders_nullMap_doesNotCrash() {
        prefs.environment = PEConstants.PROD
        prefs.analyticsUrl = server.url("/").toString()
        prefs.siteKey = "site_key"

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = RestClient.getAnalyticsClient(context, null)
            .notificationView("device_hash", "tag", PEConstants.ANDROID, PEConstants.MOBILE, "1.2.3", "UTC")
            .execute()

        assertEquals(200, response.code())
    }

    // ---- Direct helper-method tests via reflection ----

    @Test
    fun getSafeHeaderValue_null_returnsEmpty() {
        val method = RestClient::class.java.getDeclaredMethod("getSafeHeaderValue", String::class.java)
        method.isAccessible = true
        assertEquals("", method.invoke(null, null as String?))
    }

    @Test
    fun getSafeHeaderValue_nonNull_returnsSameValue() {
        val method = RestClient::class.java.getDeclaredMethod("getSafeHeaderValue", String::class.java)
        method.isAccessible = true
        assertEquals("value", method.invoke(null, "value"))
        assertEquals("", method.invoke(null, ""))
    }

    @Test
    fun addHeaderIfValuePresent_nullValue_doesNotAdd() {
        val builder = Request.Builder().url("https://example.com/")
        invokeAddHeader(builder, "X-Foo", null)
        val req = builder.build()
        assertNull(req.header("X-Foo"))
    }

    @Test
    fun addHeaderIfValuePresent_nullKey_doesNotAdd() {
        val builder = Request.Builder().url("https://example.com/")
        // Cannot inspect because key is null; just assert it does not throw.
        invokeAddHeader(builder, null, "value")
        val req = builder.build()
        // Headers count should be unchanged (only URL was set).
        assertTrue(req.headers.size == 0)
    }

    @Test
    fun addHeaderIfValuePresent_emptyKey_doesNotAdd() {
        val builder = Request.Builder().url("https://example.com/")
        invokeAddHeader(builder, "", "value")
        val req = builder.build()
        assertTrue(req.headers.size == 0)
    }

    @Test
    fun addHeaderIfValuePresent_validKeyAndValue_adds() {
        val builder = Request.Builder().url("https://example.com/")
        invokeAddHeader(builder, "X-Foo", "bar")
        val req = builder.build()
        assertEquals("bar", req.header("X-Foo"))
    }

    @Test
    fun addHeaderIfValuePresent_emptyStringValue_isAdded() {
        // Empty string is a valid value per the helper's `value != null` check.
        val builder = Request.Builder().url("https://example.com/")
        invokeAddHeader(builder, "X-Foo", "")
        val req = builder.build()
        assertEquals("", req.header("X-Foo"))
    }

    // ---- helpers ----

    private fun invokeAddHeader(builder: Request.Builder, key: String?, value: String?) {
        val method = RestClient::class.java.getDeclaredMethod(
            "addHeaderIfValuePresent",
            Request.Builder::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(null, builder, key, value)
    }

}
