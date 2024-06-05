package com.pushengage.pushengage.model.request

import com.google.gson.annotations.SerializedName

data class TriggerCampaign(
    val campaignName: String,
    val eventName: String,
    var referenceId: String? = null,
    var profileId: String? = null,
    var data: Map<String, String>? = null
)

internal data class TriggerCampaignRequest(
    @SerializedName("site_id")
    val siteId: Long,
    @SerializedName("device_token_hash")
    val deviceTokenHash: String,
    @SerializedName("campaign_name")
    val campaignName: String,
    @SerializedName("event_name")
    val eventName: String,
    @SerializedName("timezone")
    val timezone: String?,
    @SerializedName("ref_id")
    var referenceId: String? = null,
    @SerializedName("profile_id")
    var profileId: String? = null,
    @SerializedName("data")
    var data: Map<String, String>? = null
)

internal data class TriggerCampaignRequestModel(
    @SerializedName("PartitionKey")
    val partitionKey: String,
    @SerializedName("Data")
    val data: TriggerCampaignRequest
)

internal data class TriggerCampaignResponse(
    @SerializedName("SequenceNumber")
    val sequenceNumber: String,
    @SerializedName("ShardId")
    val shardID: String
)
