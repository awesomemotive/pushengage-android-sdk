package com.pushengage.pushengage.iam.network

/**
 * Result of an analytics upload batch, with per-item accounting.
 *
 * A batch can partially succeed (e.g. the network drops mid-batch): the caller
 * must mark exactly [syncedIds] as reported so a later retry re-sends only what
 * the backend never received — batch-level all-or-nothing marking re-POSTed the
 * already-delivered items on every retry.
 *
 * @param syncedIds ids (display-record ids or analytics-event ids) confirmed
 *   delivered — or, for local-only event types, requiring no delivery.
 * @param allSynced true when every item in the batch is accounted for.
 */
internal data class IAMReportOutcome(
    val syncedIds: List<Long>,
    val allSynced: Boolean
)
