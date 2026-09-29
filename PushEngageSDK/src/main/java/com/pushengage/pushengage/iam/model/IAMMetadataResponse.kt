package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Response of GET {cdn}/p/v1/iam/campaigns/metadata?site_key=X
 *. A cheap, short-cached discovery + gate document:
 * the version drives whether campaigns are re-fetched, iam_status gates display,
 * and the api hosts tell the SDK where to fetch campaigns / post analytics.
 */
internal data class IAMMetadataResponse(
    @SerializedName("version") val version: String? = null,
    @SerializedName("site_id") val siteId: Long? = null,
    @SerializedName("iam_status") val iamStatus: String? = null,
    @SerializedName("api") val api: Api? = null
) {
    data class Api(
        @SerializedName("backend") val backend: String? = null,
        @SerializedName("backend_cdn") val backendCdn: String? = null,
        @SerializedName("iam_analytics") val iamAnalytics: String? = null,
        @SerializedName("log") val log: String? = null
    )
}
