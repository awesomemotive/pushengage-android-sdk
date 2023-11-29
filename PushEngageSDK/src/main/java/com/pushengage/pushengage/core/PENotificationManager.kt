package com.pushengage.pushengage.core

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat.Builder
import com.google.gson.Gson
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import com.pushengage.pushengage.model.request.FetchRequest
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest
import com.pushengage.pushengage.notificationchannel.PENotificationChannelHelper
import com.pushengage.pushengage.notificationchannel.PENotificationChannelHelperType
import com.pushengage.pushengage.notificationhandling.PENotificationBuilder
import com.pushengage.pushengage.notificationhandling.PENotificationBuilderType
import com.pushengage.pushengage.servicehandling.PEServiceHandler
import com.pushengage.pushengage.servicehandling.PEServiceHandlerType


internal interface PENotificationManagerType {
    /**
     * Create notification builder
     */
    fun createNotificationBuilder(): Builder

    /**
     * Set channel information
     * @param channelId Id of the notification channel
     * @param payload FCM payload
     * @param isDefault Should resort to default notification channel
     * @param notificationBuilder Object of notification builder
     */
    fun setChannelInformation(channelId: String,
                              payload: FCMPayloadModel,
                              isDefault: Boolean,
                              notificationBuilder: Builder,
                              didFetchChannelInfo: Boolean)

    /**
     * API call to fetch sponsored notifications
     *
     * @param fetchRequest Request model
     * @param channelId Notification channel id
     * @param id Notification id
     * @param isRetry Is it a retry call
     * @param sendNotification Callback for completion handling
     */
    fun getSponsoredNotificationInfo(fetchRequest: FetchRequest,
                                     channelId: String,
                                     id: Int,
                                     isRetry: Boolean,
                                     sendNotification: (payload: FCMPayloadModel, isSponsored: Boolean) -> Unit)

    /**
     * API call to track notification views
     *
     * @param tag Notification tag
     * @param isRetry Is this a retry call
     */
    fun trackNotificationViewed(tag: String?,
                                isRetry: Boolean)

    /**
     * Determine notification subscriber changes
     * @param areNotificationsEnabled whether notification is enabled or not
     */
    fun determineNotificationSubscriberChanges(areNotificationsEnabled: Boolean)
}

internal class PENotificationManager constructor(private val context: Context,
                                                 private val payload: FCMPayloadModel,
                                                 private val additionalData: HashMap<String, String>?,
                                                 private val gson: Gson,
                                                 private val prefs: PEPrefs,
                                                 private val daoInterface: DaoInterface,
                                                 private val notificationManager: NotificationManager,
                                                 private val peNotificationBuilder: PENotificationBuilderType = PENotificationBuilder(context, gson, prefs),
                                                 private val peChannelHelper: PENotificationChannelHelperType = PENotificationChannelHelper(context, daoInterface, notificationManager),
                                                 private val peServiceHandler: PEServiceHandlerType = PEServiceHandler(context, prefs, gson, daoInterface)) : PENotificationManagerType {

    override fun createNotificationBuilder(): Builder {
        return peNotificationBuilder.createNotificationBuilder(payload, additionalData)
    }

    override fun determineNotificationSubscriberChanges(areNotificationsEnabled: Boolean) {
        val enabledLongValue = (if (areNotificationsEnabled) 0 else 1).toLong()
        if (enabledLongValue != prefs.isNotificationDisabled) {
            handleNotificationSubscriberChange(areNotificationsEnabled, enabledLongValue)
        }
    }

    /**
     * Handle notification subscriber changes
     * @param areNotificationsEnabled whether notifications are enabled or not
     * @param enabledLongValue long value for whether notification is enabled or not
     */
    private fun handleNotificationSubscriberChange(areNotificationsEnabled: Boolean,
                                                   enabledLongValue: Long) {
        if (areNotificationsEnabled && prefs.isSubscriberDeleted) {
            PushEngage.callAddSubscriberAPI()
        } else {
            val updateSubscriberStatusRequest = UpdateSubscriberStatusRequest(
                    prefs.siteId,
                    prefs.hash,
                    enabledLongValue,
                    prefs.deleteOnNotificationDisable
            )
            peServiceHandler.updateSubscriberStatus(updateSubscriberStatusRequest)
        }
    }

    override fun setChannelInformation(channelId: String,
                                       payload: FCMPayloadModel,
                                       isDefault: Boolean,
                                       notificationBuilder: Builder,
                                       didFetchChannelInfo: Boolean) {
        peChannelHelper.setChannelInfo(channelId, payload, isDefault, notificationBuilder, didFetchChannelInfo,
                publishNotification = {
                    payload.notificationId?.let { notificationId ->
                        peNotificationBuilder.setNotificationImages(payload, notificationBuilder) {
                            notificationManager.notify(notificationId, notificationBuilder.build())
                        }
                    }
                },
                channelInfo = {
                    peServiceHandler.getChannelInfo(channelId, notificationManager, notificationBuilder, payload) { showConsiderAsDefault ->
                        setChannelInformation(channelId, payload, showConsiderAsDefault, notificationBuilder, true)
                    }
                })
    }

    override fun getSponsoredNotificationInfo(fetchRequest: FetchRequest,
                                              channelId: String,
                                              id: Int,
                                              isRetry: Boolean,
                                              sendNotification: (payload: FCMPayloadModel, isSponsored: Boolean) -> Unit) {
        peServiceHandler.getSponsoredNotificationInfo(fetchRequest, channelId, id, isRetry, sendNotification)
    }

    override fun trackNotificationViewed(tag: String?, isRetry: Boolean) {
        peServiceHandler.trackNotificationViewed(tag, isRetry)
    }

}