package com.pushengage.pushengage.iam.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per display of an in-app message.
 *
 * Serves two purposes: it is the impression reported to analytics (via [isSynced],
 * which is this table's outbox), and it is the display history frequency capping
 * counts. The second outlives the first, so a row is never deleted once uploaded —
 * removing it would reset the user's cap and let a show-once campaign reappear.
 *
 * Clicks are deliberately NOT recorded here. A tap can land after the impression
 * has already been uploaded and marked synced, so clicks are queued separately as
 * [IAMAnalyticsEventType.CLICK] rows in `iam_analytics_events`.
 */
/*
 * Deliberately NOT a foreign key on iam_messages.
 *
 * The obvious `ON DELETE CASCADE` contradicts the invariant above. Campaign rows
 * are not permanent: a full-replace drops any campaign the server stops sending,
 * and an `iam_status` that is not active purges the table outright. Tied to the
 * parent row, this history would go with it — so a single inactive sync cycle
 * would reset every frequency cap, and a show-once campaign would show again to
 * someone who had already dismissed it.
 *
 * Orphan rows are therefore NOT swept. There is no retention pass for this table and
 * [IAMDao] says there must not be one: a campaign that stops being sent can always come
 * back, and a swept history caps it from zero. Unbounded growth is the accepted cost —
 * one row per display, an id, a timestamp and a flag.
 */
@Entity(
    tableName = "iam_display_records",
    indices = [Index("message_id")]
)
internal data class IAMDisplayRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    @JvmField val id: Long = 0,

    @ColumnInfo(name = "message_id")
    @JvmField val messageId: String,

    @ColumnInfo(name = "timestamp")
    @JvmField val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_synced")
    @JvmField val isSynced: Boolean = false // Whether this record has been synced to the server
) {
    // Secondary constructor for Java interoperability - minimum params
    constructor(id: Long, messageId: String) : this(
        id = id,
        messageId = messageId,
        timestamp = System.currentTimeMillis(),
        isSynced = false
    )
}
