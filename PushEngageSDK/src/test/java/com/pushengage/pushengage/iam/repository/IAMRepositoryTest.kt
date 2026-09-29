package com.pushengage.pushengage.iam.repository

import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.iam.IAMTestDb
import com.pushengage.pushengage.iam.model.IAMMessageResponse
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.model.IAMTriggerCondition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Guards the app-open "sync fresh, then show" ordering: the app-open auto-trigger
 * evaluation must run only after the freshly synced campaigns are committed, so
 * [IAMRepository.replaceAllMessages] with a completion must fire that completion
 * AFTER the write is visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMRepositoryTest {

    private lateinit var repository: IAMRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Clear any shared DB state so counts/ids are deterministic.
        IAMTestDb.clearIamTables(context)
        repository = IAMRepository.getInstance(context)
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        IAMTestDb.clearIamTables(context)
    }

    private fun campaign(id: String) = IAMMessageResponse(
        id, IAMPosition.CENTER, "<html></html>", 0L, false,
        emptyMap(), null, null, 1, null, null,
        IAMTriggerCondition("auto", null, null)
    )

    private fun campaignWithEvent(id: String, event: String) = IAMMessageResponse(
        id, IAMPosition.CENTER, "<html></html>", 0L, false,
        emptyMap(), null, null, 1, null, null,
        IAMTriggerCondition("custom", event, null)
    )

    /**
     * A campaign as the sync path actually receives one when the backend omits
     * `"id"`. [IAMMessageResponse.id] is a non-null Kotlin type, but Gson builds
     * the object by unsafe reflection and bypasses that check, so the field is
     * null at runtime with no exception raised. Built through Gson rather than the
     * constructor precisely so the test pins that premise instead of asserting it.
     */
    private fun campaignWithNoId(): IAMMessageResponse = Gson().fromJson(
        """{"position":"center","htmlContent":"<html></html>","displayDuration":0,
           "shouldDismissOnTap":false,"actions":{},"priority":1,
           "trigger":{"type":"auto"}}""",
        IAMMessageResponse::class.java
    )

    @Test
    fun gsonLeavesAMissingIdNullDespiteTheNonNullKotlinType() {
        assertNull("premise for the null-id guards below", campaignWithNoId().id)
    }

    @Test
    fun replaceAllMessages_dropsTheCampaignWithNoIdAndStillDeletesAbsentOnes() {
        // Unfiltered, the null id poisons `id NOT IN (:idsToKeep)` — SQL three-valued
        // logic makes it match no rows, so "stale" would survive forever — and it
        // fails the primary key on insert, losing the rest of the batch with it.
        val seedLatch = CountDownLatch(1)
        repository.replaceAllMessages(listOf(campaign("stale"), campaign("survivor"))) {
            seedLatch.countDown()
        }
        assertTrue(seedLatch.await(5, TimeUnit.SECONDS))

        val latch = CountDownLatch(1)
        repository.replaceAllMessages(
            listOf(campaign("survivor"), campaignWithNoId(), campaign("fresh"))
        ) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertNull("absent campaign still dropped", repository.getMessageById("stale"))
        assertNotNull("survivor kept", repository.getMessageById("survivor"))
        assertNotNull("the rest of the batch still inserted", repository.getMessageById("fresh"))
    }

    @Test
    fun replaceAllMessages_whenEveryCampaignIsUnusable_doesNotPurgeTheStore() {
        // A response that is empty only because everything in it was malformed is a
        // backend fault, not an instruction to delete the user's campaigns.
        seedCampaign("keeper")

        val latch = CountDownLatch(1)
        repository.replaceAllMessages(listOf(campaignWithNoId())) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertNotNull(
            "a malformed response must not be read as 'delete everything'",
            repository.getMessageById("keeper")
        )
    }

    @Test
    fun replaceAllMessages_withAnExplicitlyEmptyList_purgesTheStore() {
        // The other half of the contract: an empty set really does mean purge, which
        // is how an inactive iam_status is applied.
        seedCampaign("doomed")

        val latch = CountDownLatch(1)
        repository.replaceAllMessages(emptyList()) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertNull(repository.getMessageById("doomed"))
    }

    @Test
    fun replaceAllMessages_runsOnCompleteAfterTheWriteIsVisible() {
        val latch = CountDownLatch(1)
        // Whether the freshly written campaign is queryable at the moment the
        // completion fires — this is the ordering the app-open flow relies on.
        val visibleInCompletion = booleanArrayOf(false)

        repository.replaceAllMessages(listOf(campaign("fresh-1"))) {
            visibleInCompletion[0] = repository.getMessageById("fresh-1") != null
            latch.countDown()
        }

        assertTrue("completion should fire", latch.await(5, TimeUnit.SECONDS))
        assertTrue("fresh campaign must be committed before onComplete runs", visibleInCompletion[0])
        assertNotNull("campaign persisted", repository.getMessageById("fresh-1"))
    }

    @Test
    fun replaceAllMessages_fullReplaceDropsAbsentCampaignsThenCompletes() {
        // Seed a campaign that the next full-replace should drop.
        val seedLatch = CountDownLatch(1)
        repository.replaceAllMessages(listOf(campaign("stale"), campaign("survivor"))) { seedLatch.countDown() }
        seedLatch.await(5, TimeUnit.SECONDS)

        val latch = CountDownLatch(1)
        repository.replaceAllMessages(listOf(campaign("survivor"))) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertNull("absent campaign dropped by full-replace", repository.getMessageById("stale"))
        assertNotNull("surviving campaign kept", repository.getMessageById("survivor"))
    }

    // ------------------------------------------------ display record lifecycle

    private fun seedCampaign(id: String) {
        val latch = CountDownLatch(1)
        repository.replaceAllMessages(listOf(campaign(id))) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private fun analyticsRowCount(): Int = IAMTestDb.rowCount(
        ApplicationProvider.getApplicationContext(), "iam_analytics_events"
    )

    private fun displayRecordRowCount(): Int = IAMTestDb.rowCount(
        ApplicationProvider.getApplicationContext(), "iam_display_records"
    )

    /** Repository writes land on its own executor — poll until visible. */
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(message, condition())
    }

    @Test
    fun recordMessageDisplay_forAMessageWithNoCampaignRow_isRecordedNotRejected() {
        // There is no foreign key to iam_messages, so an impression is recorded even
        // when the campaign row is absent. That is the point: a campaign dropped from
        // one sync response, or purged by an inactive iam_status, must not be able to
        // erase or block its own cap history. Nothing here may throw into the display
        // pipeline either way.
        val recordId = repository.recordMessageDisplay("ghost-campaign")

        assertTrue("the impression is persisted", recordId > 0)
        assertEquals(1, repository.getDisplayCount("ghost-campaign"))
    }

    @Test
    fun markRecordReported_removesTheRecordFromTheUploadQueue() {
        seedCampaign("c1")
        val recordId = repository.recordMessageDisplay("c1")

        repository.markRecordReported(recordId)

        awaitCondition("synced record must leave the unsynced queue") {
            repository.unsyncedDisplayRecords.isEmpty()
        }
        // Frequency-capping still sees the display.
        assertEquals(1, repository.getDisplayCount("c1"))
    }

    @Test
    fun displayCounts_surviveACampaignContentUpdate() {
        seedCampaign("c1")
        repository.recordMessageDisplay("c1")
        repository.recordMessageDisplay("c1")

        // Server refresh with changed content for the same campaign id.
        val latch = CountDownLatch(1)
        repository.replaceAllMessages(listOf(campaign("c1"))) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(
            "frequency-cap history must survive a content refresh",
            2, repository.getDisplayCount("c1")
        )
        assertTrue(repository.getLastDisplayTimestamp("c1") > 0)
    }

    // ------------------------------------------------------- analytics events

    @Test
    fun recordAnalyticsEvent_storesTheButtonFieldsVerbatim() {
        seedCampaign("c1")
        val id = repository.recordAnalyticsEvent("CLICK", "c1", "btn-1", "Buy now", "open_url")
        assertTrue(id > 0)

        val events = repository.getUnsyncedAnalyticsEvents(10)
        assertEquals(1, events.size)
        assertEquals("CLICK", events[0].eventType)
        assertEquals("c1", events[0].messageId)
        // Exact values, not a substring of a blob — typed columns round-trip as-is.
        assertEquals("btn-1", events[0].btnId)
        assertEquals("Buy now", events[0].btnText)
        assertEquals("open_url", events[0].btnType)
    }

    @Test
    fun recordAnalyticsEvent_withNoButtonFields_doesNotCrash() {
        seedCampaign("c1")
        assertTrue(repository.recordAnalyticsEvent("CLICK", "c1", null, null, null) > 0)
    }

    @Test
    fun pruneSyncedAnalyticsEvents_dropsUploadedClicksButKeepsTheCapHistory() {
        seedCampaign("c1")
        repository.recordMessageDisplay("c1")
        val uploaded = repository.recordAnalyticsEvent("CLICK", "c1", "btn-1", "Buy", "open_url")
        val stillQueued = repository.recordAnalyticsEvent("CLICK", "c1", "btn-2", "Later", "dismiss")
        repository.markAnalyticsEventsAsSynced(listOf(uploaded))
        awaitCondition("the uploaded click must leave the queue") {
            repository.getUnsyncedAnalyticsEvents(10).size == 1
        }
        // Total rows, not the unsynced queue — the queue excludes synced rows
        // already, so it reports the same number whether the sweep ran or not.
        assertEquals(2, analyticsRowCount())

        repository.pruneSyncedAnalyticsEvents()

        awaitCondition("prune runs on the repository executor") { analyticsRowCount() == 1 }
        assertEquals(
            "the click still waiting to upload must survive",
            listOf(stillQueued),
            repository.getUnsyncedAnalyticsEvents(10).map { it.id }
        )
        // The display record is the frequency cap, not an analytics row — the
        // sweep must leave it be.
        assertEquals(1, displayRecordRowCount())
        assertEquals(1, repository.getDisplayCount("c1"))
    }

    @Test
    fun markAnalyticsEventsAsSynced_removesThemFromTheQueue() {
        seedCampaign("c1")
        val id = repository.recordAnalyticsEvent("CLICK", "c1", null, null, null)

        repository.markAnalyticsEventsAsSynced(listOf(id))

        awaitCondition("synced event must leave the unsynced queue") {
            repository.getUnsyncedAnalyticsEvents(10).isEmpty()
        }
    }

    // ------------------------------------------------------------- one instance

    @Test
    fun getInstance_isTheOnlyWayToGetARepositoryAndAlwaysReturnsTheSameOne() {
        // The write executor is per-instance, so two instances would mean two
        // independent write queues over one database — and runAfterPendingWrites,
        // which the analytics gate uses as its barrier, would cover only one of
        // them while reading as though it covered everything.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertSame(repository, IAMRepository.getInstance(context))
        assertSame(
            IAMRepository.getInstance(context),
            IAMRepository.getInstance(context.applicationContext)
        )

        // The constructor is private, so nothing outside can build a second one.
        val constructors = IAMRepository::class.java.declaredConstructors
        assertTrue(
            "IAMRepository must have no non-private constructor",
            constructors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) }
        )
    }

    // ----------------------------------------------- trigger-event lookup

    @Test
    fun getMessagesByTriggerEvent_escapesWildcardsSoOnlyTheNamedCampaignMatches() {
        // The public path: PushEngage.triggerIAMEvent hands the host app's event name
        // straight through to here, and it lands inside a SQL LIKE pattern. Nothing
        // downstream re-checks the event name, so an over-match displays the wrong
        // campaign rather than failing visibly.
        val latch = CountDownLatch(1)
        repository.replaceAllMessages(
            listOf(
                campaignWithEvent("snake", "cart_abandoned"),
                campaignWithEvent("dash", "cart-abandoned")
            )
        ) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(
            listOf("snake"),
            repository.getMessagesByTriggerEvent("cart_abandoned").map { it.id }
        )
        assertTrue(
            "an event name of '%' must not match every campaign",
            repository.getMessagesByTriggerEvent("%").isEmpty()
        )
        assertTrue(repository.getMessagesByTriggerEvent("_").isEmpty())
    }

    @Test
    fun escapeLikePattern_doublesTheEscapeCharacterBeforeUsingIt() {
        // Order matters: escaping % and _ first would leave their new backslashes to
        // be escaped in turn, putting the wildcards back.
        assertEquals("100\\%", IAMRepository.escapeLikePattern("100%"))
        assertEquals("a\\_b", IAMRepository.escapeLikePattern("a_b"))
        assertEquals("a\\\\b", IAMRepository.escapeLikePattern("a\\b"))
        assertEquals("a\\\\\\_b", IAMRepository.escapeLikePattern("a\\_b"))
        assertNull(IAMRepository.escapeLikePattern(null))
    }

    // ------------------------------------------------------------- misc safety

    @Test
    fun getDatabaseSummary_withFullContent_doesNotCrash() {
        seedCampaign("c1")
        repository.recordMessageDisplay("c1")
        repository.recordAnalyticsEvent("CLICK", "c1", "btn-1", "Buy now", "open_url")

        val summary = repository.databaseSummary
        assertTrue(summary.contains("c1"))
    }

    @Test
    fun queries_forUnknownIds_returnEmptyDefaultsNotErrors() {
        assertNull(repository.getMessageById("nope"))
        assertEquals(0, repository.getDisplayCount("nope"))
        assertEquals(0L, repository.getLastDisplayTimestamp("nope"))
        assertTrue(repository.getMessagesByTriggerEvent("nope").isEmpty())
    }

}
