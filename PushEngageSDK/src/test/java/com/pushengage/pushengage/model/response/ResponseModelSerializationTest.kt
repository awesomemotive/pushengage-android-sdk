package com.pushengage.pushengage.model.response

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ResponseModelSerializationTest {

    private lateinit var gson: Gson

    @Before
    fun setUp() {
        gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
    }

    // --- NetworkResponse Tests ---

    @Test
    fun networkResponse_deserializesSuccessResponse() {
        val json = """
        {
            "error_code": 0,
            "data": {"key": "value"}
        }
        """.trimIndent()

        val response = gson.fromJson(json, NetworkResponse::class.java)

        assertEquals(0L, response.errorCode)
        assertNotNull(response.data)
    }

    @Test
    fun networkResponse_deserializesErrorWithMessage() {
        val json = """
        {
            "error_code": 400,
            "error_message": "Bad request"
        }
        """.trimIndent()

        val response = gson.fromJson(json, NetworkResponse::class.java)

        assertEquals(400L, response.errorCode)
        assertEquals("Bad request", response.errorMessage)
        assertNull(response.data)
    }

    @Test
    fun networkResponse_deserializesNestedErrorObject() {
        val json = """
        {
            "error_code": 500,
            "error": {
                "message": "Internal server error",
                "code": 5001
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, NetworkResponse::class.java)

        assertEquals(500L, response.errorCode)
        assertNotNull(response.error)
        assertEquals("Internal server error", response.error.message)
        assertEquals(5001, response.error.code)
    }

    @Test
    fun networkResponse_handlesNullFields() {
        val json = "{}"

        val response = gson.fromJson(json, NetworkResponse::class.java)

        assertNull(response.errorCode)
        assertNull(response.data)
        assertNull(response.errorMessage)
        assertNull(response.error)
    }

    @Test
    fun networkResponse_constructorSetsAllFields() {
        val error = NetworkResponse().Error("err msg", 404)
        val response = NetworkResponse(200L, "some data", "no error", error)

        assertEquals(200L, response.errorCode)
        assertEquals("some data", response.data)
        assertEquals("no error", response.errorMessage)
        assertNotNull(response.error)
    }

    // --- AddSubscriberResponse Tests ---

    @Test
    fun addSubscriberResponse_extractsSubscriberHash() {
        val json = """
        {
            "error_code": 0,
            "data": {
                "subscriber_hash": "abc123def456"
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, AddSubscriberResponse::class.java)

        assertEquals(0L, response.errorCode)
        assertNotNull(response.data)
        assertEquals("abc123def456", response.data.subscriberHash)
    }

    @Test
    fun addSubscriberResponse_handlesNullData() {
        val json = """{"error_code": 404}"""

        val response = gson.fromJson(json, AddSubscriberResponse::class.java)

        assertEquals(404L, response.errorCode)
        assertNull(response.data)
    }

    // --- AndroidSyncResponse Tests ---

    @Test
    fun androidSyncResponse_parsesAllApiUrls() {
        val json = """
        {
            "error_code": 0,
            "data": {
                "site_id": 12345,
                "site_status": "active",
                "site_name": "Test Site",
                "api": {
                    "backend": "https://api.example.com/",
                    "backend_cdn": "https://cdn.example.com/",
                    "analytics": "https://analytics.example.com/",
                    "trigger": "https://trigger.example.com/",
                    "optin": "https://optin.example.com/",
                    "log": "https://log.example.com/"
                }
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, AndroidSyncResponse::class.java)

        assertEquals(0L, response.errorCode)
        assertNotNull(response.data)
        assertNotNull(response.data.api)
        assertEquals("https://api.example.com/", response.data.api.backend)
        assertEquals("https://cdn.example.com/", response.data.api.backendCdn)
        assertEquals("https://analytics.example.com/", response.data.api.analytics)
        assertEquals("https://trigger.example.com/", response.data.api.trigger)
        assertEquals("https://optin.example.com/", response.data.api.optin)
        assertEquals("https://log.example.com/", response.data.api.log)
    }

    @Test
    fun androidSyncResponse_parsesFlags() {
        val json = """
        {
            "error_code": 0,
            "data": {
                "site_id": 100,
                "is_eu": 1,
                "geo_fetch": true,
                "delete_on_notification_disable": true,
                "site_status": "active"
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, AndroidSyncResponse::class.java)

        assertEquals(1L, response.data.isEu)
        assertTrue(response.data.geoLocationEnabled)
        assertTrue(response.data.deleteOnNotificationDisable)
    }

    @Test
    fun androidSyncResponse_parsesSiteMetadata() {
        val json = """
        {
            "error_code": 0,
            "data": {
                "site_id": 99,
                "site_status": "active",
                "site_name": "My Site",
                "site_subdomain": "mysite",
                "is_sponsored": 0,
                "firebase_sender_id": "sender_123"
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, AndroidSyncResponse::class.java)

        assertEquals(99L, response.data.siteId)
        assertEquals("active", response.data.siteStatus)
        assertEquals("My Site", response.data.siteName)
        assertEquals("mysite", response.data.siteSubdomain)
        assertEquals(0L, response.data.isSponsored)
        assertEquals("sender_123", response.data.firebaseSenderId)
    }

    // --- ChannelResponse Tests ---

    @Test
    fun channelResponse_parsesOptionsWithAllFields() {
        val json = """
        {
            "error_code": 0,
            "data": {
                "channel_id": 1,
                "group_id": 2,
                "channel_name": "Promotions",
                "channel_description": "Promotional notifications",
                "site_id": 100,
                "group_name": "Marketing",
                "options": {
                    "importance": "IMPORTANCE_HIGH",
                    "sound": "CUSTOM",
                    "sound_file": "notification_tone",
                    "vibration": "CUSTOM",
                    "vibration_pattern": [100, 50, 100],
                    "led_color": "CUSTOM",
                    "led_color_code": "FF0000",
                    "accent_color": "00FF00",
                    "badges": true,
                    "lock_screen": "VISIBILITY_PUBLIC"
                }
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, ChannelResponse::class.java)

        assertEquals(0L, response.errorCode)
        assertNotNull(response.data)
        assertEquals(1L, response.data.channelId)
        assertEquals(2L, response.data.groupId)
        assertEquals("Promotions", response.data.channelName)
        assertEquals("Promotional notifications", response.data.channelDescription)
        assertEquals(100L, response.data.siteId)
        assertEquals("Marketing", response.data.groupName)

        val options = response.data.options
        assertNotNull(options)
        assertEquals("IMPORTANCE_HIGH", options.importance)
        assertEquals("CUSTOM", options.sound)
        assertEquals("notification_tone", options.soundFile)
        assertEquals("CUSTOM", options.vibration)
        assertEquals(listOf(100L, 50L, 100L), options.vibrationPattern)
        assertEquals("CUSTOM", options.ledColor)
        assertEquals("FF0000", options.ledColorCode)
        assertEquals("00FF00", options.accentColor)
        assertTrue(options.badges)
        assertEquals("VISIBILITY_PUBLIC", options.lockScreen)
    }

    @Test
    fun channelResponse_handlesEmptyOptions() {
        val json = """
        {
            "error_code": 0,
            "data": {
                "channel_id": 1,
                "group_id": 0,
                "channel_name": "Default",
                "site_id": 1,
                "options": {}
            }
        }
        """.trimIndent()

        val response = gson.fromJson(json, ChannelResponse::class.java)

        assertNotNull(response.data.options)
        assertNull(response.data.options.importance)
        assertNull(response.data.options.sound)
        assertNull(response.data.options.vibrationPattern)
    }
}
