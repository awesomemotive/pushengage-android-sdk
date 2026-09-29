package com.pushengage.pushengage.iam.repository

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent
import com.pushengage.pushengage.iam.model.IAMDisplayRecord
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.model.IAMPosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Exercises [IAMDao] against an in-memory Room database (same pattern as
 * DaoInterfaceTest). Covers message CRUD with type-converted columns, trigger
 * lookup semantics, display records, and analytics events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMDaoTest {

    private lateinit var db: PERoomDatabase
    private lateinit var dao: IAMDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PERoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.iamDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- message insert / read ----

    @Test
    fun insertMessages_getMessageById_roundTripsAllColumns() {
        val start = Date(1751328000000L) // 2025-07-01T00:00:00Z
        val end = Date(1754006400000L)   // 2025-08-01T00:00:00Z
        val original = message(
            id = "msg_full",
            priority = 4,
            position = IAMPosition.BOTTOM,
            htmlContent = "<html><body>full</body></html>",
            displayDuration = 5000L,
            shouldDismissOnTap = true,
            actionsJson = """{"cta":{"type":"open_url","label":"Go","parameters":{"url":"https://x.io"}}}""",
            startDate = start,
            endDate = end,
            audienceJson = """[{"field":"platform","op":"in","value":["android"]}]""",
            frequencyJson = """{"type":"capped","count":3,"interval":86400}""",
            triggerJson = """{"type":"custom","event":"checkout"}"""
        )

        dao.insertMessages(listOf(original))
        val fetched = dao.getMessageById("msg_full")

        assertNotNull(fetched)
        assertEquals("msg_full", fetched.id)
        assertEquals(IAMPosition.BOTTOM, fetched.position)
        assertEquals("<html><body>full</body></html>", fetched.htmlContent)
        assertEquals(5000L, fetched.displayDuration)
        assertEquals(true, fetched.shouldDismissOnTap)
        assertEquals(original.actionsJson, fetched.actionsJson)
        assertEquals("start date survives the Date<->Long converter", start, fetched.startDate)
        assertEquals("end date survives the Date<->Long converter", end, fetched.endDate)
        assertEquals(4, fetched.priority)
        assertEquals(original.audienceJson, fetched.audienceJson)
        assertEquals(original.frequencyJson, fetched.frequencyJson)
        assertEquals(original.triggerJson, fetched.triggerJson)
    }

    @Test
    fun insertMessages_nullableColumns_roundTripAsNull() {
        dao.insertMessages(
            listOf(
                message(
                    id = "msg_nulls",
                    priority = 1,
                    startDate = null,
                    endDate = null,
                    audienceJson = null,
                    frequencyJson = null
                )
            )
        )

        val fetched = dao.getMessageById("msg_nulls")
        assertNotNull(fetched)
        assertNull(fetched.startDate)
        assertNull(fetched.endDate)
        assertNull(fetched.audienceJson)
        assertNull(fetched.frequencyJson)
    }

    @Test
    fun getMessageById_missingId_returnsNull() {
        assertNull(dao.getMessageById("does_not_exist"))
    }

    @Test
    fun insertMessages_sameId_isIgnored_keepingExistingRow() {
        // IGNORE conflict strategy: a re-insert of an existing id is a no-op, so
        // the original row is preserved. Content changes to an existing message
        // go through updateMessage() instead.
        dao.insertMessages(listOf(message("msg_1", priority = 1, htmlContent = "first")))
        dao.insertMessages(listOf(message("msg_1", priority = 2, htmlContent = "second")))

        val all = dao.getAllActiveMessages()
        assertEquals(1, all.size)
        assertEquals("first", all[0].htmlContent)
        assertEquals(1, all[0].priority)
    }

    @Test
    fun insertMessages_sameId_preservesDisplayHistory() {
        // Regression guard: re-inserting a campaign that is already stored must
        // NOT wipe its display records (which drive frequency capping). Under the
        // old REPLACE strategy the delete reset this history to zero.
        dao.insertMessages(listOf(message("msg_1", priority = 1)))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 100L))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 200L))
        assertEquals(2, dao.getDisplayCountForMessage("msg_1"))

        dao.insertMessages(listOf(message("msg_1", priority = 1)))

        assertEquals(2, dao.getDisplayCountForMessage("msg_1"))
    }

    // ---- full-replace support ----

    @Test
    fun deleteMessagesNotIn_removesOnlyCampaignsAbsentFromTheKeepSet() {
        dao.insertMessages(
            listOf(
                message("keep_1", priority = 1),
                message("keep_2", priority = 2),
                message("drop_1", priority = 3)
            )
        )

        dao.deleteMessagesNotIn(listOf("keep_1", "keep_2"))

        assertEquals(setOf("keep_1", "keep_2"), dao.getAllMessageIds().toSet())
    }

    @Test
    fun deleteMessagesNotIn_keepsDisplayHistoryOfRemovedCampaignsToo() {
        // Display history outlives the campaign row on purpose. A campaign can be
        // dropped from one sync response, or the whole table purged when iam_status
        // goes inactive, and come back with the same id on the next sync — if the
        // history went with the row, the cap would reset and a one-time campaign
        // would show again to someone who already dismissed it. Frequency capping,
        // not referential tidiness, decides this table's lifetime.
        dao.insertMessages(listOf(message("keep_1", priority = 1), message("drop_1", priority = 2)))
        dao.insertDisplayRecord(displayRecord("keep_1", timestamp = 100L))
        dao.insertDisplayRecord(displayRecord("drop_1", timestamp = 100L))
        dao.insertAnalyticsEvent(analyticsEvent("drop_1", "CLICK"))

        dao.deleteMessagesNotIn(listOf("keep_1"))

        assertEquals("survivor keeps its history", 1, dao.getDisplayCountForMessage("keep_1"))
        assertEquals(
            "a removed campaign's cap history must survive so a re-sync cannot reset it",
            1, dao.getDisplayCountForMessage("drop_1")
        )
        assertEquals(
            "an unsynced click must not be destroyed by the campaign being dropped",
            1, dao.getUnsyncedAnalyticsEvents(10).size
        )
    }

    @Test
    fun deleteAllMessages_keepsDisplayAndAnalyticsHistory() {
        // The iam_status-inactive purge path. Same reasoning, wider blast radius:
        // this is the one that used to reset every cap on the device at once.
        dao.insertMessages(listOf(message("m1", priority = 1)))
        dao.insertDisplayRecord(displayRecord("m1", timestamp = 100L))
        dao.insertAnalyticsEvent(analyticsEvent("m1", "CLICK"))

        dao.deleteAllMessages()

        assertEquals(1, dao.getDisplayCountForMessage("m1"))
        assertEquals(1, dao.getUnsyncedAnalyticsEvents(10).size)
    }

    @Test
    fun deleteAllMessages_purgesEverything() {
        dao.insertMessages(listOf(message("m1", priority = 1), message("m2", priority = 2)))

        dao.deleteAllMessages()

        assertTrue(dao.getAllMessageIds().isEmpty())
    }

    @Test
    fun replaceMessages_dropsAbsentCampaignsUpdatesSurvivorsAndInsertsNewOnes() {
        dao.insertMessages(
            listOf(
                message("survivor", priority = 1, htmlContent = "old"),
                message("dropped", priority = 2)
            )
        )

        dao.replaceMessages(
            listOf("survivor", "fresh"),
            listOf(
                message("survivor", priority = 9, htmlContent = "refreshed"),
                message("fresh", priority = 3)
            )
        )

        assertEquals(setOf("survivor", "fresh"), dao.getAllMessageIds().toSet())
        val survivor = dao.getMessageById("survivor")
        assertNotNull(survivor)
        assertEquals("refreshed", survivor.htmlContent)
        assertEquals(9, survivor.priority)
    }

    @Test
    fun replaceMessages_withEmptyKeepSet_purgesEverything() {
        // How an inactive iam_status is applied: an explicitly empty set means purge.
        dao.insertMessages(listOf(message("m1", priority = 1), message("m2", priority = 2)))

        dao.replaceMessages(emptyList(), emptyList())

        assertTrue(dao.getAllMessageIds().isEmpty())
    }

    @Test
    fun replaceMessages_preservesDisplayHistoryOfSurvivors() {
        insertParent("survivor")
        dao.insertDisplayRecord(displayRecord("survivor", timestamp = 100L))
        dao.insertDisplayRecord(displayRecord("survivor", timestamp = 200L))

        dao.replaceMessages(
            listOf("survivor"),
            listOf(message("survivor", priority = 1, htmlContent = "refreshed"))
        )

        assertEquals(
            "frequency-cap history must survive a full-replace",
            2, dao.getDisplayCountForMessage("survivor")
        )
    }

    @Test
    fun replaceMessages_rollsBackTheDeleteWhenTheUpsertFails() {
        // The whole reason replaceMessages is one @Transaction: a failed upsert must
        // not leave the store emptied by the delete that preceded it. A trigger that
        // ABORTs on a specific id is the cheapest way to fail mid-transaction.
        insertParent("existing")
        dao.insertDisplayRecord(displayRecord("existing", timestamp = 100L))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_on_boom BEFORE INSERT ON iam_messages " +
                "WHEN NEW.id = 'boom' BEGIN SELECT RAISE(ABORT, 'boom'); END;"
        )

        assertThrows(SQLiteException::class.java) {
            dao.replaceMessages(listOf("boom"), listOf(message("boom", priority = 1)))
        }

        assertEquals(
            "the delete must roll back with the failed upsert",
            setOf("existing"), dao.getAllMessageIds().toSet()
        )
        assertEquals(1, dao.getDisplayCountForMessage("existing"))
    }

    @Test
    fun updateMessage_sameId_updatesRowWithoutDuplicating() {
        dao.insertMessages(listOf(message("msg_1", priority = 1, htmlContent = "original")))

        dao.updateMessage(message("msg_1", priority = 7, htmlContent = "updated"))

        val all = dao.getAllActiveMessages()
        assertEquals(1, all.size)
        assertEquals("updated", all[0].htmlContent)
        assertEquals(7, all[0].priority)
    }

    @Test
    fun getAllActiveMessages_orderedByPriorityAscending() {
        dao.insertMessages(
            listOf(
                message("msg_low", priority = 9),
                message("msg_high", priority = 1),
                message("msg_mid", priority = 5)
            )
        )

        val all = dao.getAllActiveMessages()
        assertEquals(listOf("msg_high", "msg_mid", "msg_low"), all.map { it.id })
    }

    // ---- trigger event lookup ----

    @Test
    fun getMessagesByTriggerEvent_matchesEventAndOrdersByPriority() {
        dao.insertMessages(
            listOf(
                message("msg_b", priority = 5, triggerJson = trigger("checkout")),
                message("msg_a", priority = 1, triggerJson = trigger("checkout")),
                message("msg_other", priority = 2, triggerJson = trigger("app_open"))
            )
        )

        val matches = dao.getMessagesByTriggerEvent("checkout")
        assertEquals(listOf("msg_a", "msg_b"), matches.map { it.id })
    }

    @Test
    fun getMessagesByTriggerEvent_noMatch_returnsEmptyList() {
        dao.insertMessages(listOf(message("msg_1", priority = 1, triggerJson = trigger("app_open"))))

        assertTrue(dao.getMessagesByTriggerEvent("checkout").isEmpty())
    }

    @Test
    fun getMessagesByTriggerEvent_matchesEventExactly_notRelatedSubstrings() {
        // The query anchors on the "event" JSON key, so asking for "sale" returns
        // only the "sale" campaign and not the one triggered by "mega_sale".
        dao.insertMessages(
            listOf(
                message("msg_sale", priority = 1, triggerJson = trigger("sale")),
                message("msg_mega", priority = 2, triggerJson = trigger("mega_sale"))
            )
        )

        val matches = dao.getMessagesByTriggerEvent("sale")
        assertEquals(listOf("msg_sale"), matches.map { it.id })
    }

    @Test
    fun getMessagesByTriggerEvent_treatsUnderscoreInTheEventNameAsALiteral() {
        // '_' is a single-character wildcard in SQL LIKE, and the event name is
        // concatenated into the pattern. Unescaped, "cart_abandoned" also matched
        // "cart-abandoned" — and snake_case is this codebase's own convention for
        // event names, so the collision is the normal case rather than a corner one.
        dao.insertMessages(
            listOf(
                message("msg_snake", priority = 1, triggerJson = trigger("cart_abandoned")),
                message("msg_dash", priority = 2, triggerJson = trigger("cart-abandoned"))
            )
        )

        val matches = dao.getMessagesByTriggerEvent(
            IAMRepository.escapeLikePattern("cart_abandoned")
        )

        assertEquals(listOf("msg_snake"), matches.map { it.id })
    }

    @Test
    fun getMessagesByTriggerEvent_treatsPercentInTheEventNameAsALiteral() {
        // '%' matched any sequence, so an event named "%" returned every campaign
        // that had an event at all.
        dao.insertMessages(
            listOf(
                message("msg_1", priority = 1, triggerJson = trigger("checkout")),
                message("msg_2", priority = 2, triggerJson = trigger("app_open"))
            )
        )

        assertTrue(
            dao.getMessagesByTriggerEvent(IAMRepository.escapeLikePattern("%")).isEmpty()
        )
        assertTrue(
            dao.getMessagesByTriggerEvent(IAMRepository.escapeLikePattern("_")).isEmpty()
        )
    }

    @Test
    fun getMessagesByTriggerEvent_stillMatchesAnEventNameContainingAnUnderscore() {
        // The other half: escaping must not stop a legitimate snake_case name from
        // matching its own campaign.
        dao.insertMessages(
            listOf(message("msg_1", priority = 1, triggerJson = trigger("cart_abandoned")))
        )

        val matches = dao.getMessagesByTriggerEvent(
            IAMRepository.escapeLikePattern("cart_abandoned")
        )

        assertEquals(listOf("msg_1"), matches.map { it.id })
    }

    @Test
    fun getMessagesByTriggerEvent_doesNotMatchTriggerType() {
        // A query equal to the trigger *type* ("custom") must not match — only
        // the event field is searched.
        dao.insertMessages(
            listOf(message("msg_1", priority = 1, triggerJson = """{"type":"custom","event":"app_open"}"""))
        )

        assertTrue(dao.getMessagesByTriggerEvent("custom").isEmpty())
        assertEquals(1, dao.getMessagesByTriggerEvent("app_open").size)
    }

    // ---- valid-message window ----

    @Test
    fun getValidMessages_filtersByDateWindowAndOrdersByPriority() {
        val now = 1751328000000L
        val past = Date(now - 100_000L)
        val future = Date(now + 100_000L)

        dao.insertMessages(
            listOf(
                message("msg_active", priority = 3, startDate = past, endDate = future),
                message("msg_no_dates", priority = 1, startDate = null, endDate = null),
                message("msg_expired", priority = 2, startDate = past, endDate = Date(now - 1L)),
                message("msg_not_started", priority = 2, startDate = Date(now + 1L), endDate = future)
            )
        )

        val valid = dao.getValidMessages(now)
        assertEquals(listOf("msg_no_dates", "msg_active"), valid.map { it.id })
    }

    @Test
    fun getValidMessages_boundaryDatesAreInclusive() {
        val now = 1751328000000L
        dao.insertMessages(
            listOf(message("msg_edge", priority = 1, startDate = Date(now), endDate = Date(now)))
        )

        assertEquals(listOf("msg_edge"), dao.getValidMessages(now).map { it.id })
    }

    // ---- display records ----

    @Test
    fun insertDisplayRecord_returnsGeneratedIdsAndCounts() {
        insertParent("msg_1")

        val firstId = dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 100L))
        val secondId = dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 200L))

        assertTrue("row id must be generated", firstId > 0)
        assertTrue("row ids must be unique", secondId != firstId)
        assertEquals(2, dao.getDisplayCountForMessage("msg_1"))
    }

    @Test
    fun getDisplayCountForMessage_countsPerMessageId() {
        insertParent("msg_1")
        insertParent("msg_2")
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 100L))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 200L))
        dao.insertDisplayRecord(displayRecord("msg_2", timestamp = 300L))

        assertEquals(2, dao.getDisplayCountForMessage("msg_1"))
        assertEquals(1, dao.getDisplayCountForMessage("msg_2"))
        assertEquals(0, dao.getDisplayCountForMessage("msg_never_shown"))
    }

    @Test
    fun getLastDisplayTimestamp_returnsMaxTimestamp() {
        insertParent("msg_1")
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 500L))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 900L))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 700L))

        assertEquals(900L, dao.getLastDisplayTimestamp("msg_1"))
    }

    @Test
    fun getLastDisplayTimestamp_neverDisplayed_returnsZero() {
        assertEquals(0L, dao.getLastDisplayTimestamp("msg_unknown"))
    }

    @Test
    fun getDisplayRecordsForMessage_orderedByTimestampDescending() {
        insertParent("msg_1")
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 100L))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 300L))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 200L))

        val records = dao.getDisplayRecordsForMessage("msg_1")
        assertEquals(listOf(300L, 200L, 100L), records.map { it.timestamp })
    }

    @Test
    fun displayRecords_unsyncedQueryAndMarkSynced_roundTrip() {
        insertParent("msg_1")
        val idA = dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 100L, isSynced = false))
        val idB = dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 200L, isSynced = false))
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 300L, isSynced = true))

        val unsynced = dao.getUnsyncedDisplayRecords()
        assertEquals(setOf(idA, idB), unsynced.map { it.id }.toSet())

        dao.markDisplayRecordsAsSynced(listOf(idA, idB))
        assertTrue(dao.getUnsyncedDisplayRecords().isEmpty())
    }

    @Test
    fun insertDisplayRecord_forAMessageWithNoCampaignRow_isAccepted() {
        // Accepting this is what makes the cap survive a purge. A campaign whose row
        // was dropped by a full-replace or an inactive iam_status can still be on
        // screen, and its impression must land — if the write were rejected, the cap
        // would under-count exactly when the campaign is about to be re-synced with
        // the same id, and it would show again.
        val id = dao.insertDisplayRecord(displayRecord("msg_absent", timestamp = 100L))

        assertTrue(id > 0)
        assertEquals(1, dao.getDisplayCountForMessage("msg_absent"))
    }

    // ---- analytics events ----

    @Test
    fun insertAnalyticsEvent_returnsGeneratedId() {
        insertParent("msg_1")

        val id = dao.insertAnalyticsEvent(analyticsEvent("msg_1", "CLICK"))
        assertTrue(id > 0)
    }

    @Test
    fun analyticsEvents_unsyncedQuery_roundTripsColumns() {
        insertParent("msg_1")
        dao.insertAnalyticsEvent(
            IAMAnalyticsEvent(
                messageId = "msg_1",
                eventType = "CLICK",
                eventDate = 12345L,
                btnId = "cta",
                btnText = "Buy now",
                btnType = "open_url",
                isSynced = false
            )
        )

        val event = dao.getUnsyncedAnalyticsEvents(10).single()
        assertEquals("msg_1", event.messageId)
        assertEquals("CLICK", event.eventType)
        assertEquals(12345L, event.eventDate)
        assertEquals("cta", event.btnId)
        assertEquals("Buy now", event.btnText)
        assertEquals("open_url", event.btnType)
        assertEquals(false, event.isSynced)
    }

    @Test
    fun getUnsyncedAnalyticsEvents_respectsLimitAndExcludesSynced() {
        insertParent("msg_1")
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "CLICK"))
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "DISMISS"))
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "ERROR"))
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "CLICK", isSynced = true))

        assertEquals(3, dao.getUnsyncedAnalyticsEvents(10).size)
        assertEquals("LIMIT must cap the result", 2, dao.getUnsyncedAnalyticsEvents(2).size)
    }

    @Test
    fun getUnsyncedAnalyticsEventCount_isExactAndUncapped() {
        insertParent("msg_1")
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "CLICK"))
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "DISMISS"))
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "CLICK", isSynced = true)) // excluded

        assertEquals(2, dao.getUnsyncedAnalyticsEventCount())
    }

    @Test
    fun markAnalyticsEventsAsSynced_removesThemFromUnsyncedQuery() {
        insertParent("msg_1")
        val idA = dao.insertAnalyticsEvent(analyticsEvent("msg_1", "CLICK"))
        val idB = dao.insertAnalyticsEvent(analyticsEvent("msg_1", "DISMISS"))

        dao.markAnalyticsEventsAsSynced(listOf(idA))

        val remaining = dao.getUnsyncedAnalyticsEvents(10)
        assertEquals(listOf(idB), remaining.map { it.id })
    }

    @Test
    fun reinsertingMessage_preservesChildRows() {
        // With OnConflictStrategy.IGNORE, re-inserting an existing message id is a
        // no-op, so its display records and analytics events survive — refreshing
        // campaigns no longer wipes the frequency-capping history or unsent
        // analytics. Content updates go through updateMessage() (an in-place
        // UPDATE), which also keeps these child rows.
        insertParent("msg_1")
        dao.insertDisplayRecord(displayRecord("msg_1", timestamp = 100L))
        dao.insertAnalyticsEvent(analyticsEvent("msg_1", "CLICK"))

        dao.insertMessages(listOf(message("msg_1", priority = 2, htmlContent = "refreshed")))

        assertEquals(1, dao.getDisplayCountForMessage("msg_1"))
        assertEquals(1, dao.getUnsyncedAnalyticsEvents(10).size)
    }

    @Test
    fun deleteSyncedAnalyticsEvents_removesAcknowledgedRowsAndKeepsQueuedOnes() {
        insertParent("m1")
        val queued = dao.insertAnalyticsEvent(analyticsEvent("m1", "CLICK"))
        val delivered = dao.insertAnalyticsEvent(analyticsEvent("m1", "CLICK"))
        dao.markAnalyticsEventsAsSynced(listOf(delivered))
        // Total rows, not the unsynced queue: the queue already excludes synced
        // rows, so asserting on it would pass whether or not the delete ran.
        assertEquals(2, rowCount("iam_analytics_events"))

        dao.deleteSyncedAnalyticsEvents()

        assertEquals("the acknowledged row is gone from the table", 1, rowCount("iam_analytics_events"))
        val remaining = dao.getUnsyncedAnalyticsEvents(10)
        assertEquals(1, remaining.size)
        assertEquals("and it is the one still waiting to upload", queued, remaining[0].id)
    }

    @Test
    fun deleteSyncedAnalyticsEvents_withNothingAcknowledged_deletesNothing() {
        insertParent("m1")
        dao.insertAnalyticsEvent(analyticsEvent("m1", "CLICK"))
        dao.insertAnalyticsEvent(analyticsEvent("m1", "CLICK"))

        dao.deleteSyncedAnalyticsEvents()

        assertEquals("an un-uploaded click must never be swept", 2, rowCount("iam_analytics_events"))
    }

    @Test
    fun deleteSyncedAnalyticsEvents_doesNotTouchDisplayRecords() {
        // The asymmetry that matters: a synced display record is still the
        // frequency-cap history, so the analytics sweep must not reach it. A
        // one_time campaign whose synced impression was swept would show again.
        insertParent("m1")
        val recordId = dao.insertDisplayRecord(displayRecord("m1", timestamp = 100L))
        dao.markDisplayRecordsAsSynced(listOf(recordId))
        dao.insertAnalyticsEvent(analyticsEvent("m1", "CLICK"))
        dao.markAnalyticsEventsAsSynced(listOf(recordId))

        dao.deleteSyncedAnalyticsEvents()

        assertEquals("the synced display record must survive", 1, rowCount("iam_display_records"))
        assertEquals(
            "the cap must still see the display after its impression uploaded",
            1, dao.getDisplayCountForMessage("m1")
        )
        assertEquals("while the synced click is swept", 0, rowCount("iam_analytics_events"))
    }

    // ---- helpers ----

    private fun trigger(event: String): String = """{"type":"custom","event":"$event"}"""

    private fun message(
        id: String,
        priority: Int,
        position: IAMPosition = IAMPosition.CENTER,
        htmlContent: String = "<html><body>$id</body></html>",
        displayDuration: Long = 0L,
        shouldDismissOnTap: Boolean = false,
        actionsJson: String = "{}",
        startDate: Date? = null,
        endDate: Date? = null,
        audienceJson: String? = null,
        frequencyJson: String? = null,
        triggerJson: String = trigger("app_open")
    ): IAMMessage = IAMMessage(
        id = id,
        position = position,
        htmlContent = htmlContent,
        displayDuration = displayDuration,
        shouldDismissOnTap = shouldDismissOnTap,
        actionsJson = actionsJson,
        startDate = startDate,
        endDate = endDate,
        priority = priority,
        audienceJson = audienceJson,
        frequencyJson = frequencyJson,
        triggerJson = triggerJson
    )

    private fun insertParent(id: String) {
        dao.insertMessages(listOf(message(id, priority = 1)))
    }

    /**
     * Total rows in [table]. The DAO only counts what is still waiting to upload,
     * which is useless for asserting a delete happened — that query filters synced
     * rows out regardless, so the assertion would hold either way.
     */
    private fun rowCount(table: String): Int {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun displayRecord(
        messageId: String,
        timestamp: Long,
        isSynced: Boolean = false
    ): IAMDisplayRecord = IAMDisplayRecord(
        id = 0,
        messageId = messageId,
        timestamp = timestamp,
        isSynced = isSynced
    )

    private fun analyticsEvent(
        messageId: String,
        eventType: String,
        isSynced: Boolean = false
    ): IAMAnalyticsEvent = IAMAnalyticsEvent(
        messageId = messageId,
        eventType = eventType,
        eventDate = System.currentTimeMillis(),
        isSynced = isSynced
    )

}
