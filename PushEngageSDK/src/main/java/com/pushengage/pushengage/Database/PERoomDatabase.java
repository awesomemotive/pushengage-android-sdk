package com.pushengage.pushengage.Database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent;
import com.pushengage.pushengage.iam.model.IAMDisplayRecord;
import com.pushengage.pushengage.iam.model.IAMMessage;
import com.pushengage.pushengage.iam.repository.IAMDao;
import com.pushengage.pushengage.iam.repository.IAMTypeConverters;

@Database(entities = {
        ClickRequestEntity.class,
        ChannelEntity.class,
        IAMMessage.class,
        IAMDisplayRecord.class,
        IAMAnalyticsEvent.class
}, version = 2)
@TypeConverters(IAMTypeConverters.class)
public abstract class PERoomDatabase extends RoomDatabase {
    public abstract DaoInterface daoInterface();

    public abstract IAMDao iamDao();

    private static volatile PERoomDatabase peRoomDatabaseInstance;

    // Migration from 1 to 2 - Adding the IAM tables (messages, display records,
    // analytics events)
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Create iam_messages table. Column names/types/nullability must
            // match the IAMMessage entity exactly (snake_case @ColumnInfo names),
            // or Room's post-migration schema validation fails at open time on
            // any device upgrading from a pre-IAM database.
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `iam_messages` (" +
                            "`id` TEXT NOT NULL, " +
                            "`position` TEXT NOT NULL, " +
                            "`html_content` TEXT NOT NULL, " +
                            "`display_duration` INTEGER NOT NULL, " +
                            "`should_dismiss_on_tap` INTEGER NOT NULL, " +
                            "`actions_json` TEXT NOT NULL, " +
                            "`start_date` INTEGER, " +
                            "`end_date` INTEGER, " +
                            "`priority` INTEGER NOT NULL, " +
                            "`audience_json` TEXT, " +
                            "`frequency_json` TEXT, " +
                            "`trigger_json` TEXT NOT NULL, " +
                            "PRIMARY KEY(`id`))");

            // Create iam_display_records table (snake_case to match IAMDisplayRecord).
            // No foreign key on iam_messages, deliberately: campaign rows are dropped
            // whenever the server stops sending a campaign or iam_status goes
            // inactive, and a CASCADE would take this table's frequency-cap history
            // with them — resetting every cap and re-showing dismissed one-time
            // campaigns. See the note on the IAMDisplayRecord entity.
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `iam_display_records` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`message_id` TEXT NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`is_synced` INTEGER NOT NULL)");

            // Index name must match Room's generated name for Index("message_id")
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_iam_display_records_message_id` ON `iam_display_records` (`message_id`)");

            // Create iam_analytics_events table. Also without a foreign key: this is
            // the offline click outbox, so cascading from a dropped campaign would
            // delete taps that were never uploaded.
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `iam_analytics_events` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`message_id` TEXT NOT NULL, " +
                            "`event_type` TEXT NOT NULL, " +
                            "`event_date` INTEGER NOT NULL, " +
                            "`btn_id` TEXT, " +
                            "`btn_text` TEXT, " +
                            "`btn_type` TEXT, " +
                            "`is_synced` INTEGER NOT NULL, " +
                            "`upload_attempts` INTEGER NOT NULL DEFAULT 0)");

            // Create index on message_id for faster queries
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_iam_analytics_events_message_id` ON `iam_analytics_events` (`message_id`)");
        }
    };

    public static PERoomDatabase getDatabase(final Context context) {
        if (peRoomDatabaseInstance == null) {
            synchronized (PERoomDatabase.class) {
                if (peRoomDatabaseInstance == null) {
                    peRoomDatabaseInstance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            PERoomDatabase.class,
                            "pe_database")
                            .allowMainThreadQueries()
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return peRoomDatabaseInstance;
    }
}
