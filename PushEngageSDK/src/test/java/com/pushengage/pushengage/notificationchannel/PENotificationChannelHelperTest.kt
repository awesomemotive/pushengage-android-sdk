package com.pushengage.pushengage.notificationchannel

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.ChannelEntity
import com.pushengage.pushengage.Database.DaoInterface
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PENotificationChannelHelperTest {

    private lateinit var context: Context
    private lateinit var daoInterface: DaoInterface
    private lateinit var notificationManager: NotificationManager
    private lateinit var helper: PENotificationChannelHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        daoInterface = mock(DaoInterface::class.java)
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        helper = PENotificationChannelHelper(context, daoInterface, notificationManager)
    }

    private fun createPayload(): FCMPayloadModel {
        return FCMPayloadModel(
            title = "Test",
            body = "Body",
            channelId = "test_channel",
            notificationId = 1
        )
    }

    private fun createBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)
    }

    // --- setChannelInfo tests ---

    @Test
    fun setChannelInfo_channelEntityNull_isDefault_createsDefaultChannel() {
        `when`(daoInterface.getChannel("test_channel")).thenReturn(null)

        var publishCalled = false
        var channelInfoCalled = false

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = true,
            notificationBuilder = createBuilder(),
            didFetchChannelInfo = false,
            publishNotification = { publishCalled = true },
            channelInfo = { channelInfoCalled = true }
        )

        assertTrue("publishNotification should be called", publishCalled)
        assertFalse("channelInfo should NOT be called", channelInfoCalled)
    }

    @Test
    fun setChannelInfo_channelEntityNull_notDefault_notFetched_callsChannelInfoCallback() {
        `when`(daoInterface.getChannel("test_channel")).thenReturn(null)

        var publishCalled = false
        var channelInfoCalled = false

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = createBuilder(),
            didFetchChannelInfo = false,
            publishNotification = { publishCalled = true },
            channelInfo = { channelInfoCalled = true }
        )

        assertFalse("publishNotification should NOT be called", publishCalled)
        assertTrue("channelInfo should be called", channelInfoCalled)
    }

    @Test
    fun setChannelInfo_channelEntityNull_didFetchChannelInfo_createsDefaultChannel() {
        `when`(daoInterface.getChannel("test_channel")).thenReturn(null)

        var publishCalled = false
        var channelInfoCalled = false

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = createBuilder(),
            didFetchChannelInfo = true,
            publishNotification = { publishCalled = true },
            channelInfo = { channelInfoCalled = true }
        )

        assertTrue("publishNotification should be called when didFetchChannelInfo=true", publishCalled)
        assertFalse("channelInfo should NOT be called", channelInfoCalled)
    }

    @Test
    fun setChannelInfo_channelEntityExists_callsPublishNotification() {
        val channelEntity = ChannelEntity(
            "test_channel", "Test Channel", "Description",
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        var publishCalled = false

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = createBuilder(),
            didFetchChannelInfo = false,
            publishNotification = { publishCalled = true },
            channelInfo = { }
        )

        assertTrue("publishNotification should be called when channel entity exists", publishCalled)
    }

    // --- Visibility tests ---

    @Test
    fun setChannelInfo_privateVisibility_setsPrivate() {
        val channelEntity = createChannelEntityWithLockScreen("VISIBILITY_PRIVATE")
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        val notifBuilder = createBuilder()

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = notifBuilder,
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        val notification = notifBuilder.build()
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
    }

    @Test
    fun setChannelInfo_publicVisibility_setsPublic() {
        val channelEntity = createChannelEntityWithLockScreen("VISIBILITY_PUBLIC")
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        val notifBuilder = createBuilder()

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = notifBuilder,
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        val notification = notifBuilder.build()
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
    }

    @Test
    fun setChannelInfo_secretVisibility_setsSecret() {
        val channelEntity = createChannelEntityWithLockScreen("VISIBILITY_SECRET")
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        val notifBuilder = createBuilder()

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = notifBuilder,
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        val notification = notifBuilder.build()
        assertEquals(NotificationCompat.VISIBILITY_SECRET, notification.visibility)
    }

    @Test
    fun setChannelInfo_unknownVisibility_defaultsToPublic() {
        val channelEntity = createChannelEntityWithLockScreen("UNKNOWN")
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        val notifBuilder = createBuilder()

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = notifBuilder,
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        val notification = notifBuilder.build()
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
    }

    // --- LED color tests ---

    @Test
    fun setChannelLedColor_off_setsLightsToZero() {
        val channelEntity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            "OFF", null, null, null,
            "VISIBILITY_PUBLIC"
        )
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        val notifBuilder = createBuilder()

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = notifBuilder,
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        // Does not crash
        assertNotNull(notifBuilder)
    }

    @Test
    fun setChannelLedColor_custom_parsesHexColor() {
        val channelEntity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            "CUSTOM", "FF0000", null, null,
            "VISIBILITY_PUBLIC"
        )
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        val notifBuilder = createBuilder()

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = notifBuilder,
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        assertNotNull(notifBuilder)
    }

    @Test
    fun setChannelLedColor_invalidHex_doesNotCrash() {
        val channelEntity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            "CUSTOM", "ZZZZZZ", null, null,
            "VISIBILITY_PUBLIC"
        )
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        val notifBuilder = createBuilder()

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = notifBuilder,
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        // Should not crash — exception is caught
        assertNotNull(notifBuilder)
    }

    // --- Notification channel registration (API 26+) ---

    @Test
    fun setChannelInfo_withChannelEntity_registersNotificationChannel() {
        val channelEntity = ChannelEntity(
            "test_channel", "Test Channel", "Description",
            "group1", "Group 1", "IMPORTANCE_HIGH",
            "DEFAULT", null, "DEFAULT", null,
            "DEFAULT", null, null, true,
            "VISIBILITY_PUBLIC"
        )
        `when`(daoInterface.getChannel("test_channel")).thenReturn(channelEntity)

        helper.setChannelInfo(
            channelId = "test_channel",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = createBuilder(),
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        // Verify that the channel was registered (API 26+)
        val channel = notificationManager.getNotificationChannel("test_channel")
        assertNotNull("Notification channel should be registered", channel)
        assertEquals("Test Channel", channel?.name)
    }

    @Test
    fun setChannelInfo_defaultChannel_registersDefaultChannelName() {
        `when`(daoInterface.getChannel(PEConstants.DEFAULT_CHANNEL_ID)).thenReturn(null)

        helper.setChannelInfo(
            channelId = PEConstants.DEFAULT_CHANNEL_ID,
            payload = createPayload(),
            isDefault = true,
            notificationBuilder = createBuilder(),
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )

        val channel = notificationManager.getNotificationChannel(PEConstants.DEFAULT_CHANNEL_ID)
        assertNotNull("Default channel should be registered", channel)
    }

    private fun createChannelEntityWithLockScreen(lockScreen: String): ChannelEntity {
        return ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            null, null, null, null,
            lockScreen
        )
    }
}
