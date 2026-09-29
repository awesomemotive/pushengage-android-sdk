package com.pushengage.pushengage.iam.network

import com.pushengage.pushengage.helper.PELogger
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds Retrofit [IAMApi] clients for the IAM backend. Kept separate from the
 * notification RestClient so IAM uses [IAMJson] (ISO-8601 dates + the enum wire
 * values from the contract) rather than a plain Gson.
 *
 * A client is bound to one base host (a URL ending in `/p/v1/`); the caller
 * picks the host — BASE_CDN for metadata, the metadata-advertised backend_cdn
 * for campaigns, iam_analytics for analytics.
 */
internal object IAMRestClient {

    private const val TIMEOUT_SECONDS = 30L

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (PELogger.isLoggingEnabled()) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }
        builder.build()
    }

    /**
     * @param baseUrl a host ending in `/`, e.g. `https://dexter-cdn.pushengage.com/p/v1/`
     */
    @JvmStatic
    fun api(baseUrl: String): IAMApi = Retrofit.Builder()
        .baseUrl(normalizeBaseUrl(baseUrl))
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(IAMJson.gson))
        .build()
        .create(IAMApi::class.java)

    /** Retrofit requires the base URL to end with `/`. */
    private fun normalizeBaseUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
}
