package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Represents the position where an in-app message should be displayed.
 *
 * Wire values are lowercase on the wire; alternates keep JSON serialized
 * by older SDK builds (enum names) readable. Room storage
 * is unaffected — it persists positions via [IAMPosition.name].
 */
internal enum class IAMPosition {
    @SerializedName(value = "center", alternate = ["CENTER"])
    CENTER, // Modal dialogs

    @SerializedName(value = "top", alternate = ["TOP"])
    TOP, // Banner notifications

    @SerializedName(value = "bottom", alternate = ["BOTTOM"])
    BOTTOM, // Toast-style messages

    @SerializedName(value = "full", alternate = ["FULL"])
    FULL // Full-screen experiences
}
