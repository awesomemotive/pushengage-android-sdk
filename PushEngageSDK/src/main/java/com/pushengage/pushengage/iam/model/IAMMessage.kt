package com.pushengage.pushengage.iam.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.pushengage.pushengage.iam.repository.IAMTypeConverters
import java.util.Date

/**
 * Room entity for storing in-app messages
 */
@Entity(tableName = "iam_messages")
@TypeConverters(IAMTypeConverters::class)
internal data class IAMMessage(
    @PrimaryKey
    @ColumnInfo(name = "id")
    @JvmField val id: String,

    @ColumnInfo(name = "position")
    @JvmField val position: IAMPosition,

    @ColumnInfo(name = "html_content")
    @JvmField val htmlContent: String,

    @ColumnInfo(name = "display_duration")
    @JvmField val displayDuration: Long,

    @ColumnInfo(name = "should_dismiss_on_tap")
    @JvmField val shouldDismissOnTap: Boolean,

    @ColumnInfo(name = "actions_json")
    @JvmField val actionsJson: String, // JSON string of actions Map

    @ColumnInfo(name = "start_date")
    @JvmField val startDate: Date?,

    @ColumnInfo(name = "end_date")
    @JvmField val endDate: Date?,

    @ColumnInfo(name = "priority")
    @JvmField val priority: Int,

    @ColumnInfo(name = "audience_json")
    @JvmField val audienceJson: String?, // JSON string of audience List

    @ColumnInfo(name = "frequency_json")
    @JvmField val frequencyJson: String?, // JSON string of frequency

    @ColumnInfo(name = "trigger_json")
    @JvmField val triggerJson: String   // JSON string of trigger
) 