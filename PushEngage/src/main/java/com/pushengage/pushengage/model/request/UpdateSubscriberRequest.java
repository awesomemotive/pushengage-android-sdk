package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class UpdateSubscriberRequest {

    @SerializedName("site_id")
    @Expose
    private Long siteId;
    @SerializedName("device")
    @Expose
    private String device;
    @SerializedName("device_version")
    @Expose
    private String deviceVersion;
    @SerializedName("timezone")
    @Expose
    private String timezone;
    @SerializedName("language")
    @Expose
    private String language;
    @SerializedName("total_screen_width_height")
    @Expose
    private String totalScreenWidthHeight;
    @SerializedName("notification_disabled")
    @Expose
    private Long notificationDisabled;

    /**
     * No args constructor for use in serialization
     *
     */
    public UpdateSubscriberRequest() {
    }

    /**
     *
     * @param timezone
     * @param siteId
     * @param totalScreenWidthHeight
     * @param language
     * @param deviceVersion
     * @param device
     */
    public UpdateSubscriberRequest(Long siteId, String device, String deviceVersion, String timezone, String language, String totalScreenWidthHeight, Long notificationDisabled) {
        super();
        this.siteId = siteId;
        this.device = device;
        this.deviceVersion = deviceVersion;
        this.timezone = timezone;
        this.language = language;
        this.totalScreenWidthHeight = totalScreenWidthHeight;
        this.notificationDisabled = notificationDisabled;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getDeviceVersion() {
        return deviceVersion;
    }

    public void setDeviceVersion(String deviceVersion) {
        this.deviceVersion = deviceVersion;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getTotalScreenWidthHeight() {
        return totalScreenWidthHeight;
    }

    public void setTotalScreenWidthHeight(String totalScreenWidthHeight) {
        this.totalScreenWidthHeight = totalScreenWidthHeight;
    }

    public Long getNotificationDisabled() {
        return notificationDisabled;
    }

    public void setNotificationDisabled(Long notificationDisabled) {
        this.notificationDisabled = notificationDisabled;
    }

}