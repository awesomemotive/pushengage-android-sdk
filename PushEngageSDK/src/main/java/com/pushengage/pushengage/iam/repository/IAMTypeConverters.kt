package com.pushengage.pushengage.iam.repository

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pushengage.pushengage.iam.model.IAMAction
import com.pushengage.pushengage.iam.model.IAMFrequency
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.model.IAMTriggerCondition
import java.util.Date

/**
 * Type converters for Room database to handle complex types in IAM entities
 */
internal class IAMTypeConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromPositionString(value: String?): IAMPosition? {
        return value?.let { IAMPosition.valueOf(it) }
    }

    @TypeConverter
    fun positionToString(position: IAMPosition?): String? {
        return position?.name
    }

    @TypeConverter
    fun fromActionsJson(value: String?): Map<String, IAMAction>? {
        if (value == null) return null
        val type = object : TypeToken<Map<String, IAMAction>>() {}.type
        return gson.fromJson(value, type)
    }

    @TypeConverter
    fun actionsToJson(actions: Map<String, IAMAction>?): String? {
        return actions?.let { gson.toJson(it) }
    }

    // No audience converters: `IAMMessage.audience_json` is stored as a raw JSON
    // string, so Room never needed them. The removed pair also described the legacy
    // flat-array shape, which would now be misleading — IAMRulesEngine is the single
    // place that interprets the audience shape.

    @TypeConverter
    fun fromFrequencyJson(value: String?): IAMFrequency? {
        if (value == null) return null
        return gson.fromJson(value, IAMFrequency::class.java)
    }

    @TypeConverter
    fun frequencyToJson(frequency: IAMFrequency?): String? {
        return frequency?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun fromTriggerJson(value: String?): IAMTriggerCondition? {
        if (value == null) return null
        return gson.fromJson(value, IAMTriggerCondition::class.java)
    }

    @TypeConverter
    fun triggerToJson(trigger: IAMTriggerCondition?): String? {
        return trigger?.let { gson.toJson(it) }
    }
} 