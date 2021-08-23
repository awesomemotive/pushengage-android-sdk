package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class AddSubscriberRequest {

    @SerializedName("site_id")
    @Expose
    private Long siteId;
    @SerializedName("subscription")
    @Expose
    private Subscription subscription;
    @SerializedName("device_type")
    @Expose
    private String deviceType;
    @SerializedName("device")
    @Expose
    private String device;
    @SerializedName("device_version")
    @Expose
    private String deviceVersion;
    @SerializedName("device_model")
    @Expose
    private String deviceModel;
    @SerializedName("device_manufacturer")
    @Expose
    private String deviceManufacturer;
    @SerializedName("timezone")
    @Expose
    private String timezone;
    @SerializedName("language")
    @Expose
    private String language;
    @SerializedName("user_agent")
    @Expose
    private String userAgent;
    @SerializedName("total_screen_width_height")
    @Expose
    private String totalScrWidthHeight;
    @SerializedName("host")
    @Expose
    private String host;
    @SerializedName("notification_disabled")
    @Expose
    private Long notificationDisabled;

    /**
     * No args constructor for use in serialization
     */
    public AddSubscriberRequest() {
    }

    /**
     * @param deviceType
     * @param timezone
     * @param language
     * @param userAgent
     * @param subscription
     * @param deviceVersion
     * @param host
     * @param siteId
     * @param deviceModel
     * @param totalScrWidthHeight
     * @param deviceManufacturer
     * @param device
     */
    public AddSubscriberRequest(Long siteId, Subscription subscription, String deviceType, String device, String deviceVersion, String deviceModel, String deviceManufacturer, String timezone, String language, String userAgent, String totalScrWidthHeight, String host, Long notificationDisabled) {
        super();
        this.siteId = siteId;
        this.subscription = subscription;
        this.deviceType = deviceType;
        this.device = device;
        this.deviceVersion = deviceVersion;
        this.deviceModel = deviceModel;
        this.deviceManufacturer = deviceManufacturer;
        this.timezone = timezone;
        this.language = language;
        this.userAgent = userAgent;
        this.totalScrWidthHeight = totalScrWidthHeight;
        this.host = host;
        this.notificationDisabled = notificationDisabled;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public void setSubscription(Subscription subscription) {
        this.subscription = subscription;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
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

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getDeviceManufacturer() {
        return deviceManufacturer;
    }

    public void setDeviceManufacturer(String deviceManufacturer) {
        this.deviceManufacturer = deviceManufacturer;
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

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getTotalScrWidthHeight() {
        return totalScrWidthHeight;
    }

    public void setTotalScrWidthHeight(String totalScrWidthHeight) {
        this.totalScrWidthHeight = totalScrWidthHeight;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Long getNotificationDisabled() {
        return notificationDisabled;
    }

    public void setNotificationDisabled(Long notificationDisabled) {
        this.notificationDisabled = notificationDisabled;
    }

    public class Subscription {

        @SerializedName("endpoint")
        @Expose
        private String endpoint;
        @SerializedName("project_id")
        @Expose
        private String projectId;

        /**
         * No args constructor for use in serialization
         */
        public Subscription() {
        }

        /**
         * @param endpoint
         * @param projectId
         */
        public Subscription(String endpoint, String projectId) {
            super();
            this.endpoint = endpoint;
            this.projectId = projectId;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

    }

}