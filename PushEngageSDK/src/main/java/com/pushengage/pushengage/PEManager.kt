package com.pushengage.pushengage

import android.content.Context
import android.os.Build
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.PushEngage.TriggerAlertAvailabilityType
import com.pushengage.pushengage.PushEngage.TriggerAlertType
import com.pushengage.pushengage.PushEngage.TriggerStatusType
import com.pushengage.pushengage.RestClient.RestClient
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.helper.PEUtilities
import com.pushengage.pushengage.model.request.Goal
import com.pushengage.pushengage.model.request.GoalRequest
import com.pushengage.pushengage.model.request.TriggerAlert
import com.pushengage.pushengage.model.request.TriggerCampaign
import com.pushengage.pushengage.model.request.TriggerCampaignRequest
import com.pushengage.pushengage.model.request.TriggerCampaignRequestModel
import com.pushengage.pushengage.model.request.TriggerCampaignResponse
import com.pushengage.pushengage.model.request.UpdateTriggerStatusRequest
import com.pushengage.pushengage.model.response.NetworkResponse
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

interface CampaignManagerType {
    fun automatedNotification(status: TriggerStatusType, callback: PushEngageResponseCallback?)
    fun sendTriggerEvent(trigger: TriggerCampaign, callback: PushEngageResponseCallback?)
    fun addAlert(triggerAlert: TriggerAlert, callback: PushEngageResponseCallback?)
}

interface GoalManagerType {
    fun sendGoal(goal: Goal, callback: PushEngageResponseCallback?)
}

interface PEManagerType:
    CampaignManagerType,
    GoalManagerType {}

final class PEManager(private val context: Context,
                      private val preferences: PEPrefs): PEManagerType {

    enum class TriggerAlertKeys(val value: String) {
        SiteId("site_id"),
        DeviceTokenHash("device_token_hash"),
        Type("type"),
        ProductId("product_id"),
        Link("link"),
        Price("price"),
        VariantId("variant_id"),
        ExpiryTimestamp("ts_expires"),
        AlertPrice("alert_price"),
        Availability("availability"),
        ProfileId("profile_id"),
        MRP("mrp")
    }

    override fun automatedNotification(
        status: TriggerStatusType,
        callback: PushEngageResponseCallback?
    ) {
        val validationResult = PEUtilities.apiPreValidate(context)
        if(validationResult.equals(PEConstants.VALID)) {
            val triggerStatus = if (status == TriggerStatusType.enabled) 1 else 0
            val request = UpdateTriggerStatusRequest(preferences.siteId, preferences.hash, triggerStatus)
            val osInfo = "${Build.VERSION.SDK_INT}"
            val requestCall = RestClient.getBackendClient(context).automatedNotification(request, PushEngage.getSdkVersion(),osInfo)
            requestCall.enqueue(object: Callback<NetworkResponse> {
                override fun onResponse(
                    call: Call<NetworkResponse>,
                    response: Response<NetworkResponse>
                ) {
                    if (response.isSuccessful) {
                        callback?.onSuccess(response.body())
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                val errorBody = response.errorBody()?.charStream()?.readText()
                                errorBody?.let { body ->
                                    val errorJson = JSONObject(body)
                                    val errorCode: Int? = errorJson.opt("error_code") as? Int
                                    var errorMessage: String? =
                                        errorJson.opt("error_message") as? String
                                    callback?.onFailure(errorCode, errorMessage)
                                }
                            } catch (e: Exception) {
                                callback?.onFailure(
                                    response.code(),
                                    context.getString(R.string.server_error)
                                )
                            }
                        } else {
                            callback?.onFailure(
                                response.code(),
                                context.getString(R.string.server_error)
                            )
                        }
                    }
                }

                override fun onFailure(call: Call<NetworkResponse>, t: Throwable) {
                    callback?.onFailure(400, t.message)
                }

            })
        } else {
            callback?.onFailure(400, validationResult)
        }

    }

    override fun sendTriggerEvent(trigger: TriggerCampaign, callback: PushEngageResponseCallback?) {
        if(trigger.eventName == "" || trigger.campaignName == "") {
            callback?.onFailure(400, "One or more inputs provided are not valid.")
            return
        }

        val validationResult = PEUtilities.apiPreValidate(context)
        if(validationResult.equals(PEConstants.VALID)) {

            val requestData = TriggerCampaignRequest(
                preferences.siteId,
                preferences.hash,
                trigger.campaignName,
                trigger.eventName,
                PEUtilities.getTimeZone(),
                trigger.referenceId,
                trigger.profileId,
                trigger.data)
            val request = TriggerCampaignRequestModel(
                partitionKey = preferences.hash,
                data = requestData
            )

            val requestCall = RestClient.getTriggerClient(context).sendTriggerEvent(request)
            requestCall.enqueue(object: Callback<TriggerCampaignResponse> {
                override fun onResponse(
                    call: Call<TriggerCampaignResponse>,
                    response: Response<TriggerCampaignResponse>
                ) {
                    if (response.isSuccessful) {
                        callback?.onSuccess(response.body())
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                val errorBody = response.errorBody()?.charStream()?.readText()
                                errorBody?.let { body ->
                                    val errorJson = JSONObject(body)
                                    val errorCode: Int? = errorJson.opt("error_code") as? Int
                                    var errorMessage: String? =
                                        errorJson.opt("error_message") as? String
                                    callback?.onFailure(errorCode, errorMessage)
                                }
                            } catch (e: Exception) {
                                callback?.onFailure(
                                    response.code(),
                                    context.getString(R.string.server_error)
                                )
                            }
                        } else {
                            callback?.onFailure(
                                response.code(),
                                context.getString(R.string.server_error)
                            )
                        }
                    }
                }

                override fun onFailure(call: Call<TriggerCampaignResponse>, t: Throwable) {
                    callback?.onFailure(400, t.message)
                }

            })
        } else {
            callback?.onFailure(400, validationResult)
        }
    }

    override fun addAlert(triggerAlert: TriggerAlert, callback: PushEngageResponseCallback?) {
        val validationResult = PEUtilities.apiPreValidate(context)
        if (validationResult.equals(PEConstants.VALID)) {
            val requestBody = mutableMapOf<String, Any>(
                TriggerAlertKeys.SiteId.value to preferences.siteId,
                TriggerAlertKeys.DeviceTokenHash.value to preferences.hash,
                TriggerAlertKeys.Type.value to this.getTriggerAlertTypeName(triggerAlert.type),
                TriggerAlertKeys.ProductId.value to triggerAlert.productId,
                TriggerAlertKeys.Link.value to triggerAlert.link,
                TriggerAlertKeys.Price.value to triggerAlert.price
            )

            triggerAlert.variantId?.let {
                requestBody[TriggerAlertKeys.VariantId.value] = it
            }
            triggerAlert.expiryTimestamp?.let {
                val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                isoFormatter.timeZone = TimeZone.getTimeZone("UTC")
                val isoDateTime = isoFormatter.format(it)
                requestBody[TriggerAlertKeys.ExpiryTimestamp.value] = isoDateTime
            }
            triggerAlert.alertPrice?.let {
                requestBody[TriggerAlertKeys.AlertPrice.value] = it
            }
            triggerAlert.profileId?.let {
                requestBody[TriggerAlertKeys.ProfileId.value] = it
            }
            triggerAlert.mrp?.let {
                requestBody[TriggerAlertKeys.MRP.value] = it
            }
            this.getTriggerAlertAvailabilityName(triggerAlert.availability)?.let {
                requestBody[TriggerAlertKeys.Availability.value] = it
            }
            triggerAlert.data?.let {
                it.forEach{ (key, value) ->
                    if(key.isNotBlank()) {
                        requestBody[key] = value
                    }
                }
            }

            val osInfo = "${Build.VERSION.SDK_INT}"
            val requestCall = RestClient.getBackendClient(context).addAlert(requestBody, PushEngage.getSdkVersion(), osInfo)

            requestCall.enqueue(object : Callback<NetworkResponse> {
                override fun onResponse(
                    call: Call<NetworkResponse>,
                    response: Response<NetworkResponse>
                ) {
                    if (response.isSuccessful) {
                        callback?.onSuccess(response.body())
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                val errorBody = response.errorBody()?.charStream()?.readText()
                                errorBody?.let { body ->
                                    val errorJson = JSONObject(body)
                                    val errorCode: Int? = errorJson.opt("error_code") as? Int
                                    var errorMessage: String? =
                                        errorJson.opt("error_message") as? String
                                    callback?.onFailure(errorCode, errorMessage)
                                }
                            } catch (e: Exception) {
                                callback?.onFailure(
                                    response.code(),
                                    context.getString(R.string.server_error)
                                )
                            }
                        } else {
                            callback?.onFailure(
                                response.code(),
                                context.getString(R.string.server_error)
                            )
                        }
                    }
                }

                override fun onFailure(call: Call<NetworkResponse>, t: Throwable) {
                    callback?.onFailure(400, t.message)
                }

            })
        } else {
            callback?.onFailure(400, validationResult)
        }
    }

    private fun getTriggerAlertTypeName(type: TriggerAlertType) : String {
        return when(type) {
            TriggerAlertType.priceDrop -> "price_drop"
            TriggerAlertType.inventory -> return "inventory"
        }
    }

    private fun getTriggerAlertAvailabilityName(availability: TriggerAlertAvailabilityType?) : String? {
        return when(availability) {
            TriggerAlertAvailabilityType.inStock -> "inStock"
            TriggerAlertAvailabilityType.outOfStock -> "outOfStock"
            else -> null
        }
    }

    override fun sendGoal(goal: Goal, callback: PushEngageResponseCallback?) {
        if(goal.name == "") {
            callback?.onFailure(400, "One or more inputs provided are not valid.")
            return
        }
        val validationResult = PEUtilities.apiPreValidate(context)
        if(validationResult.equals(PEConstants.VALID)) {
            val request = GoalRequest(preferences.siteId, preferences.hash, goal.name, goal.count, goal.value)
            val osInfo = "${Build.VERSION.SDK_INT}"
            val requestCall = RestClient.getBackendClient(context).sendGoal(request, PushEngage.getSdkVersion(), osInfo)
            requestCall.enqueue(object: Callback<NetworkResponse> {
                override fun onResponse(
                    call: Call<NetworkResponse>,
                    response: Response<NetworkResponse>
                ) {
                    if (response.isSuccessful) {
                        callback?.onSuccess(response.body())
                    } else {
                        if (response.errorBody() != null) {
                            try {
                                val errorBody = response.errorBody()?.charStream()?.readText()
                                errorBody?.let { body ->
                                    val errorJson = JSONObject(body)
                                    val errorCode: Int? = errorJson.opt("error_code") as? Int
                                    var errorMessage: String? =
                                        errorJson.opt("error_message") as? String
                                    callback?.onFailure(errorCode, errorMessage)
                                }
                            } catch (e: Exception) {
                                callback?.onFailure(
                                    response.code(),
                                    context.getString(R.string.server_error)
                                )
                            }
                        } else {
                            callback?.onFailure(
                                response.code(),
                                context.getString(R.string.server_error)
                            )
                        }
                    }
                }

                override fun onFailure(call: Call<NetworkResponse>, t: Throwable) {
                    callback?.onFailure(400, t.message)
                }
            })
        } else {
            callback?.onFailure(400, validationResult)
        }
    }

}