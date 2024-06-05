package com.pushengage.pushengage.model.request

import com.google.gson.annotations.SerializedName

data class Goal(
    val name: String,
    val count: Int?,
    val value: Double?
)

internal data class GoalRequest(
    @SerializedName("site_id")
    val siteId: Long,
    @SerializedName("device_token_hash")
    val deviceTokenHash: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("count")
    val count: Int?,
    @SerializedName("value")
    val value: Double?
)