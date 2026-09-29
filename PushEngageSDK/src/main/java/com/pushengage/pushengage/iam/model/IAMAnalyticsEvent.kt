package com.pushengage.pushengage.iam.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for tracking in-app message analytics events.
 *
 * This table is the offline outbox for clicks: a tap made with no connectivity is
 * queued here and flushed on reconnect. Like [IAMDisplayRecord] it deliberately
 * carries no foreign key to `iam_messages` — cascading from the parent would
 * destroy events that were never uploaded whenever a campaign is dropped from a
 * sync or the campaign table is purged, which is exactly the loss the offline
 * queue exists to prevent.
 */
@Entity(
    tableName = "iam_analytics_events",
    indices = [Index("message_id")]
)
internal data class IAMAnalyticsEvent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    
    @ColumnInfo(name = "message_id")
    val messageId: String,
    
    @ColumnInfo(name = "event_type")
    val eventType: String,
    
    @ColumnInfo(name = "event_date")
    val eventDate: Long = System.currentTimeMillis(),
    
    // The button that was tapped, reported verbatim as the analytics `btn_*`
    // fields. Stored as typed columns rather than a JSON blob: the shape is fixed,
    // so a blob only added a serialize/parse round-trip and a malformed-JSON path.
    @ColumnInfo(name = "btn_id")
    val btnId: String? = null,

    @ColumnInfo(name = "btn_text")
    val btnText: String? = null,

    @ColumnInfo(name = "btn_type")
    val btnType: String? = null,
    
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false,

    /**
     * Upload attempts that ended in a failure this event could not be dropped for.
     *
     * Bounds the head-of-line block. Events upload oldest-first and a row that is not
     * delivered is never marked synced, so a failure the payload cannot recover from —
     * a revoked site key, a wrong host — keeps the same row at the front of every pass
     * and nothing queued behind it ever uploads. A permanent per-payload rejection
     * (400/409/413/422) is dropped by the network layer instead; this covers the rest.
     */
    @ColumnInfo(name = "upload_attempts")
    val uploadAttempts: Int = 0
) 