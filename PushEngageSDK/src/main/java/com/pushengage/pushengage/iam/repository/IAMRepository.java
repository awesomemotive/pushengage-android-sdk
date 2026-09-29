package com.pushengage.pushengage.iam.repository;

import androidx.annotation.RestrictTo;

import android.content.Context;

import com.google.gson.Gson;
import com.pushengage.pushengage.Database.PERoomDatabase;
import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.iam.model.IAMDisplayRecord;
import com.pushengage.pushengage.iam.model.IAMMessage;
import com.pushengage.pushengage.iam.model.IAMMessageResponse;
import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Repository for handling in-app messaging data operations
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class IAMRepository {
    private final IAMDao iamDao;
    private final Gson gson;
    private final Executor executor;

    /**
     * Completion for a write that is allowed to fail.
     *
     * <p>Deliberately not a {@link Runnable}: with no way to express the outcome,
     * a caller cannot tell a committed write from a swallowed exception, and the
     * sync path acted on that by recording the new campaign version either way —
     * which turned a retryable persist failure into a permanent one, because the
     * next sync then saw a matching version and skipped the re-fetch.
     */
    public interface WriteCallback {
        void onWriteComplete(boolean success);
    }

    /**
     * Private: every caller goes through {@link #getInstance(Context)}.
     *
     * <p>Each instance owns a single-thread executor, and the ordering guarantees
     * here are expressed in terms of it — {@link #runAfterPendingWrites} is a
     * barrier over the writes queued on <em>this</em> object. A second instance
     * would quietly mean a second write queue, so those guarantees would hold over
     * only half the writes while still reading as if they covered all of them.
     */
    private IAMRepository(Context context) {
        PERoomDatabase database = PERoomDatabase.getDatabase(context);
        this.iamDao = database.iamDao();
        this.gson = new Gson();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Converts a network response to a Room entity
     * 
     * @param response The network response
     * @return The Room entity
     */
    private IAMMessage convertResponseToEntity(IAMMessageResponse response) {
        return new IAMMessage(
                response.id,
                response.position,
                response.htmlContent,
                response.displayDuration,
                response.shouldDismissOnTap,
                gson.toJson(response.actions),
                response.startDate,
                response.endDate,
                response.priority,
                // Raw JSON passthrough; an explicit JSON null becomes SQL NULL so the
                // rules engine reads it as "no criteria" (eligible for everyone).
                response.audience != null && !response.audience.isJsonNull()
                        ? gson.toJson(response.audience) : null,
                response.frequency != null ? gson.toJson(response.frequency) : null,
                gson.toJson(response.trigger));
    }

    /**
     * Saves multiple messages from network responses while preserving display
     * records
     * 
     * @param responses The message responses from the network
     */
    public void saveMessages(List<IAMMessageResponse> responses) {
        executor.execute(() -> {
            try {
                List<IAMMessage> entities = toEntitiesDroppingInvalid(responses, new ArrayList<>());
                if (!entities.isEmpty()) {
                    iamDao.upsertMessages(entities);
                }
            } catch (Exception e) {
                PELogger.error("Error saving IAM messages: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Full-replace of the local campaign set: campaigns
     * absent from {@code responses} are deleted, the rest are upserted. Surviving
     * campaigns keep their display/analytics history (they are updated in place),
     * so frequency-capping state is preserved across a server refresh. An empty
     * list purges all campaigns (used when iam_status is not active).
     */
    public void replaceAllMessages(List<IAMMessageResponse> responses) {
        replaceAllMessages(responses, null);
    }

    /**
     * As {@link #replaceAllMessages(List)}, but reports the outcome on the
     * repository executor once the write has finished, so callers that must read
     * the fresh store — e.g. app-open auto-trigger evaluation — can proceed only
     * once the data is committed, and callers that record "we now hold this
     * version" can do so only when that is actually true.
     */
    public void replaceAllMessages(List<IAMMessageResponse> responses, WriteCallback onComplete) {
        executor.execute(() -> {
            boolean success = false;
            try {
                List<String> idsToKeep = new ArrayList<>();
                List<IAMMessage> entities = toEntitiesDroppingInvalid(responses, idsToKeep);

                // An explicitly empty campaign set is an instruction to purge (it is
                // how an inactive iam_status is applied). A set that ended up empty
                // only because every campaign in it was unusable is not — that is a
                // backend fault, and acting on it would delete the user's campaigns
                // and their frequency-cap history on the strength of a bad response.
                // Reported as a failure, so the version pointer is not advanced and
                // the next sync fetches again instead of assuming it is up to date.
                if (idsToKeep.isEmpty() && responses != null && !responses.isEmpty()) {
                    PELogger.error("IAM full-replace skipped: all " + responses.size()
                            + " campaigns in the response were unusable");
                } else {
                    iamDao.replaceMessages(idsToKeep, entities);
                    PELogger.debug("Full-replace complete: kept/updated " + idsToKeep.size() + " campaigns");
                    success = true;
                }
            } catch (Exception e) {
                PELogger.error("Error replacing IAM messages: " + e.getMessage(), e);
            } finally {
                if (onComplete != null) {
                    onComplete.onWriteComplete(success);
                }
            }
        });
    }

    /**
     * Converts campaign responses to entities, dropping any campaign the SDK
     * cannot key on, and collecting the surviving ids into {@code idsToKeep}.
     *
     * <p>{@code IAMMessageResponse.id} is a non-null Kotlin type, but Gson builds
     * these objects by unsafe reflection and bypasses that check, so a campaign
     * whose JSON omits {@code "id"} arrives with a null id and no exception. Left
     * unfiltered it does two kinds of damage: the null poisons the
     * {@code id NOT IN (:idsToKeep)} delete, which under SQL three-valued logic
     * then matches no rows at all and removes nothing, and it fails the primary
     * key on insert, taking every other new campaign in the same batch with it.
     */
    private List<IAMMessage> toEntitiesDroppingInvalid(List<IAMMessageResponse> responses,
                                                       List<String> idsToKeep) {
        List<IAMMessage> entities = new ArrayList<>();
        if (responses == null) {
            return entities;
        }
        for (IAMMessageResponse response : responses) {
            if (response == null || response.id == null || response.id.isEmpty()) {
                PELogger.error("IAM: dropping a campaign with no id from the response");
                continue;
            }
            idsToKeep.add(response.id);
            entities.add(convertResponseToEntity(response));
        }
        return entities;
    }

    /**
     * Retrieves a message by its ID
     * 
     * @param messageId The message ID
     * @return The message, or null if not found
     */
    public IAMMessage getMessageById(String messageId) {
        try {
            return iamDao.getMessageById(messageId);
        } catch (Exception e) {
            PELogger.error("Error getting IAM message: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Gets all active messages
     * 
     * @return List of active messages
     */
    public List<IAMMessage> getAllActiveMessages() {
        try {
            return iamDao.getAllActiveMessages();
        } catch (Exception e) {
            PELogger.error("Error getting active IAM messages: " + e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Gets messages that match a specific trigger event
     * 
     * @param triggerEvent The trigger event name
     * @return List of matching messages
     */
    public List<IAMMessage> getMessagesByTriggerEvent(String triggerEvent) {
        try {
            return iamDao.getMessagesByTriggerEvent(escapeLikePattern(triggerEvent));
        } catch (Exception e) {
            PELogger.error("Error getting IAM messages by trigger: " + e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Escapes the SQL {@code LIKE} wildcards in {@code value} so it matches
     * literally, against a query declaring {@code ESCAPE '\'}.
     *
     * <p>Order matters: the escape character itself has to be doubled first, or the
     * backslashes added for {@code %} and {@code _} would then be escaped again and
     * the wildcards would survive.
     */
    static String escapeLikePattern(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * Gets messages that are valid based on their date range
     * 
     * @return List of valid messages
     */
    public List<IAMMessage> getValidMessages() {
        try {
            Date currentDate = new Date();
            return iamDao.getValidMessages(currentDate.getTime());
        } catch (Exception e) {
            PELogger.error("Error getting valid IAM messages: " + e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Records that a message was displayed
     * 
     * @param messageId The ID of the message that was displayed
     * @return The ID of the display record
     */
    public long recordMessageDisplay(String messageId) {
        try {
            IAMDisplayRecord record = new IAMDisplayRecord(0, messageId);
            return iamDao.insertDisplayRecord(record);
        } catch (Exception e) {
            PELogger.error("Error recording IAM message display: " + e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Gets the number of times a message has been displayed
     * 
     * @param messageId The message ID
     * @return The display count
     */
    public int getDisplayCount(String messageId) {
        try {
            return iamDao.getDisplayCountForMessage(messageId);
        } catch (Exception e) {
            PELogger.error("Error getting IAM message display count: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Gets the timestamp of the most recent display for a message
     * 
     * @param messageId The message ID
     * @return The timestamp in milliseconds, or 0 if never displayed
     */
    public long getLastDisplayTimestamp(String messageId) {
        try {
            return iamDao.getLastDisplayTimestamp(messageId);
        } catch (Exception e) {
            PELogger.error("Error getting IAM last display timestamp: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Gets unsynced display records for analytics
     * 
     * @return List of unsynced display records
     */
    public List<IAMDisplayRecord> getUnsyncedDisplayRecords() {
        try {
            return iamDao.getUnsyncedDisplayRecords();
        } catch (Exception e) {
            PELogger.error("Error getting unsynced IAM display records: " + e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Marks a display record as reported
     * 
     * @param recordId The record ID
     */
    public void markRecordReported(long recordId) {
        executor.execute(() -> {
            try {
                List<Long> recordIds = new ArrayList<>();
                recordIds.add(recordId);
                iamDao.markDisplayRecordsAsSynced(recordIds);
                PELogger.debug("Display record marked as reported: " + recordId);
            } catch (Exception e) {
                PELogger.error("Error marking display record as reported: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Gets unreported display records
     * 
     * @return List of unreported display records
     */
    public List<IAMDisplayRecord> getUnreportedDisplayRecords() {
        try {
            return iamDao.getUnsyncedDisplayRecords();
        } catch (Exception e) {
            PELogger.error("Error getting unreported IAM display records: " + e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Removes analytics events the backend has already acknowledged.
     *
     * <p>This table is only ever read to find events still waiting to upload, so
     * an acknowledged row has no reader left. Without this sweep it stayed
     * forever: one row per button tap, for the life of the install.
     *
     * <p>Display records are deliberately left alone. They serve a second purpose
     * that outlives the upload — they *are* the frequency-cap history, and the
     * caps are lifetime ones ({@code one_time} asks whether a campaign was ever
     * shown, {@code capped} counts displays for the life of the install). Pruning
     * them, on any schedule, would eventually reset a cap and re-show a campaign
     * the user already dismissed.
     */
    public void pruneSyncedAnalyticsEvents() {
        executor.execute(() -> {
            try {
                iamDao.deleteSyncedAnalyticsEvents();
            } catch (Exception e) {
                PELogger.error("Error pruning synced IAM analytics events: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Records an analytics event
     * 
     * @param eventType Type of the analytics event
     * @param messageId ID of the associated message
     * @param metadata  Optional metadata for the event
     * @return The ID of the recorded event
     */
    public long recordAnalyticsEvent(String eventType, String messageId,
                                     String btnId, String btnText, String btnType) {
        try {
            IAMAnalyticsEvent event = new IAMAnalyticsEvent(
                    0,
                    messageId,
                    eventType,
                    System.currentTimeMillis(),
                    btnId,
                    btnText,
                    btnType,
                    false,
                    0); // uploadAttempts — Java does not see Kotlin default arguments

            return iamDao.insertAnalyticsEvent(event);
        } catch (Exception e) {
            PELogger.error("Error recording analytics event: " + e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Gets unsynced analytics events
     * 
     * @param limit Maximum number of events to retrieve
     * @return List of unsynced analytics events
     */
    public List<IAMAnalyticsEvent> getUnsyncedAnalyticsEvents(int limit) {
        try {
            return iamDao.getUnsyncedAnalyticsEvents(limit);
        } catch (Exception e) {
            PELogger.error("Error getting unsynced analytics events: " + e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Marks analytics events as synced
     * 
     * @param eventIds The event IDs to mark as synced
     */
    public void markAnalyticsEventsAsSynced(List<Long> eventIds) {
        executor.execute(() -> {
            try {
                iamDao.markAnalyticsEventsAsSynced(eventIds);
            } catch (Exception e) {
                PELogger.error("Error marking analytics events as synced: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Charges one failed upload against {@code eventId}, dropping it once it has spent
     * {@code maxAttempts}.
     *
     * <p>Only the event a batch stopped on should be charged: the events behind it never
     * reached the wire, so they keep a full budget of their own.
     */
    public void chargeFailedUpload(long eventId, int maxAttempts) {
        executor.execute(() -> {
            try {
                iamDao.incrementUploadAttempts(eventId);
                int dropped = iamDao.deleteEventsPastUploadBudget(maxAttempts);
                if (dropped > 0) {
                    PELogger.error("Dropped " + dropped + " IAM analytics event(s) after "
                            + maxAttempts + " failed uploads — they were blocking every "
                            + "event queued behind them");
                }
            } catch (Exception e) {
                PELogger.error("Error charging a failed analytics upload: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Runs {@code task} on this repository's single-thread write executor — i.e.
     * strictly after every write enqueued <em>through this repository</em> before
     * this call has committed. Used as a completion barrier: the analytics sync
     * gate must not reopen until the mark-as-synced writes from the current pass
     * are visible.
     *
     * <p>The qualifier matters. The barrier is the executor, and the executor
     * belongs to the instance, so this says nothing about writes made through a
     * different one — which is why {@link #getInstance(Context)} is the only way
     * to obtain a repository. It also says nothing about writes that never go
     * through the executor at all: {@link #recordMessageDisplay(String)} and
     * {@link #recordAnalyticsEvent} run on the calling thread so they can return
     * the new row id.
     */
    public void runAfterPendingWrites(Runnable task) {
        executor.execute(task);
    }

    /**
     * Creates a summary of the in-app messaging database content
     * including message counts, display records, and analytics events
     *
     * @return A formatted string containing the database summary
     */
    public String getDatabaseSummary() {
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("===== In-App Messaging Database Summary =====\n");

            // Get messages
            List<IAMMessage> activeMessages = getAllActiveMessages();
            summary.append("Total IAM Messages: ").append(activeMessages.size()).append("\n");

            // Get valid messages
            List<IAMMessage> validMessages = getValidMessages();
            summary.append("Valid IAM Messages: ").append(validMessages.size()).append("\n");

            // Get display records
            List<IAMDisplayRecord> displayRecords = getUnsyncedDisplayRecords();
            summary.append("Unsynced Display Records: ").append(displayRecords.size()).append("\n");

            // Get analytics events (exact count, not capped)
            summary.append("Unsynced Analytics Events: ").append(iamDao.getUnsyncedAnalyticsEventCount()).append("\n");

            // Detailed message info, including content so the stored campaign UI
            // can be inspected (e.g. from the demo app's "IAM Database State").
            if (!activeMessages.isEmpty()) {
                summary.append("\nMessage Details:\n");
                for (IAMMessage message : activeMessages) {
                    summary.append("• ID: ").append(message.id)
                            .append("\n    Position: ").append(message.position)
                            .append(", Priority: ").append(message.priority)
                            .append(", Displays: ").append(getDisplayCount(message.id))
                            .append("\n    Trigger: ").append(message.triggerJson)
                            .append("\n    Actions: ").append(message.actionsJson)
                            .append("\n    HTML:\n").append(message.htmlContent)
                            .append("\n");
                }
            }

            summary.append("===========================================");
            return summary.toString();
        } catch (Exception e) {
            PELogger.error("Error generating IAM database summary: " + e.getMessage(), e);
            return "Error generating IAM database summary: " + e.getMessage();
        }
    }

    private static IAMRepository instance;

    /**
     * The one repository for the process.
     *
     * <p>Not merely a convenience: the write executor is per-instance, so two
     * instances means two independent write queues over the same database, and
     * {@link #runAfterPendingWrites} stops being the barrier it claims to be. The
     * constructor is private so that cannot happen by accident — it previously
     * could, because the controller built its own with {@code new} and left this
     * singleton to be populated by whoever called it next.
     *
     * @param context any context; the application context is used
     * @return the process-wide instance
     */
    public static synchronized IAMRepository getInstance(Context context) {
        if (instance == null) {
            instance = new IAMRepository(context.getApplicationContext());
        }
        return instance;
    }
}