package com.pushengage.pushengage.iam.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pushengage.pushengage.iam.analytics.IAMAnalyticsManager
import com.pushengage.pushengage.iam.repository.IAMRepository
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.helper.PEPrefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for synchronizing IAM messages in the background
 */
internal class IAMSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    
    override fun doWork(): Result {
        PELogger.debug("Starting background sync of IAM messages")
        
        try {
            // IAM fetches campaigns with the site_key (App ID) alone — it does not
            // need a push subscription. Gate on the App ID, not siteId, so the sync
            // runs even without notification permission.
            val pePrefs = PEPrefs(applicationContext)
            val siteKey = pePrefs.siteKey
            if (siteKey.isNullOrEmpty()) {
                PELogger.debug("No App ID configured, skipping IAM sync")
                return Result.failure()
            }

            // Get repository instance
            val repository = IAMRepository.getInstance(applicationContext)

            // Create sync manager
            val syncManager = IAMSyncManager(applicationContext, repository)

            // Create a latch to wait for sync completion
            val syncLatch = CountDownLatch(1)
            var syncResult = false

            // Perform sync using the App ID
            syncManager.syncMessages(siteKey, object : IAMSyncManager.SyncCallback {
                override fun onComplete(success: Boolean) {
                    syncResult = success
                    syncLatch.countDown()
                }
            })
            
            // Wait for sync to complete with timeout
            val completed = syncLatch.await(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!completed) {
                PELogger.error("Sync timed out after $SYNC_TIMEOUT_SECONDS seconds")
                return Result.retry()
            }
            
            // Report unreported analytics through the shared process-wide gate.
            // Reading + posting directly here bypassed the gate, so a worker
            // running while an in-process pass was mid-flight could POST the
            // same records twice.
            IAMAnalyticsManager.drainPendingAnalytics(repository, syncManager)
            
            PELogger.debug("Background sync completed with result: $syncResult")
            
            return if (syncResult) Result.success() else Result.retry()
        } catch (e: Exception) {
            PELogger.error("Error during background sync: ${e.message}")
            return Result.retry()
        }
    }
    
    companion object {
        private const val SYNC_TIMEOUT_SECONDS = 30L
    }
} 