package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Body of POST {iam_analytics}/p/v1/iam/campaigns/analytics?site_key=X
 *. One POST per event; impression and click are 0/1.
 * btn_* are only populated for click events.
 */
internal data class IAMAnalyticsPayload(
    @SerializedName("campaign_id") val campaignId: String,
    @SerializedName("impression") val impression: Int,
    @SerializedName("click") val click: Int,
    @SerializedName("btn_id") val btnId: String? = null,
    @SerializedName("btn_text") val btnText: String? = null,
    @SerializedName("btn_type") val btnType: String? = null
)
