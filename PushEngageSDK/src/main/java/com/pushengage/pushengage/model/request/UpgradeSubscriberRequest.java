package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class UpgradeSubscriberRequest {

    @SerializedName("device_token_hash")
    @Expose
    private String deviceTokenHash;
    @SerializedName("subscription")
    @Expose
    private Subscription subscription;
    @SerializedName("site_id")
    @Expose
    private Long siteId;

    /**
     * No args constructor for use in serialization
     */
    public UpgradeSubscriberRequest() {
    }

    /**
     * @param siteId
     * @param subscription
     * @param deviceTokenHash
     */
    public UpgradeSubscriberRequest(String deviceTokenHash, Subscription subscription, Long siteId) {
        super();
        this.deviceTokenHash = deviceTokenHash;
        this.subscription = subscription;
        this.siteId = siteId;
    }

    public String getDeviceTokenHash() {
        return deviceTokenHash;
    }

    public void setDeviceTokenHash(String deviceTokenHash) {
        this.deviceTokenHash = deviceTokenHash;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public void setSubscription(Subscription subscription) {
        this.subscription = subscription;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
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
