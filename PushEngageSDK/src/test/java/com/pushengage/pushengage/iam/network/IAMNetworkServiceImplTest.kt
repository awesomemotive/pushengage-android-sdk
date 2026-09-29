package com.pushengage.pushengage.iam.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.iam.model.IAMActionType
import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent
import com.pushengage.pushengage.iam.model.IAMDisplayRecord
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.model.IAMPosition
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * MockWebServer coverage for [IAMNetworkServiceImpl] — the real backend path
 *: metadata status/version/cache gating, campaign fetch + parse
 * (exercising the IAMJson wire format end-to-end through Retrofit), and the
 * analytics POST payloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMNetworkServiceImplTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var prefs: PEPrefs
    private lateinit var service: IAMNetworkServiceImpl

    @Before
    fun setUp() {
        PELogger.enableLogging(false)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        prefs = PEPrefs(context).apply {
            siteKey = "site_abc"
            iamBaseUrl = server.url("/").toString() // points the IAM metadata base host at MockWebServer
        }
        service = IAMNetworkServiceImpl(context)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- syncCampaigns: ACTIVE ----

    @Test
    fun syncCampaigns_active_fetchesAndParsesCampaigns() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v1", "active")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(campaignsJson("v1")))

        val result = syncBlocking(storedVersion = "")

        assertEquals(IAMCampaignSyncResult.Status.ACTIVE, result.status)
        assertEquals("v1", result.version)
        assertEquals(1, result.campaigns.size)
        val c = result.campaigns[0]
        assertEquals("c1", c.id)
        assertEquals(IAMPosition.CENTER, c.position)          // "center" wire value parsed
        assertEquals(IAMActionType.OPEN_URL, c.actions["cta"]?.type) // "open_url" parsed
        assertEquals("See more", c.actions["cta"]?.label)
        assertNotNull("startDate ISO-8601 parsed", c.startDate)
        assertEquals(server.url("/").toString(), result.analyticsHost)

        // metadata then campaigns, both with site_key; campaigns carries version.
        val metaReq = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(metaReq.path!!.contains(PEConstants.IAM_PATH_METADATA))
        assertTrue(metaReq.path!!.contains("site_key=site_abc"))
        val campReq = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(campReq.path!!.contains(PEConstants.IAM_PATH_CAMPAIGNS))
        assertTrue(campReq.path!!.contains("version=v1"))
    }

    @Test
    fun syncCampaigns_missingApiBlock_fallsBackToBaseHost() {
        // No `api` block → campaigns/analytics hosts fall back to the IAM base
        // host (the MockWebServer, via iamBaseUrl).
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJsonNoApi("v2", "active")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(campaignsJson("v2")))

        val result = syncBlocking(storedVersion = "")

        assertEquals(IAMCampaignSyncResult.Status.ACTIVE, result.status)
        assertEquals(1, result.campaigns.size)
        assertEquals(server.url("/").toString(), result.campaignsHost)
        assertEquals(server.url("/").toString(), result.analyticsHost)
    }

    // ---- syncCampaigns: INACTIVE ----

    @Test
    fun syncCampaigns_inactiveStatus_returnsInactiveAndSkipsCampaignFetch() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v9", "paused")))

        val result = syncBlocking(storedVersion = "")

        assertEquals(IAMCampaignSyncResult.Status.INACTIVE, result.status)
        assertEquals("only metadata is fetched when inactive", 1, server.requestCount)
    }

    // ---- syncCampaigns: UNCHANGED (version) ----

    @Test
    fun syncCampaigns_versionUnchanged_returnsUnchangedAndSkipsCampaignFetch() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v5", "active")))

        val result = syncBlocking(storedVersion = "v5")

        assertEquals(IAMCampaignSyncResult.Status.UNCHANGED, result.status)
        assertEquals(1, server.requestCount)
    }

    // ---- syncCampaigns: metadata is always checked (no client-side freshness gate) ----

    @Test
    fun syncCampaigns_alwaysFetchesMetadata_evenWhenRecentlyFetched() {
        // A recent fetch must NOT suppress the metadata call: the version is only
        // observable by fetching metadata, so the SDK checks it on every sync. The
        // version gate still avoids the campaigns fetch when nothing changed.
        prefs.iamMetadataFetchedAt = System.currentTimeMillis() // "fresh" — must be ignored now
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v1", "active")))

        val result = syncBlocking(storedVersion = "v1")

        assertEquals(IAMCampaignSyncResult.Status.UNCHANGED, result.status)
        assertEquals("metadata is fetched regardless of last-fetch time", 1, server.requestCount)
    }

    // ---- syncCampaigns: error ----

    // A 200 whose body cannot be read as a campaign array must NOT be reported as
    // "the server sent zero campaigns": the caller purges the local store on an
    // empty ACTIVE set and records the new version, so coercing a malformed body to
    // emptyList() would delete every campaign for the site and then skip the
    // re-fetch on the next sync because the version now matches.

    @Test
    fun syncCampaigns_campaignsBodyWithNoDataField_reportsErrorInsteadOfAnEmptySet() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v1", "active")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error_code": 0}"""))

        assertNotNull(syncExpectingError(storedVersion = ""))
    }

    @Test
    fun syncCampaigns_campaignsBodyWithExplicitNullData_reportsError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v1", "active")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"error_code": 0, "data": null}""")
        )

        assertNotNull(syncExpectingError(storedVersion = ""))
    }

    @Test
    fun syncCampaigns_campaignsBodyWithANonArrayData_reportsError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v1", "active")))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"error_code": 0, "data": {"unexpected": "shape"}}""")
        )

        assertNotNull(syncExpectingError(storedVersion = ""))
    }

    @Test
    fun syncCampaigns_campaignsBodyWithAnExplicitlyEmptyArray_isStillASuccessfulPurge() {
        // The other side of the contract: an explicit `[]` really does mean "no
        // campaigns" and must keep working, or a dashboard that deactivates every
        // campaign would never take effect on device.
        server.enqueue(MockResponse().setResponseCode(200).setBody(metadataJson("v1", "active")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"error_code": 0, "data": []}""")
        )

        val result = syncBlocking(storedVersion = "")

        assertEquals(IAMCampaignSyncResult.Status.ACTIVE, result.status)
        assertTrue(result.campaigns.isEmpty())
    }

    @Test
    fun syncCampaigns_metadataHttpError_reportsError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        var error: Exception? = null
        service.syncCampaigns("site_abc", "", object : IAMNetworkCallback<IAMCampaignSyncResult> {
            override fun onSuccess(data: IAMCampaignSyncResult) {}
            override fun onError(e: Exception) { error = e }
        })

        assertNotNull(error)
    }

    // ---- analytics ----

    @Test
    fun reportAnalytics_postsOneImpressionAndNeverAClick() {
        // A display record only ever carries the impression — clicks travel as
        // queued CLICK events, so click must always be 0 here.
        prefs.iamAnalyticsUrl = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(200))

        val record = IAMDisplayRecord(
            id = 1L, messageId = "c1", timestamp = 100L, isSynced = false
        )

        var ok = false
        service.reportAnalytics(listOf(record), object : IAMNetworkCallback<IAMReportOutcome> {
            override fun onSuccess(data: IAMReportOutcome) { ok = data.allSynced }
            override fun onError(e: Exception) {}
        })

        assertTrue(ok)
        assertEquals("only the impression is posted; clicks go via events", 1, server.requestCount)
        val payload = Gson().fromJson(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8(), Map::class.java)
        assertEquals("c1", payload["campaign_id"])
        assertEquals(1.0, payload["impression"])
        assertEquals(0.0, payload["click"])
    }

    @Test
    fun reportAnalyticsEvents_noSiteKey_reportsError() {
        // Prerequisite failure: nothing was attempted, so the queued click must
        // NOT be reported as synced or it would be silently dropped.
        prefs.siteKey = ""
        prefs.iamAnalyticsUrl = server.url("/").toString()
        var error: Exception? = null
        service.reportAnalyticsEvents(
            listOf(IAMAnalyticsEvent(id = 1L, messageId = "c1", eventType = "CLICK")),
            object : IAMNetworkCallback<IAMReportOutcome> {
                override fun onSuccess(data: IAMReportOutcome) {}
                override fun onError(e: Exception) { error = e }
            })
        assertNotNull(error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun reportAnalytics_fallsBackToDexterHostWhenNoAnalyticsHostAdvertised() {
        // iamAnalyticsUrl not set — the dexter (metadata) host is used instead.
        // backendCdnUrl is the mock server, so the POST lands there.
        server.enqueue(MockResponse().setResponseCode(200))

        var ok = false
        service.reportAnalytics(
            listOf(IAMDisplayRecord(id = 1L, messageId = "c1")),
            object : IAMNetworkCallback<IAMReportOutcome> {
                override fun onSuccess(data: IAMReportOutcome) { ok = data.allSynced }
                override fun onError(e: Exception) {}
            }
        )

        assertTrue(ok)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reportAnalytics_noSiteKey_reportsError() {
        prefs.siteKey = ""
        prefs.iamAnalyticsUrl = server.url("/").toString()

        var error: Exception? = null
        service.reportAnalytics(
            listOf(IAMDisplayRecord(id = 1L, messageId = "c1")),
            object : IAMNetworkCallback<IAMReportOutcome> {
                override fun onSuccess(data: IAMReportOutcome) {}
                override fun onError(e: Exception) { error = e }
            }
        )
        assertEquals(0, server.requestCount)
        assertNotNull(error)
    }

    // ---- partial failure: only delivered items may be marked synced ----

    @Test
    fun reportAnalytics_partialFailure_reportsOnlyDeliveredRecords() {
        prefs.iamAnalyticsUrl = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(200)) // record 1 delivered
        server.enqueue(MockResponse().setResponseCode(500)) // record 2 fails

        val records = listOf(
            IAMDisplayRecord(id = 1L, messageId = "c1"),
            IAMDisplayRecord(id = 2L, messageId = "c2"),
            IAMDisplayRecord(id = 3L, messageId = "c3")
        )

        var outcome: IAMReportOutcome? = null
        service.reportAnalytics(records, object : IAMNetworkCallback<IAMReportOutcome> {
            override fun onSuccess(data: IAMReportOutcome) { outcome = data }
            override fun onError(e: Exception) {}
        })

        assertNotNull("partial delivery is a success outcome carrying the delivered ids, not an error", outcome)
        assertEquals(listOf(1L), outcome!!.syncedIds)
        assertFalse(outcome!!.allSynced)
        // A mid-batch failure is most likely network-wide: don't burn a timeout
        // per remaining record — they retry on the next pass.
        assertEquals("no further POST after the failure", 2, server.requestCount)
    }

    @Test
    fun reportAnalyticsEvents_partialClickFailure_reportsDeliveredClicksAndLocalOnlyEvents() {
        prefs.iamAnalyticsUrl = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(200)) // click 7 delivered
        server.enqueue(MockResponse().setResponseCode(500)) // click 8 fails

        val events = listOf(
            IAMAnalyticsEvent(id = 7L, messageId = "c1", eventType = "CLICK", btnId = "a"),
            IAMAnalyticsEvent(id = 8L, messageId = "c1", eventType = "CLICK", btnId = "b"),
            IAMAnalyticsEvent(id = 9L, messageId = "c1", eventType = "DISMISS")
        )

        var outcome: IAMReportOutcome? = null
        service.reportAnalyticsEvents(events, object : IAMNetworkCallback<IAMReportOutcome> {
            override fun onSuccess(data: IAMReportOutcome) { outcome = data }
            override fun onError(e: Exception) {}
        })

        assertNotNull(outcome)
        assertTrue("delivered click must be marked syncable", outcome!!.syncedIds.contains(7L))
        assertTrue("local-only event needs no delivery and is syncable", outcome!!.syncedIds.contains(9L))
        assertFalse("undelivered click must stay queued", outcome!!.syncedIds.contains(8L))
        assertFalse(outcome!!.allSynced)
    }

    // ---- reportAnalyticsEvents: queued (e.g. offline) clicks ----

    @Test
    fun reportAnalyticsEvents_postsOneClickPayloadPerQueuedClickEvent() {
        prefs.iamAnalyticsUrl = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(200))

        val queuedClick = IAMAnalyticsEvent(
            id = 7L, messageId = "c1", eventType = "CLICK", eventDate = 100L,
            btnId = "cta", btnText = "Buy now", btnType = "open_url",
            isSynced = false
        )
        val localOnly = IAMAnalyticsEvent(id = 8L, messageId = "c1", eventType = "DISMISS")

        var ok = false
        service.reportAnalyticsEvents(listOf(queuedClick, localOnly), object : IAMNetworkCallback<IAMReportOutcome> {
            override fun onSuccess(data: IAMReportOutcome) { ok = data.allSynced }
            override fun onError(e: Exception) {}
        })

        assertTrue(ok)
        assertEquals("exactly one POST — the click; DISMISS stays local-only", 1, server.requestCount)
        val click = Gson().fromJson(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8(), Map::class.java)
        assertEquals("c1", click["campaign_id"])
        assertEquals(0.0, click["impression"])
        assertEquals(1.0, click["click"])
        assertEquals("cta", click["btn_id"])
        assertEquals("Buy now", click["btn_text"])
        assertEquals("open_url", click["btn_type"])
    }

    @Test
    fun reportAnalyticsEvents_onlyLocalOnlyEvents_succeedsWithoutNetworkCall() {
        prefs.iamAnalyticsUrl = server.url("/").toString()

        var ok = false
        service.reportAnalyticsEvents(
            listOf(IAMAnalyticsEvent(id = 1L, messageId = "c1", eventType = "DISMISS")),
            object : IAMNetworkCallback<IAMReportOutcome> {
                override fun onSuccess(data: IAMReportOutcome) { ok = data.allSynced }
                override fun onError(e: Exception) {}
            }
        )

        assertTrue(ok)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun reportAnalyticsEvents_serverFailure_reportsClickAsUndeliveredSoItStaysQueued() {
        prefs.iamAnalyticsUrl = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(500))

        var outcome: IAMReportOutcome? = null
        service.reportAnalyticsEvents(
            listOf(
                IAMAnalyticsEvent(id = 7L, messageId = "c1", eventType = "CLICK", btnId = "cta")
            ),
            object : IAMNetworkCallback<IAMReportOutcome> {
                override fun onSuccess(data: IAMReportOutcome) { outcome = data }
                override fun onError(e: Exception) {}
            }
        )

        assertNotNull(outcome)
        assertFalse("a failed click must not be marked synced", outcome!!.syncedIds.contains(7L))
        assertFalse(outcome!!.allSynced)
    }

    @Test
    fun reportAnalyticsEvents_clickWithNoButtonFields_stillPostsBareClick() {
        // btn_* are nullable, so a click with none of them must still post a valid
        // click rather than being skipped. (Malformed metadata is no longer possible
        // — the fields are typed columns, not a JSON blob.)
        prefs.iamAnalyticsUrl = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(200))

        var ok = false
        service.reportAnalyticsEvents(
            listOf(IAMAnalyticsEvent(id = 7L, messageId = "c1", eventType = "CLICK")),
            object : IAMNetworkCallback<IAMReportOutcome> {
                override fun onSuccess(data: IAMReportOutcome) { ok = data.allSynced }
                override fun onError(e: Exception) {}
            }
        )

        assertTrue(ok)
        assertEquals(1, server.requestCount)
        val click = Gson().fromJson(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8(), Map::class.java)
    }

    // ---- helpers ----

    private fun syncBlocking(storedVersion: String): IAMCampaignSyncResult {
        var out: IAMCampaignSyncResult? = null
        service.syncCampaigns("site_abc", storedVersion, object : IAMNetworkCallback<IAMCampaignSyncResult> {
            override fun onSuccess(data: IAMCampaignSyncResult) { out = data }
            override fun onError(e: Exception) { throw AssertionError("unexpected error", e) }
        })
        return out ?: throw AssertionError("callback did not fire")
    }

    /** As [syncBlocking], but for the paths that must report an error. */
    private fun syncExpectingError(storedVersion: String): Exception {
        var out: Exception? = null
        service.syncCampaigns("site_abc", storedVersion, object : IAMNetworkCallback<IAMCampaignSyncResult> {
            override fun onSuccess(data: IAMCampaignSyncResult) {
                throw AssertionError("expected an error, got ${data.status} with ${data.campaigns.size} campaigns")
            }

            override fun onError(e: Exception) { out = e }
        })
        return out ?: throw AssertionError("callback did not fire")
    }

    // Responses use the real PushEngage envelope: { error_code, data: {...} }.
    private fun metadataJson(version: String, status: String): String {
        val host = server.url("/").toString()
        return """
            {
              "error_code": 0,
              "data": {
                "version": "$version",
                "site_id": 5276,
                "iam_status": "$status",
                "api": {
                  "backend": "$host",
                  "backend_cdn": "$host",
                  "iam_analytics": "$host",
                  "log": "$host"
                }
              }
            }
        """.trimIndent()
    }

    // Metadata with no `api` block, to exercise the host fallback.
    private fun metadataJsonNoApi(version: String, status: String): String = """
        {
          "error_code": 0,
          "data": { "version": "$version", "site_id": 5276, "iam_status": "$status" }
        }
    """.trimIndent()

    // The campaigns endpoint returns the campaign array directly in `data`.
    private fun campaignsJson(@Suppress("UNUSED_PARAMETER") version: String): String = """
        {
          "error_code": 0,
          "data": [
            {
              "id": "c1",
              "position": "center",
              "htmlContent": "<html><body>hi</body></html>",
              "displayDuration": 0,
              "shouldDismissOnTap": false,
              "priority": 1,
              "startDate": "2025-07-01T00:00:00Z",
              "endDate": null,
              "trigger": { "type": "custom", "event": "promo", "parameters": {} },
              "audience": [ { "field": "platform", "op": "in", "value": ["android"] } ],
              "frequency": { "type": "capped", "count": 3, "interval": 0 },
              "actions": {
                "cta": { "type": "open_url", "label": "See more", "parameters": { "url": "https://pushengage.com" } }
              }
            }
          ]
        }
    """.trimIndent()
}
