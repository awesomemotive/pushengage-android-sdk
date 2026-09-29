package com.pushengage.pushengage.iam.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Represents a network response for an in-app message
 */
internal data class IAMMessageResponse(
    @SerializedName("id") @JvmField public val id: String,
    @SerializedName("position") @JvmField public val position: IAMPosition,
    @SerializedName("htmlContent") @JvmField public val htmlContent: String,
    @SerializedName("displayDuration") @JvmField public val displayDuration: Long, // seconds
    @SerializedName("shouldDismissOnTap") @JvmField public val shouldDismissOnTap: Boolean,
    @SerializedName("actions") @JvmField public val actions: Map<String, IAMAction>,
    @SerializedName("startDate") @JvmField public val startDate: Date?,
    @SerializedName("endDate") @JvmField public val endDate: Date?,
    @SerializedName("priority") @JvmField public val priority: Int, // Lower value means higher priority (e.g., 1=High, 3=Low)
    /**
     * Audience targeting, kept as raw JSON rather than a typed model.
     *
     * Two reasons. First, this value is only ever written straight back out
     * (`gson.toJson`) into the `audience_json` column, so a typed model would add
     * no behaviour — and having both a model *and* the evaluator interpret the
     * shape invites the two to disagree. [IAMRulesEngine] is the single place that
     * understands it, and it accepts both the current
     * `{ match, groups[ { match, conditions[] } ] }` form and the legacy flat
     * condition array.
     *
     * Second, isolation: campaigns are parsed as one list, so a typed model would
     * let a single malformed `audience` throw and take **every** campaign in the
     * response down with it. Held as raw JSON, a bad audience fails closed for that
     * one campaign at display time and the rest still sync.
     */
    @SerializedName("audience") @JvmField public val audience: JsonElement?,
    @SerializedName("frequency") @JvmField public val frequency: IAMFrequency?,
    @SerializedName("trigger") @JvmField public val trigger: IAMTriggerCondition
) 