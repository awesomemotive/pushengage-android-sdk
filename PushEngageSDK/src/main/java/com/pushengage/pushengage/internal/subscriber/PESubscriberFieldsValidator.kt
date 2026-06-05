package com.pushengage.pushengage.internal.subscriber

/**
 * Validates payloads for [com.pushengage.pushengage.PushEngage.identify] and
 * [com.pushengage.pushengage.PushEngage.logout].
 *
 * Pure JVM — no Android dependencies — so it can be exercised by plain JUnit
 * tests without Robolectric. Callers (the handler) are responsible for any
 * JSONObject ↔ Map conversion before invoking these functions.
 *
 * Mirrors the web SDK validator at
 * pushengage-web-sdk/client/src/api/validators/subscriber.validator.ts.
 */
internal object PESubscriberFieldsValidator {

    /**
     * The 12 keys the PushEngage backend recognizes as predefined subscriber
     * fields. Anything outside this set is rejected client-side, matching the
     * web SDK's `validSubscriberFields`.
     */
    val VALID_SUBSCRIBER_FIELDS: List<String> = listOf(
        "first_name",
        "last_name",
        "email",
        "phone",
        "gender",
        "dob",
        "language",
        "profile_id",
        "country",
        "city",
        "state",
        "zip",
    )

    /**
     * Default field set wiped when `logout(...)` is called with a null or
     * empty list — matches the web SDK fallback.
     */
    val DEFAULT_LOGOUT_FIELDS: List<String> = listOf(
        "first_name",
        "last_name",
        "email",
        "phone",
        "gender",
        "dob",
        "profile_id",
    )

    /**
     * Returns null when [fields] is a valid identify payload, else a
     * human-readable error message.
     */
    fun validateIdentifyPayload(fields: Map<String, Any?>?): String? {
        if (fields == null) {
            return "Payload is required. The payload should be in object format, e.g., {key: value}."
        }
        if (fields.isEmpty()) {
            return "Payload must have at least one key."
        }
        for ((key, _) in fields) {
            if (key !in VALID_SUBSCRIBER_FIELDS) {
                return "Key $key is not valid. Valid keys are ${formatList(VALID_SUBSCRIBER_FIELDS)}."
            }
        }
        for ((_, value) in fields) {
            if (value !is String && value !is Number && value !is Boolean) {
                return "Value must be a string, number, or boolean."
            }
        }
        return null
    }

    /**
     * Returns null when every name in [fieldNames] is one of the valid
     * subscriber fields, else a human-readable error message.
     *
     * The empty list is considered valid — the handler normalizes null/empty
     * to [DEFAULT_LOGOUT_FIELDS] before calling.
     */
    fun validateLogoutFieldNames(fieldNames: List<String>): String? {
        for (name in fieldNames) {
            if (name !in VALID_SUBSCRIBER_FIELDS) {
                return "Subscriber field name $name is not valid. Valid names are ${formatList(VALID_SUBSCRIBER_FIELDS)}."
            }
        }
        return null
    }

    /**
     * Coerces a numeric `profile_id` to its string form so it travels to the
     * server in the same shape the web SDK sends — matches
     * `formatSubscriberFields` in subscriber.api.ts.
     */
    fun formatSubscriberFields(fields: Map<String, Any?>): Map<String, Any?> {
        val profileId = fields["profile_id"]
        if (profileId !is Number) {
            return fields
        }
        val result = LinkedHashMap<String, Any?>(fields)
        result["profile_id"] = profileId.toString()
        return result
    }

    private fun formatList(values: List<String>): String {
        if (values.isEmpty()) return ""
        if (values.size == 1) return values[0]
        val head = values.subList(0, values.size - 1).joinToString(", ")
        val tail = values[values.size - 1]
        return "$head, and $tail"
    }
}
