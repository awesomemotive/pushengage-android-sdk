package com.pushengage.pushengage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.request.AddDynamicSegmentRequest
import com.pushengage.pushengage.model.request.TrackEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MockWebServer-driven coverage for the network-coupled PushEngage methods. Each test
 * - sets up Robolectric with a connected network and an active site,
 * - points the backend URL at a local mock server,
 * - enqueues a canned response,
 * - invokes the API method with a latch-backed callback,
 * - asserts the callback fires and the request was sent to the expected path.
 *
 * Hits the success and error branches of unsubscribe / addSegment / removeSegment /
 * addDynamicSegment / addProfileId / *SubscriberAttributes / getSubscriberDetails /
 * getSubscriberAttributes — the branches that the early-validation tests don't reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageNetworkPathsTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var prefs: PEPrefs
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null
    private var originalManager: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        setNetworkConnected(true)

        server = MockWebServer()
        server.start()

        prefs = PEPrefs(context).apply {
            siteStatus = PEConstants.ACTIVE
            hash = "test_hash"
            siteId = 1L
            backendUrl = server.url("/").toString()
        }

        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")
        originalManager = PushEngageTestSupport.getStaticField("peManager")
        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", prefs)
        // peManager is required for facade methods that delegate to it (e.g. trackEvent).
        PushEngageTestSupport.setStaticField("peManager", PEManager(context, prefs))
    }

    @After
    fun tearDown() {
        server.shutdown()
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
        PushEngageTestSupport.setStaticField("peManager", originalManager)
    }

    // ---- unsubscribe ----

    @Test
    fun unsubscribe_serverReturns200_invokesOnSuccessAndPersistsManualFlag() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":true}"""))

        val cb = LatchCallback()
        PushEngage.unsubscribe(cb)

        assertTrue(cb.await())
        assertTrue("Expected onSuccess", cb.successInvoked)
        assertEquals(true, cb.successObject)

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        // Local manually-unsubscribed flag is set before the server call.
        assertTrue("prefs.isManuallyUnsubscribed must be true after success", prefs.isManuallyUnsubscribed)
    }

    @Test
    fun unsubscribe_serverReturns500_invokesOnFailureAndRevertsManualFlag() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))

        val cb = LatchCallback()
        PushEngage.unsubscribe(cb)

        assertTrue(cb.await())
        assertTrue("Expected onFailure", cb.failureInvoked)
        assertEquals(500, cb.failureCode)
        // Reverted on server error.
        assertFalse("prefs.isManuallyUnsubscribed must be reverted on failure", prefs.isManuallyUnsubscribed)
    }

    // ---- addSegment ----

    @Test
    fun addSegment_serverReturns200_invokesOnSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.addSegment(listOf("seg_a", "seg_b"), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
    }

    @Test
    fun addSegment_serverReturnsError_invokesOnFailureWithServerErrorMessage() {
        server.enqueue(MockResponse().setResponseCode(503).setBody(""))
        val cb = LatchCallback()
        PushEngage.addSegment(listOf("seg_a"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(503, cb.failureCode)
    }

    @Test
    fun addSegment_serverReturnsErrorWithJsonBody_propagatesErrorCodeAndMessage() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":42,"error_message":"bad segment"}""")
        )
        val cb = LatchCallback()
        PushEngage.addSegment(listOf("seg_a"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(42, cb.failureCode)
        assertEquals("bad segment", cb.failureMessage)
    }

    // ---- removeSegment ----

    @Test
    fun removeSegment_serverReturns200_invokesOnSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.removeSegment(listOf("seg_x"), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
    }

    // ---- addDynamicSegment ----

    @Test
    fun addDynamicSegment_serverReturns200_invokesOnSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val outer = AddDynamicSegmentRequest()
        val segs = listOf(outer.Segment("seg_dyn", 30L))

        val cb = LatchCallback()
        PushEngage.addDynamicSegment(segs, cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
    }

    // ---- addProfileId ----

    @Test
    fun addProfileId_serverReturns200_invokesOnSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.addProfileId("user_12345", cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
    }

    @Test
    fun addProfileId_serverReturns200_writesProfileIdToSubscriberFieldsCache() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.addProfileId("user_99", cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        assertEquals("user_99", prefs.subscriberFields["profile_id"])
    }

    // ---- identify ----

    @Test
    fun identify_serverReturns200_invokesOnSuccessAndUpdatesCache() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.identify(
            JSONObject().put("email", "jane@example.com").put("profile_id", "u_42"),
            cb,
        )

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.startsWith("/subscriber/test_hash"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"email\":\"jane@example.com\""))
        assertTrue(body.contains("\"profile_id\":\"u_42\""))

        assertEquals("jane@example.com", prefs.subscriberFields["email"])
        assertEquals("u_42", prefs.subscriberFields["profile_id"])
    }

    @Test
    fun identify_numericProfileId_isCoercedToStringInBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.identify(JSONObject().put("profile_id", 12345), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        val body = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue("body should send profile_id as a string, got: $body", body.contains("\"profile_id\":\"12345\""))
    }

    @Test
    fun identify_serverReturnsErrorJson_propagatesErrorCode() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":42,"error_message":"bad fields"}""")
        )
        val cb = LatchCallback()
        PushEngage.identify(JSONObject().put("email", "x@y.com"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(42, cb.failureCode)
        assertEquals("bad fields", cb.failureMessage)
        // Cache must not be polluted on failure.
        assertNull(prefs.subscriberFields["email"])
    }

    @Test
    fun identify_cacheAlreadyInSync_shortCircuitsWithOnSuccessAndNoRequest() {
        prefs.mergeSubscriberFields(mapOf("email" to "jane@example.com", "profile_id" to "u_42"))
        val cb = LatchCallback()
        PushEngage.identify(
            JSONObject().put("email", "jane@example.com").put("profile_id", "u_42"),
            cb,
        )

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        assertNull("Expected no HTTP request when cache is in sync", server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun identify_cacheInSyncButTtlExpired_forcesNetworkCallToRefreshTimestamp() {
        // Cache is byte-for-byte in sync, but its last-confirmed timestamp is older
        // than 24h, so the handler must distrust it and re-hit the server. This
        // bounds the server-side divergence window (dashboard edits, PII purges)
        // at one day worst-case.
        prefs.mergeSubscriberFields(mapOf("email" to "jane@example.com"))
        backdateCacheTimestamp(System.currentTimeMillis() - 25L * 60L * 60L * 1000L)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val cb = LatchCallback()
        PushEngage.identify(JSONObject().put("email", "jane@example.com"), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Expected a network call when cache TTL has expired", req)
        // And the successful response should have refreshed the timestamp.
        assertTrue(
            "Timestamp should be refreshed after a successful network call",
            prefs.subscriberCacheTimestamp > System.currentTimeMillis() - 5_000L,
        )
    }

    // ---- logout ----

    @Test
    fun logout_serverReturns200_invokesOnSuccessAndRemovesFromCache() {
        prefs.mergeSubscriberFields(mapOf("email" to "jane@example.com", "first_name" to "Jane"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.logout(listOf("email", "first_name"), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.endsWith("/subscriber/test_hash/fields"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("email"))
        assertTrue(body.contains("first_name"))

        assertNull(prefs.subscriberFields["email"])
        assertNull(prefs.subscriberFields["first_name"])
    }

    @Test
    fun logout_emptyList_defaultsToPiiFieldSet() {
        prefs.mergeSubscriberFields(mapOf("email" to "jane@example.com", "profile_id" to "u_1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.logout(emptyList(), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        val body = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
        // Default PII set: first_name, last_name, email, phone, gender, dob, profile_id
        assertTrue(body.contains("email"))
        assertTrue(body.contains("profile_id"))
        assertTrue(body.contains("first_name"))
    }

    @Test
    fun logout_nothingInCacheAndNeverConfirmed_makesNetworkCall() {
        // An empty cache with timestamp == 0L means "we've never confirmed any
        // subscriber state with the server" — the absence of keys locally tells
        // us nothing about server state. Under the 24h TTL contract this is
        // treated as not-fresh, so logout must hit the server to ensure removal.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.logout(listOf("email", "profile_id"), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        assertNotNull(
            "Expected a network call: empty cache is not server-confirmed under TTL contract",
            server.takeRequest(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun logout_cachedButTtlExpired_makesNetworkCall() {
        // Cache has the key locally, but it's older than 24h, so the optimization
        // can't assume the server still has the same state.
        prefs.mergeSubscriberFields(mapOf("email" to "jane@example.com"))
        backdateCacheTimestamp(System.currentTimeMillis() - 25L * 60L * 60L * 1000L)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val cb = LatchCallback()
        PushEngage.logout(listOf("first_name"), cb) // key not in cache, would normally short-circuit

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        assertNotNull(
            "Expected a network call when cache TTL has expired",
            server.takeRequest(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun logout_serverReturnsError_invokesOnFailure() {
        prefs.mergeSubscriberFields(mapOf("email" to "jane@example.com"))
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val cb = LatchCallback()
        PushEngage.logout(listOf("email"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(500, cb.failureCode)
        // Cache must not be mutated on failure.
        assertEquals("jane@example.com", prefs.subscriberFields["email"])
    }

    @Test
    fun addProfileId_serverReturnsErrorJson_propagatesErrorCode() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":71,"error_message":"invalid profile"}""")
        )
        val cb = LatchCallback()
        PushEngage.addProfileId("user_12345", cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(71, cb.failureCode)
        assertEquals("invalid profile", cb.failureMessage)
    }

    // ---- addSubscriberAttributes ----

    @Test
    fun addSubscriberAttributes_serverReturns200_invokesOnSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.addSubscriberAttributes(JSONObject().apply { put("country", "IN") }, cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
    }

    // ---- setSubscriberAttributes ----

    @Test
    fun setSubscriberAttributes_serverReturns200_invokesOnSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.setSubscriberAttributes(JSONObject().apply { put("plan", "pro") }, cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
    }

    // ---- deleteSubscriberAttributes ----

    @Test
    fun deleteSubscriberAttributes_serverReturns200_invokesOnSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val cb = LatchCallback()
        PushEngage.deleteSubscriberAttributes(listOf("country"), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
    }

    @Test
    fun deleteSubscriberAttributes_serverReturnsError_invokesOnFailure() {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val cb = LatchCallback()
        PushEngage.deleteSubscriberAttributes(listOf("country"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(500, cb.failureCode)
    }

    // ---- getSubscriberDetails ----

    @Test
    fun getSubscriberDetails_serverReturns200WithData_invokesOnSuccessWithBody() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"country":"IN","city":"Pune"}}""")
        )
        val cb = LatchCallback()
        PushEngage.getSubscriberDetails(listOf("country", "city"), cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        assertNotNull(cb.successObject)
    }

    @Test
    fun getSubscriberDetails_serverReturnsErrorJson_propagatesErrorCodeAndMessage() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":13,"error_message":"unknown field"}""")
        )
        val cb = LatchCallback()
        PushEngage.getSubscriberDetails(listOf("nope"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(13, cb.failureCode)
        assertEquals("unknown field", cb.failureMessage)
    }

    // ---- getSubscriberAttributes ----

    @Test
    fun getSubscriberAttributes_serverReturns200WithData_invokesOnSuccessWithBody() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"plan":"pro"}}""")
        )
        val cb = LatchCallback()
        PushEngage.getSubscriberAttributes(cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)
        assertNotNull(cb.successObject)
    }

    // ---- trackEvent ----

    @Test
    fun trackEvent_serverReturns200_invokesOnSuccess_andHitsEventsTrackPath() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":true}"""))

        val cb = LatchCallback()
        val event = TrackEvent("MySite.AddToCart", data = mapOf("product_id" to "p123"))
        PushEngage.trackEvent(event, cb)

        assertTrue(cb.await())
        assertTrue(cb.successInvoked)

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertTrue("Path should target events/track, got: ${req.path}", req.path!!.contains("events/track"))
        val body = req.body.readUtf8()
        assertTrue("Body should include the event_name field", body.contains("\"event_name\":\"MySite.AddToCart\""))
        assertTrue("Body should include the default provider", body.contains("\"provider\":\"PushEngage\""))
        assertTrue(
            "Body should include the default event_type",
            body.contains("\"event_type\":\"PushEngage.CustomEvent\"")
        )
    }

    @Test
    fun trackEvent_serverReturnsErrorJson_propagatesErrorCodeAndMessage() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":17,"error_message":"bad event"}""")
        )
        val cb = LatchCallback()
        PushEngage.trackEvent(TrackEvent("evt"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(17, cb.failureCode)
        assertEquals("bad event", cb.failureMessage)
    }

    @Test
    fun trackEvent_serverReturnsNonJsonError_fallsBackToServerErrorString() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal boom"))
        val cb = LatchCallback()
        PushEngage.trackEvent(TrackEvent("evt"), cb)

        assertTrue(cb.await())
        assertTrue(cb.failureInvoked)
        assertEquals(500, cb.failureCode)
    }

    @Test
    fun trackEvent_overridesProviderAndEventType_whenSupplied() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":true}"""))
        val cb = LatchCallback()
        val event = TrackEvent("evt", provider = "MyProvider", eventType = "MyType", profileId = "u_99")
        PushEngage.trackEvent(event, cb)

        assertTrue(cb.await())
        val body = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(body.contains("\"provider\":\"MyProvider\""))
        assertTrue(body.contains("\"event_type\":\"MyType\""))
        assertTrue(body.contains("\"profile_id\":\"u_99\""))
    }

    // ---- helpers ----

    private fun backdateCacheTimestamp(timestampMillis: Long) {
        // The cache timestamp is only written via mergeSubscriberFields / clearCache.
        // To simulate an expired-TTL scenario we reach into SharedPreferences directly
        // and overwrite just the timestamp key. This mirrors the only realistic source
        // of cache-without-fresh-timestamp in production: a cache written long ago that
        // has now aged past the TTL window.
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE)
            .edit()
            .putLong("subscriber_cache_timestamp", timestampMillis)
            .commit()
    }

    private fun setNetworkConnected(connected: Boolean) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(connectivityManager)
        val info = ShadowNetworkInfo.newInstance(
            null,
            ConnectivityManager.TYPE_WIFI,
            0,
            connected,
            if (connected) NetworkInfo.State.CONNECTED else NetworkInfo.State.DISCONNECTED
        )
        shadow.setActiveNetworkInfo(info)
        shadow.setNetworkInfo(ConnectivityManager.TYPE_WIFI, info)
        shadow.setNetworkInfo(ConnectivityManager.TYPE_MOBILE, info)
    }

    private class LatchCallback : PushEngageResponseCallback {
        private val latch = CountDownLatch(1)
        var successInvoked: Boolean = false
        var failureInvoked: Boolean = false
        var successObject: Any? = null
        var failureCode: Int? = null
        var failureMessage: String? = null

        override fun onSuccess(responseObject: Any?) {
            successInvoked = true
            successObject = responseObject
            latch.countDown()
        }

        override fun onFailure(errorCode: Int?, errorMessage: String?) {
            failureInvoked = true
            failureCode = errorCode
            failureMessage = errorMessage
            latch.countDown()
        }

        fun await(): Boolean = latch.await(5, TimeUnit.SECONDS)
    }
}
