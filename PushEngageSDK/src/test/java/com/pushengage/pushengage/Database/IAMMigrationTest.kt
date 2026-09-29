package com.pushengage.pushengage.Database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the IAM Room migration DDL against the entity schema. The bug this
 * pins: MIGRATION_1_2 created camelCase columns (htmlContent, messageId, …) plus
 * phantom isDeleted/lastUpdated columns, while the entities declare snake_case
 * @ColumnInfo names. A device upgrading from a pre-IAM database (v1) would run
 * this migration and then fail Room's schema validation at open time.
 *
 * Runs MIGRATION_1_2 in isolation against a raw in-memory database and inspects
 * the produced columns via PRAGMA — no room-testing artifact required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMMigrationTest {

    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {}
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(callback)
            .build()
        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun migration_1_2_createsMessagesTableMatchingEntityColumns() {
        PERoomDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(
            setOf(
                "id", "position", "html_content", "display_duration", "should_dismiss_on_tap",
                "actions_json", "start_date", "end_date", "priority", "audience_json",
                "frequency_json", "trigger_json"
            ),
            columnsOf("iam_messages")
        )
    }

    @Test
    fun migration_1_2_createsDisplayRecordsTableMatchingEntityColumns() {
        PERoomDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(
            setOf("id", "message_id", "timestamp", "is_synced"),
            columnsOf("iam_display_records")
        )
    }

    @Test
    fun migration_1_2_doesNotCreateCamelCaseOrPhantomColumns() {
        PERoomDatabase.MIGRATION_1_2.migrate(db)
        val columns = columnsOf("iam_messages")

        assertFalse("phantom isDeleted column removed", columns.contains("isDeleted"))
        assertFalse("phantom lastUpdated column removed", columns.contains("lastUpdated"))
        assertFalse("camelCase htmlContent replaced by html_content", columns.contains("htmlContent"))
    }

    @Test
    fun migration_1_2_createsAnalyticsTableMatchingEntityColumns() {
        PERoomDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(
            setOf(
                "id", "message_id", "event_type", "event_date",
                "btn_id", "btn_text", "btn_type", "is_synced",
                // Upload attempt budget: bounds the head-of-line block when an event
                // fails for a reason the payload cannot recover from. Folded into this
                // migration rather than a v3 because the feature is unreleased.
                "upload_attempts"
            ),
            columnsOf("iam_analytics_events")
        )
    }

    @Test
    fun migration_1_2_givesTheChildTablesNoForeignKeyOnMessages() {
        // Both child tables outlive the campaign row on purpose: a campaign is
        // dropped whenever the server stops sending it or iam_status goes inactive,
        // and a CASCADE from iam_messages would take the frequency-cap history and
        // the un-uploaded click queue with it — resetting caps and re-showing
        // dismissed one-time campaigns. The DDL must agree with the entities, or a
        // device upgrading from v1 gets the cascade the entities no longer declare.
        PERoomDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(emptyList<String>(), foreignKeyTargetsOf("iam_display_records"))
        assertEquals(emptyList<String>(), foreignKeyTargetsOf("iam_analytics_events"))
    }

    private fun foreignKeyTargetsOf(table: String): List<String> {
        val targets = mutableListOf<String>()
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val tableIndex = cursor.getColumnIndex("table")
            while (cursor.moveToNext()) {
                targets.add(cursor.getString(tableIndex))
            }
        }
        return targets
    }

    private fun columnsOf(table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameIndex))
            }
        }
        return names
    }

    /**
     * The real upgrade a consumer performs: a database written by the released
     * SDK (v1 — ClickRequest + Channel only) is opened by this build. Room runs
     * MIGRATION_1_2 and then validates the whole schema against the entities; a
     * single type, nullability, index or default that the DDL above gets wrong
     * fails that validation and the app crashes on first database access. The
     * PRAGMA tests above only compare column names, so this is the one that says
     * whether the upgrade path actually works.
     */
    @Test
    fun `a database written by the released v1 SDK upgrades and opens`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        writeReleasedV1Database(context)

        val upgraded = Room.databaseBuilder(context, PERoomDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addMigrations(PERoomDatabase.MIGRATION_1_2)
            .build()

        try {
            // Touching it is what triggers open -> migrate -> validate.
            assertEquals(1, upgraded.daoInterface().allClick.size)

            // And the IAM tables the migration added are usable.
            upgraded.query("SELECT COUNT(*) FROM iam_messages", emptyArray()).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        } finally {
            upgraded.close()
        }
    }

    /** A v1 database exactly as the released SDK's Room created it, with one row. */
    private fun writeReleasedV1Database(context: Context) {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ClickRequest` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`deviceHash` TEXT NOT NULL, `tag` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, `device_type` TEXT NOT NULL, " +
                        "`device` TEXT NOT NULL, `swv` TEXT NOT NULL, " +
                        "`timezone` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Channel` (" +
                        "`channel_id` TEXT NOT NULL, `channel_name` TEXT, " +
                        "`channel_description` TEXT, `group_id` TEXT, `group_name` TEXT, " +
                        "`importance` TEXT, `sound` TEXT, `sound_file` TEXT, " +
                        "`vibration` TEXT, `vibration_pattern` TEXT, `led_color` TEXT, " +
                        "`led_color_code` TEXT, `accent_color` TEXT, `badges` INTEGER, " +
                        "`lock_screen` TEXT, PRIMARY KEY(`channel_id`))"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(callback)
            .build()
        val v1 = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        v1.execSQL(
            "INSERT INTO ClickRequest (deviceHash, tag, action, device_type, device, swv, timezone) " +
                "VALUES ('hash', 'tag', 'action', 'android', 'device', '1.0', 'UTC')"
        )
        v1.close()
    }

    private companion object {
        const val DB_NAME = "pe_database"
    }
}
