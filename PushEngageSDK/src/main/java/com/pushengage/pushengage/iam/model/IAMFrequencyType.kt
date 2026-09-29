package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Represents the types of frequency settings for in-app messages.
 *
 * Wire values follow the cross-platform IAM wire format. Alternates keep
 * JSON serialized by older SDK builds (enum names) and the iOS-legacy
 * hyphenated value readable.
 */
internal enum class IAMFrequencyType {
    @SerializedName(value = "one_time", alternate = ["ONE_TIME", "one-time"])
    ONE_TIME, // Show exactly once

    @SerializedName(value = "recurring", alternate = ["RECURRING"])
    RECURRING, // Show repeatedly at intervals

    @SerializedName(value = "capped", alternate = ["CAPPED"])
    CAPPED // Show up to a maximum count
}
