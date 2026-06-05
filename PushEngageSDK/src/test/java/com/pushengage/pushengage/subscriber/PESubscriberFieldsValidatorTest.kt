package com.pushengage.pushengage.subscriber

import com.pushengage.pushengage.internal.subscriber.PESubscriberFieldsValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [PESubscriberFieldsValidator]. No Robolectric.
 *
 * Mirrors the behavior of the web SDK's subscriber.validator (identify/logout)
 * — see pushengage-web-sdk/client/src/api/validators/subscriber.validator.ts.
 */
class PESubscriberFieldsValidatorTest {

    // ---- validateIdentifyPayload ----

    @Test
    fun identify_null_returnsRequiredMessage() {
        val msg = PESubscriberFieldsValidator.validateIdentifyPayload(null)
        assertNotNull(msg)
        assertTrue(msg!!.contains("required", ignoreCase = true))
    }

    @Test
    fun identify_emptyMap_returnsAtLeastOneKeyMessage() {
        val msg = PESubscriberFieldsValidator.validateIdentifyPayload(emptyMap())
        assertNotNull(msg)
        assertTrue(msg!!.contains("at least one key", ignoreCase = true))
    }

    @Test
    fun identify_allValidKeysWithStringValues_returnsNull() {
        val payload = mapOf(
            "first_name" to "Jane",
            "last_name" to "Doe",
            "email" to "jane@example.com",
            "phone" to "+15551234567",
            "gender" to "female",
            "dob" to "1990-01-01",
            "language" to "en",
            "profile_id" to "user_42",
            "country" to "US",
            "city" to "NYC",
            "state" to "NY",
            "zip" to "10001",
        )
        assertNull(PESubscriberFieldsValidator.validateIdentifyPayload(payload))
    }

    @Test
    fun identify_numberValue_returnsNull() {
        assertNull(PESubscriberFieldsValidator.validateIdentifyPayload(mapOf("profile_id" to 42)))
        assertNull(PESubscriberFieldsValidator.validateIdentifyPayload(mapOf("zip" to 10001L)))
        assertNull(PESubscriberFieldsValidator.validateIdentifyPayload(mapOf("zip" to 3.14)))
    }

    @Test
    fun identify_booleanValue_returnsNull() {
        // Web SDK allows boolean as a value type.
        assertNull(PESubscriberFieldsValidator.validateIdentifyPayload(mapOf("first_name" to true)))
    }

    @Test
    fun identify_invalidKey_returnsKeyNotValidMessage() {
        val msg = PESubscriberFieldsValidator.validateIdentifyPayload(mapOf("favorite_color" to "blue"))
        assertNotNull(msg)
        assertTrue("expected 'favorite_color' to be flagged, got: $msg", msg!!.contains("favorite_color"))
        assertTrue("expected list of valid keys, got: $msg", msg.contains("profile_id"))
    }

    @Test
    fun identify_nonStringNumberBooleanValue_returnsValueTypeMessage() {
        val msg = PESubscriberFieldsValidator.validateIdentifyPayload(mapOf("first_name" to listOf("Jane")))
        assertNotNull(msg)
        assertTrue(msg!!.contains("string", ignoreCase = true))
    }

    @Test
    fun identify_nullValue_returnsValueTypeMessage() {
        val msg = PESubscriberFieldsValidator.validateIdentifyPayload(mapOf("first_name" to null))
        assertNotNull(msg)
        assertTrue(msg!!.contains("string", ignoreCase = true))
    }

    // ---- validateLogoutFieldNames ----

    @Test
    fun logout_allValidNames_returnsNull() {
        assertNull(
            PESubscriberFieldsValidator.validateLogoutFieldNames(
                listOf("first_name", "email", "profile_id"),
            ),
        )
    }

    @Test
    fun logout_emptyList_returnsNull() {
        // Default-fallback is the handler's job; the validator just accepts
        // any subset of the valid set, including the empty subset.
        assertNull(PESubscriberFieldsValidator.validateLogoutFieldNames(emptyList()))
    }

    @Test
    fun logout_invalidName_returnsNotValidMessage() {
        val msg = PESubscriberFieldsValidator.validateLogoutFieldNames(listOf("first_name", "ssn"))
        assertNotNull(msg)
        assertTrue("expected 'ssn' to be flagged, got: $msg", msg!!.contains("ssn"))
    }

    // ---- formatSubscriberFields ----

    @Test
    fun format_profileIdNumber_coercedToString() {
        val out = PESubscriberFieldsValidator.formatSubscriberFields(
            mapOf("email" to "a@b.com", "profile_id" to 12345),
        )
        assertEquals("a@b.com", out["email"])
        assertEquals("12345", out["profile_id"])
        assertTrue("profile_id must be a String after formatting", out["profile_id"] is String)
    }

    @Test
    fun format_profileIdAlreadyString_passesThrough() {
        val out = PESubscriberFieldsValidator.formatSubscriberFields(mapOf("profile_id" to "u_99"))
        assertEquals("u_99", out["profile_id"])
    }

    @Test
    fun format_noProfileId_otherKeysUntouched() {
        val out = PESubscriberFieldsValidator.formatSubscriberFields(mapOf("email" to "a@b.com"))
        assertEquals(mapOf("email" to "a@b.com"), out)
    }

    // ---- DEFAULT_LOGOUT_FIELDS ----

    @Test
    fun defaultLogoutFields_matchesWebSdkPiiSet() {
        // The web SDK falls back to this exact set when logout() is called with
        // no/empty field names. See pushengage-web-sdk subscriber.api.ts logout().
        assertEquals(
            listOf("first_name", "last_name", "email", "phone", "gender", "dob", "profile_id"),
            PESubscriberFieldsValidator.DEFAULT_LOGOUT_FIELDS,
        )
    }
}
