package com.pushengage.pushengage.iam.analytics

import android.content.Context
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.model.IAMAnalyticsEventType
import com.pushengage.pushengage.iam.network.IAMNetworkStateManager
import com.pushengage.pushengage.iam.network.IAMNetworkStateObserver
import com.pushengage.pushengage.iam.repository.IAMRepository
import com.pushengage.pushengage.iam.sync.IAMSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager class for tracking analytics related to in-app messages.
 *
 * The collaborators default to the production implementations; they are
 * injectable (same pattern as [IAMSyncManager]'s network service) so tests can
 * substitute a scripted sync manager, a controllable network-state source, or
 * a captured scheduler.
 */
internal class IAMAnalyticsManager @JvmOverloads constructor(
    context: Context,
    private val repository: IAMRepository,
    injectedSyncManager: IAMSyncManager? = null,
    injectedNetworkStateManager: IAMNetworkStateManager? = null,
    injectedExecutor: ScheduledExecutorService? = null
) : IAMNetworkStateObserver {

    // This manager outlives any single screen (periodic executor + singleton
    // observer registration), so it must never retain an Activity — resolve
    // the application context regardless of what the caller passed.
    private val appContext = context.applicationContext ?: context
    private val syncManager = injectedSyncManager ?: IAMSyncManager(appContext, repository)
    private val analyticsExecutor: ScheduledExecutorService =
        injectedExecutor ?: Executors.newSingleThreadScheduledExecutor()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val networkStateManager =
        injectedNetworkStateManager ?: IAMNetworkStateManager.getInstance(appContext)

    // Constants
    companion object {
        private const val ANALYTICS_SYNC_INTERVAL_MINUTES = 30L
        private const val BATCH_SIZE = 50 // Number of records to sync at once

        // Shared across ALL instances (the action handler, display manager and
        // controller each build their own IAMAnalyticsManager). A per-instance
        // guard let two managers read the same unsynced records and POST them
        // concurrently — double-counting impressions. One process-wide gate
        // serializes syncs so each record is reported once.
        private val isSyncing = AtomicBoolean(false)

        /**
         * One gated drain of pending analytics (display records + local events),
         * shared by EVERY reporting path — the instance passes (timer tick,
         * reconnect, tracked events) and [com.pushengage.pushengage.iam.sync.IAMSyncWorker],
         * which previously bypassed the gate and could re-POST records an
         * in-process pass was already reporting.
         *
         * The gate is released via [IAMRepository.runAfterPendingWrites] —
         * strictly AFTER the mark-as-synced writes enqueued by the report
         * callbacks have committed (the sync manager invokes its callbacks
         * synchronously). Releasing on return re-opened a window in which the
         * next pass re-read, and re-POSTed, records that were reported but not
         * yet marked.
         */
        @JvmStatic
        internal fun drainPendingAnalytics(repository: IAMRepository, syncManager: IAMSyncManager) {
            if (!isSyncing.compareAndSet(false, true)) {
                return
            }
            try {
                val unsyncedRecords = repository.unsyncedDisplayRecords
                if (unsyncedRecords.isNotEmpty()) {
                    PELogger.debug("IAMAnalytics: Syncing ${unsyncedRecords.size} display records")
                    syncManager.reportAnalytics(unsyncedRecords)
                }

                val analyticsEvents = repository.getUnsyncedAnalyticsEvents(BATCH_SIZE)
                if (analyticsEvents.isNotEmpty()) {
                    PELogger.debug("IAMAnalytics: Syncing ${analyticsEvents.size} analytics events")
                    syncManager.reportAnalyticsEvents(analyticsEvents)
                }
            } catch (e: Exception) {
                PELogger.error("IAMAnalytics: Error during analytics sync: ${e.message}", e)
            } finally {
                repository.runAfterPendingWrites { isSyncing.set(false) }
            }
        }
    }
    
    /**
     * Initializes analytics tracking
     * Sets up periodic syncing of analytics data
     */
    init {
        // Register for network state changes
        networkStateManager.addObserver(this)
        
        // Schedule periodic analytics syncing
        analyticsExecutor.scheduleWithFixedDelay({
            processPendingEvents()
        }, ANALYTICS_SYNC_INTERVAL_MINUTES, ANALYTICS_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }
    
    /**
     * Records a button click, independently of the display-record/impression
     * flow so it isn't lost when the impression has already been synced.
     *
     * Persist-first: the click is stored as a CLICK event carrying the full
     * button payload, then uploaded through the pending-events pipeline
     * ([processPendingEvents]) — immediately when online, otherwise on
     * reconnect / the periodic tick / the next app open. An offline tap is
     * therefore queued, not dropped.
     *
     * @param messageId campaign id (btn analytics `campaign_id`)
     * @param actionId  the actions-map key the HTML invoked (`btn_id`)
     * @param label     the action's label (`btn_text`)
     * @param actionType the action's wire value, e.g. `open_url` (`btn_type`)
     */
    fun recordClick(messageId: String, actionId: String, label: String?, actionType: String?) {
        trackEvent(IAMAnalyticsEventType.CLICK, messageId, actionId, label, actionType)
    }

    /**
     * Records the start of a message display.
     *
     * The returned id identifies the row that carries the impression; the display
     * manager writes the duration onto it when the message closes.
     *
     * @param messageId ID of the message being displayed
     * @return the display-record id, or -1 when the local write failed
     */
    fun recordImpression(messageId: String): Long {
        PELogger.debug("IAMAnalytics: Recording impression for message: $messageId")
        
        // Create a new display record
        val recordId = repository.recordMessageDisplay(messageId)
        
        // If recording locally fails, return -1
        if (recordId == -1L) {
            PELogger.error("IAMAnalytics: Failed to record impression for message: $messageId")
            return -1
        }
        
        return recordId
    }
    
    /**
     * Processes pending analytics events in batches
     */
    private fun processPendingEvents() {
        // If offline or already syncing, don't proceed (the drain re-checks the
        // gate atomically — this is just a cheap early skip).
        if (!networkStateManager.isConnected || isSyncing.get()) {
            return
        }

        coroutineScope.launch {
            drainPendingAnalytics(repository, syncManager)
        }
    }
    
    /**
     * Forces an immediate sync of analytics data
     */
    fun forceSyncAnalytics() {
        processPendingEvents()
    }
    
    /**
     * Tracks an analytics event
     * @param type Type of event
     * @param messageId ID of the message
     * @param metadata Additional data about the event
     */
    private fun trackEvent(
        type: IAMAnalyticsEventType,
        messageId: String,
        btnId: String?,
        btnText: String?,
        btnType: String?
    ) {
        coroutineScope.launch {
            try {
                repository.recordAnalyticsEvent(type.name, messageId, btnId, btnText, btnType)
                
                // If we have network and not already syncing, process events
                if (networkStateManager.isConnected && !isSyncing.get()) {
                    processPendingEvents()
                }
            } catch (e: Exception) {
                PELogger.error("IAMAnalytics: Failed to track ${type.name} event: ${e.message}", e)
            }
        }
    }
    
    /**
     * Handles network state changes
     */
    override fun networkStateDidChange(isConnected: Boolean) {
        if (isConnected) {
            // Process pending events when connection is restored
            processPendingEvents()
        }
    }
    
    /**
     * Cleans up resources when the manager is no longer needed
     */
    fun shutdown() {
        analyticsExecutor.shutdown()
        networkStateManager.removeObserver(this)
    }
} 