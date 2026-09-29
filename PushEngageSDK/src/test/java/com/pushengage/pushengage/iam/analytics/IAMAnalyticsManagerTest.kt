package com.pushengage.pushengage.iam.analytics

import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.iam.IAMTestDb
import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent
import com.pushengage.pushengage.iam.model.IAMDisplayRecord
import com.pushengage.pushengage.iam.model.IAMMessageResponse
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.model.IAMTriggerCondition
import com.pushengage.pushengage.iam.network.IAMCampaignSyncResult
import com.pushengage.pushengage.iam.network.IAMNetworkCallback
import com.pushengage.pushengage.iam.network.IAMNetworkService
import com.pushengage.pushengage.iam.network.IAMNetworkStateManager
import com.pushengage.pushengage.iam.network.IAMReportOutcome
import com.pushengage.pushengage.iam.repository.IAMRepository
import com.pushengage.pushengage.iam.sync.IAMSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Direct coverage for [IAMAnalyticsManager]: the impression record lifecycle,
 * local event recording, the network-state observer (register → sync on
 * reconnect → unregister on shutdown), and the periodic executor's task —
 * all with the real repository/database and a scripted network layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMAnalyticsManagerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repository: IAMRepository

    // Controllable connectivity: the manager consults isConnected on every path.
    private lateinit var networkState: IAMNetworkStateManager
    private var connected = true

    // Captured periodic scheduling.
    private lateinit var scheduler: ScheduledExecutorService

    private lateinit var networkService: ScriptedNetworkService

    @Before
    fun setUp() {
        IAMTestDb.clearIamTables(context)
        repository = IAMRepository.getInstance(context)
        seedCampaign("c1")

        connected = true
        networkState = mock()
        whenever(networkState.isConnected).thenAnswer { connected }
        scheduler = mock()
        networkService = ScriptedNetworkService()
    }

    private fun manager(): IAMAnalyticsManager = IAMAnalyticsManager(
        context, repository,
        IAMSyncManager(context, repository, networkService),
        networkState,
        scheduler
    )

    // ------------------------------------------------------------ test double

    private class ScriptedNetworkService : IAMNetworkService {
        @Volatile var analyticsSucceeds = true
        @Volatile var reportedRecordBatches = 0
        @Volatile var clickEventsSeen = 0

        override fun syncCampaigns(
            siteKey: String, storedVersion: String?,
            callback: IAMNetworkCallback<IAMCampaignSyncResult>
        ) = callback.onSuccess(IAMCampaignSyncResult.unchanged())

        override fun reportAnalytics(
            records: List<IAMDisplayRecord>, callback: IAMNetworkCallback<IAMReportOutcome>
        ) {
            reportedRecordBatches++
            if (analyticsSucceeds) {
                callback.onSuccess(IAMReportOutcome(records.map { it.id }, allSynced = true))
            } else {
                callback.onError(Exception("endpoint down"))
            }
        }

        override fun markMessagesSeen(
            messageIds: List<String>, callback: IAMNetworkCallback<Boolean>
        ) = callback.onSuccess(true)

        override fun reportAnalyticsEvents(
            events: List<IAMAnalyticsEvent>, callback: IAMNetworkCallback<IAMReportOutcome>
        ) {
            clickEventsSeen += events.count { it.eventType == "CLICK" }
            if (analyticsSucceeds) {
                callback.onSuccess(IAMReportOutcome(events.map { it.id }, allSynced = true))
            } else {
                callback.onError(Exception("endpoint down"))
            }
        }
    }

    private fun seedCampaign(id: String) {
        val latch = CountDownLatch(1)
        repository.replaceAllMessages(
            listOf(
                IAMMessageResponse(
                    id, IAMPosition.CENTER, "<html></html>", 0L, false,
                    emptyMap(), null, null, 1, null, null,
                    IAMTriggerCondition("auto", null, null)
                )
            )
        ) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(message, condition())
    }

    /** Asserts [condition] keeps holding for a short observation window. */
    private fun assertHolds(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < deadline) {
            assertTrue(message, condition())
            Thread.sleep(20)
        }
    }

    // ----------------------------------------------------- impression records

    @Test
    fun `recordImpression inserts a display record and returns its id`() {
        connected = false // keep the record local so it is observable
        val m = manager()

        val recordId = m.recordImpression("c1")

        assertTrue(recordId > 0)
        assertEquals(1, repository.getDisplayCount("c1"))
        assertEquals(1, repository.unsyncedDisplayRecords.size)
    }

    @Test
    fun `recordImpression for a campaign with no stored row is still recorded`() {
        // iam_display_records carries no foreign key to iam_messages, so an
        // impression for a campaign whose row has been purged (inactive iam_status,
        // or dropped from a full-replace) is persisted rather than rejected. Losing
        // it would under-count the frequency cap for the campaign most likely to be
        // re-synced under the same id.
        assertTrue(manager().recordImpression("ghost") > 0)
    }

    // ------------------------------------------------------------ click events

    @Test
    fun `recordClick while connected uploads the click through the events pipeline`() {
        connected = true
        val m = manager()

        m.recordClick("c1", "btn-1", "Buy now", "open_url")

        awaitCondition("click event must reach the network layer") {
            networkService.clickEventsSeen >= 1
        }
        awaitCondition("uploaded click must leave the unsynced queue") {
            repository.getUnsyncedAnalyticsEvents(50).none { it.eventType == "CLICK" }
        }
    }

    @Test
    fun `recordClick stores the button metadata needed to upload the click later`() {
        // An offline click can only be reconstructed into a click POST if the
        // stored event carries the full button payload (btn_id/btn_text/btn_type).
        connected = false
        val m = manager()

        m.recordClick("c1", "btn-1", "Buy now", "open_url")

        awaitCondition("click event must carry the button fields") {
            repository.getUnsyncedAnalyticsEvents(50).any {
                it.eventType == "CLICK" &&
                    it.btnId == "btn-1" &&
                    it.btnText == "Buy now" &&
                    it.btnType == "open_url"
            }
        }
    }

    @Test
    fun `click made while offline stays queued and is uploaded on reconnect`() {
        connected = false
        val m = manager()

        m.recordClick("c1", "btn-1", "Buy now", "open_url")

        awaitCondition("click must be recorded locally") {
            repository.getUnsyncedAnalyticsEvents(50).any { it.eventType == "CLICK" }
        }
        assertEquals("no network call while offline", 0, networkService.clickEventsSeen)

        // Network comes back: the click must reach the backend and be dequeued.
        connected = true
        m.networkStateDidChange(true)

        awaitCondition("offline click must be uploaded on reconnect") {
            networkService.clickEventsSeen >= 1
        }
        awaitCondition("uploaded click must leave the unsynced queue") {
            repository.getUnsyncedAnalyticsEvents(50).none { it.eventType == "CLICK" }
        }
    }

    // ------------------------------------------------- network-state observer

    @Test
    fun `manager registers itself as a network-state observer on creation`() {
        val m = manager()
        verify(networkState).addObserver(m)
    }

    @Test
    fun `connectivity restoration syncs the pending impression records`() {
        connected = false
        val m = manager()
        m.recordImpression("c1")
        assertHolds("record must stay queued while offline") {
            repository.unsyncedDisplayRecords.size == 1
        }

        // Network comes back: the observer callback must drain the queue.
        connected = true
        m.networkStateDidChange(true)

        awaitCondition("pending impression must sync on reconnect") {
            repository.unsyncedDisplayRecords.isEmpty()
        }
        assertTrue(networkService.reportedRecordBatches >= 1)
    }

    @Test
    fun `losing connectivity does not trigger a sync`() {
        connected = false
        val m = manager()
        m.recordImpression("c1")

        m.networkStateDidChange(false)

        assertHolds("no sync may run while offline") {
            repository.unsyncedDisplayRecords.size == 1 && networkService.reportedRecordBatches == 0
        }
    }

    @Test
    fun `failed upload keeps records queued for the next attempt`() {
        connected = true
        networkService.analyticsSucceeds = false
        val m = manager()
        m.recordImpression("c1")

        m.forceSyncAnalytics()

        assertHolds("failed upload must not mark records synced") {
            repository.unsyncedDisplayRecords.size == 1
        }

        // Endpoint recovers: the same record syncs.
        networkService.analyticsSucceeds = true
        m.forceSyncAnalytics()
        awaitCondition("record must sync once the endpoint recovers") {
            repository.unsyncedDisplayRecords.isEmpty()
        }
    }

    // ------------------------------------------------------ double-send window

    /**
     * Blocks the repository's single-thread write executor until the returned
     * latch opens. Mark-as-synced writes enqueue behind the block, holding open
     * the exact window in which a second sync pass could re-read (and re-POST)
     * records that were already reported but not yet marked.
     */
    private fun stallRepositoryWrites(): CountDownLatch {
        val stall = CountDownLatch(1)
        val field = IAMRepository::class.java.getDeclaredField("executor")
        field.isAccessible = true
        (field.get(repository) as java.util.concurrent.Executor).execute { stall.await() }
        return stall
    }

    @Test
    fun `a second sync pass inside the mark-as-synced window does not re-post the same records`() {
        connected = true
        val m = manager()
        m.recordImpression("c1")

        val stall = stallRepositoryWrites()
        try {
            m.forceSyncAnalytics()
            awaitCondition("first pass must post the batch") {
                networkService.reportedRecordBatches == 1
            }

            // The POST succeeded but the mark-as-synced write is still stuck
            // behind the stall — a second pass (timer tick, reconnect, click)
            // fires exactly in this window.
            m.forceSyncAnalytics()
            Thread.sleep(300) // give a buggy second pass time to re-post

            assertEquals(
                "records already reported must not be posted again before their marks commit",
                1, networkService.reportedRecordBatches
            )
        } finally {
            stall.countDown()
        }

        awaitCondition("record must end up marked synced") {
            repository.unsyncedDisplayRecords.isEmpty()
        }
    }

    // ------------------------------------------------------- periodic executor

    @Test
    fun `periodic sync is scheduled at the 30-minute cadence and its task drains the queue`() {
        connected = false
        val m = manager()

        // Pin the schedule itself.
        val taskCaptor = argumentCaptor<Runnable>()
        verify(scheduler).scheduleWithFixedDelay(
            taskCaptor.capture(), eq(30L), eq(30L), eq(TimeUnit.MINUTES)
        )

        // Pin what the tick actually does: sync pending analytics.
        m.recordImpression("c1")
        connected = true
        taskCaptor.firstValue.run()

        awaitCondition("the periodic tick must upload pending impressions") {
            repository.unsyncedDisplayRecords.isEmpty()
        }
    }

    // ---------------------------------------------------------------- shutdown

    @Test
    fun `shutdown stops the periodic executor and unregisters the observer`() {
        val m = manager()

        m.shutdown()

        verify(scheduler).shutdown()
        verify(networkState).removeObserver(m)
    }

    @Test
    fun `shutdown is idempotent`() {
        val m = manager()
        m.shutdown()
        m.shutdown()
    }
}
