package com.pushengage.pushengage.notificationhandling

import android.content.Context
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash-scenario tests for PENotificationBuilder.
 *
 * Tests marked "PROVES BUG" will FAIL on current production code because
 * Color.parseColor("#ZZZZZZ") throws IllegalArgumentException and there is
 * no try-catch around it (line 231).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PENotificationBuilderCrashTest {

    private lateinit var context: Context
    private lateinit var gson: Gson
    private lateinit var prefs: PEPrefs
    private lateinit var builder: PENotificationBuilder
    private lateinit var mockImageLoader: PENotificationImageLoaderType

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()
        gson = Gson()
        prefs = PEPrefs(context)
        mockImageLoader = mock(PENotificationImageLoaderType::class.java)
        builder = PENotificationBuilder(context, gson, prefs, mockImageLoader)
    }

    // --- setAccentColor crash tests (PROVES BUG: no try-catch at line 231) ---

    @Test(expected = IllegalArgumentException::class)
    fun setAccentColor_invalidHex_crashes() {
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            accentColor = "ZZZZZZ", notificationId = 1
        )
        builder.createNotificationBuilder(payload, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setAccentColor_shortHex_crashes() {
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            accentColor = "F", notificationId = 1
        )
        builder.createNotificationBuilder(payload, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setAccentColor_emptyString_crashes() {
        // Note: accentColor="" passes isNullOrEmpty check? No - empty string returns true for isNullOrEmpty,
        // so this should NOT enter the branch. Let's test with a single space instead.
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            accentColor = " ", notificationId = 1
        )
        builder.createNotificationBuilder(payload, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setAccentColor_withHashPrefix_crashes() {
        // If server sends "#FF5733", code does "#" + "#FF5733" = "##FF5733"
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            accentColor = "#FF5733", notificationId = 1
        )
        builder.createNotificationBuilder(payload, null)
    }

    // --- createClickPendingIntent with null notificationId ---

    @Test
    fun createClickPendingIntent_nullNotificationId_pendingIntentIsNull() {
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            url = "https://example.com",
            notificationId = null
        )
        // Should not crash - PendingIntent will be null via ?.let
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createClickPendingIntent_nullNotificationId_notificationStillBuilds() {
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            notificationId = null
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()
        assertNotNull(notification)
    }

    // --- All fields null/empty ---

    @Test
    fun allFieldsNull_doesNotCrash() {
        val payload = FCMPayloadModel()
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun allFieldsEmptyStrings_doesNotCrash() {
        val payload = FCMPayloadModel(
            actionButtons = "", additionalData = "", body = "",
            bigPicture = "", channelId = "", groupKey = "",
            commonNotificationImage = "", largeIcon = "",
            priority = "", smallIcon = "", tag = "",
            title = "", url = "", reFetch = "",
            accentColor = "", commonUrl = ""
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    // --- setSmallIcon edge cases ---

    @Test
    fun setSmallIcon_invalidIconName_fallsBackToPrefs() {
        prefs.smallIconResource = "ic_stat_notification_default"
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            smallIcon = "nonexistent_icon_xyz",
            notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun setSmallIcon_bothIconNamesInvalid_fallsBackToDefault() {
        prefs.smallIconResource = "also_nonexistent_xyz"
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            smallIcon = "nonexistent_icon_xyz",
            notificationId = 1
        )
        // Both icon names are invalid, setSmallIcon sets 0.
        // The try-catch will handle the exception if setSmallIcon(0) fails
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    // --- createActionButtons edge cases ---

    @Test
    fun createActionButtons_nullLabel_doesNotCrash() {
        // ActionButton with null label
        val actionButtonsJson = """[{"l":null,"i":"","u":"https://shop.com"}]"""
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            actionButtons = actionButtonsJson, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createActionButtons_nullUrl_doesNotCrash() {
        val actionButtonsJson = """[{"l":"Buy","i":"","u":null}]"""
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            actionButtons = actionButtonsJson, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createActionButtons_missingJsonFields_doesNotCrash() {
        // JSON object without l/i/u keys
        val actionButtonsJson = """[{"x":"y"}]"""
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            actionButtons = actionButtonsJson, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createActionButtons_truncatedJson_doesNotCrash() {
        // Incomplete JSON
        val actionButtonsJson = """[{"l":"Buy"""
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            actionButtons = actionButtonsJson, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createActionButtons_threeButtons_addsThreeActions() {
        val actionButtonsJson = """[{"l":"A","i":"","u":""},{"l":"B","i":"","u":""},{"l":"C","i":"","u":""}]"""
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            actionButtons = actionButtonsJson, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()
        assertEquals(3, notification.actions?.size ?: 0)
    }

    // --- setNotificationPriority ---

    @Test
    fun setNotificationPriority_caseSensitive_returnsDefault() {
        // "HIGH" uppercase doesn't match "high" in the when block
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            priority = "HIGH", notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, notifBuilder.priority)
    }

    // --- Large/unusual content ---

    @Test
    fun createClickPendingIntent_veryLongUrl_doesNotCrash() {
        val longUrl = "https://example.com/" + "a".repeat(10000)
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            url = longUrl, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createActionButtons_largeJsonArray_doesNotCrash() {
        val buttons = (1..20).joinToString(",") { """{"l":"Button$it","i":"","u":""}""" }
        val actionButtonsJson = "[$buttons]"
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            actionButtons = actionButtonsJson, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createNotificationBuilder_unicodeTitleAndBody_doesNotCrash() {
        val payload = FCMPayloadModel(
            title = "\uD83D\uDE00 你好世界 مرحبا",
            body = "🎉 Special chars: <>&\"'",
            notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()
        assertNotNull(notification)
    }

    // --- setContentTitleAndText null cases ---

    @Test
    fun setContentTitleAndText_nullBody_doesNotCrash() {
        val payload = FCMPayloadModel(
            title = "Title", body = null, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun setContentTitleAndText_bothNull_doesNotCrash() {
        val payload = FCMPayloadModel(
            title = null, body = null, notificationId = 1
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    // --- additionalData ---

    @Test
    fun createNotificationBuilder_nullAdditionalData_doesNotCrash() {
        val payload = FCMPayloadModel(
            title = "Title", body = "Body",
            notificationId = 1, url = "https://example.com"
        )
        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }
}
