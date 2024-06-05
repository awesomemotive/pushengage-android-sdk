package com.pushengage.pushengage.model.request

import com.pushengage.pushengage.PushEngage
import java.util.Date

data class TriggerAlert(
    val type: PushEngage.TriggerAlertType,
    val productId: String,
    val link: String,
    val price: Double,
    val variantId: String? = null,
    val expiryTimestamp: Date? = null,
    val alertPrice: Double? = null,
    val availability: PushEngage.TriggerAlertAvailabilityType? = null,
    val profileId: String? = null,
    val mrp: Double? = null,
    val data: Map<String, String>? = null
)

