package com.pushengage.pushengage

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageBadgeCountTest {

    private lateinit var context: Context
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        originalContext = PushEngageTestSupport.getStaticField("context")
        originalPrefs = PushEngageTestSupport.getStaticField("prefs")

        PushEngageTestSupport.setStaticField("context", context)
        PushEngageTestSupport.setStaticField("prefs", PEPrefs(context))
    }

    @After
    fun tearDown() {
        PushEngageTestSupport.setStaticField("context", originalContext)
        PushEngageTestSupport.setStaticField("prefs", originalPrefs)
    }

    @Test
    fun setBadgeCount_withPositiveValue_persistsCount() {
        PushEngage.setBadgeCount(5)

        val prefs = PEPrefs(context)
        assertEquals(5, prefs.badgeCount)
    }

    @Test
    fun setBadgeCount_withZero_persistsZero() {
        val prefs = PEPrefs(context)
        prefs.badgeCount = 3

        PushEngage.setBadgeCount(0)

        assertEquals(0, PEPrefs(context).badgeCount)
    }

    @Test
    fun setBadgeCount_withNegativeValue_coercedToZero() {
        PushEngage.setBadgeCount(-4)

        assertEquals(0, PEPrefs(context).badgeCount)
    }

    @Test
    fun setBadgeCount_withZero_cancelsActiveNotifications() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification: Notification = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test")
            .build()
        nm.notify(42, notification)
        assertEquals(1, shadowOf(nm).activeNotifications.size)

        PushEngage.setBadgeCount(0)

        assertEquals(0, shadowOf(nm).activeNotifications.size)
    }

    @Test
    fun setBadgeCount_withPositiveValue_doesNotCancelActiveNotifications() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification: Notification = NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test")
            .build()
        nm.notify(42, notification)

        PushEngage.setBadgeCount(7)

        assertEquals(1, shadowOf(nm).activeNotifications.size)
    }

    @Test
    fun setBadgeCount_whenSdkNotInitialized_doesNotThrow() {
        PushEngageTestSupport.resetSingleton()

        PushEngage.setBadgeCount(5)
    }
}
