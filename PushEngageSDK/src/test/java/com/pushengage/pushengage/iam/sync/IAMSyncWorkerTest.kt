package com.pushengage.pushengage.iam.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.pushengage.pushengage.iam.IAMTestDb
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.helper.PEPrefs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies IAM background sync is gated on the App ID (site_key), not on a push
 * subscription (siteId). In-app messaging must work without notification
 * permission, and siteId is populated only by the notification subscribe flow.
 *
 * The worker uses the real network impl, so the sync is driven against a
 * MockWebServer (pointed at via iamBaseUrl); the point is the gating.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var prefs: PEPrefs
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        PELogger.enableLogging(false)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        prefs = PEPrefs(context)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun doWork_withAppIdButNoSubscription_syncsSuccessfully() {
        prefs.siteKey = "3ca8257d-app-id"
        prefs.siteId = 0L // never subscribed / no notification permission
        prefs.iamBaseUrl = server.url("/").toString() // real impl hits MockWebServer
        val host = server.url("/").toString()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"error_code":0,"data":{"version":"v1","site_id":1,"iam_status":"active",
                   "api":{"backend_cdn":"$host","iam_analytics":"$host"}}}""".trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error_code":0,"data":[]}"""))

        val worker = IAMSyncWorker(context, mock(WorkerParameters::class.java))

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun doWork_withoutAppId_failsAndSkips() {
        prefs.siteKey = ""
        prefs.siteId = 999L // even with a siteId, no App ID means no sync

        val worker = IAMSyncWorker(context, mock(WorkerParameters::class.java))

        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test
    fun doWork_whileAnotherSyncPassHoldsTheGate_doesNotRepostItsRecords() {
        prefs.siteKey = "3ca8257d-app-id"
        prefs.iamVersion = "v1"
        prefs.iamBaseUrl = server.url("/").toString()
        // Metadata reports the stored version → UNCHANGED → the campaign sync
        // makes exactly ONE request. Any further request the worker makes can
        // only be an analytics POST.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"error_code":0,"data":{"version":"v1","site_id":1,"iam_status":"active"}}"""
            )
        )

        // Seed a campaign + one unsynced impression record.
        val repository = com.pushengage.pushengage.iam.repository.IAMRepository.getInstance(context)
        val seedLatch = java.util.concurrent.CountDownLatch(1)
        repository.replaceAllMessages(
            listOf(
                com.pushengage.pushengage.iam.model.IAMMessageResponse(
                    "c1", com.pushengage.pushengage.iam.model.IAMPosition.CENTER,
                    "<html></html>", 0L, false, emptyMap(), null, null, 1, null, null,
                    com.pushengage.pushengage.iam.model.IAMTriggerCondition("auto", null, null)
                )
            )
        ) { seedLatch.countDown() }
        seedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        repository.recordMessageDisplay("c1")

        // An in-process sync pass is mid-flight: it read the unsynced record
        // and is blocked inside its POST, holding the process-wide gate.
        val blockingService = BlockingNetworkService()
        val inFlightManager = com.pushengage.pushengage.iam.analytics.IAMAnalyticsManager(
            context, repository,
            IAMSyncManager(context, repository, blockingService),
            alwaysConnectedNetworkState(),
            mock(java.util.concurrent.ScheduledExecutorService::class.java)
        )
        inFlightManager.forceSyncAnalytics()
        assertTrue(
            "in-flight pass must have started its POST",
            blockingService.postStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)
        )

        try {
            val worker = IAMSyncWorker(context, mock(WorkerParameters::class.java))
            assertEquals(ListenableWorker.Result.success(), worker.doWork())

            assertEquals(
                "worker must not re-post records an in-flight pass is already reporting",
                1, server.requestCount
            )
        } finally {
            blockingService.release.countDown()
        }

        // Drain: the in-flight pass completes and marks the record synced.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && repository.unsyncedDisplayRecords.isNotEmpty()) {
            Thread.sleep(20)
        }
        assertTrue("in-flight pass must finish syncing", repository.unsyncedDisplayRecords.isEmpty())
        inFlightManager.shutdown()
        IAMTestDb.clearIamTables(context)
    }

    private fun alwaysConnectedNetworkState(): com.pushengage.pushengage.iam.network.IAMNetworkStateManager {
        val state = mock(com.pushengage.pushengage.iam.network.IAMNetworkStateManager::class.java)
        org.mockito.Mockito.`when`(state.isConnected).thenReturn(true)
        return state
    }

    /** Succeeds everything, but blocks inside reportAnalytics until released. */
    private class BlockingNetworkService : com.pushengage.pushengage.iam.network.IAMNetworkService {
        val postStarted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        override fun syncCampaigns(
            siteKey: String, storedVersion: String?,
            callback: com.pushengage.pushengage.iam.network.IAMNetworkCallback<com.pushengage.pushengage.iam.network.IAMCampaignSyncResult>
        ) = callback.onSuccess(com.pushengage.pushengage.iam.network.IAMCampaignSyncResult.unchanged())

        override fun reportAnalytics(
            records: List<com.pushengage.pushengage.iam.model.IAMDisplayRecord>,
            callback: com.pushengage.pushengage.iam.network.IAMNetworkCallback<com.pushengage.pushengage.iam.network.IAMReportOutcome>
        ) {
            postStarted.countDown()
            release.await()
            callback.onSuccess(
                com.pushengage.pushengage.iam.network.IAMReportOutcome(records.map { it.id }, allSynced = true)
            )
        }

        override fun markMessagesSeen(
            messageIds: List<String>,
            callback: com.pushengage.pushengage.iam.network.IAMNetworkCallback<Boolean>
        ) = callback.onSuccess(true)

        override fun reportAnalyticsEvents(
            events: List<com.pushengage.pushengage.iam.model.IAMAnalyticsEvent>,
            callback: com.pushengage.pushengage.iam.network.IAMNetworkCallback<com.pushengage.pushengage.iam.network.IAMReportOutcome>
        ) = callback.onSuccess(
            com.pushengage.pushengage.iam.network.IAMReportOutcome(events.map { it.id }, allSynced = true)
        )
    }
}
