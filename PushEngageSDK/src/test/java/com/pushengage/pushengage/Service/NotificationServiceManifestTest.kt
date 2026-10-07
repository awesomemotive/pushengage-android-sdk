package com.pushengage.pushengage.Service

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the manifest declaration of [NotificationService]. The service is started only by
 * PENotificationHandlerActivity inside the same app; an exported declaration would let any
 * installed app cancel the host's notifications and forge click analytics for its subscriber.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationServiceManifestTest {

    @Test
    fun notificationService_isNotExported() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, NotificationService::class.java), 0
        )
        assertFalse("NotificationService must not be exported", info.exported)
    }
}
