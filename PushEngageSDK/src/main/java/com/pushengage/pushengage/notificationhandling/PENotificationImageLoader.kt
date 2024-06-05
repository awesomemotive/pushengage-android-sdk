package com.pushengage.pushengage.notificationhandling

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal interface PENotificationImageLoaderType {
    /**
     * Set all type of notification images
     * @param payload FCM payload
     * @param notificationBuilder Object of notification builder
     * @param completion Callback for completion
     */
    fun setNotificationImages(payload: FCMPayloadModel,
                              notificationBuilder: Builder,
                              completion: () -> Unit)
}

internal class PENotificationImageLoader(private val context: Context) : PENotificationImageLoaderType {

    override fun setNotificationImages(payload: FCMPayloadModel, notificationBuilder: Builder, completion: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val commonImageDeferred = async { setCommonImage(payload.commonNotificationImage, payload.notificationId, notificationBuilder) }
            val largeIconDeferred = async { setLargeIcon(payload.largeIcon, payload.notificationId, notificationBuilder) }
            val bigPictureDeferred = async { setBigPicture(payload.bigPicture, payload.notificationId, notificationBuilder) }
            awaitAll(commonImageDeferred, largeIconDeferred, bigPictureDeferred)
            completion()
        }
    }

    private suspend fun setCommonImage(imageUrl: String?, id: Int?, notificationBuilder: Builder) {
        if (!imageUrl.isNullOrEmpty()) {
            val resource = loadImageAsync(imageUrl)
            resource?.let {
                withContext(Dispatchers.Main) {
                    notificationBuilder.setLargeIcon(it)
                    notificationBuilder.setStyle(
                            NotificationCompat.BigPictureStyle()
                                    .bigPicture(it)
                                    .bigLargeIcon(null))
                }
            }
        }
    }

    private suspend fun setLargeIcon(imageUrl: String?, id: Int?, notificationBuilder: Builder) {
        if (!imageUrl.isNullOrEmpty()) {
            val resource = loadImageAsync(imageUrl)
            resource?.let {
                withContext(Dispatchers.Main) {
                    notificationBuilder.setLargeIcon(resource)
                }
            }
        }
    }

    private suspend fun setBigPicture(imageUrl: String?, id: Int?, notificationBuilder: Builder) {
        if (!imageUrl.isNullOrEmpty()) {
            val resource = loadImageAsync(imageUrl)
            resource?.let {
                withContext(Dispatchers.Main) {
                    notificationBuilder.setStyle(NotificationCompat.BigPictureStyle()
                            .bigPicture(resource)
                            .bigLargeIcon(null))
                }
            }
        }
    }

    private suspend fun loadImageAsync(imageUrl: String): Bitmap? = suspendCoroutine { continuation ->
        try {
            Glide.with(context)
                    .asBitmap()
                    .load(imageUrl)
                    .into(object : CustomTarget<Bitmap?>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap?>?) {
                            continuation.resume(resource)
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            continuation.resume(null)
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            continuation.resume(null)
                        }
                    })
        } catch (e: Exception) {
            PELogger.error("PEImageLoader", e)
            continuation.resume(null)
        }
    }

}