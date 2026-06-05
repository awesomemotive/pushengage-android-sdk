package com.pushengage.pushengage.internal.subscriber

import android.content.Context
import com.google.gson.JsonObject
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.R
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.helper.PEUtilities
import com.pushengage.pushengage.model.response.NetworkResponse
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Orchestrates [com.pushengage.pushengage.PushEngage.identify] and
 * [com.pushengage.pushengage.PushEngage.logout].
 *
 * Flow per call:
 *
 *  1. Synchronous validation via [PESubscriberFieldsValidator]. Failure →
 *     onFailure(400, message). No network, no preflight.
 *  2. Identify only: format payload (coerce numeric `profile_id` to String).
 *  3. apiPreValidate(context). Failure → onFailure(400, validationResult).
 *  4. Cache short-circuit using [PEPrefs.getSubscriberFields]:
 *       - identify: if every requested key already matches the cached value,
 *         fire onSuccess(null) and skip the network call.
 *       - logout: if none of the requested keys exist in the cache, fire
 *         onSuccess(null) and skip the network call.
 *     The short-circuit is bounded by [CACHE_TTL_MS]: once the cache's last
 *     successful-write timestamp is older than 24h, the check is treated as
 *     a miss so the next call forces a server round-trip and refreshes the
 *     timestamp. This caps server-side divergence (dashboard edits, PII
 *     purges) at 24h worst-case.
 *  5. Otherwise enqueue the Retrofit call. On 2xx: mutate cache and
 *     onSuccess(null). On error: parse error_code/error_message from the
 *     body (matching the convention used by addProfileId).
 */
internal object PESubscriberFieldsHandler {

    private const val CACHE_TTL_MS: Long = 24L * 60L * 60L * 1000L

    fun identify(
        context: Context,
        prefs: PEPrefs,
        fields: JSONObject?,
        callback: PushEngageResponseCallback?,
    ) {
        val payload: Map<String, Any?>? = fields?.let(::jsonObjectToMap)
        val validationError = PESubscriberFieldsValidator.validateIdentifyPayload(payload)
        if (validationError != null) {
            callback?.onFailure(400, validationError)
            return
        }
        val formatted = PESubscriberFieldsValidator.formatSubscriberFields(payload!!)

        val preflight = PEUtilities.apiPreValidate(context)
        if (!preflight.equals(PEConstants.VALID, ignoreCase = true)) {
            callback?.onFailure(400, preflight)
            return
        }

        if (isCacheFresh(prefs) && isCacheInSync(prefs.subscriberFields, formatted)) {
            callback?.onSuccess(null)
            return
        }

        val body = mapToGsonJsonObject(formatted)
        val call = RestClient.getBackendClient(context).identifySubscriber(prefs.hash, body)
        call.enqueue(object : Callback<NetworkResponse> {
            override fun onResponse(call: Call<NetworkResponse>, response: Response<NetworkResponse>) {
                if (response.isSuccessful) {
                    prefs.mergeSubscriberFields(formatted)
                    callback?.onSuccess(null)
                } else {
                    propagateError(context, response, callback)
                }
            }

            override fun onFailure(call: Call<NetworkResponse>, t: Throwable) {
                callback?.onFailure(400, t.message)
            }
        })
    }

    fun logout(
        context: Context,
        prefs: PEPrefs,
        fieldNames: List<String>?,
        callback: PushEngageResponseCallback?,
    ) {
        val effective: List<String> = if (fieldNames.isNullOrEmpty()) {
            PESubscriberFieldsValidator.DEFAULT_LOGOUT_FIELDS
        } else {
            fieldNames
        }

        val validationError = PESubscriberFieldsValidator.validateLogoutFieldNames(effective)
        if (validationError != null) {
            callback?.onFailure(400, validationError)
            return
        }

        val preflight = PEUtilities.apiPreValidate(context)
        if (!preflight.equals(PEConstants.VALID, ignoreCase = true)) {
            callback?.onFailure(400, preflight)
            return
        }

        val cached = prefs.subscriberFields
        if (isCacheFresh(prefs) && effective.none { cached.containsKey(it) }) {
            callback?.onSuccess(null)
            return
        }

        val call = RestClient.getBackendClient(context).logoutSubscriberFields(prefs.hash, effective)
        call.enqueue(object : Callback<NetworkResponse> {
            override fun onResponse(call: Call<NetworkResponse>, response: Response<NetworkResponse>) {
                if (response.isSuccessful) {
                    prefs.removeSubscriberFields(effective)
                    callback?.onSuccess(null)
                } else {
                    propagateError(context, response, callback)
                }
            }

            override fun onFailure(call: Call<NetworkResponse>, t: Throwable) {
                callback?.onFailure(400, t.message)
            }
        })
    }

    private fun isCacheFresh(prefs: PEPrefs): Boolean {
        val ts = prefs.subscriberCacheTimestamp
        if (ts <= 0L) return false
        return System.currentTimeMillis() - ts <= CACHE_TTL_MS
    }

    private fun isCacheInSync(cached: Map<String, Any?>, requested: Map<String, Any?>): Boolean {
        if (requested.isEmpty()) return false
        for ((key, value) in requested) {
            val cachedValue = cached[key]
            if (cachedValue == null || cachedValue.toString() != value.toString()) {
                return false
            }
        }
        return true
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = if (json.isNull(k)) null else json.get(k)
            out[k] = v
        }
        return out
    }

    private fun mapToGsonJsonObject(fields: Map<String, Any?>): JsonObject {
        val obj = JsonObject()
        for ((k, v) in fields) {
            when (v) {
                null -> obj.add(k, com.google.gson.JsonNull.INSTANCE)
                is String -> obj.addProperty(k, v)
                is Boolean -> obj.addProperty(k, v)
                is Number -> obj.addProperty(k, v)
                else -> obj.addProperty(k, v.toString())
            }
        }
        return obj
    }

    private fun propagateError(
        context: Context,
        response: Response<NetworkResponse>,
        callback: PushEngageResponseCallback?,
    ) {
        if (callback == null) return
        val errorBody = response.errorBody()
        if (errorBody != null) {
            try {
                val raw = errorBody.charStream().readText()
                val json = JSONObject(raw)
                callback.onFailure(json.getInt("error_code"), json.getString("error_message"))
                return
            } catch (_: Exception) {
                // Fall through to generic server-error message.
            }
        }
        callback.onFailure(response.code(), context.getString(R.string.server_error))
    }
}
