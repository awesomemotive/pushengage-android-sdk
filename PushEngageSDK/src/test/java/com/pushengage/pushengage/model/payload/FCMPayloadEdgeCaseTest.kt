package com.pushengage.pushengage.model.payload

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FCMPayloadEdgeCaseTest {

    private lateinit var gson: Gson

    @Before
    fun setUp() {
        gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
    }

    // --- Null/empty JSON string deserialization ---

    @Test
    fun nullJsonString_deserializesToNull() {
        val result = gson.fromJson(null as String?, FCMPayloadModel::class.java)
        assertNull(result)
    }

    @Test
    fun emptyString_deserializesToNull() {
        val result = gson.fromJson("", FCMPayloadModel::class.java)
        assertNull(result)
    }

    @Test
    fun literalNullString_returnsNull() {
        val result = gson.fromJson("null", FCMPayloadModel::class.java)
        assertNull(result)
    }

    @Test(expected = JsonSyntaxException::class)
    fun malformedJson_throwsJsonSyntaxException() {
        gson.fromJson("{\"t\": \"Title\", \"b\":", FCMPayloadModel::class.java)
    }

    // --- channelId defaulting ---

    @Test
    fun allFieldsNull_channelIdIsNull() {
        val payload = gson.fromJson("{}", FCMPayloadModel::class.java)
        assertNull(payload.channelId)
        assertTrue(payload.channelId.isNullOrEmpty())
    }

    @Test
    fun emptyChannelId_isNullOrEmptyReturnsTrue() {
        val payload = gson.fromJson("{\"ci\": \"\"}", FCMPayloadModel::class.java)
        assertTrue(payload.channelId.isNullOrEmpty())
    }

    // --- reFetch field ---

    @Test
    fun reFetch_null_isNullOrEmptyReturnsTrue() {
        val payload = gson.fromJson("{}", FCMPayloadModel::class.java)
        assertTrue(payload.reFetch.isNullOrEmpty())
    }

    @Test
    fun reFetch_one_equalsOneReturnsTrue() {
        val payload = gson.fromJson("{\"rf\": \"1\"}", FCMPayloadModel::class.java)
        assertEquals("1", payload.reFetch)
        assertTrue(payload.reFetch.equals("1", ignoreCase = true))
    }

    @Test
    fun reFetch_nonOne_equalsOneReturnsFalse() {
        val payload = gson.fromJson("{\"rf\": \"0\"}", FCMPayloadModel::class.java)
        assertFalse(payload.reFetch.equals("1", ignoreCase = true))
    }

    // --- notificationId null ---

    @Test
    fun notificationId_null_payloadAccessible() {
        val payload = gson.fromJson("{\"t\": \"Title\"}", FCMPayloadModel::class.java)
        assertNull(payload.notificationId)
        // This is what the production code does with notificationId?.let
        val result = payload.notificationId?.let { it + 1 }
        assertNull(result)
    }

    // --- additionalData parsing ---

    @Test
    fun additionalData_nullJson_parsesToNull() {
        // Simulates: gson.fromJson(null, HashMap::class.java)
        val result = gson.fromJson(null as String?, HashMap::class.java)
        assertNull(result)
    }

    @Test
    fun additionalData_invalidJson_throwsJsonSyntaxException() {
        // Simulates: gson.fromJson("not json", HashMap::class.java)
        try {
            gson.fromJson("not valid json", HashMap::class.java)
            fail("Expected JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            // Expected
        }
    }

    @Test
    fun additionalData_validJson_parsesToHashMap() {
        val result = gson.fromJson("{\"key\":\"value\"}", HashMap::class.java) as? HashMap<*, *>
        assertNotNull(result)
        assertEquals("value", result!!["key"])
    }

    // --- Extreme values ---

    @Test
    fun payload_extremeValues_doesNotCrash() {
        val longString = "A".repeat(10000)
        val unicodeString = "\uD83D\uDE00\uD83C\uDF1F你好世界"
        val json = """{"t": "$longString", "b": "$unicodeString", "ci": "ch", "id": ${Int.MAX_VALUE}, "ac": "FFFFFF", "gk": "group", "u": "https://example.com/$longString"}"""

        val payload = gson.fromJson(json, FCMPayloadModel::class.java)
        assertNotNull(payload)
        assertEquals(longString, payload.title)
        assertEquals(unicodeString, payload.body)
        assertEquals(Int.MAX_VALUE, payload.notificationId)
    }
}
