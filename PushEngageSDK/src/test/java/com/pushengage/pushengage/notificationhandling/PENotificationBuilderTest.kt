package com.pushengage.pushengage.notificationhandling

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.model.payload.FCMPayloadModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PENotificationBuilderTest {

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

    // --- getChannelId tests ---

    @Test
    fun getChannelId_withChannelId_returnsPayloadChannelId() {
        val payload = FCMPayloadModel(
            channelId = "custom_channel",
            title = "Test",
            body = "Body",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        // The builder was created with the channelId from payload
        assertNotNull(notifBuilder)
    }

    @Test
    fun getChannelId_withNullChannelId_returnsDefaultChannel() {
        val payload = FCMPayloadModel(
            channelId = null,
            title = "Test",
            body = "Body",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun getChannelId_withEmptyChannelId_returnsDefaultChannel() {
        val payload = FCMPayloadModel(
            channelId = "",
            title = "Test",
            body = "Body",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    // --- createNotificationBuilder tests ---

    @Test
    fun createNotificationBuilder_setsAutoCancel() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        // AutoCancel sets FLAG_AUTO_CANCEL
        assertTrue(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    // --- createClickPendingIntent tests ---

    @Test
    fun createClickPendingIntent_withUrl_setsUrlExtra() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            url = "https://example.com/click",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
        // Notification builder was created without crash, meaning intent was set
    }

    @Test
    fun createClickPendingIntent_withoutUrl_fallsBackToCommonUrl() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            url = null,
            commonUrl = "https://example.com/common",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createClickPendingIntent_withNoUrls_noUrlExtra() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            url = null,
            commonUrl = null,
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    // --- createActionButtons tests ---

    @Test
    fun createActionButtons_validJsonArray_addsActions() {
        val actionButtonsJson = """[{"l":"Buy","i":"","u":"https://shop.com"},{"l":"Later","i":"","u":""}]"""
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            actionButtons = actionButtonsJson,
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertEquals(2, notification.actions?.size ?: 0)
        assertEquals("Buy", notification.actions[0].title)
        assertEquals("Later", notification.actions[1].title)
    }

    @Test
    fun createActionButtons_invalidJson_doesNotCrash() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            actionButtons = "not valid json",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    @Test
    fun createActionButtons_emptyArray_noActionsAdded() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            actionButtons = "[]",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun createActionButtons_nullActionButtons_noActionsAdded() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            actionButtons = null,
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    // --- getActionButtonIconResourceId tests ---
    // Regression: issue #33 — when the server stops sending action button icons, the previous
    // implementation called Resources.getIdentifier(null, ...), which threw NPE and produced a
    // bogus error log. The fix short-circuits null/empty names to return 0 silently.

    @Test
    fun getActionButtonIconResourceId_nullIconName_returnsZeroSilently() {
        val method = PENotificationBuilder::class.java.getDeclaredMethod(
            "getActionButtonIconResourceId",
            String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(builder, null as String?) as Int
        assertEquals(0, result)
    }

    @Test
    fun getActionButtonIconResourceId_emptyIconName_returnsZeroSilently() {
        val method = PENotificationBuilder::class.java.getDeclaredMethod(
            "getActionButtonIconResourceId",
            String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(builder, "") as Int
        assertEquals(0, result)
    }

    @Test
    fun getActionButtonIconResourceId_unknownDrawableName_returnsZero() {
        val method = PENotificationBuilder::class.java.getDeclaredMethod(
            "getActionButtonIconResourceId",
            String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(builder, "no_such_drawable_in_app") as Int
        assertEquals("Unknown drawable name must resolve to 0, not throw", 0, result)
    }

    @Test
    fun createActionButtons_nullIconKey_doesNotCrashAndStillAddsAction() {
        // Action button payload with `i` (icon) field absent — mirrors what the server now sends.
        val actionButtonsJson = """[{"l":"Open","u":"https://example.com"}]"""
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            actionButtons = actionButtonsJson,
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertEquals(1, notification.actions?.size ?: 0)
        assertEquals("Open", notification.actions[0].title)
        // Action icon resource id must be 0 (no icon) — historically the trigger for the
        // erroneous PELogger.error call.
        assertEquals(0, notification.actions[0].icon)
    }

    // --- setContentTitleAndText tests ---

    @Test
    fun setContentTitleAndText_setsTitle() {
        val payload = FCMPayloadModel(
            title = "My Title",
            body = "My Body",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        // Notification extras contain the title
        assertEquals("My Title", notification.extras?.getCharSequence("android.title")?.toString())
    }

    @Test
    fun setContentTitleAndText_setsBody() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Test Body Content",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertEquals("Test Body Content", notification.extras?.getCharSequence("android.text")?.toString())
    }

    @Test
    fun setContentTitleAndText_nullTitle_doesNotSetTitle() {
        val payload = FCMPayloadModel(
            title = null,
            body = "Body only",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertNull(notification.extras?.getCharSequence("android.title"))
    }

    // --- setAccentColor tests ---

    @Test
    fun setAccentColor_validHex_setsColor() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            accentColor = "FF5733",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
        // Color was parsed without throwing
    }

    @Test
    fun setAccentColor_null_doesNotSetColor() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            accentColor = null,
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertNotNull(notifBuilder)
    }

    // --- setNotificationPriority tests ---

    @Test
    fun setNotificationPriority_high_setsPriorityMax() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            priority = "high",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertEquals(NotificationCompat.PRIORITY_MAX, notifBuilder.priority)
    }

    @Test
    fun setNotificationPriority_min_setsPriorityMin() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            priority = "min",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertEquals(NotificationCompat.PRIORITY_MIN, notifBuilder.priority)
    }

    @Test
    fun setNotificationPriority_null_setsPriorityDefault() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            priority = null,
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, notifBuilder.priority)
    }

    @Test
    fun setNotificationPriority_unknownValue_setsPriorityDefault() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            priority = "unknown",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, notifBuilder.priority)
    }

    // --- setGroupKey tests ---

    @Test
    fun setGroupKey_nonEmpty_setsGroup() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            groupKey = "my_group",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertEquals("my_group", notification.group)
    }

    @Test
    fun setGroupKey_null_doesNotSetGroup() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            groupKey = null,
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertNull(notification.group)
    }

    @Test
    fun setGroupKey_empty_doesNotSetGroup() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            groupKey = "",
            notificationId = 1
        )

        val notifBuilder = builder.createNotificationBuilder(payload, null)
        val notification = notifBuilder.build()

        assertNull(notification.group)
    }

    // --- Additional data is passed through ---

    @Test
    fun createNotificationBuilder_withAdditionalData_doesNotCrash() {
        val payload = FCMPayloadModel(
            title = "Title",
            body = "Body",
            notificationId = 1,
            url = "https://example.com"
        )
        val additionalData = hashMapOf("key1" to "value1", "key2" to "value2")

        val notifBuilder = builder.createNotificationBuilder(payload, additionalData)
        assertNotNull(notifBuilder)
    }

    // --- badge count tests ---

    @Test
    fun createNotificationBuilder_whenBadgeCountPositive_appliesSetNumber() {
        prefs.badgeCount = 9
        val payload = FCMPayloadModel(title = "T", body = "B", notificationId = 1)

        val notification = builder.createNotificationBuilder(payload, null).build()

        assertEquals(9, notification.number)
    }

    @Test
    fun createNotificationBuilder_whenBadgeCountZero_doesNotSetNumber() {
        prefs.badgeCount = 0
        val payload = FCMPayloadModel(title = "T", body = "B", notificationId = 1)

        val notification = builder.createNotificationBuilder(payload, null).build()

        assertEquals(0, notification.number)
    }
}
