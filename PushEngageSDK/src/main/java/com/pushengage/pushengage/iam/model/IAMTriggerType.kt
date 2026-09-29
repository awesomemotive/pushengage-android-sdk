package com.pushengage.pushengage.iam.model

/**
 * Represents the types of triggers for in-app messages
 */
internal enum class IAMTriggerType {
    AUTO,    // Triggered automatically by predefined SDK events (e.g., app open)
    CUSTOM   // Triggered explicitly by the host app calling an SDK method
} 