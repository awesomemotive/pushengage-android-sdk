package com.pushengage.pushengage.iam

import android.content.Context
import com.pushengage.pushengage.Database.PERoomDatabase

/**
 * Resets the IAM tables of the process-wide [PERoomDatabase] between tests.
 *
 * Tests that go through `IAMRepository.getInstance` share one database for the
 * whole JVM, so rows leak from one test to the next. Clearing `iam_messages`
 * alone used to be enough, because the child tables carried an `ON DELETE
 * CASCADE` and emptied with it. They deliberately no longer do — display history
 * has to outlive the campaign row so a re-sync cannot reset a frequency cap — so
 * every table has to be truncated explicitly.
 *
 * Raw SQL rather than DAO methods on purpose: production has no reason to delete
 * all display history at once, and adding a method so tests can is how test-only
 * API ends up in a shipped surface.
 */
internal object IAMTestDb {

    @JvmStatic
    fun clearIamTables(context: Context) {
        val db = PERoomDatabase.getDatabase(context).openHelper.writableDatabase
        db.execSQL("DELETE FROM iam_analytics_events")
        db.execSQL("DELETE FROM iam_display_records")
        db.execSQL("DELETE FROM iam_messages")
    }

    /**
     * Total rows in [table], synced or not.
     *
     * The DAO deliberately exposes no such count — production only ever asks what
     * is still waiting to upload. But that makes the DAO useless for asserting
     * that a row was *deleted*: `getUnsynced…` filters synced rows out already, so
     * it returns the same answer whether the sweep ran or not, and a test built on
     * it passes vacuously.
     */
    @JvmStatic
    fun rowCount(context: Context, table: String): Int {
        val db = PERoomDatabase.getDatabase(context).openHelper.readableDatabase
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }
}
