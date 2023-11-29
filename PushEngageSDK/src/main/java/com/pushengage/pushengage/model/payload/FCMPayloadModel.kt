package com.pushengage.pushengage.model.payload

import com.google.gson.annotations.SerializedName
import com.google.gson.annotations.Expose

data class FCMPayloadModel(
        @SerializedName("ab")
        @Expose
        val actionButtons: String? = null,
        @SerializedName("ad")
        @Expose
        val additionalData: String? = null,
        @SerializedName("b")
        @Expose
        val body: String? = null,
        @SerializedName("bp")
        @Expose
        val bigPicture: String? = null,
        @SerializedName("ci")
        @Expose
        var channelId: String? = null,
        @SerializedName("gk")
        @Expose
        val groupKey: String? = null,
        @SerializedName("id")
        @Expose
        var notificationId: Int? = null,
        @SerializedName("im")
        @Expose
        val commonNotificationImage: String? = null,
        @SerializedName("li")
        @Expose
        val largeIcon: String? = null,
        @SerializedName("p")
        @Expose
        val priority: String? = null,
        @SerializedName("si")
        @Expose
        val smallIcon: String? = null,
        @SerializedName("tag")
        @Expose
        val tag: String? = null,
        @SerializedName("t")
        @Expose
        val title: String? = null,
        @SerializedName("u")
        @Expose
        val url: String? = null,
        @SerializedName("rf")
        @Expose
        val reFetch: String? = null,
        @SerializedName("pb")
        @Expose
        val postbackData: Any? = null,
        @SerializedName("ac")
        @Expose
        val accentColor: String? = null,
        @SerializedName("cu")
        @Expose
        val commonUrl: String? = null
) {
    data class ActionButton(
            @SerializedName("l")
            @Expose
            val label: String? = null,
            @SerializedName("i")
            @Expose
            val icon: String? = null,
            @SerializedName("u")
            @Expose
            val url: String? = null
    )
}

