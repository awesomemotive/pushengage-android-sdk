package com.pushengage.pushengage.iam.network

/**
 * A non-2xx response from an IAM endpoint, carrying the status code.
 *
 * The code is what lets a caller tell a permanent rejection of one payload (400, 409,
 * 413, 422) from a transient or systemic failure worth retrying (429, 408, 5xx, and the
 * auth codes). Without it every failure looked alike and one malformed row blocked every
 * analytics event queued behind it indefinitely.
 */
internal class IAMHttpException(val code: Int) : Exception("Analytics POST failed: HTTP $code")
