package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Standard PushEngage API envelope wrapping every IAM response:
 * `{ "error_code": 0, "data": { ... }, "error_message": "..." }`.
 * The useful payload is [data]; error_code 0 (with HTTP 2xx) means success.
 */
internal data class IAMEnvelope<T>(
    @SerializedName("error_code") val errorCode: Int? = null,
    @SerializedName("error_message") val errorMessage: String? = null,
    @SerializedName("data") val data: T? = null
)
