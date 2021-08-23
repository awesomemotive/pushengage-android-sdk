package com.pushengage.pushengage.model.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class RecordsRequest {

    public class Data {

        @SerializedName("campaign_name")
        @Expose
        private String campaignName;
        @SerializedName("event_name")
        @Expose
        private String eventName;
        @SerializedName("title")
        @Expose
        private Map<String, String> title;
        @SerializedName("message")
        @Expose
        private Map<String, String> message;
        @SerializedName("notification_url")
        @Expose
        private Map<String, String> notificationUrl;
        @SerializedName("notification_image")
        @Expose
        private Map<String, String> notificationImage;
        @SerializedName("big_image")
        @Expose
        private Map<String, String> bigImage;
        @SerializedName("device_token_hash")
        @Expose
        private String deviceTokenHash;
        @SerializedName("site_id")
        @Expose
        private Long siteId;
        @SerializedName("data")
        @Expose
        private Map<String, String> data;
        /**
         * No args constructor for use in serialization
         */
        public Data() {
        }

        /**
         * @param notificationUrl
         * @param notificationImage
         * @param bigImage
         * @param eventName
         * @param siteId
         * @param title
         * @param message
         * @param campaignName
         * @param deviceTokenHash
         */
        public Data(String campaignName, String eventName, Map<String, String> title, Map<String, String> message, Map<String, String> notificationUrl, Map<String, String> notificationImage, Map<String, String> bigImage, String deviceTokenHash, Long siteId) {
            super();
            this.campaignName = campaignName;
            this.eventName = eventName;
            this.title = title;
            this.message = message;
            this.notificationUrl = notificationUrl;
            this.notificationImage = notificationImage;
            this.bigImage = bigImage;
            this.deviceTokenHash = deviceTokenHash;
            this.siteId = siteId;
        }

        /**
         * @param notificationUrl
         * @param notificationImage
         * @param bigImage
         * @param eventName
         * @param siteId
         * @param title
         * @param message
         * @param campaignName
         * @param deviceTokenHash
         * @param data
         */
        public Data(String campaignName, String eventName, Map<String, String> title, Map<String, String> message, Map<String, String> notificationUrl, Map<String, String> notificationImage, Map<String, String> bigImage, String deviceTokenHash, Long siteId, Map<String, String> data) {
            super();
            this.campaignName = campaignName;
            this.eventName = eventName;
            this.title = title;
            this.message = message;
            this.notificationUrl = notificationUrl;
            this.notificationImage = notificationImage;
            this.bigImage = bigImage;
            this.deviceTokenHash = deviceTokenHash;
            this.siteId = siteId;
            this.data = data;
        }

        public String getCampaignName() {
            return campaignName;
        }

        public void setCampaignName(String campaignName) {
            this.campaignName = campaignName;
        }

        public String getEventName() {
            return eventName;
        }

        public void setEventName(String eventName) {
            this.eventName = eventName;
        }

        public Map<String, String> getTitle() {
            return title;
        }

        public void setTitle(Map<String, String> title) {
            this.title = title;
        }

        public Map<String, String> getMessage() {
            return message;
        }

        public void setMessage(Map<String, String> message) {
            this.message = message;
        }

        public Map<String, String> getNotificationUrl() {
            return notificationUrl;
        }

        public void setNotificationUrl(Map<String, String> notificationUrl) {
            this.notificationUrl = notificationUrl;
        }

        public Map<String, String> getNotificationImage() {
            return notificationImage;
        }

        public void setNotificationImage(Map<String, String> notificationImage) {
            this.notificationImage = notificationImage;
        }

        public Map<String, String> getBigImage() {
            return bigImage;
        }

        public void setBigImage(Map<String, String> bigImage) {
            this.bigImage = bigImage;
        }

        public String getDeviceTokenHash() {
            return deviceTokenHash;
        }

        public void setDeviceTokenHash(String deviceTokenHash) {
            this.deviceTokenHash = deviceTokenHash;
        }

        public Long getSiteId() {
            return siteId;
        }

        public void setSiteId(Long siteId) {
            this.siteId = siteId;
        }

        public Map<String, String> getData() {
            return data;
        }

        public void setData(Map<String, String> data) {
            this.data = data;
        }

    }

    @SerializedName("Data")
    @Expose
    private Data data;
    @SerializedName("PartitionKey")
    @Expose
    private String partitionKey;

    /**
     * No args constructor for use in serialization
     */
    public RecordsRequest() {
    }

    /**
     * @param data
     * @param partitionKey
     */
    public RecordsRequest(Data data, String partitionKey) {
        super();
        this.data = data;
        this.partitionKey = partitionKey;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

}