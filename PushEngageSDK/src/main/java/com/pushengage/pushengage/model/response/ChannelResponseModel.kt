package com.pushengage.pushengage.model.response

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

internal data class ChannelResponseModel(
        @SerializedName("error_code")
        @Expose
        val errorCode: Long?=null,

        @SerializedName("data")
        @Expose
        val data: Data?=null
) {
    internal data class Data(
            @SerializedName("channel_id")
            @Expose
            val channelId: Long?=null,

            @SerializedName("group_id")
            @Expose
            val groupId: Long?=null,

            @SerializedName("channel_name")
            @Expose
            val channelName: String?=null,

            @SerializedName("channel_description")
            @Expose
            val channelDescription: String?=null,

            @SerializedName("options")
            @Expose
            val options: Options?=null,

            @SerializedName("site_id")
            @Expose
            val siteId: Long?=null,

            @SerializedName("group_name")
            @Expose
            val groupName: String?=null
    )

    internal data class Options(
            @SerializedName("importance")
            @Expose
            val importance: String?=null,

            @SerializedName("sound")
            @Expose
            val sound: String?=null,

            @SerializedName("sound_file")
            @Expose
            val soundFile: String?=null,

            @SerializedName("vibration")
            @Expose
            val vibration: String?=null,

            @SerializedName("vibration_pattern")
            @Expose
            val vibrationPattern: List<Long>?=null,

            @SerializedName("led_color")
            @Expose
            val ledColor: String?=null,

            @SerializedName("led_color_code")
            @Expose
            val ledColorCode: String?=null,

            @SerializedName("accent_color")
            @Expose
            val accentColor: String?=null,

            @SerializedName("badges")
            @Expose
            val badges: Boolean?=null,

            @SerializedName("lock_screen")
            @Expose
            val lockScreen: String?=null
    )
}
