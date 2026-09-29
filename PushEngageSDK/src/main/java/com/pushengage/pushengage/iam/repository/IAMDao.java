package com.pushengage.pushengage.iam.repository;

import androidx.annotation.RestrictTo;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent;
import com.pushengage.pushengage.iam.model.IAMDisplayRecord;
import com.pushengage.pushengage.iam.model.IAMMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for in-app messaging related database operations
 */
@Dao
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface IAMDao {
    // Message operations.
    // IGNORE (not REPLACE) on conflict: REPLACE deletes the existing row before
    // re-inserting it, which loses any column the new row does not carry and
    // churns the primary key for no reason. Inserts are for genuinely-new
    // messages; content changes to an existing message go through updateMessage(),
    // which updates in place. (The child tables hold no foreign key to this one,
    // so their history no longer depends on this choice — see IAMDisplayRecord.)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertMessages(List<IAMMessage> messages);

    @Update
    void updateMessage(IAMMessage message);

    @Query("SELECT * FROM iam_messages WHERE id = :messageId")
    IAMMessage getMessageById(String messageId);

    @Query("SELECT * FROM iam_messages ORDER BY priority ASC")
    List<IAMMessage> getAllActiveMessages();

    @Query("SELECT id FROM iam_messages")
    List<String> getAllMessageIds();

    // Full-replace support: delete campaigns no longer
    // present in the server's set. Surviving campaigns keep their display/analytics
    // history because they are updated in place, not deleted+reinserted.
    @Query("DELETE FROM iam_messages WHERE id NOT IN (:idsToKeep)")
    void deleteMessagesNotIn(List<String> idsToKeep);

    @Query("DELETE FROM iam_messages")
    void deleteAllMessages();

    /**
     * Upserts a campaign set in one transaction: ids already stored are updated
     * in place (which preserves their display/analytics child rows), the rest
     * are inserted.
     */
    @Transaction
    default void upsertMessages(List<IAMMessage> messages) {
        List<IAMMessage> toInsert = new ArrayList<>();
        for (IAMMessage message : messages) {
            if (getMessageById(message.id) != null) {
                updateMessage(message);
            } else {
                toInsert.add(message);
            }
        }
        if (!toInsert.isEmpty()) {
            insertMessages(toInsert);
        }
    }

    /**
     * Full-replace of the campaign set as a single transaction.
     *
     * <p>Atomicity is the point, not a nicety. Run as two statements, the window
     * between the delete and the upsert is one where a campaign that is about to
     * be re-inserted does not exist — and an impression insert landing in that
     * window fails the {@code iam_display_records} foreign key, gets swallowed,
     * and the frequency cap never increments. Wrapping both in one transaction
     * means no other connection ever observes the gap, and a failed upsert rolls
     * the delete back instead of leaving the store half-empty.
     *
     * @param idsToKeep ids present in the server's set; an empty list purges all
     *                  campaigns (used when iam_status is not active)
     * @param messages  the entities to upsert, whose ids must be {@code idsToKeep}
     */
    @Transaction
    default void replaceMessages(List<String> idsToKeep, List<IAMMessage> messages) {
        if (idsToKeep.isEmpty()) {
            deleteAllMessages();
        } else {
            deleteMessagesNotIn(idsToKeep);
        }
        upsertMessages(messages);
    }

    // Match the trigger's "event" JSON field precisely rather than doing a bare
    // substring search over the whole blob. The bare '%event%' form over-matched:
    // event "sale" also matched a stored trigger "mega_sale", and a query equal to
    // a trigger *type* (e.g. "custom") matched every custom-trigger campaign.
    // Anchoring to the "event":"<x>" key relies on Gson's space-free serialization
    // (see IAMTypeConverters).
    //
    // The event name is concatenated into the LIKE *pattern*, so its own characters
    // are pattern syntax: '_' matches any single character and '%' any sequence.
    // Since event names come from the host app and this codebase's own convention is
    // snake_case, that over-matched in the same way the anchoring was meant to stop —
    // firing "cart_abandoned" also matched a stored "cart-abandoned", and an event
    // named "%" matched every campaign that has one. Nothing downstream re-checks the
    // event name, so the wrong campaign simply displays. Hence the ESCAPE clause;
    // callers escape the value with IAMRepository.escapeLikePattern.
    @Query("SELECT * FROM iam_messages WHERE trigger_json LIKE '%\"event\":\"' || :triggerEventPattern || '\"%' ESCAPE '\\' ORDER BY priority ASC")
    List<IAMMessage> getMessagesByTriggerEvent(String triggerEventPattern);

    // Display records operations
    @Insert
    long insertDisplayRecord(IAMDisplayRecord record);

    @Query("SELECT * FROM iam_display_records WHERE message_id = :messageId ORDER BY timestamp DESC")
    List<IAMDisplayRecord> getDisplayRecordsForMessage(String messageId);

    @Query("SELECT COUNT(*) FROM iam_display_records WHERE message_id = :messageId")
    int getDisplayCountForMessage(String messageId);

    /**
     * Gets the timestamp of the most recent display for a message
     * 
     * @param messageId The message ID to check
     * @return The timestamp in milliseconds, or 0 if never displayed
     */
    @Query("SELECT IFNULL(MAX(timestamp), 0) FROM iam_display_records WHERE message_id = :messageId")
    long getLastDisplayTimestamp(String messageId);

    @Query("SELECT * FROM iam_display_records WHERE is_synced = 0")
    List<IAMDisplayRecord> getUnsyncedDisplayRecords();

    @Query("UPDATE iam_display_records SET is_synced = 1 WHERE id IN (:recordIds)")
    void markDisplayRecordsAsSynced(List<Long> recordIds);

    @Transaction
    @Query("SELECT m.* FROM iam_messages m " +
            "WHERE (m.start_date IS NULL OR m.start_date <= :currentDateMillis) " +
            "AND (m.end_date IS NULL OR m.end_date >= :currentDateMillis) " +
            "ORDER BY m.priority ASC")
    List<IAMMessage> getValidMessages(Long currentDateMillis);

    // Analytics events operations
    @Insert
    long insertAnalyticsEvent(IAMAnalyticsEvent event);

    @Query("SELECT * FROM iam_analytics_events WHERE is_synced = 0 LIMIT :limit")
    List<IAMAnalyticsEvent> getUnsyncedAnalyticsEvents(int limit);

    @Query("SELECT COUNT(*) FROM iam_analytics_events WHERE is_synced = 0")
    int getUnsyncedAnalyticsEventCount();

    @Query("UPDATE iam_analytics_events SET is_synced = 1 WHERE id IN (:eventIds)")
    void markAnalyticsEventsAsSynced(List<Long> eventIds);

    @Query("UPDATE iam_analytics_events SET upload_attempts = upload_attempts + 1 WHERE id = :eventId")
    void incrementUploadAttempts(long eventId);

    /**
     * Drops events that have spent their upload budget, so the queue behind them moves.
     *
     * Unlike the display-record table this one is purely an outbox — nothing reads a row
     * for any purpose other than uploading it — so deleting one costs an event, not a
     * frequency cap. Losing one undeliverable event beats losing every event behind it.
     */
    @Query("DELETE FROM iam_analytics_events WHERE upload_attempts >= :maxAttempts")
    int deleteEventsPastUploadBudget(int maxAttempts);

    // An acknowledged analytics event is dead weight: this table is purely the
    // click outbox, and nothing reads a row once the backend has it.
    //
    // Note the asymmetry with iam_display_records, which has NO equivalent sweep
    // and must not gain one. A display record is both the impression outbox and
    // the frequency-cap history, and the cap outlives the upload by design:
    // one_time asks "was this ever shown" and capped counts displays for the
    // lifetime of the install, so deleting synced rows there — on a timer or
    // otherwise — would eventually let a show-once campaign show again.
    //
    // A sweep rather than a delete-on-upload, so a wrong "delivered" response
    // cannot destroy the queue outright: the row is marked first and removed on a
    // later pass.
    @Query("DELETE FROM iam_analytics_events WHERE is_synced = 1")
    void deleteSyncedAnalyticsEvents();
}