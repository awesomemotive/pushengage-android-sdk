package com.pushengage.pushengage.notificationchannel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pushengage.pushengage.Database.ChannelEntity
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.model.PEChannelImportance
import com.pushengage.pushengage.model.PENotificationVisibility
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import org.json.JSONArray

internal interface PENotificationChannelHelperType {
    /**
     * Set notification vibration for devices older than Android O
     * @param channelId Channel id
     * @param payload FCM payload
     * @param isDefault Should set to default channel
     * @param notificationBuilder Object of notification builder
     */
    fun setChannelInfo(channelId: String,
                       payload: FCMPayloadModel,
                       isDefault: Boolean,
                       notificationBuilder: NotificationCompat.Builder,
                       didFetchChannelInfo: Boolean,
                       publishNotification: ()->Unit,
                       channelInfo: ()->Unit)
}
internal class PENotificationChannelHelper(private val context: Context,
                                           private val daoInterface: DaoInterface,
                                           private val notificationManager: NotificationManager): PENotificationChannelHelperType {

    private val className = this::class.java.simpleName

    override fun setChannelInfo(channelId: String,
                                payload: FCMPayloadModel,
                                isDefault: Boolean,
                                notificationBuilder: NotificationCompat.Builder,
                                didFetchChannelInfo: Boolean,
                                publishNotification: ()->Unit,
                                channelInfo: ()->Unit) {
        val channelEntity = daoInterface.getChannel(channelId)
        if (channelEntity == null) {
            if (isDefault || didFetchChannelInfo) {
                createDefaultChannelIfRequired()
                publishNotification()
            } else {
                channelInfo()
            }
        } else {
            val visibility = getLockScreenVisibility(channelEntity, notificationBuilder)
            notificationBuilder.setVisibility(visibility)

            setChannelLedColorForNotificationBuilder(channelEntity, notificationBuilder)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelImportance = getChannelImportance(channelEntity)
//              Register the channel with the system, can't change the importance or other notification behaviors after this
                registerNotificationChannel(channelId, channelEntity, channelImportance, visibility)
            } else {
                setNotificationSound(channelEntity, notificationBuilder)
                setNotificationVibration(channelEntity, notificationBuilder)
            }
            publishNotification()
        }
    }

    /**
     * Create default notification channel
     */
    private fun createDefaultChannelIfRequired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(PEConstants.DEFAULT_CHANNEL_ID, PEConstants.DEFAULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Get lock screen visibility
     * @param channelEntity Channel entity class
     * @param notificationBuilder Object of notification builder
     */
    private fun getLockScreenVisibility(channelEntity: ChannelEntity,
                                        notificationBuilder: NotificationCompat.Builder): Int {
        val visibility: Int
        when (channelEntity.lockScreen) {
            PENotificationVisibility.PRIVATE.visibility -> {
                visibility = NotificationCompat.VISIBILITY_PRIVATE
                notificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                notificationBuilder.setPublicVersion(notificationBuilder.build())
            }

            PENotificationVisibility.PUBLIC.visibility -> visibility = NotificationCompat.VISIBILITY_PUBLIC
            PENotificationVisibility.SECRET.visibility -> visibility = NotificationCompat.VISIBILITY_SECRET
            else -> visibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        return visibility
    }

    /**
     * Set notification LED color
     * @param channelEntity Channel entity class
     * @param notificationBuilder Object of notification builder
     */
    private fun setChannelLedColorForNotificationBuilder(channelEntity: ChannelEntity,
                                                         notificationBuilder: NotificationCompat.Builder) {
        try {
            if (channelEntity.ledColor.equals("OFF", ignoreCase = true)) {
                notificationBuilder.setLights(0, 0, 0)
            } else if (channelEntity.ledColor.equals("CUSTOM", ignoreCase = true)) {
                notificationBuilder.setLights(Color.parseColor("#" + channelEntity.ledColorCode), 1000, 1000)
            }
        } catch (e: Exception) {
            PELogger.error("Set notification LED color", e)
        }
    }

    /**
     * Get notification channel level
     * @param channelEntity Channel entity class
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun getChannelImportance(channelEntity: ChannelEntity) : Int {
        return when (channelEntity.importance) {
            PEChannelImportance.HIGH.importance -> NotificationManager.IMPORTANCE_HIGH //Important for heads up notification otherwise it will go to notification drawer directly
            PEChannelImportance.DEFAULT.importance -> NotificationManager.IMPORTANCE_DEFAULT
            PEChannelImportance.LOW.importance -> NotificationManager.IMPORTANCE_LOW
            PEChannelImportance.MIN.importance -> NotificationManager.IMPORTANCE_MIN
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
    }

    /**
     * Set notification vibration for devices older than Android O
     * @param channelId Channel id
     * @param channelEntity Channel entity class
     * @param channelImportance Importance level of channel
     * @param visibility Lock screen visibility of channel notifications
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun registerNotificationChannel(channelId: String,
                                            channelEntity: ChannelEntity,
                                            channelImportance: Int,
                                            visibility: Int) {
        if (!TextUtils.isEmpty(channelId) && !TextUtils.isEmpty(channelEntity.channelName)) {
            var channel = NotificationChannel(channelId, channelEntity?.channelName, channelImportance)
            if (!TextUtils.isEmpty(channelEntity?.channelDescription)) channel?.description = channelEntity?.channelDescription
            channel = setChannelSound(channel, channelEntity?.sound, channelEntity?.soundFile)
            channel = setChannelVibration(channel, channelEntity?.vibration, channelEntity?.vibrationPattern)
            channel = setChannelLedColor(channel, channelEntity?.ledColor, channelEntity?.ledColorCode)
            channel.lockscreenVisibility = visibility
            if (channelEntity?.badges != null) {
                channel.setShowBadge(channelEntity?.badges)
            }
            if (!TextUtils.isEmpty(channelEntity?.groupId) && !TextUtils.isEmpty(channelEntity?.groupName)) {
                notificationManager.createNotificationChannelGroup(NotificationChannelGroup(channelEntity?.groupId, channelEntity?.groupName))
                channel?.group = channelEntity?.groupId
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Set notification vibration for devices older than Android O
     * @param channelEntity Channel entity class
     */
    private fun setNotificationVibration(channelEntity: ChannelEntity,
                                         notificationBuilder: NotificationCompat.Builder) {
        try {
            if (TextUtils.isEmpty(channelEntity.vibration) || channelEntity.vibration.equals("DEFAULT", ignoreCase = true)) {
                notificationBuilder.setDefaults(Notification.DEFAULT_VIBRATE)
            } else if (channelEntity.vibration.equals("OFF", ignoreCase = true)) {
                notificationBuilder.setVibrate(null)
            } else if (channelEntity.vibration.equals("CUSTOM", ignoreCase = true)) {
                val jsonArray = JSONArray(channelEntity.vibrationPattern)
                val array = LongArray(jsonArray.length())
                for (i in 0 until jsonArray.length()) {
                    array[i] = jsonArray.getLong(i)
                }
                notificationBuilder.setVibrate(array)
            }
        } catch (e: Exception) {
            PELogger.error("Set notification vibration", e)
        }
    }

    /**
     * Set notification Sound for devices older than Android O
     * @param channelEntity Channel entity class
     */
    private fun setNotificationSound(channelEntity: ChannelEntity,
                                     notificationBuilder: NotificationCompat.Builder) {
        try {
            if (TextUtils.isEmpty(channelEntity.sound) || channelEntity.sound.equals("DEFAULT", ignoreCase = true)) {
                val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                notificationBuilder.setSound(defaultSoundUri)
            } else if (channelEntity.sound.equals("OFF", ignoreCase = true)) {
                notificationBuilder.setSound(null)
            } else if (channelEntity.sound.equals("CUSTOM", ignoreCase = true)) {
                val soundUri = Uri.parse("android.resource://" + context.packageName + "/raw/" + channelEntity.soundFile)
                notificationBuilder.setSound(soundUri)
            }
        } catch (e: Exception) {
            PELogger.error("Set notification sound", e)
        }
    }

    /**
     * Set Notification Sound for Channel
     * @param channel NotificationChannel object of the notification
     * @param sound Sound status
     * @param soundFile Name of sound file
     * @return NotificationChannel channel object of the notification
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun setChannelSound(channel: NotificationChannel, sound: String?, soundFile: String?): NotificationChannel {
        try {
            if (sound.isNullOrEmpty() || sound.equals("DEFAULT", ignoreCase = true)) {
                val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val att = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                channel.setSound(defaultSoundUri, att)
            } else if (sound.equals("OFF", ignoreCase = true)) {
                channel.setSound(null, null)
            } else if (sound.equals("CUSTOM", ignoreCase = true)) {
                val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                val soundUri = Uri.parse("android.resource://" + context.packageName + "/raw/" + soundFile)
                channel.setSound(soundUri, audioAttributes)
            }
        } catch (e: Exception) {
            PELogger.error("Set Notification Sound for Channel", e)
        }
        return channel
    }

    /**
     * Set Channel Vibration pattern
     * @param channel NotificationChannel object of the notification
     * @param vibration Vibration status
     * @param vibrationPattern
     * @return
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun setChannelVibration(channel: NotificationChannel, vibration: String?, vibrationPattern: String?): NotificationChannel {
        try {
            if (vibration.isNullOrEmpty() || vibration.equals("DEFAULT", ignoreCase = true)) {
                channel.enableVibration(true)
            } else if (vibration.equals("OFF", ignoreCase = true)) {
                channel.enableVibration(false)
            } else if (vibration.equals("CUSTOM", ignoreCase = true)) {
                channel.enableVibration(true)
                val jsonArray = JSONArray(vibrationPattern)
                val array = LongArray(jsonArray.length())
                for (i in 0 until jsonArray.length()) {
                    array[i] = jsonArray.getLong(i)
                }
                channel.vibrationPattern = array
            }
        } catch (e: Exception) {
            PELogger.error("Set Channel Vibration", e)
        }
        return channel
    }

    /**
     * Set Led Color for Channel
     *
     * @param channel NotificationChannel object of the notification
     * @param led LED status
     * @param colorCode Color code of LED
     * @return
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun setChannelLedColor(channel: NotificationChannel, led: String?, colorCode: String?): NotificationChannel {
        try {
            if (led.isNullOrEmpty() || led.equals("DEFAULT", ignoreCase = true)) {
                channel.enableLights(true)
            } else if (led.equals("OFF", ignoreCase = true)) {
                channel.enableLights(false)
            } else if (led.equals("CUSTOM", ignoreCase = true)) {
                channel.enableLights(true)
                channel.lightColor = Color.parseColor("#$colorCode")
            }
        } catch (e: Exception) {
            PELogger.error("Set LED Color for Channel", e)
        }
        return channel
    }

}