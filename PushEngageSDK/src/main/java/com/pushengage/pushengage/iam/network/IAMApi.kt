package com.pushengage.pushengage.iam.network

import com.pushengage.pushengage.iam.model.IAMAnalyticsPayload
import com.pushengage.pushengage.iam.model.IAMEnvelope
import com.pushengage.pushengage.iam.model.IAMMessageResponse
import com.pushengage.pushengage.iam.model.IAMMetadataResponse
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit surface for the IAM backend. Every response is wrapped
 * in the standard PushEngage [IAMEnvelope]; the campaigns endpoint puts the
 * campaign array directly in `data`. Paths are relative to a host ending in
 * `/p/v1/`.
 */
internal interface IAMApi {

    @GET("iam/campaigns/metadata")
    fun getMetadata(@Query("site_key") siteKey: String): Call<IAMEnvelope<IAMMetadataResponse>>

    @GET("iam/campaigns")
    fun getCampaigns(
        @Query("version") version: String,
        @Query("site_key") siteKey: String
    ): Call<IAMEnvelope<List<IAMMessageResponse>>>

    @POST("iam/campaigns/analytics")
    fun postAnalytics(
        @Query("site_key") siteKey: String,
        @Body payload: IAMAnalyticsPayload
    ): Call<Void>
}
