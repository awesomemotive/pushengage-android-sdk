package com.pushengage.pushengage.model.request

import com.google.gson.annotations.SerializedName

/**
 * Track Event data class for sending event data to the server.
 * These events can be used to start or exit workflows.
 *
 * @param eventName The name of the event (required, non-empty).
 * @param data Custom data associated with the event (optional). Values should be strings,
 *             numbers, or booleans — complex objects are not supported by the backend.
 * @param profileId The profile ID of the subscriber (optional).
 * @param provider The provider name (optional, defaults to "PushEngage" when null).
 * @param eventType The event type (optional, defaults to "PushEngage.CustomEvent" when null).
 *
 * `@JvmOverloads` generates Java-friendly constructors so callers can write
 * `new TrackEvent("event_name")`, `new TrackEvent("event_name", data)`, or
 * `new TrackEvent("event_name", data, profileId)` without supplying every
 * optional parameter. `data` and `profileId` are promoted to positions 2 and 3
 * because they are the most commonly set optional fields.
 */
data class TrackEvent @JvmOverloads constructor(
    val eventName: String,
    val data: Map<String, Any>? = null,
    val profileId: String? = null,
    val provider: String? = null,
    val eventType: String? = null
)

internal data class TrackEventRequest(
    @SerializedName("site_id")
    val siteId: Long,
    @SerializedName("device_token_hash")
    val deviceTokenHash: String,
    @SerializedName("event_name")
    val eventName: String,
    @SerializedName("provider")
    val provider: String,
    @SerializedName("event_type")
    val eventType: String,
    @SerializedName("profile_id")
    val profileId: String?,
    @SerializedName("data")
    val data: Map<String, Any>
)
