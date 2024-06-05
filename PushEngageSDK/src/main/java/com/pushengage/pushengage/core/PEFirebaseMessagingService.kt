package com.pushengage.pushengage.core

import android.app.NotificationManager
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import com.pushengage.pushengage.model.request.FetchRequest
import com.pushengage.pushengage.model.request.UpgradeSubscriberRequest
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

internal class PEFirebaseMessagingService : FirebaseMessagingService() {
    private val TAG = "PEFirebaseMsgService"
    private var additionalData: HashMap<String, String>? = null
    private var prefs: PEPrefs? = null
    private val gson = Gson()
    private var peRoomDatabase: PERoomDatabase? = null
    private var daoInterface: DaoInterface? = null

    override fun onMessageReceived(message: RemoteMessage) {
        PELogger.debug("From: " + message.from)
        initializeDependencies()
        setPayloadInPreferences(message)

        if (hasDataPayload(message)) {
            PELogger.debug("RemoteMessage data payload: " + message.data)
        }
        try {
            val jsonObject = (message.data as? Map<*, *>?)?.let { JSONObject(it) }
            val payload: FCMPayloadModel = gson.fromJson(jsonObject?.toString(), FCMPayloadModel::class.java)
            if (payload.channelId.isNullOrEmpty()) {
                payload.channelId = PEConstants.DEFAULT_CHANNEL_ID
            }
            additionalData = gson.fromJson(payload.additionalData, HashMap::class.java) as? HashMap<String, String>?
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                prefs?.let { prefs ->
                    daoInterface?.let {daoInterface ->
                    val peNotificationManager: PENotificationManagerType = PENotificationManager(this, payload, additionalData, gson, prefs, daoInterface, notificationManager)
                    val notificationBuilder = peNotificationManager.createNotificationBuilder()

                    val areNotificationsEnabled: Boolean = areNotificationsEnabled()
                    peNotificationManager.determineNotificationSubscriberChanges(areNotificationsEnabled)
                    processPayloadData(areNotificationsEnabled, payload, peNotificationManager, notificationBuilder)
                } }

            }
        } catch (e: Exception) {
            PELogger.error("onMessageReceived: error", e)
        }
    }

    override fun onNewToken(token: String) {
        upgradeToken(token)
    }

    /**
     * Initialize PE SharedPreferences
     */
    private fun initializeDependencies() {
        prefs = PEPrefs(this)
        peRoomDatabase = PERoomDatabase.getDatabase(applicationContext)
        daoInterface = peRoomDatabase?.daoInterface()
    }

    /**
     * Set payload in PE SharedPreferences
     *
     * @param remoteMessage FCM payload received
     */
    private fun setPayloadInPreferences(remoteMessage: RemoteMessage) {
        prefs?.payload = remoteMessage.data.toString()
    }

    /**
     * Check for data field in payload
     * @param remoteMessage FCM payload received
     */
    private fun hasDataPayload(remoteMessage: RemoteMessage): Boolean {
        return remoteMessage.data.isNotEmpty()
    }

    /**
     * Check if notifications are enabled or not
     */
    private fun areNotificationsEnabled(): Boolean {
        val notificationManagerCompat = NotificationManagerCompat.from(applicationContext)
        return notificationManagerCompat.areNotificationsEnabled()
    }

    /**
     * Process FCM payload
     * @param areNotificationsEnabled whether notifications are enabled or not
     */
    private fun processPayloadData(areNotificationsEnabled: Boolean,
                                   payload: FCMPayloadModel,
                                   peNotificationManager: PENotificationManagerType,
                                   notificationBuilder: NotificationCompat.Builder) {
        if (!payload.reFetch.isNullOrEmpty() && payload.reFetch.equals("1", ignoreCase = true)) {
            val fetchRequest = FetchRequest(payload.tag, payload.postbackData)

            payload.channelId?.let { channelId ->
                payload.notificationId?.let {notificationId ->
                    peNotificationManager.getSponsoredNotificationInfo(fetchRequest, channelId, notificationId, false) {payload: FCMPayloadModel, isSponsored: Boolean ->
                        sendNotification(payload, isSponsored, peNotificationManager, notificationBuilder)
                    }
                }
            }

        } else {
            if (areNotificationsEnabled) {
                peNotificationManager.trackNotificationViewed(payload.tag, false)
            }
            sendNotification(payload, false, peNotificationManager, notificationBuilder)
        }
    }

    /**
     * Create and show a simple notification containing the received FCM message.
     * @param payload FCM payload received
     * @param isSponsored is sponsored one
     */
    private fun sendNotification(payload: FCMPayloadModel,
                                 isSponsored: Boolean,
                                 peNotificationManager: PENotificationManagerType,
                                 notificationBuilder: NotificationCompat.Builder) {
        val channelId: String? = getChannelId(payload)
        try {
            channelId?.let {id ->
                peNotificationManager.setChannelInformation(id, payload, false, notificationBuilder, false)
            }
        } catch (e: Exception) {
            PELogger.error("sendNotification: error", e)
        }
    }

    /**
     * Get channelId from POJO
     * @param payload FCM payload received
     */
    private fun getChannelId(payload: FCMPayloadModel): String? {
        val channelId = payload.channelId
        return if (TextUtils.isEmpty(channelId)) PEConstants.DEFAULT_CHANNEL_ID else channelId
    }

    /**
     * API call to upgrade token when new token is generated.
     *
     * @param token
     */
    private fun upgradeToken(token: String) {
        prefs = PEPrefs(this)
        var upgradeSubscriberRequest = UpgradeSubscriberRequest()
        val subscription = upgradeSubscriberRequest.Subscription(token, prefs?.projectId)
        upgradeSubscriberRequest = UpgradeSubscriberRequest(prefs?.hash, subscription, prefs?.siteId)
        val addRecordsResponseCall = RestClient.getBackendClient(applicationContext).upgradeSubscriber(upgradeSubscriberRequest)
        addRecordsResponseCall.enqueue(object : Callback<ResponseBody?> {
            override fun onResponse(call: Call<ResponseBody?>, response: Response<ResponseBody?>) {
            }

            override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
            }
        })
    }
}