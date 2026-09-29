package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Represents frequency settings for an in-app message
 */
internal data class IAMFrequency(
    @SerializedName("type") val type: IAMFrequencyType,
    @SerializedName("count") val count: Int? = null, // Max count for CAPPED
    @SerializedName("interval") val interval: Long? = null // Seconds between recurring displays
) 