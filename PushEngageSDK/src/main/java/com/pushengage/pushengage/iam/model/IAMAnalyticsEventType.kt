package com.pushengage.pushengage.iam.model

/**
 * Types of analytics event queued for upload in `iam_analytics_events`.
 *
 * Only [CLICK] exists: it is the queued click trail, so a tap made while offline
 * is uploaded on the next drain instead of being lost. Impressions are not here —
 * they are tracked as display records, which frequency capping also counts.
 *
 * The stored `event_type` column is retained even though a single value is
 * written today, so adding a second type needs no schema migration.
 */
internal enum class IAMAnalyticsEventType {
    CLICK
}
