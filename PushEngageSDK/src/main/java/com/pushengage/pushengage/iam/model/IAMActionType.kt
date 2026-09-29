package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Represents the types of actions that can be triggered by an in-app message.
 *
 * The lowercase [wireValue] is the single accepted form, matching `position`,
 * `frequency.type` and `trigger.type`, which are lowercase throughout. It is both
 * what the SDK writes as analytics `btn_type` and the only spelling it reads for
 * `actions[].type` — an unrecognised value deserializes to null and leaves the
 * button inert, so the campaign API must send this form.
 */
internal enum class IAMActionType(@JvmField val wireValue: String) {
    // SDK-handled actions
    @SerializedName("open_url")
    OPEN_URL("open_url"), // Opens a URL in a browser

    @SerializedName("request_notification_permission")
    REQUEST_NOTIFICATION_PERMISSION("request_notification_permission"), // Requests notification permission

    @SerializedName("dismiss")
    DISMISS("dismiss"), // Dismisses the message

    // Custom actions delegated to the app
    @SerializedName("custom")
    CUSTOM("custom"); // Custom action handled by app

    /**
     * The lowercase wire value, mirroring this constant's [SerializedName].
     *
     * Reported outbound as analytics `btn_type` so the same vocabulary is used in
     * both directions — inbound `actions[].type` and outbound `btn_type`. Use this
     * in preference to [name] / [toString] whenever the value leaves the SDK;
     * `name` is the Kotlin constant (uppercase) and is not the wire format.
     * `IAMWireFormatTest` asserts these stay in sync with the annotations.
     */
    override fun toString(): String = wireValue
}
