package com.pushengage.pushengage.model.payload

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FCMPayloadModelSerializationTest {

    private lateinit var gson: Gson

    @Before
    fun setUp() {
        gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
    }

    @Test
    fun deserializesFullPayload_allFieldsPopulated() {
        val json = """
        {
            "ab": "[{\"l\":\"Buy\",\"i\":\"icon\",\"u\":\"https://example.com\"}]",
            "ad": "{\"key\":\"value\"}",
            "b": "Test body",
            "bp": "https://example.com/big.png",
            "ci": "channel_1",
            "gk": "group_key_1",
            "id": 12345,
            "im": "https://example.com/image.png",
            "li": "https://example.com/large.png",
            "p": "high",
            "si": "ic_small",
            "tag": "test_tag",
            "t": "Test Title",
            "u": "https://example.com/click",
            "rf": "1",
            "pb": {"postback_key": "postback_value"},
            "ac": "FF5733",
            "cu": "https://example.com/common"
        }
        """.trimIndent()

        val payload = gson.fromJson(json, FCMPayloadModel::class.java)

        assertEquals("[{\"l\":\"Buy\",\"i\":\"icon\",\"u\":\"https://example.com\"}]", payload.actionButtons)
        assertEquals("{\"key\":\"value\"}", payload.additionalData)
        assertEquals("Test body", payload.body)
        assertEquals("https://example.com/big.png", payload.bigPicture)
        assertEquals("channel_1", payload.channelId)
        assertEquals("group_key_1", payload.groupKey)
        assertEquals(12345, payload.notificationId)
        assertEquals("https://example.com/image.png", payload.commonNotificationImage)
        assertEquals("https://example.com/large.png", payload.largeIcon)
        assertEquals("high", payload.priority)
        assertEquals("ic_small", payload.smallIcon)
        assertEquals("test_tag", payload.tag)
        assertEquals("Test Title", payload.title)
        assertEquals("https://example.com/click", payload.url)
        assertEquals("1", payload.reFetch)
        assertNotNull(payload.postbackData)
        assertEquals("FF5733", payload.accentColor)
        assertEquals("https://example.com/common", payload.commonUrl)
    }

    @Test
    fun deserializesMinimalPayload_nullableFieldsDefaultToNull() {
        val json = """{"t": "Title", "b": "Body"}"""

        val payload = gson.fromJson(json, FCMPayloadModel::class.java)

        assertEquals("Title", payload.title)
        assertEquals("Body", payload.body)
        assertNull(payload.actionButtons)
        assertNull(payload.additionalData)
        assertNull(payload.bigPicture)
        assertNull(payload.channelId)
        assertNull(payload.groupKey)
        assertNull(payload.notificationId)
        assertNull(payload.commonNotificationImage)
        assertNull(payload.largeIcon)
        assertNull(payload.priority)
        assertNull(payload.smallIcon)
        assertNull(payload.tag)
        assertNull(payload.url)
        assertNull(payload.reFetch)
        assertNull(payload.postbackData)
        assertNull(payload.accentColor)
        assertNull(payload.commonUrl)
    }

    @Test
    fun deserializesEmptyJson_allFieldsNull() {
        val json = "{}"
        val payload = gson.fromJson(json, FCMPayloadModel::class.java)

        assertNull(payload.title)
        assertNull(payload.body)
        assertNull(payload.actionButtons)
        assertNull(payload.channelId)
        assertNull(payload.notificationId)
    }

    @Test
    fun deserializesActionButtons_nestedJsonArray() {
        val actionButtonsJson = """[{"l":"Buy Now","i":"cart_icon","u":"https://shop.com"},{"l":"Dismiss","i":"close","u":""}]"""
        val json = """{"ab": ${gson.toJson(actionButtonsJson)}}"""

        val payload = gson.fromJson(json, FCMPayloadModel::class.java)

        assertNotNull(payload.actionButtons)
        assertTrue(payload.actionButtons!!.contains("Buy Now"))
        assertTrue(payload.actionButtons!!.contains("Dismiss"))
    }

    @Test
    fun deserializesActionButton_withAbbreviatedKeys() {
        val json = """{"l": "Click Me", "i": "icon_name", "u": "https://example.com/action"}"""

        val button = gson.fromJson(json, FCMPayloadModel.ActionButton::class.java)

        assertEquals("Click Me", button.label)
        assertEquals("icon_name", button.icon)
        assertEquals("https://example.com/action", button.url)
    }

    @Test
    fun serializesAndDeserializesRoundTrip_preservesData() {
        val original = FCMPayloadModel(
            actionButtons = "[{\"l\":\"OK\"}]",
            body = "Hello",
            title = "World",
            channelId = "ch1",
            notificationId = 42,
            url = "https://test.com",
            priority = "high",
            tag = "tag1",
            accentColor = "FF0000",
            commonUrl = "https://common.com",
            groupKey = "grp1"
        )

        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, FCMPayloadModel::class.java)

        assertEquals(original.actionButtons, deserialized.actionButtons)
        assertEquals(original.body, deserialized.body)
        assertEquals(original.title, deserialized.title)
        assertEquals(original.channelId, deserialized.channelId)
        assertEquals(original.notificationId, deserialized.notificationId)
        assertEquals(original.url, deserialized.url)
        assertEquals(original.priority, deserialized.priority)
        assertEquals(original.tag, deserialized.tag)
        assertEquals(original.accentColor, deserialized.accentColor)
        assertEquals(original.commonUrl, deserialized.commonUrl)
        assertEquals(original.groupKey, deserialized.groupKey)
    }

    @Test
    fun handlesUnknownFields_ignoresWithoutCrash() {
        val json = """
        {
            "t": "Title",
            "b": "Body",
            "unknown_field": "value",
            "another_unknown": 123
        }
        """.trimIndent()

        val payload = gson.fromJson(json, FCMPayloadModel::class.java)

        assertEquals("Title", payload.title)
        assertEquals("Body", payload.body)
    }

    @Test
    fun channelIdIsMutable_canBeOverwritten() {
        val payload = FCMPayloadModel(channelId = "original")
        assertEquals("original", payload.channelId)

        payload.channelId = "modified"
        assertEquals("modified", payload.channelId)
    }

    @Test
    fun notificationIdIsMutable_canBeOverwritten() {
        val payload = FCMPayloadModel(notificationId = 100)
        assertEquals(100, payload.notificationId)

        payload.notificationId = 200
        assertEquals(200, payload.notificationId)
    }
}
