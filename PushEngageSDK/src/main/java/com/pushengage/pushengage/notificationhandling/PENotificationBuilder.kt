package com.pushengage.pushengage.notificationhandling

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import com.google.gson.Gson
import com.pushengage.pushengage.R
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.PENotificationPriority
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import org.json.JSONArray
import org.json.JSONException


internal interface PENotificationBuilderType {
    /**
     * Create notification builder from payload
     * @param payload FCM payload received
     * @param additionalData Map of additional data
     */
    fun createNotificationBuilder(payload: FCMPayloadModel,
                                  additionalData: HashMap<String, String>?) : Builder
    /**
     * Set notification icons and images
     * @param payload FCM payload received
     * @param notificationBuilder Object of notification builder
     * @param completion Callback for completion
     */
    fun setNotificationImages(payload: FCMPayloadModel,
                              notificationBuilder: Builder,
                              completion: ()->Unit)
}

internal class PENotificationBuilder(private val context: Context,
                                     private val gson: Gson,
                                     private val prefs: PEPrefs,
                                     private val imageLoader: PENotificationImageLoaderType = PENotificationImageLoader(context)): PENotificationBuilderType {
    private val className = this::class.java.simpleName

    override fun createNotificationBuilder(payload: FCMPayloadModel,
                                           additionalData: HashMap<String, String>?) : Builder {
        val channelId = getChannelId(payload)
        val notificationBuilder = Builder(context, channelId)
                .setAutoCancel(true)

        createClickPendingIntent(payload, additionalData, notificationBuilder)
        createActionButtons(payload, notificationBuilder, additionalData)
        setContentTitleAndText(payload, notificationBuilder)
        setSmallIcon(payload, notificationBuilder)
        setAccentColor(payload, notificationBuilder)
        setNotificationPriority(payload, notificationBuilder)
        setGroupKey(payload, notificationBuilder)

        return notificationBuilder
    }

    override fun setNotificationImages(payload: FCMPayloadModel,
                                       notificationBuilder: Builder, completion: ()->Unit) {
        imageLoader.setNotificationImages(payload, notificationBuilder, completion)
    }

    /**
     * Get channelId from payload
     * @param payload FCM payload received
     */
    private fun getChannelId(payload: FCMPayloadModel): String {
        val channelId = payload.channelId
        return if (channelId.isNullOrEmpty()) PEConstants.DEFAULT_CHANNEL_ID else channelId
    }

    /**
     * Create notification click PendingIntent from payload
     * @param payload FCM payload
     * @param additionalData Map of additional data
     */
    private fun createClickPendingIntent(payload: FCMPayloadModel,
                                         additionalData: HashMap<String, String>?,
                                         notificationBuilder: Builder) {
        val clickIntent = Intent(context, PENotificationHandlerActivity::class.java)

        if (!payload.url.isNullOrEmpty()) {
            clickIntent.putExtra(PEConstants.URL_EXTRA, payload.url)
        } else if (!payload.commonUrl.isNullOrEmpty()) {
            clickIntent.putExtra(PEConstants.URL_EXTRA, payload.commonUrl)
        }

        if (!payload.tag.isNullOrEmpty()) {
            clickIntent.putExtra(PEConstants.TAG_EXTRA, payload.tag)
        }

        clickIntent.putExtra(PEConstants.DATA_EXTRA, additionalData)
        clickIntent.putExtra(PEConstants.ID_EXTRA, payload.notificationId)

        val clickPendingIntent = payload.notificationId?.let { id ->
            PendingIntent.getActivity(context.applicationContext,
                    id,
                    clickIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        }
        notificationBuilder.setContentIntent(clickPendingIntent)
    }

    /**
     * Create notification action buttons from payload
     * @param payload FCM payload
     * @param notificationBuilder Object of NotificationCompat.Builder
     */
    private fun createActionButtons(payload: FCMPayloadModel,
                                    notificationBuilder: Builder,
                                    additionalData: HashMap<String, String>?) {
        val actionButtonsJSON = payload.actionButtons

        if (!actionButtonsJSON.isNullOrEmpty()) {
            try {
                val jsonArray = JSONArray(actionButtonsJSON)
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val actionButton = gson.fromJson(jsonObject.toString(), FCMPayloadModel.ActionButton::class.java)

                    val actionButtonIntent = createActionIntent(payload, actionButton, i, additionalData)
                    val actionButtonsPendingIntent = PendingIntent.getActivity(
                            context.applicationContext,
                            1200 + i,
                            actionButtonIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val iconResourceId = getActionButtonIconResourceId(actionButton.icon)
                    //action button icons are not available from Android 7(API level 24) onwards
                    notificationBuilder.addAction(iconResourceId, actionButton.label, actionButtonsPendingIntent)
                }
            } catch (e: JSONException) {
                Log.d(className, "createActionButtons: "+ e.localizedMessage)
            }
        }
    }

    /**
     * Create notification action buttons from payload
     * @param payload FCM payload
     * @param actionButton Object having info about action button
     * @param index Index of action button
     * @param additionalData Map of additional data
     */
    private fun createActionIntent(payload: FCMPayloadModel,
                                   actionButton: FCMPayloadModel.ActionButton,
                                   index: Int,
                                   additionalData: HashMap<String, String>?) : Intent {
        val actionButtonIntent = Intent(context, PENotificationHandlerActivity::class.java)
        if(!actionButton.url.isNullOrEmpty()) {
            actionButtonIntent.putExtra(PEConstants.URL_EXTRA, actionButton.url)
        } else if(!payload.commonUrl.isNullOrEmpty()) {
            actionButtonIntent.putExtra(PEConstants.URL_EXTRA, payload.commonUrl)
        }
        actionButtonIntent.putExtra(PEConstants.DATA_EXTRA, additionalData)
        actionButtonIntent.putExtra(PEConstants.TAG_EXTRA, payload.tag)
        actionButtonIntent.putExtra(PEConstants.ACTION_EXTRA, PEConstants.ACTION_EXTRA + (index + 1))
        actionButtonIntent.putExtra(PEConstants.ID_EXTRA, payload.notificationId)
        return actionButtonIntent
    }

    /**
     * Retrieve action button icon from app resources
     * @param iconName Name of icon
     */
    private fun getActionButtonIconResourceId(iconName: String?): Int {
        return try {
            val resources: Resources = context.applicationContext.resources
            resources.getIdentifier(iconName, "drawable", context.applicationContext.packageName)
        } catch (e: Exception) {
            Log.d(className, "getActionButtonIconResourceId: "+ e.localizedMessage)
            return 0
        }
    }

    /**
     * Set notification title and text
     * @param payload FCM payload
     * @param notificationBuilder Object of NotificationCompat.Builder
     */
    private fun setContentTitleAndText(payload: FCMPayloadModel,
                                       notificationBuilder: Builder) {
        if (!payload.title.isNullOrEmpty()) {
            notificationBuilder.setContentTitle(payload.title)
        }
        if (!payload.body.isNullOrEmpty()) {
            notificationBuilder.setContentText(payload.body)
        }
    }

    /**
     * Set notification small icon
     * @param payload FCM payload
     * @param notificationBuilder Object of NotificationCompat.Builder
     */
    private fun setSmallIcon(payload: FCMPayloadModel, notificationBuilder: Builder) {
        try {
            val resources: Resources = context.applicationContext.resources
            var icon = if (payload.smallIcon.isNullOrEmpty())
                prefs.smallIconResource
            else
                payload.smallIcon

            var smallIconResourceId = resources.getIdentifier(
                    icon,
                    "drawable", context.applicationContext.packageName)
            //if small icon name is invalid, set it from prefs
            if(smallIconResourceId == 0) {
                smallIconResourceId = resources.getIdentifier(prefs.smallIconResource, "drawable", context.applicationContext.packageName)
            }
            notificationBuilder.setSmallIcon(smallIconResourceId)
        } catch (e: Exception) {
            notificationBuilder.setSmallIcon(R.drawable.ic_stat_notification_default)
        }
    }

    /**
     * Set notification accent color
     * @param payload FCM payload
     * @param notificationBuilder Object of NotificationCompat.Builder
     */
    private fun setAccentColor(payload: FCMPayloadModel, notificationBuilder: Builder) {
        if (!payload.accentColor.isNullOrEmpty()) {
            notificationBuilder.color = Color.parseColor("#" + payload.accentColor)
        }
    }

    /**
     * Set notification priority
     * @param payload FCM payload
     * @param notificationBuilder Object of NotificationCompat.Builder
     */
    private fun setNotificationPriority(payload: FCMPayloadModel, notificationBuilder: Builder) {
        try {
            when (payload.priority) {
                PENotificationPriority.HIGH.priority -> notificationBuilder.priority = NotificationCompat.PRIORITY_MAX
                PENotificationPriority.MIN.priority -> notificationBuilder.priority = NotificationCompat.PRIORITY_MIN
                else -> notificationBuilder.priority = NotificationCompat.PRIORITY_DEFAULT
            }
        } catch (e: Exception) {
            notificationBuilder.priority = NotificationCompat.PRIORITY_DEFAULT
        }
    }

    /**
     * Set notification group key
     * @param payload FCM payload
     * @param notificationBuilder Object of NotificationCompat.Builder
     */
    private fun setGroupKey(payload: FCMPayloadModel, notificationBuilder: Builder) {
        try {
            val groupKey = payload.groupKey
            if (!groupKey.isNullOrEmpty()) {
                notificationBuilder.setGroup(groupKey)
            }
        } catch (e: Exception) {
            Log.d(className, "setGroupKey: "+ e.localizedMessage)
        }
    }

}