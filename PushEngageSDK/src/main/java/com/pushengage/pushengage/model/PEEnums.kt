package com.pushengage.pushengage.model

internal enum class PENotificationPriority(val priority: String) {
    HIGH("high"),
    MIN("min")
}

internal enum class PENotificationVisibility(val visibility: String) {
    PRIVATE("VISIBILITY_PRIVATE"),
    PUBLIC("VISIBILITY_PUBLIC"),
    SECRET("VISIBILITY_SECRET")
}

internal enum class PEChannelImportance(val importance: String) {
    HIGH("IMPORTANCE_HIGH"),
    DEFAULT("IMPORTANCE_DEFAULT"),
    LOW("IMPORTANCE_LOW"),
    MIN("IMPORTANCE_MIN")
}