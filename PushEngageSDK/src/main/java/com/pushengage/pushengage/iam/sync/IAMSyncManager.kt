package com.pushengage.pushengage.iam.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.iam.model.IAMAnalyticsEvent
import com.pushengage.pushengage.iam.model.IAMDisplayRecord
import com.pushengage.pushengage.iam.network.IAMCampaignSyncResult
import com.pushengage.pushengage.iam.network.IAMNetworkCallback
import com.pushengage.pushengage.iam.network.IAMNetworkService
import com.pushengage.pushengage.iam.network.IAMNetworkServiceImpl
import com.pushengage.pushengage.iam.network.IAMReportOutcome
import com.pushengage.pushengage.iam.repository.IAMRepository
import com.pushengage.pushengage.helper.PELogger
import java.util.concurrent.TimeUnit

/**
 * Synchronizes IAM campaigns and analytics with the backend. Uses the real
 * [IAMNetworkServiceImpl] by default; [networkService] is injectable so tests
 * can substitute a fake.
 */
internal class IAMSyncManager @JvmOverloads constructor(
    private val context: Context,
    private val repository: IAMRepository,
    private val networkService: IAMNetworkService = IAMNetworkServiceImpl(context)
) {
    private val prefs: PEPrefs = PEPrefs(context.applicationContext)
    
    /**
     * Interface for sync operation callbacks with Java compatibility
     */
    @FunctionalInterface
    interface SyncCallback {
        /**
         * Called when sync completes
         * @param success Whether the sync was successful
         */
        fun onComplete(success: Boolean)
    }
    
    /**
     * Synchronizes messages from the server immediately
     * @param siteId The site ID
     * @param callback Optional callback to be notified of sync completion
     */
    fun syncMessages(siteId: String, callback: SyncCallback? = null) {
        PELogger.debug("Starting campaign sync for site: $siteId")

        val siteKey = prefs.siteKey?.takeIf { it.isNotEmpty() } ?: siteId

        networkService.syncCampaigns(siteKey, prefs.iamVersion, object : IAMNetworkCallback<IAMCampaignSyncResult> {
            override fun onSuccess(data: IAMCampaignSyncResult) {
                when (data.status) {
                    IAMCampaignSyncResult.Status.ACTIVE -> {
                        PELogger.debug("Sync ACTIVE: ${data.campaigns.size} campaigns, version ${data.version}")
                        prefs.iamStatus = PEConstants.ACTIVE
                        // Persist the analytics host (from the metadata api block)
                        // for the standalone analytics uploader. The campaigns host
                        // is resolved fresh from metadata each sync, so it isn't
                        // persisted — and must never overwrite the metadata base host.
                        data.analyticsHost?.let { prefs.iamAnalyticsUrl = it }
                        // Full-replace so paused/deleted campaigns are dropped while
                        // surviving ones keep their display history. Fire the callback
                        // only AFTER the write commits, so app-open auto-trigger
                        // evaluation reads the fresh campaigns.
                        repository.replaceAllMessages(data.campaigns) { written ->
                            // The version pointer is this SDK's "we already hold these
                            // campaigns" marker: once bumped, the next sync sees a match
                            // and returns UNCHANGED without re-fetching. Advancing it
                            // before the write commits would therefore turn a retryable
                            // persist failure into a permanent one, so it follows the
                            // commit rather than preceding it.
                            if (written) {
                                data.version?.let { prefs.iamVersion = it }
                            } else {
                                PELogger.error(
                                    "IAM campaign persist failed; keeping version " +
                                        "${prefs.iamVersion} so the next sync retries"
                                )
                            }
                            callback?.onComplete(written)
                        }
                    }
                    IAMCampaignSyncResult.Status.INACTIVE -> {
                        // iam_status is a per-SITE feature gate, not a campaign one: the
                        // backend collapses site status, payment and plan checks into it,
                        // so it is expected to go off and come back on.
                        //
                        // Recording the status and letting the display path refuse is
                        // therefore the whole job. Deleting the stored campaigns instead
                        // was worse in two ways. It took the frequency-cap history with
                        // them, so a campaign already shown could show again once the
                        // site came back. And it could not recover: the version pointer
                        // was left untouched, so the next sync found a matching version,
                        // returned UNCHANGED, and never re-fetched — leaving the store
                        // empty and in-app messaging dead until someone happened to edit
                        // a campaign on the dashboard.
                        PELogger.debug("Sync INACTIVE: IAM is off for this site, keeping stored campaigns")
                        prefs.iamStatus = data.status.name.lowercase()
                        callback?.onComplete(true)
                    }
                    IAMCampaignSyncResult.Status.UNCHANGED -> {
                        PELogger.debug("Sync UNCHANGED: nothing to fetch")
                        // UNCHANGED is only reachable past the status gate, so the site
                        // is active — and saying so matters, because this is the branch a
                        // site that was switched off and back on lands in. The version
                        // did not move while it was off, so there is nothing to fetch;
                        // without re-recording the status the gate would stay closed on
                        // campaigns that are sitting right there.
                        prefs.iamStatus = PEConstants.ACTIVE
                        // Store already holds the current version's campaigns.
                        callback?.onComplete(true)
                    }
                }
            }

            override fun onError(error: Exception) {
                PELogger.error("Error syncing campaigns: ${error.message}")
                callback?.onComplete(false)
            }
        })
    }
    
    /**
     * Reports analytics data to the server
     * @param records The display records to report
     */
    fun reportAnalytics(records: List<IAMDisplayRecord>) {
        if (records.isEmpty()) {
            return
        }
        
        PELogger.debug("Reporting ${records.size} analytics records to server")
        
        networkService.reportAnalytics(records, object : IAMNetworkCallback<IAMReportOutcome> {
            override fun onSuccess(data: IAMReportOutcome) {
                PELogger.debug(
                    "Reported ${data.syncedIds.size}/${records.size} analytics records" +
                        if (data.allSynced) "" else " (partial — rest retried next pass)"
                )
                // Mark ONLY the delivered records — marking the whole batch on a
                // partial delivery would lose the undelivered ones, and marking
                // nothing would re-POST (double-count) the delivered ones.
                data.syncedIds.forEach { repository.markRecordReported(it) }
            }

            override fun onError(error: Exception) {
                PELogger.error("Error reporting analytics: ${error.message}")
                // Analytics reporting failures are non-critical, so we just log them
            }
        })
    }
    
    /**
     * Reports analytics events to the server
     * @param events The analytics events to report
     */
    fun reportAnalyticsEvents(events: List<IAMAnalyticsEvent>) {
        if (events.isEmpty()) {
            return
        }
        
        PELogger.debug("Reporting ${events.size} analytics events to server")
        
        networkService.reportAnalyticsEvents(events, object : IAMNetworkCallback<IAMReportOutcome> {
            override fun onSuccess(data: IAMReportOutcome) {
                PELogger.debug(
                    "Reported ${data.syncedIds.size}/${events.size} analytics events" +
                        if (data.allSynced) "" else " (partial — rest retried next pass)"
                )
                // Mark ONLY the delivered/local-only events (see reportAnalytics).
                if (data.syncedIds.isNotEmpty()) {
                    repository.markAnalyticsEventsAsSynced(data.syncedIds)
                }
                if (!data.allSynced) {
                    // The batch stopped on the first event missing from syncedIds; the
                    // ones behind it were never attempted, so only it is charged.
                    val settled = data.syncedIds.toSet()
                    events.firstOrNull { it.id !in settled }?.let { blocked ->
                        repository.chargeFailedUpload(blocked.id, MAX_UPLOAD_ATTEMPTS)
                    }
                }
            }

            override fun onError(error: Exception) {
                PELogger.error("Error reporting analytics events: ${error.message}")
                // Analytics reporting failures are non-critical, so we just log them
            }
        })
    }
    
    /**
     * Schedules periodic background sync of messages
     * Uses WorkManager to ensure reliable background execution
     */
    fun schedulePeriodicSync(siteId: String) {
        PELogger.debug("Scheduling periodic message sync")
        
        // Create network constraint - only run when network is available
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        // Create periodic work request
        val syncWorkRequest = PeriodicWorkRequest.Builder(
            IAMSyncWorker::class.java,
            SYNC_INTERVAL_HOURS, TimeUnit.HOURS,
            SYNC_FLEX_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag("iam_sync")
            .build()
        
        // Schedule unique periodic work. Guard against WorkManager not being
        // initialized (e.g. the host app disabled the default initializer) so a
        // scheduling failure never crashes SDK startup.
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "iam_periodic_sync",
                ExistingPeriodicWorkPolicy.REPLACE,
                syncWorkRequest
            )
        } catch (e: Exception) {
            PELogger.error("Could not schedule IAM periodic sync: ${e.message}", e)
        }
    }

    /**
     * Cancels periodic background sync
     */
    fun cancelPeriodicSync() {
        PELogger.debug("Canceling periodic message sync")
        try {
            WorkManager.getInstance(context).cancelUniqueWork("iam_periodic_sync")
        } catch (e: Exception) {
            PELogger.error("Could not cancel IAM periodic sync: ${e.message}", e)
        }
    }
    
    companion object {
        // Sync every 6 hours by default, with 30 minutes of flex time
        private const val SYNC_INTERVAL_HOURS = 6L
        private const val SYNC_FLEX_INTERVAL_MINUTES = 30L

        /**
         * Failed attempts an analytics event gets before it is dropped.
         *
         * Only the event a batch stopped on is charged, once per flush, and a flush
         * happens per tracked event — so a low bound would delete events during a short
         * outage that would have uploaded fine later. High enough to clear a busy session
         * or two against a down backend, low enough that a permanent failure stops
         * wedging the queue forever. Matches the iOS bound.
         */
        private const val MAX_UPLOAD_ATTEMPTS = 25
    }
} 