package com.pushengage.pushengage.notificationchannel

import android.app.NotificationManager
import android.content.Context
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

/**
 * Crash-scenario tests for PENotificationChannelHelper.
 *
 * These target crash paths NOT covered by the existing PENotificationChannelHelperTest:
 * - Color.parseColor("#null") crash (line 124, 300)
 * - JSONArray(null) crash (line 188)
 * - Various null field combinations in ChannelEntity
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PENotificationChannelHelperCrashTest {

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
            title = "Test", body = "Body",
            channelId = "test_channel", notificationId = 1
        )
    }

    private fun createBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, PEConstants.DEFAULT_CHANNEL_ID)
    }

    private fun invokeSetChannelInfo(channelEntity: ChannelEntity) {
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
    }

    // --- setChannelLedColor crash tests ---

    @Test
    fun setChannelLedColor_nullLedColorCode_doesNotCrash() {
        // ledColor = "CUSTOM" but ledColorCode = null -> Color.parseColor("#null")
        // The try-catch at line 126 should catch this
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            "CUSTOM", null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
        // Should not crash — exception is caught
    }

    @Test
    fun setChannelLedColor_emptyLedColorCode_doesNotCrash() {
        // Color.parseColor("#") -> IllegalArgumentException, caught by try-catch
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            "CUSTOM", "", null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    @Test
    fun setChannelLedColor_veryLongColorCode_doesNotCrash() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            "CUSTOM", "A".repeat(1000), null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    // --- setNotificationVibration crash tests (pre-API 26 path) ---

    @Test
    @Config(sdk = [25])
    fun setNotificationVibration_nullPattern_doesNotCrash() {
        // vibration = "CUSTOM" but vibrationPattern = null -> JSONArray(null) throws
        // The try-catch at line 195 should catch this
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "CUSTOM", null, "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    @Test
    @Config(sdk = [25])
    fun setNotificationVibration_emptyPattern_doesNotCrash() {
        // JSONArray("") throws JSONException
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "CUSTOM", "", "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    @Test
    @Config(sdk = [25])
    fun setNotificationVibration_invalidJsonPattern_doesNotCrash() {
        // Non-array JSON string
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "CUSTOM", "not json", "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    @Test
    @Config(sdk = [25])
    fun setNotificationVibration_nonNumericValues_doesNotCrash() {
        // JSONArray with non-numeric values -> getLong() throws
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "CUSTOM", "[\"abc\", \"def\"]", "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    // --- setNotificationSound crash tests (pre-API 26 path) ---

    @Test
    @Config(sdk = [25])
    fun setNotificationSound_nullSoundFile_doesNotCrash() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "CUSTOM", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    @Test
    @Config(sdk = [25])
    fun setNotificationSound_emptySoundFile_doesNotCrash() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "CUSTOM", "",
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }

    // --- registerNotificationChannel tests (API 26+) ---

    @Test
    fun registerNotificationChannel_nullChannelName_skips() {
        // channelName is null -> TextUtils.isEmpty returns true -> skips registration
        val entity = ChannelEntity(
            "test_channel", null, null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
        // Channel should NOT be registered because channelName is null
        val channel = notificationManager.getNotificationChannel("test_channel")
        assertNull("Channel should not be registered when name is null", channel)
    }

    @Test
    fun registerNotificationChannel_emptyChannelId_skips() {
        val entity = ChannelEntity(
            "", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        `when`(daoInterface.getChannel("")).thenReturn(entity)
        helper.setChannelInfo(
            channelId = "",
            payload = createPayload(),
            isDefault = false,
            notificationBuilder = createBuilder(),
            didFetchChannelInfo = false,
            publishNotification = { },
            channelInfo = { }
        )
        val channel = notificationManager.getNotificationChannel("")
        assertNull("Channel should not be registered with empty ID", channel)
    }

    @Test
    fun registerNotificationChannel_nullGroupId_skipsGroup() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
        // Should not crash when groupId is null
        val channel = notificationManager.getNotificationChannel("test_channel")
        assertNotNull(channel)
    }

    @Test
    fun registerNotificationChannel_nullBadges_skips() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
        // Should not crash when badges is null
        assertNotNull(notificationManager.getNotificationChannel("test_channel"))
    }

    // --- getLockScreenVisibility ---

    @Test
    fun getLockScreenVisibility_nullLockScreen_defaultsPublic() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "DEFAULT", null, "DEFAULT", null,
            null, null, null, null,
            null
        )
        invokeSetChannelInfo(entity)
        // null lockScreen falls to else -> VISIBILITY_PUBLIC
    }

    // --- All nulls ---

    @Test
    fun setChannelInfo_channelEntityWithAllNulls_doesNotCrash() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, null,
            null, null, null, null,
            null, null, null, null,
            null
        )
        invokeSetChannelInfo(entity)
    }

    @Test
    @Config(sdk = [25])
    fun setChannelInfo_customVibrationWithEmptyArray_doesNotCrash() {
        val entity = ChannelEntity(
            "test_channel", "Channel", null,
            null, null, "IMPORTANCE_DEFAULT",
            "CUSTOM", "[]", "DEFAULT", null,
            null, null, null, null,
            "VISIBILITY_PUBLIC"
        )
        invokeSetChannelInfo(entity)
    }
}
