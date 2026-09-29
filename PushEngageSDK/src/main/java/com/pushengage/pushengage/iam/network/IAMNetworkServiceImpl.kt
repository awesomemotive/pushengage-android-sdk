package com.pushengage.pushengage.iam.network

import android.content.Context
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent
import com.pushengage.pushengage.iam.model.IAMAnalyticsPayload
import com.pushengage.pushengage.iam.model.IAMDisplayRecord

/**
 * Real IAM backend implementation. Network calls block on the
 * calling thread via Retrofit's [retrofit2.Call.execute]; all entry points are
 * invoked from background threads (sync worker / analytics executor), matching
 * how the mock is used.
 *
 * Host discovery: metadata is fetched from the environment's BASE_CDN host, and
 * its response advertises the campaigns (`backend_cdn`) and analytics
 * (`iam_analytics`) hosts, which are persisted for later analytics posts.
 */
internal class IAMNetworkServiceImpl(context: Context) : IAMNetworkService {

    private val appContext = context.applicationContext
    private val prefs = PEPrefs(appContext)

    private companion object {
        const val CLICK_EVENT_TYPE = "CLICK"

        /** Status codes a payload will never recover from, so the row is dropped. */
        val PERMANENT_REJECTION_CODES = setOf(400, 409, 413, 422)
    }

    override fun syncCampaigns(
        siteKey: String,
        storedVersion: String?,
        callback: IAMNetworkCallback<IAMCampaignSyncResult>
    ) {
        try {
            // Always fetch metadata — it's the cheap discovery document that carries
            // the version, and the version is only observable by fetching it. The
            // version gate below still prevents a redundant campaigns (CDN) fetch
            // when nothing changed, so a dashboard edit is picked up on the next
            // sync instead of being hidden behind a client-side freshness cache.
            //
            // Responses are wrapped in the standard PushEngage envelope; the useful
            // payload is `.data`.
            val host = metadataBaseUrl()
            val metadataResponse = IAMRestClient.api(host).getMetadata(siteKey).execute()
            val metadata = metadataResponse.body()?.data
            if (!metadataResponse.isSuccessful || metadata == null) {
                callback.onError(Exception("Metadata fetch failed: HTTP ${metadataResponse.code()}"))
                return
            }

            // Gate on status.
            if (!PEConstants.ACTIVE.equals(metadata.iamStatus, ignoreCase = true)) {
                PELogger.debug(
                    "IAM status is '${metadata.iamStatus}' — not active; stored campaigns " +
                        "are kept and simply will not display"
                )
                callback.onSuccess(IAMCampaignSyncResult.inactive())
                return
            }

            // Gate on version — nothing changed since the last successful sync.
            val version = metadata.version
            if (version.isNullOrEmpty()) {
                callback.onError(Exception("Metadata missing version"))
                return
            }
            if (version == storedVersion) {
                PELogger.debug("IAM version unchanged ($version) — no campaign fetch needed")
                callback.onSuccess(IAMCampaignSyncResult.unchanged())
                return
            }

            // Follow the metadata `api` block for host discovery (campaigns from
            // backend_cdn, analytics from iam_analytics), falling back to the IAM
            // base host when a field is missing. The envelope's `data` is the
            // campaign array directly.
            val campaignsHost = metadata.api?.backendCdn?.takeIf { it.isNotEmpty() } ?: host
            val analyticsHost = metadata.api?.iamAnalytics?.takeIf { it.isNotEmpty() } ?: host

            val campaignsResponse = IAMRestClient.api(campaignsHost).getCampaigns(version, siteKey).execute()
            if (!campaignsResponse.isSuccessful) {
                callback.onError(Exception("Campaigns fetch failed: HTTP ${campaignsResponse.code()}"))
                return
            }
            // Coercing an unreadable body to emptyList() here would be indistinguishable
            // from the server deliberately sending zero campaigns, and the caller acts on
            // "zero campaigns" by purging the local store. Since the new version is
            // recorded on success, the next sync would then see a version match and skip
            // the re-fetch — so one 200 with a malformed body would delete every campaign
            // for the site until the dashboard bumped the version. Fail like the metadata
            // fetch above does, which leaves both the store and the version untouched.
            val campaignsBody = campaignsResponse.body()
            val campaigns = campaignsBody?.data
            if (campaigns == null) {
                callback.onError(
                    Exception("Campaigns response missing data: HTTP ${campaignsResponse.code()}")
                )
                return
            }
            PELogger.debug("Fetched ${campaigns.size} campaigns for version $version from $campaignsHost")
            callback.onSuccess(
                IAMCampaignSyncResult(
                    status = IAMCampaignSyncResult.Status.ACTIVE,
                    version = version,
                    campaigns = campaigns,
                    campaignsHost = campaignsHost,
                    analyticsHost = analyticsHost
                )
            )
        } catch (e: Exception) {
            PELogger.error("IAM syncCampaigns failed: ${e.message}", e)
            callback.onError(e)
        }
    }

    override fun reportAnalytics(records: List<IAMDisplayRecord>, callback: IAMNetworkCallback<IAMReportOutcome>) {
        // Prefer the metadata-advertised analytics host; fall back to the dexter
        // host, which is where analytics live when metadata doesn't split hosts.
        val host = prefs.iamAnalyticsUrl?.takeIf { it.isNotEmpty() } ?: metadataBaseUrl()
        val siteKey = prefs.siteKey
        if (siteKey.isNullOrEmpty()) {
            callback.onError(Exception("IAM site_key not available yet"))
            return
        }
        // Per-record accounting: a batch that fails mid-way still reports the
        // records the backend DID receive, so the caller marks exactly those
        // synced and the retry re-sends only the rest — all-or-nothing marking
        // re-POSTed (double-counted) the delivered ones on every retry. The
        // loop aborts on the first failure (it is most likely network-wide;
        // no point burning a timeout per remaining record).
        val api = try {
            IAMRestClient.api(host)
        } catch (e: Exception) {
            callback.onError(e) // prerequisite failure — nothing attempted
            return
        }
        val delivered = mutableListOf<Long>()
        var failed = false
        for (record in records) {
            try {
                // One impression per display. Clicks travel separately as queued
                // CLICK events (a click can land after this record has already been
                // synced, so it cannot ride the impression record).
                post(api, siteKey, IAMAnalyticsPayload(record.messageId, impression = 1, click = 0))
                delivered.add(record.id)
            } catch (e: Exception) {
                if (isPermanentRejection(e)) {
                    // Accounted for, not delivered: retrying keeps this row at the head
                    // of every pass and blocks everything queued behind it.
                    PELogger.error(
                        "IAM reportAnalytics: record ${record.id} permanently rejected " +
                            "(${e.message}) — dropping it rather than blocking the queue", e
                    )
                    delivered.add(record.id)
                    continue
                }
                PELogger.error("IAM reportAnalytics: record ${record.id} failed — ${e.message}", e)
                failed = true
                break
            }
        }
        callback.onSuccess(IAMReportOutcome(delivered, allSynced = !failed))
    }

    override fun markMessagesSeen(messageIds: List<String>, callback: IAMNetworkCallback<Boolean>) {
        // No endpoint in the v1 contract.
        callback.onSuccess(true)
    }

    override fun reportAnalyticsEvents(events: List<IAMAnalyticsEvent>, callback: IAMNetworkCallback<IAMReportOutcome>) {
        // CLICK events are the queued click trail (e.g. taps made offline) and are
        // uploaded as click POSTs. Only clicks are produced, but any other type is
        // treated as synced-without-delivery rather than posted: an unpostable row
        // would fail every pass and, because the loop breaks on failure, block
        // every click queued behind it.
        val clicks = events.filter { CLICK_EVENT_TYPE.equals(it.eventType, ignoreCase = true) }
        val localOnlyIds = events.filter { !CLICK_EVENT_TYPE.equals(it.eventType, ignoreCase = true) }.map { it.id }
        if (clicks.isEmpty()) {
            callback.onSuccess(IAMReportOutcome(localOnlyIds, allSynced = true))
            return
        }
        val host = prefs.iamAnalyticsUrl?.takeIf { it.isNotEmpty() } ?: metadataBaseUrl()
        val siteKey = prefs.siteKey
        if (siteKey.isNullOrEmpty()) {
            callback.onError(Exception("IAM site_key not available yet"))
            return
        }
        // Per-event accounting, mirroring reportAnalytics: delivered clicks
        // (plus the local-only events, which need no delivery) are reported as
        // synced; an undelivered click stays queued for the next pass.
        val api = try {
            IAMRestClient.api(host)
        } catch (e: Exception) {
            callback.onError(e) // prerequisite failure — nothing attempted
            return
        }
        val delivered = localOnlyIds.toMutableList()
        var failed = false
        for (event in clicks) {
            try {
                post(
                    api, siteKey,
                    IAMAnalyticsPayload(
                        campaignId = event.messageId,
                        impression = 0,
                        click = 1,
                        btnId = event.btnId,
                        btnText = event.btnText,
                        btnType = event.btnType
                    )
                )
                delivered.add(event.id)
            } catch (e: Exception) {
                if (isPermanentRejection(e)) {
                    PELogger.error(
                        "IAM reportAnalyticsEvents: click ${event.id} permanently rejected " +
                            "(${e.message}) — dropping it rather than blocking the queue", e
                    )
                    delivered.add(event.id)
                    continue
                }
                PELogger.error("IAM reportAnalyticsEvents: click ${event.id} failed — ${e.message}", e)
                failed = true
                break
            }
        }
        callback.onSuccess(IAMReportOutcome(delivered, allSynced = !failed))
    }


    private fun post(api: IAMApi, siteKey: String, payload: IAMAnalyticsPayload) {
        val response = api.postAnalytics(siteKey, payload).execute()
        if (!response.isSuccessful) {
            throw IAMHttpException(response.code())
        }
    }

    /**
     * True when [e] is a rejection this payload will never recover from, so the row is
     * dropped rather than retried.
     *
     * Deliberately narrow. "Any 4xx" would also drop 429 (rate limited) and 408
     * (timeout), which are retryable — discarding analytics exactly when the backend is
     * under load. 401/403/404 are systemic misconfiguration rather than a bad row, and a
     * later config fix should let the backlog through, so they keep retrying too.
     */
    private fun isPermanentRejection(e: Exception): Boolean =
        e is IAMHttpException && e.code in PERMANENT_REJECTION_CODES


    /**
     * IAM base host: the host persisted from a prior sync if present, else the
     * dedicated per-environment IAM constant. IAM has its own host (not the
     * notification CDN). Mirrors RestClient's prefs-then-env pattern.
     */
    private fun metadataBaseUrl(): String {
        val override = prefs.iamBaseUrl
        if (!override.isNullOrEmpty()) return override
        return if (PEConstants.PROD.equals(prefs.environment, ignoreCase = true)) {
            PEConstants.PROD_IAM_URL
        } else {
            PEConstants.STG_IAM_URL
        }
    }
}
