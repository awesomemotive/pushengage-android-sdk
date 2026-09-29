package com.pushengage.pushengage.iam.sync

import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.iam.IAMTestDb
import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent
import com.pushengage.pushengage.iam.model.IAMDisplayRecord
import com.pushengage.pushengage.iam.model.IAMMessageResponse
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.model.IAMTriggerCondition
import com.pushengage.pushengage.iam.network.IAMCampaignSyncResult
import com.pushengage.pushengage.iam.network.IAMNetworkCallback
import com.pushengage.pushengage.iam.network.IAMNetworkService
import com.pushengage.pushengage.iam.network.IAMReportOutcome
import com.pushengage.pushengage.iam.repository.IAMRepository
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the sync → local-Room pipeline end to end with a scripted network
 * service (real repository, real database): every server outcome must leave
 * the local store in the correct state, and no outcome may corrupt or lose
 * display/analytics history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMSyncManagerTest {

    private lateinit var repository: IAMRepository
    private lateinit var prefs: PEPrefs
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        IAMTestDb.clearIamTables(context)
        repository = IAMRepository.getInstance(context)
        prefs = PEPrefs(context)
        prefs.siteKey = "site-key-1"
    }

    // ------------------------------------------------------------ test doubles

    /** Scripted network service: replies with a fixed result, records calls. */
    private class ScriptedNetworkService(
        private val syncResult: IAMCampaignSyncResult? = null,
        private val syncError: Exception? = null,
        private val analyticsSucceeds: Boolean = true,
        /** When set, report calls succeed PARTIALLY, delivering only these ids. */
        private val partialDeliveredIds: List<Long>? = null
    ) : IAMNetworkService {
        var reportedRecords: List<IAMDisplayRecord>? = null
        var reportedEvents: List<IAMAnalyticsEvent>? = null

        override fun syncCampaigns(
            siteKey: String,
            storedVersion: String?,
            callback: IAMNetworkCallback<IAMCampaignSyncResult>
        ) {
            syncError?.let { callback.onError(it); return }
            callback.onSuccess(syncResult!!)
        }

        override fun reportAnalytics(
            records: List<IAMDisplayRecord>,
            callback: IAMNetworkCallback<IAMReportOutcome>
        ) {
            reportedRecords = records
            if (partialDeliveredIds != null) {
                callback.onSuccess(IAMReportOutcome(partialDeliveredIds, allSynced = false))
            } else if (analyticsSucceeds) {
                callback.onSuccess(IAMReportOutcome(records.map { it.id }, allSynced = true))
            } else {
                callback.onError(Exception("analytics endpoint down"))
            }
        }

        override fun markMessagesSeen(
            messageIds: List<String>,
            callback: IAMNetworkCallback<Boolean>
        ) {
            callback.onSuccess(true)
        }

        override fun reportAnalyticsEvents(
            events: List<IAMAnalyticsEvent>,
            callback: IAMNetworkCallback<IAMReportOutcome>
        ) {
            reportedEvents = events
            if (partialDeliveredIds != null) {
                callback.onSuccess(IAMReportOutcome(partialDeliveredIds, allSynced = false))
            } else if (analyticsSucceeds) {
                callback.onSuccess(IAMReportOutcome(events.map { it.id }, allSynced = true))
            } else {
                callback.onError(Exception("analytics endpoint down"))
            }
        }
    }

    private fun campaign(id: String, priority: Int = 1) = IAMMessageResponse(
        id, IAMPosition.CENTER, "<html><body>$id</body></html>", 0L, false,
        emptyMap(), null, null, priority, null, null,
        IAMTriggerCondition("auto", null, null)
    )

    /**
     * A campaign the SDK cannot key on, built the way one actually arrives: Gson
     * fills a missing `"id"` with null despite the non-null Kotlin type, because it
     * constructs the object by unsafe reflection. Used here to make a persist fail
     * without reaching for a mock repository.
     */
    private fun campaignWithNoId(): IAMMessageResponse = Gson().fromJson(
        """{"position":"center","htmlContent":"<html></html>","displayDuration":0,
           "shouldDismissOnTap":false,"actions":{},"priority":1,
           "trigger":{"type":"auto"}}""",
        IAMMessageResponse::class.java
    )

    private fun syncBlocking(manager: IAMSyncManager): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        manager.syncMessages("site-key-1", object : IAMSyncManager.SyncCallback {
            override fun onComplete(success: Boolean) {
                result = success
                latch.countDown()
            }
        })
        assertTrue("sync callback should fire", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    /** Polls until [condition] holds — repository writes land on its own executor. */
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(message, condition())
    }

    // ------------------------------------------------------------------- sync

    @Test
    fun `ACTIVE sync commits the fresh campaign set and persists version and analytics host`() {
        val service = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE,
                version = "v42",
                campaigns = listOf(campaign("c1"), campaign("c2")),
                analyticsHost = "https://analytics.example.com"
            )
        )
        val manager = IAMSyncManager(context, repository, service)

        assertTrue(syncBlocking(manager))

        assertNotNull(repository.getMessageById("c1"))
        assertNotNull(repository.getMessageById("c2"))
        assertEquals("v42", prefs.iamVersion)
        assertEquals("https://analytics.example.com", prefs.iamAnalyticsUrl)
    }

    @Test
    fun `ACTIVE sync drops campaigns the server no longer sends but keeps survivors' display history`() {
        // Seed: two campaigns, one with a display record (frequency-cap history).
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1",
                listOf(campaign("keep"), campaign("drop"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))
        val recordId = repository.recordMessageDisplay("keep")
        assertTrue("display record should insert", recordId > 0)

        // Refresh: server now sends only "keep" (with changed HTML).
        val refresh = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v2",
                listOf(campaign("keep", priority = 9))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, refresh)))

        assertNull("dropped campaign must be deleted", repository.getMessageById("drop"))
        val kept = repository.getMessageById("keep")
        assertNotNull(kept)
        assertEquals("survivor content must update in place", 9, kept!!.priority)
        assertEquals(
            "survivor's display history (frequency capping) must be preserved",
            1, repository.getDisplayCount("keep")
        )
    }

    @Test
    fun `INACTIVE sync records the status and keeps the local campaigns`() {
        // iam_status is a per-site feature gate the backend also flips for billing
        // and plan reasons, so it is expected to go off and come back on. Deleting
        // campaigns to express "off" throws away data that "on" cannot restore; the
        // display path refuses on the recorded status instead.
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))
        assertNotNull(repository.getMessageById("c1"))

        val inactive = ScriptedNetworkService(syncResult = IAMCampaignSyncResult.inactive())
        assertTrue(syncBlocking(IAMSyncManager(context, repository, inactive)))

        assertNotNull(
            "a site-level feature gate must not delete campaign data",
            repository.getMessageById("c1")
        )
        assertEquals("inactive", prefs.iamStatus)
    }

    @Test
    fun `a site switched off and back on keeps working without a dashboard change`() {
        // The recovery path that purging had no answer for. Going inactive left the
        // version pointer untouched, so when the site came back the metadata version
        // still matched, the sync returned UNCHANGED, and nothing was re-fetched —
        // leaving an emptied store and IAM permanently dead until someone happened to
        // edit a campaign. Keeping the campaigns makes UNCHANGED correct again.
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("once"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))
        assertTrue(repository.recordMessageDisplay("once") > 0)

        val inactive = ScriptedNetworkService(syncResult = IAMCampaignSyncResult.inactive())
        assertTrue(syncBlocking(IAMSyncManager(context, repository, inactive)))

        // Site comes back on. Nothing changed on the dashboard, so the version still
        // matches and the sync legitimately has nothing to fetch.
        val backOn = ScriptedNetworkService(syncResult = IAMCampaignSyncResult.unchanged())
        assertTrue(syncBlocking(IAMSyncManager(context, repository, backOn)))

        assertNotNull(
            "the campaign must still be there for UNCHANGED to be true",
            repository.getMessageById("once")
        )
        assertEquals(
            "and its frequency-cap history must be intact",
            1, repository.getDisplayCount("once")
        )
        assertEquals("v1", prefs.iamVersion)
        // The gate has to reopen too, or the campaigns sit there undisplayable —
        // the same permanent failure as before, just moved from the store to a flag.
        assertEquals(PEConstants.ACTIVE, prefs.iamStatus)
    }

    @Test
    fun `UNCHANGED sync leaves the local store untouched`() {
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))

        val unchanged = ScriptedNetworkService(syncResult = IAMCampaignSyncResult.unchanged())
        assertTrue(syncBlocking(IAMSyncManager(context, repository, unchanged)))

        assertNotNull("UNCHANGED must not delete local campaigns", repository.getMessageById("c1"))
    }

    @Test
    fun `network error reports failure and leaves the local store untouched`() {
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))

        val failing = ScriptedNetworkService(syncError = Exception("airplane mode"))
        assertFalse(
            "sync must report failure, not crash",
            syncBlocking(IAMSyncManager(context, repository, failing))
        )

        assertNotNull("a failed sync must not lose local campaigns", repository.getMessageById("c1"))
        // The version pointer is what makes a bad sync permanent: bump it and the
        // next sync sees a match, returns UNCHANGED, and never retries.
        assertEquals("a failed sync must leave the version pointer alone", "v1", prefs.iamVersion)
    }

    @Test
    fun `a persist that did not happen reports failure and leaves the version pointer alone`() {
        // The version pointer means "we already hold these campaigns", so it must
        // follow the commit, not precede it: bumped on a write that never landed,
        // the next sync sees a match, returns UNCHANGED, and the retry never
        // happens. Failure is injected the way it actually reaches this path — an
        // ACTIVE response whose campaigns are all unusable, so nothing is written.
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))
        assertEquals("v1", prefs.iamVersion)

        val unusable = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v2", listOf(campaignWithNoId())
            )
        )
        assertFalse(
            "a sync whose campaigns were never persisted must report failure",
            syncBlocking(IAMSyncManager(context, repository, unusable))
        )

        assertEquals("version must still point at the last committed set", "v1", prefs.iamVersion)
        assertNotNull("the committed campaigns must survive", repository.getMessageById("c1"))
    }

    @Test
    fun `ACTIVE sync with an empty campaign list purges the store`() {
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))

        val empty = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v2", emptyList()
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, empty)))

        assertNull(repository.getMessageById("c1"))
    }

    // -------------------------------------------------------------- analytics

    @Test
    fun `successful impression report marks the display records synced`() {
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        val manager = IAMSyncManager(context, repository, seed)
        assertTrue(syncBlocking(manager))
        repository.recordMessageDisplay("c1")
        val unsynced = repository.unsyncedDisplayRecords
        assertEquals(1, unsynced.size)

        manager.reportAnalytics(unsynced)

        awaitCondition("record must be marked synced after successful upload") {
            repository.unsyncedDisplayRecords.isEmpty()
        }
    }

    @Test
    fun `failed impression report keeps the display records unsynced for retry`() {
        val service = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            ),
            analyticsSucceeds = false
        )
        val manager = IAMSyncManager(context, repository, service)
        assertTrue(syncBlocking(manager))
        repository.recordMessageDisplay("c1")

        manager.reportAnalytics(repository.unsyncedDisplayRecords)

        // Failure path is synchronous in the scripted service; the record must
        // survive for the next sync cycle.
        assertEquals(
            "failed upload must leave the record queued for retry",
            1, repository.unsyncedDisplayRecords.size
        )
    }

    @Test
    fun `partially delivered impression batch marks only the delivered records synced`() {
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))
        val delivered = repository.recordMessageDisplay("c1")
        val undelivered = repository.recordMessageDisplay("c1")

        val service = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult.unchanged(),
            partialDeliveredIds = listOf(delivered)
        )
        IAMSyncManager(context, repository, service).reportAnalytics(repository.unsyncedDisplayRecords)

        awaitCondition("delivered record must leave the queue") {
            repository.unsyncedDisplayRecords.none { it.id == delivered }
        }
        assertTrue(
            "undelivered record must stay queued for the next pass",
            repository.unsyncedDisplayRecords.any { it.id == undelivered }
        )
    }

    @Test
    fun `partially delivered event batch marks only the delivered events synced`() {
        val seed = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult(
                IAMCampaignSyncResult.Status.ACTIVE, "v1", listOf(campaign("c1"))
            )
        )
        assertTrue(syncBlocking(IAMSyncManager(context, repository, seed)))
        val delivered = repository.recordAnalyticsEvent("CLICK", "c1", "cta", null, "open_url")
        val undelivered = repository.recordAnalyticsEvent("CLICK", "c1", "cta", null, "open_url")

        val service = ScriptedNetworkService(
            syncResult = IAMCampaignSyncResult.unchanged(),
            partialDeliveredIds = listOf(delivered)
        )
        IAMSyncManager(context, repository, service)
            .reportAnalyticsEvents(repository.getUnsyncedAnalyticsEvents(50))

        awaitCondition("delivered event must leave the queue") {
            repository.getUnsyncedAnalyticsEvents(50).none { it.id == delivered }
        }
        assertTrue(
            "undelivered event must stay queued for the next pass",
            repository.getUnsyncedAnalyticsEvents(50).any { it.id == undelivered }
        )
    }

    @Test
    fun `reportAnalytics with an empty list does not call the network`() {
        val service = ScriptedNetworkService(syncResult = IAMCampaignSyncResult.unchanged())
        IAMSyncManager(context, repository, service).reportAnalytics(emptyList())
        assertNull(service.reportedRecords)
    }
}
