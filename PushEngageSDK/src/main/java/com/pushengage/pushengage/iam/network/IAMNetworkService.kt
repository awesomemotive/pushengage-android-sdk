package com.pushengage.pushengage.iam.network

import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent
import com.pushengage.pushengage.iam.model.IAMDisplayRecord

/**
 * Network surface for In-App Messaging, implemented in production by
 * [IAMNetworkServiceImpl]. Kept as an interface so tests can substitute a fake.
 */
internal interface IAMNetworkService {

    /**
     * Runs the metadata-gated campaign sync: checks status and
     * version, and on change returns the fresh campaign set plus the hosts to
     * persist. See [IAMCampaignSyncResult] for the outcomes.
     *
     * @param siteKey the site App ID
     * @param storedVersion the last version persisted locally (null/empty if none)
     */
    fun syncCampaigns(
        siteKey: String,
        storedVersion: String?,
        callback: IAMNetworkCallback<IAMCampaignSyncResult>
    )

    /**
     * Reports display records (impressions) to the analytics endpoint. Clicks
     * travel separately, as queued CLICK events via [reportAnalyticsEvents],
     * because a tap can occur after the impression has already been synced.
     *
     * The success outcome carries the ids actually delivered — a batch can
     * partially succeed, and only the delivered records may be marked synced.
     * [IAMNetworkCallback.onError] is reserved for prerequisite failures
     * (e.g. no site key) where nothing was attempted.
     */
    fun reportAnalytics(records: List<IAMDisplayRecord>, callback: IAMNetworkCallback<IAMReportOutcome>)

    /**
     * Marks messages as seen on the server. No endpoint in the v1 contract —
     * implementations may no-op.
     */
    fun markMessagesSeen(messageIds: List<String>, callback: IAMNetworkCallback<Boolean>)

    /**
     * Reports queued CLICK events. As with [reportAnalytics], the outcome's ids
     * are the events safe to mark synced, so an undelivered click stays queued.
     *
     * Any row whose type is not CLICK counts as synced without delivery: only
     * clicks are produced today, and treating an unexpected type as deliverable
     * would let one unpostable row block the queue indefinitely.
     */
    fun reportAnalyticsEvents(events: List<IAMAnalyticsEvent>, callback: IAMNetworkCallback<IAMReportOutcome>)
}
