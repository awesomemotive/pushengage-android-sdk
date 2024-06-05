package com.pushengage.pushengage.servicehandling

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.pushengage.pushengage.Database.ChannelEntity
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.PushEngage
import com.pushengage.pushengage.R
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.helper.PEUtilities
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import com.pushengage.pushengage.model.request.ErrorLogRequest
import com.pushengage.pushengage.model.request.FetchRequest
import com.pushengage.pushengage.model.request.UpdateSubscriberStatusRequest
import com.pushengage.pushengage.model.response.ChannelResponseModel
import com.pushengage.pushengage.model.response.FetchResponse
import com.pushengage.pushengage.model.response.NetworkResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Timer
import java.util.TimerTask

internal interface PEServiceHandlerType {
    /**
     * API call to Update Subscriber status based on Notification Permission
     *
     * @param updateSubscriberStatusRequest
     */
    fun updateSubscriberStatus(updateSubscriberStatusRequest: UpdateSubscriberStatusRequest)
    /**
     * API call to fetch notification channel information
     *
     * @param channelId Notification channel id
     * @param notificationManager Object of notification manager
     * @param notificationBuilder Object of builder
     * @param payload FCM payload
     * @param completion Callback for completion handling
     */
    fun getChannelInfo(channelId: String,
                       notificationManager: NotificationManager,
                       notificationBuilder: NotificationCompat.Builder,
                       payload: FCMPayloadModel,
                       completion: (isDefault: Boolean)->Unit)

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
                  sendNotification: (payload: FCMPayloadModel, isSponsored: Boolean)->Unit)

    /**
     * API call to track notification views
     *
     * @param tag Notification tag
     * @param isRetry Is this a retry call
     */
    fun trackNotificationViewed(tag: String?, isRetry: Boolean)
}
internal class PEServiceHandler(private val context: Context,
                                private val pePrefs: PEPrefs,
                                private val gson: Gson,
                                private val daoInterface: DaoInterface): PEServiceHandlerType {

    private val className = this::class.java.simpleName

    override fun getChannelInfo(channelId: String,
                                notificationManager: NotificationManager,
                                notificationBuilder: NotificationCompat.Builder,
                                payload: FCMPayloadModel,
                                completion: (isDefault: Boolean)->Unit) {
        val channelResponseCall = RestClient.getBackendCdnClient(context).getChannelInfo(pePrefs.siteKey, channelId)
        channelResponseCall.enqueue(object : Callback<ChannelResponseModel> {
            override fun onResponse(call: Call<ChannelResponseModel>, response: Response<ChannelResponseModel>) {
                if (response.isSuccessful) {
                    val channelResponse = response.body()
                    PELogger.debug("Channel Information: ${channelResponse?.data?.options?.importance}")
                    val channelEntity = ChannelEntity(
                            channelResponse?.data?.channelId?.toString(),
                            channelResponse?.data?.channelName ?: "",
                            channelResponse?.data?.channelDescription ?: "",
                            channelResponse?.data?.groupId?.toString() ?: "",
                            channelResponse?.data?.groupName ?: "",
                            channelResponse?.data?.options?.importance ?: "",
                            channelResponse?.data?.options?.sound ?: "",
                            channelResponse?.data?.options?.soundFile ?: "",
                            channelResponse?.data?.options?.vibration ?: "",
                            channelResponse?.data?.options?.vibrationPattern?.toString() ?: "",
                            channelResponse?.data?.options?.ledColor ?: "",
                            channelResponse?.data?.options?.ledColorCode ?: "",
                            "",
                            channelResponse?.data?.options?.badges ?: false,
                            channelResponse?.data?.options?.lockScreen ?: ""
                    )

                    daoInterface.insert(channelEntity)
                    completion(false)
                } else {
                    completion(true)
                }
            }

            override fun onFailure(call: Call<ChannelResponseModel>, t: Throwable) {
                completion(true)
            }
        })
    }

    override fun trackNotificationViewed(tag: String?, isRetry: Boolean) {
        val headerMap = hashMapOf("referer" to "https://pushengage.com/service-worker.js")
        val device = if(context.resources.getBoolean(R.bool.is_tablet)) {
            PEConstants.TABLET
        } else {
            PEConstants.MOBILE
        }

        val notificationViewResponseCall = RestClient.getAnalyticsClient(context.applicationContext, headerMap)
                .notificationView(pePrefs.hash, tag, PEConstants.ANDROID, device, PushEngage.getSdkVersion(), PEUtilities.getTimeZone())

        notificationViewResponseCall.enqueue(object : Callback<NetworkResponse> {
            override fun onResponse(call: Call<NetworkResponse>, response: Response<NetworkResponse>) {
                if (response.isSuccessful) {
                    val genericResponse = response.body()
                    PELogger.debug("Notification View API Success")
                } else {
                    if (!isRetry) {
                        Timer().schedule(object : TimerTask() {
                            override fun run() {
                                trackNotificationViewed(tag, true)
                            }
                        }, PEConstants.RETRY_DELAY.toLong())
                    } else {
                        handleErrorResponse(tag, gson.toJson(response.body()), PEConstants.VIEW_COUNT_TRACKING_FAILED)
                        PELogger.debug("Notification View API Failure")
                    }
                }
            }

            override fun onFailure(call: Call<NetworkResponse>, t: Throwable) {
                if (!isRetry) {
                    Timer().schedule(object : TimerTask() {
                        override fun run() {
                            trackNotificationViewed(tag, true)
                        }
                    }, PEConstants.RETRY_DELAY.toLong())
                } else {
                    handleErrorResponse(tag, t.message, PEConstants.VIEW_COUNT_TRACKING_FAILED)
                    PELogger.debug("Notification View API Failure")
                }
            }
        })
    }

    override fun getSponsoredNotificationInfo(fetchRequest: FetchRequest,
                  channelId: String,
                  id: Int,
                  isRetry: Boolean,
                  sendNotification: (payload: FCMPayloadModel, isSponsored: Boolean)->Unit) {
        val addRecordsResponseCall = RestClient.getBackendClient(context.applicationContext).fetch(fetchRequest)
        addRecordsResponseCall.enqueue(object : Callback<FetchResponse> {
            override fun onResponse(call: Call<FetchResponse>, response: Response<FetchResponse>) {
                if (response.isSuccessful) {
                    handleSuccessfulResponse(response.body(), channelId, id, sendNotification)
                } else if (response.code() != 404 && !isRetry) {
                    scheduleRetry(fetchRequest, channelId, id, sendNotification)
                } else if (response.code() != 404) {
                    handleErrorResponse(fetchRequest?.tag, gson.toJson(response.body()), PEConstants.NOTIFICATION_REFETCH_FAILED)
                }
            }

            override fun onFailure(call: Call<FetchResponse>, t: Throwable) {
                if (!isRetry) {
                    scheduleRetry(fetchRequest, channelId, id, sendNotification)
                } else {
                    handleErrorResponse(fetchRequest?.tag, t.message, PEConstants.NOTIFICATION_REFETCH_FAILED)
                }
            }
        })
    }

    private fun handleSuccessfulResponse(fetchResponse: FetchResponse?,
                                         channelId: String,
                                         id: Int,
                                         sendNotification: (payload: FCMPayloadModel, isSponsored: Boolean)->Unit) {
        fetchResponse?.data?.let { data ->
            try {
                val json = gson.toJson(data)
                val payloadPOJO = gson.fromJson(json, FCMPayloadModel::class.java)
                payloadPOJO?.channelId = channelId
                payloadPOJO?.notificationId = id
                val notificationManagerCompat = NotificationManagerCompat.from(context.applicationContext)
                if (notificationManagerCompat.areNotificationsEnabled()) {
                    trackNotificationViewed(payloadPOJO.tag, false)
                }
                sendNotification(payloadPOJO, true)
            } catch (e: Exception) {
                PELogger.error("Notification View API Failure", e)
            }
        }
    }

    private fun scheduleRetry(fetchRequest: FetchRequest,
                              channelId: String,
                              id: Int,
                              sendNotification: (payload: FCMPayloadModel, isSponsored: Boolean)->Unit) {
        Timer().schedule(object : TimerTask() {
            override fun run() {
                getSponsoredNotificationInfo(fetchRequest, channelId, id, true, sendNotification)
            }
        }, PEConstants.RETRY_DELAY.toLong())
    }

    private fun handleErrorResponse(tag: String?, error: String?, errorName: String) {
        val errorLogRequest = ErrorLogRequest().apply {
            app = PEConstants.ANDROID_SDK
            name = errorName
            data = Data(tag, pePrefs.hash, PEConstants.MOBILE, PEUtilities.getTimeZone(), error)
        }
        PEUtilities.addLogs(context.applicationContext, className, errorLogRequest)
    }

    override fun updateSubscriberStatus(updateSubscriberStatusRequest: UpdateSubscriberStatusRequest) {
        updateSubscriberStatusRequest.deviceTokenHash = pePrefs.hash
        val updateSubscriberStatusResponseCall = RestClient.getBackendClient(context.applicationContext).updateSubscriberStatus(updateSubscriberStatusRequest)
        updateSubscriberStatusResponseCall.enqueue(object : Callback<NetworkResponse?> {
            override fun onResponse(call: Call<NetworkResponse?>, response: Response<NetworkResponse?>) {
                if(response.isSuccessful) {
                    pePrefs.setIsNotificationDisabled(updateSubscriberStatusRequest.isUnSubscribed)
                } else {
                    PELogger.debug("Update Subscriber Status API Failure")
                }
            }

            override fun onFailure(call: Call<NetworkResponse?>, t: Throwable) {
                PELogger.debug("Update Subscriber Status API Failure")
            }
        })
    }

}